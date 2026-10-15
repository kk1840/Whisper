package com.stepos.whisper.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.stepos.whisper.R
import com.stepos.whisper.Settings
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 智能眼镜（Even G2）双镜腿 BLE 连接管理。
 *
 * 线程模型：
 *  - ble 线程（[h]）：状态机——扫描、连接、GATT 映射、重连退避、心跳定时。GATT 回调都转到这里，
 *    只有“写完成”和 EvenHub ack 这两种延迟敏感的回调直接在回调线程上唤醒等待者。
 *  - work 线程（[worker]）：会阻塞的流程——鉴权、推图、关页、心跳发送。Android 要求 GATT 写串行化
 *    （一次只能有一个在途写，要等 onCharacteristicWrite 再写下一个），所以所有写都经 [performWrite] 排队，
 *    一条多片消息整体持 [writeLock]，不让心跳插进分片中间（固件只有一个按 seq 重组的缓冲区）。
 *
 * 协议要点见 [G2Protocol] / [EvenHub]。EvenHub（页面 / 图片）只发右镜腿；鉴权和文本心跳发两条。
 */
@SuppressLint("MissingPermission")
class G2Connection(context: Context) {
    companion object {
        private const val TAG = "G2"
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val DISCOVERY_SCAN_MS = 20_000L
        /** Android 对超过 30 分钟的扫描会降级成机会扫描，定向扫描每 20 分钟重启一次。 */
        private const val TARGETED_SCAN_CYCLE_MS = 20 * 60_000L
        private const val CONNECT_TIMEOUT_MS = 35_000L
        private const val RETRY_MIN_MS = 3_000L
        private const val RETRY_MAX_MS = 60_000L
        private const val TEXT_HEARTBEAT_MS = 1_500L
        private const val HUB_HEARTBEAT_MS = 2_000L
        private const val WRITE_ACK_TIMEOUT_MS = 120L
        private const val ACK_WINDOW = 4

        /** 图片瓦片：2×2，每块 288×128，顶部 32 px 留给必须存在的文本容器。 */
        const val TILE_W = 288
        const val TILE_H = 128
        const val HEADER_H = EvenHub.LENS_H - TILE_H * 2
        const val PAGE_W = EvenHub.LENS_W
        const val PAGE_H = TILE_H * 2
    }

    private val ctx = context.applicationContext
    private val thread = HandlerThread("g2-ble").also { it.start() }
    private val h = Handler(thread.looper)
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "g2-work") }

    private val _state = MutableLiveData(G2State(G2Status.OFF))
    val state: LiveData<G2State> get() = _state
    private val _scanResults = MutableLiveData<List<ScanArm>>(emptyList())
    val scanResults: LiveData<List<ScanArm>> get() = _scanResults

    val current: G2State get() = _state.value ?: G2State(G2Status.OFF)
    /** 两条镜腿都握手完成，可以推送。任意线程可读。 */
    val isReady: Boolean get() = status == G2Status.READY

    // ---------------------------------------------------------------- 镜腿

    private inner class Arm(val side: Side) {
        @Volatile var name: String? = null
        @Volatile var address: String? = null
        @Volatile var device: BluetoothDevice? = null
        @Volatile var gatt: BluetoothGatt? = null
        @Volatile var write: BluetoothGattCharacteristic? = null
        @Volatile var notify: BluetoothGattCharacteristic? = null
        @Volatile var connected = false
        @Volatile var ready = false
        @Volatile var writeLatch: CountDownLatch? = null
        var connectTimeout: Runnable? = null

        val label: String get() = ctx.getString(if (side == Side.LEFT) R.string.glasses_arm_left else R.string.glasses_arm_right)

        fun snapshot() = ArmState(side, name, address, device != null, connected, ready)

        /** 关掉 GATT，清空连接期状态，保留地址 / 名字 / 设备以便重连。 */
        fun closeGatt() {
            connectTimeout?.let { h.removeCallbacks(it) }
            connectTimeout = null
            gatt?.let { g -> runCatching { g.disconnect() }; runCatching { g.close() } }
            gatt = null
            write = null
            notify = null
            connected = false
            ready = false
            writeLatch?.countDown()
            writeLatch = null
        }

        fun forgetDevice() {
            closeGatt()
            device = null
            address = null
            name = null
        }
    }

    private val left = Arm(Side.LEFT)
    private val right = Arm(Side.RIGHT)
    private fun arm(side: Side) = if (side == Side.LEFT) left else right
    private fun armFor(gatt: BluetoothGatt): Arm? {
        val addr = gatt.device?.address ?: return null
        return when (addr) {
            left.device?.address -> left
            right.device?.address -> right
            else -> null
        }
    }

    private val adapter: BluetoothAdapter?
        get() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile private var enabled = false
    /** 用户点了“断开”：不自动重连，直到再次 start / scanNow。 */
    @Volatile private var userStopped = false
    @Volatile private var status = G2Status.OFF
    private var lastError: String? = null
    private var retryCount = 0
    private val retryRunnable = Runnable { connectOrScan() }

    /** 任一镜腿断开 / 重置时 +1；阻塞中的流程据此中止。 */
    @Volatile private var epoch = 0

    // ---------------------------------------------------------------- 扫描

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var scanTargeted = false
    private val scanStopRunnable = Runnable { onScanWindowEnd() }
    private val scanCycleRunnable = Runnable { restartTargetedScan() }
    private val found = LinkedHashMap<String, Pair<BluetoothDevice, ScanArm>>()

    init {
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val s = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                h.post {
                    when (s) {
                        BluetoothAdapter.STATE_ON -> if (enabled && !userStopped) { retryCount = 0; connectOrScan() }
                        BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                            stopScan()
                            for (a in arms()) a.closeGatt()
                            bumpEpoch()
                            if (enabled) publish(G2Status.DISCONNECTED, ctx.getString(R.string.glasses_status_bt_off))
                        }
                    }
                }
            }
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    private fun arms() = listOf(left, right)

    private fun log(m: String) = Log.d(TAG, m)
    private fun warn(m: String) = Log.w(TAG, m)

    private fun bumpEpoch() {
        epoch++
        resetEvenHubState()
        cancelHeartbeat()
    }

    private fun publish(newStatus: G2Status = status, error: String? = lastError) {
        status = newStatus
        lastError = error
        _state.postValue(
            G2State(
                status = status,
                left = left.snapshot(),
                right = right.snapshot(),
                error = error,
                pageShown = pageShown,
                remembered = Settings.getGlassesLeftAddress(ctx) != null && Settings.getGlassesRightAddress(ctx) != null,
            )
        )
    }

    private fun publishScan() {
        _scanResults.postValue(found.values.map { it.second })
    }

    // ================================================================ 对外接口（任意线程）

    /** 打开：如果配对过就自动重连；没配对过只是待命，等用户点“扫描并配对”。 */
    fun start() {
        h.post {
            enabled = true
            userStopped = false
            retryCount = 0
            connectOrScan()
        }
    }

    /** 关闭：断开并不再重连。 */
    fun stop() {
        h.post {
            enabled = false
            userStopped = true
            teardown()
            publish(G2Status.OFF, null)
        }
    }

    /** 用户手动断开（开关仍开着；下次 start / scanNow 再连）。 */
    fun disconnect() {
        h.post {
            userStopped = true
            teardown()
            publish(G2Status.DISCONNECTED, null)
        }
    }

    /** 用户点“扫描并配对”：忽略记住的地址，做一次全量发现扫描。 */
    fun scanNow() {
        h.post {
            enabled = true
            userStopped = false
            retryCount = 0
            teardown()
            // 从头来：丢掉本次会话里的设备对象（Settings 里记住的地址保留，扫描失败后仍可自动重连）。
            for (a in arms()) a.forgetDevice()
            found.clear()
            publishScan()
            beginDiscoveryScan()
        }
    }

    /** 忘记已配对的眼镜。 */
    fun forget() {
        h.post {
            teardown()
            for (a in arms()) a.forgetDevice()
            Settings.clearGlassesArms(ctx)
            found.clear()
            publishScan()
            publish(G2Status.DISCONNECTED, null)
        }
    }

    /** 配对界面手动把某条扫描结果指定为左 / 右镜腿。 */
    fun assignSide(address: String, side: Side) {
        h.post {
            val entry = found[address] ?: return@post
            val target = arm(side)
            val other = arm(if (side == Side.LEFT) Side.RIGHT else Side.LEFT)
            if (other.device?.address == address) {
                other.closeGatt()
                other.device = null; other.address = null; other.name = null
            }
            if (target.device?.address != address) {
                target.closeGatt()
                bumpEpoch()
            }
            target.device = entry.first
            target.address = address
            target.name = entry.second.name
            found[address] = entry.first to entry.second.copy(assigned = side)
            for ((k, v) in found.entries.toList()) {
                if (k != address && v.second.assigned == side) found[k] = v.first to v.second.copy(assigned = null)
            }
            publishScan()
            connectArm(target)
            if (left.device != null && right.device != null) stopScan()
        }
    }

    /** 在 work 线程上执行（推图等阻塞流程用）。 */
    fun execute(task: Runnable) {
        worker.execute(task)
    }

    // ================================================================ 连接 / 扫描（ble 线程）

    private fun hasPermissions(): Boolean {
        fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun teardown() {
        h.removeCallbacks(retryRunnable)
        stopScan()
        for (a in arms()) a.closeGatt()
        bumpEpoch()
    }

    /** 按当前情况决定：定向扫描已记住的镜腿，或什么都不做。 */
    private fun connectOrScan() {
        if (!enabled || userStopped) return
        val a = adapter
        if (a == null || !a.isEnabled) {
            publish(G2Status.DISCONNECTED, ctx.getString(R.string.glasses_status_bt_off)); return
        }
        if (!hasPermissions()) {
            publish(G2Status.DISCONNECTED, ctx.getString(R.string.glasses_status_no_permission)); return
        }
        if (left.address == null || right.address == null) {
            left.address = Settings.getGlassesLeftAddress(ctx); left.name = Settings.getGlassesLeftName(ctx)
            right.address = Settings.getGlassesRightAddress(ctx); right.name = Settings.getGlassesRightName(ctx)
        }
        if (left.address == null || right.address == null) {
            publish(G2Status.DISCONNECTED, ctx.getString(R.string.glasses_err_not_paired)); return
        }
        adoptConnected()
        if (left.gatt != null && right.gatt != null) return
        beginTargetedScan()
    }

    /** 系统已经连着的镜腿不会出现在扫描结果里，直接接管。 */
    private fun adoptConnected() {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val connected = runCatching { mgr.getConnectedDevices(BluetoothProfile.GATT) }.getOrNull() ?: return
        for (d in connected) {
            val arm = arms().firstOrNull { it.address == d.address && it.gatt == null } ?: continue
            log("adopting already-connected ${arm.side}: ${d.address}")
            arm.device = d
            connectArm(arm)
        }
    }

    private fun scanSettings(mode: Int): ScanSettings = ScanSettings.Builder().setScanMode(mode).build()

    private fun startScanInternal(filters: List<ScanFilter>?, mode: Int): Boolean {
        val s = adapter?.bluetoothLeScanner
        if (s == null) {
            publish(G2Status.DISCONNECTED, ctx.getString(R.string.glasses_status_bt_off)); return false
        }
        stopScan()
        scanner = s
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                h.post { onDiscovered(result) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                h.post { results.forEach { onDiscovered(it) } }
            }

            override fun onScanFailed(errorCode: Int) {
                h.post {
                    warn("scan failed: $errorCode")
                    scanCallback = null
                    publish(G2Status.DISCONNECTED, ctx.getString(R.string.glasses_err_scan_failed, errorCode))
                    scheduleRetry()
                }
            }
        }
        scanCallback = cb
        return try {
            s.startScan(filters, scanSettings(mode), cb)
            true
        } catch (e: Exception) {
            warn("startScan threw: $e")
            scanCallback = null
            publish(G2Status.DISCONNECTED, e.message)
            false
        }
    }

    /** 全量发现：名字里带 Even 的都收进列表，20 秒窗口。 */
    private fun beginDiscoveryScan() {
        scanTargeted = false
        log("discovery scan…")
        if (!startScanInternal(null, ScanSettings.SCAN_MODE_LOW_LATENCY)) return
        publish(G2Status.SCANNING, null)
        h.postDelayed(scanStopRunnable, DISCOVERY_SCAN_MS)
    }

    /** 定向扫描已记住的镜腿：按地址，也按名字兜底（有的固件地址会变）。可长期跑，硬件过滤省电。 */
    private fun beginTargetedScan() {
        scanTargeted = true
        val filters = ArrayList<ScanFilter>()
        for (a in arms()) {
            if (a.gatt != null) continue
            a.address?.let { filters.add(ScanFilter.Builder().setDeviceAddress(it).build()) }
            a.name?.let { filters.add(ScanFilter.Builder().setDeviceName(it).build()) }
        }
        if (filters.isEmpty()) return
        log("targeted scan for ${filters.size} filters…")
        if (!startScanInternal(filters, ScanSettings.SCAN_MODE_BALANCED)) return
        if (status != G2Status.CONNECTING) publish(G2Status.SCANNING, lastError)
        h.postDelayed(scanCycleRunnable, TARGETED_SCAN_CYCLE_MS)
    }

    private fun restartTargetedScan() {
        if (scanCallback == null || !scanTargeted) return
        log("targeted scan cycle restart")
        beginTargetedScan()
    }

    private fun stopScan() {
        h.removeCallbacks(scanStopRunnable)
        h.removeCallbacks(scanCycleRunnable)
        val cb = scanCallback ?: return
        scanCallback = null
        runCatching { scanner?.stopScan(cb) }
    }

    /** 发现扫描的窗口到了。 */
    private fun onScanWindowEnd() {
        if (scanTargeted) return
        stopScan()
        if (left.device != null && right.device != null) return
        val yes = ctx.getString(R.string.glasses_found_yes)
        val no = ctx.getString(R.string.glasses_found_no)
        publish(
            if (left.device != null || right.device != null) G2Status.CONNECTING else G2Status.DISCONNECTED,
            ctx.getString(R.string.glasses_err_scan_timeout, if (left.device != null) yes else no, if (right.device != null) yes else no)
        )
    }

    private fun classify(name: String): Side? {
        val u = name.uppercase()
        return when {
            u.contains("_L_") || u.endsWith("_L") || u.contains("LEFT") -> Side.LEFT
            u.contains("_R_") || u.endsWith("_R") || u.contains("RIGHT") -> Side.RIGHT
            else -> null
        }
    }

    private fun onDiscovered(result: ScanResult) {
        val dev = result.device ?: return
        val name = result.scanRecord?.deviceName ?: runCatching { dev.name }.getOrNull() ?: return
        if (scanTargeted) {
            val arm = arms().firstOrNull { it.gatt == null && (it.address == dev.address || it.name == name) } ?: return
            log("targeted: found ${arm.side} '$name' ${dev.address} rssi=${result.rssi}")
            arm.device = dev
            arm.address = dev.address
            arm.name = name
            connectArm(arm)
            if (arms().all { it.gatt != null }) stopScan()
            return
        }
        if (!name.contains("Even", ignoreCase = true)) return
        val side = classify(name)
        val existing = found[dev.address]
        val assigned = existing?.second?.assigned ?: arms().firstOrNull { it.device?.address == dev.address }?.side
        found[dev.address] = dev to ScanArm(name, dev.address, result.rssi, side, assigned)
        if (existing == null) log("discovered '$name' ${dev.address} rssi=${result.rssi} side=$side")
        // 自动归位：按名字能分出左右、且这一侧还空着。
        if (assigned == null && side != null) {
            val arm = arm(side)
            if (arm.device == null) {
                arm.device = dev
                arm.address = dev.address
                arm.name = name
                found[dev.address] = dev to ScanArm(name, dev.address, result.rssi, side, side)
                connectArm(arm)
                if (left.device != null && right.device != null) {
                    log("both arms found - stopping discovery scan")
                    stopScan()
                }
            }
        }
        publishScan()
    }

    private fun connectArm(arm: Arm) {
        val dev = arm.device ?: return
        if (arm.gatt != null) return
        log("${arm.side} connecting ${dev.address}…")
        arm.gatt = dev.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (arm.gatt == null) {
            warn("${arm.side} connectGatt returned null")
            publish(G2Status.DISCONNECTED, ctx.getString(R.string.glasses_err_connect_failed, arm.label, -1))
            scheduleRetry()
            return
        }
        val timeout = Runnable {
            if (arm.gatt != null && !arm.connected) {
                warn("${arm.side} connect timeout")
                arm.closeGatt()
                bumpEpoch()
                publish(G2Status.DISCONNECTED, ctx.getString(R.string.glasses_err_connect_failed, arm.label, 0))
                scheduleRetry()
            }
        }
        arm.connectTimeout = timeout
        h.postDelayed(timeout, CONNECT_TIMEOUT_MS)
        publish(G2Status.CONNECTING)
    }

    private fun scheduleRetry() {
        if (!enabled || userStopped) return
        if (left.address == null || right.address == null) return
        h.removeCallbacks(retryRunnable)
        val delay = minOf(RETRY_MAX_MS, RETRY_MIN_MS shl minOf(retryCount, 5))
        retryCount++
        log("retry in ${delay / 1000}s")
        h.postDelayed(retryRunnable, delay)
    }

    // ================================================================ GATT 回调

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, gattStatus: Int, newState: Int) {
            h.post {
                val arm = armFor(gatt) ?: run { runCatching { gatt.close() }; return@post }
                if (gatt !== arm.gatt) { runCatching { gatt.close() }; return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED && gattStatus == BluetoothGatt.GATT_SUCCESS) {
                    log("${arm.side} connected")
                    arm.connected = true
                    arm.connectTimeout?.let { h.removeCallbacks(it) }
                    arm.connectTimeout = null
                    publish(G2Status.CONNECTING)
                    // 刚连上就 requestMtu 在部分手机上会失败，稍等一下。
                    h.postDelayed({
                        if (arm.gatt === gatt && arm.connected && !gatt.requestMtu(247)) gatt.discoverServices()
                    }, 200)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    onArmDisconnected(arm, gattStatus)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, gattStatus: Int) {
            h.post {
                val arm = armFor(gatt) ?: return@post
                if (gattStatus == BluetoothGatt.GATT_SUCCESS && arm === right) lastMtu = mtu
                log("${arm.side} MTU=$mtu (status $gattStatus)")
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, gattStatus: Int) {
            h.post {
                val arm = armFor(gatt) ?: return@post
                if (gattStatus != BluetoothGatt.GATT_SUCCESS) {
                    warn("${arm.side} service discovery failed: $gattStatus")
                    arm.closeGatt(); bumpEpoch()
                    publish(G2Status.DISCONNECTED, ctx.getString(R.string.glasses_err_connect_failed, arm.label, gattStatus))
                    scheduleRetry()
                    return@post
                }
                mapGatt(arm, gatt)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, gattStatus: Int) {
            h.post {
                val arm = armFor(gatt) ?: return@post
                log("${arm.side} notify enabled (status $gattStatus)")
                arm.ready = arm.write != null
                onArmReady(arm)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, gattStatus: Int) {
            // 直接在回调线程上放行写队列，不绕 handler。
            armFor(gatt)?.writeLatch?.countDown()
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                handleNotify(gatt, characteristic, characteristic.value ?: ByteArray(0))
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(gatt, characteristic, value)
        }
    }

    private fun onArmDisconnected(arm: Arm, gattStatus: Int) {
        warn("${arm.side} disconnected (status $gattStatus)")
        val wasReady = arm.ready
        arm.closeGatt()
        bumpEpoch()
        if (!enabled || userStopped) {
            publish(if (enabled) G2Status.DISCONNECTED else G2Status.OFF)
            return
        }
        val msgRes = if (wasReady) R.string.glasses_err_disconnected else R.string.glasses_err_connect_failed
        publish(G2Status.DISCONNECTED, ctx.getString(msgRes, arm.label, gattStatus))
        scheduleRetry()
    }

    private fun mapGatt(arm: Arm, gatt: BluetoothGatt) {
        var write: BluetoothGattCharacteristic? = null
        var notify: BluetoothGattCharacteristic? = null
        for (svc in gatt.services) {
            for (ch in svc.characteristics) {
                val u = ch.uuid.toString().lowercase()
                val props = ch.properties
                val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                    (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                val canWrite = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                    (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                // 优先按 UUID 后缀选 …5401 / …5402；找不到就退回第一个可写 / 可通知的特征（麦克风特征除外）。
                if (canNotify && !u.endsWith(G2Protocol.MIC_UUID_SUFFIX) &&
                    (u.endsWith(G2Protocol.NOTIFY_UUID_SUFFIX) || notify == null)) {
                    notify = ch
                }
                if (canWrite && (u.endsWith(G2Protocol.WRITE_UUID_SUFFIX) || write == null)) {
                    write = ch
                }
            }
        }
        if (write == null) {
            warn("${arm.side}: no writable characteristic")
            arm.closeGatt(); bumpEpoch()
            publish(G2Status.DISCONNECTED, ctx.getString(R.string.glasses_err_no_service, arm.label))
            return
        }
        arm.write = write
        arm.notify = notify
        log("${arm.side} GATT mapped write=${write.uuid} notify=${notify?.uuid}")
        if (notify != null && enableNotify(gatt, notify)) {
            // 等 onDescriptorWrite 再标记 ready。
            return
        }
        arm.ready = true
        onArmReady(arm)
    }

    private fun enableNotify(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        gatt.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID) ?: return false
        val value = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
    }

    private fun onArmReady(arm: Arm) {
        log("${arm.side} ready")
        if (left.ready && right.ready) {
            log("both arms ready - authenticating")
            publish(G2Status.AUTHENTICATING, null)
            val e = epoch
            worker.execute { authenticate(e) }
        } else {
            publish(G2Status.CONNECTING)
        }
    }

    // ================================================================ 通知

    private fun handleNotify(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
        val arm = armFor(gatt)
        if (ch.uuid.toString().lowercase().endsWith(G2Protocol.MIC_UUID_SUFFIX)) return
        if (!G2Protocol.isEvenHubFrame(value)) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "◀ ${arm?.side} ${G2Protocol.hex(value)}")
            return
        }
        val flag = value[7].toInt() and 0xFF
        val pb = value.copyOfRange(8, value.size)
        if (flag == EvenHub.FLAG_EVENT || flag == EvenHub.FLAG_EVENT_ALT) {
            val ev = EvenHub.parseEvent(pb)
            if (ev != null) {
                log("◀ ${arm?.side} event $ev")
                if (ev is EvenHub.InEvent.Sys && EvenHub.SysEvent.isPageGone(ev.type)) {
                    h.post { onPageGone() }
                }
                return
            }
        }
        val r = EvenHub.parseResponse(pb)
        log("◀ ${arm?.side} ${EvenHub.describeResponse(r)}")
        resolveAck(r.magic, r.result)
    }

    /** 眼镜那边把我们的页面关掉了（用户退出 / 系统退出 / 异常）。 */
    private fun onPageGone() {
        log("page gone - will re-create before next paint")
        resetEvenHubState()
        publish()
        if (status == G2Status.READY) startHeartbeat()
    }

    // ================================================================ 写队列

    private val writeLock = ReentrantLock()

    /** 一次 GATT 写，并等它被底层送出（或超时）。调用方必须持有 [writeLock]。 */
    private fun performWrite(arm: Arm, data: ByteArray): Boolean {
        val gatt = arm.gatt ?: return false
        val ch = arm.write ?: return false
        val latch = CountDownLatch(1)
        arm.writeLatch = latch
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    ch.value = data
                    gatt.writeCharacteristic(ch)
                }
            }
        } catch (e: Exception) {
            warn("write threw: $e"); false
        }
        if (!ok) {
            // 底层忙 / 已断开：稍等再继续，writeWithoutResponse 本来就是尽力而为。
            sleep(8)
            return false
        }
        latch.await(WRITE_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return true
    }

    private fun writeTo(arm: Arm, data: ByteArray): Boolean = writeLock.withLock { performWrite(arm, data) }

    /** 一条多片消息整体占住写锁，不让心跳插到分片中间。 */
    private fun writeFrames(arm: Arm, frames: List<ByteArray>): Boolean = writeLock.withLock {
        var ok = true
        for (f in frames) ok = performWrite(arm, f) && ok
        ok
    }

    private fun sendBoth(data: ByteArray) {
        writeTo(left, data)
        sleep(18)
        writeTo(right, data)
        sleep(12)
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    // ================================================================ 鉴权（work 线程）

    private fun authenticate(e: Int) {
        val ts = (System.currentTimeMillis() / 1000L).toInt()
        for (pkt in G2Protocol.authPackets(ts)) {
            if (e != epoch) { log("auth aborted (link changed)"); return }
            sendBoth(pkt)
            sleep(100)
        }
        sleep(500)
        h.post {
            if (e != epoch || !left.ready || !right.ready) return@post
            log("authenticated - READY")
            retryCount = 0
            Settings.setGlassesArms(ctx, left.address, left.name, right.address, right.name)
            publish(G2Status.READY, null)
            startHeartbeat()
        }
    }

    // ================================================================ 心跳

    private val heartbeatQueued = AtomicBoolean(false)
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (status != G2Status.READY) return
            if (heartbeatQueued.compareAndSet(false, true)) {
                worker.execute {
                    heartbeatQueued.set(false)
                    sendHeartbeat()
                }
            }
            h.postDelayed(this, if (evenHubPrimed) HUB_HEARTBEAT_MS else TEXT_HEARTBEAT_MS)
        }
    }

    private fun startHeartbeat() {
        h.removeCallbacks(heartbeatRunnable)
        h.postDelayed(heartbeatRunnable, if (evenHubPrimed) HUB_HEARTBEAT_MS else TEXT_HEARTBEAT_MS)
    }

    private fun cancelHeartbeat() {
        h.removeCallbacks(heartbeatRunnable)
    }

    private var hbSeq = 0xC0

    private fun sendHeartbeat() {
        if (!left.ready || !right.ready) return
        if (evenHubPrimed) {
            sendHubHeartbeat()
        } else {
            val hb = G2Protocol.heartbeat(hbSeq)
            hbSeq = (hbSeq + 1) and 0xFF
            if (hbSeq == 0) hbSeq = 0xC0
            writeTo(left, hb)
            writeTo(right, hb)
        }
    }

    /** EvenHub Cmd=12，只发右腿，而且必须在第一次 CREATE 之后（之前插件任务还不存在）。 */
    private fun sendHubHeartbeat() {
        if (!right.ready || !evenHubPrimed) return
        val frames = G2Protocol.framePb(nextSeq(), EvenHub.SID, EvenHub.FLAG_REQUEST, EvenHub.heartbeat(nextMagic()), chunk)
        writeFrames(right, frames)
    }

    // ================================================================ EvenHub：页面与图片（work 线程）

    @Volatile private var evenHubPrimed = false
    @Volatile private var pageShown = false
    private var warmedUp = false
    private var ehSeq = 0
    private var ehMagic = 1
    private var ehSession = 1
    private var sessionJump = false
    private val tileFingerprint = HashMap<Int, Int>()
    @Volatile private var lastMtu = 247

    private val header = EvenHub.TextContainer(1, "hdr", 0, 0, EvenHub.LENS_W, HEADER_H, " ")
    private val grid = listOf(
        EvenHub.ImageContainer(10, "t0", 0, HEADER_H, TILE_W, TILE_H),
        EvenHub.ImageContainer(11, "t1", TILE_W, HEADER_H, TILE_W, TILE_H),
        EvenHub.ImageContainer(12, "t2", 0, HEADER_H + TILE_H, TILE_W, TILE_H),
        EvenHub.ImageContainer(13, "t3", TILE_W, HEADER_H + TILE_H, TILE_W, TILE_H),
    )

    /** EvenHub 分片大小：min(232, MTU - 3 - 8)。 */
    private val chunk: Int get() = maxOf(20, minOf(232, lastMtu - 3 - 8))

    private fun nextSeq(): Int { val s = ehSeq; ehSeq = (ehSeq + 1) and 0xFF; return s }
    private fun nextMagic(): Int { val m = ehMagic; ehMagic = if (ehMagic >= 120) 1 else ehMagic + 1; return m }
    private fun nextSession(): Int {
        ehSession += if (sessionJump) 2 else 1
        sessionJump = false
        if (ehSession >= 250) ehSession = 2
        return ehSession
    }

    private fun resetEvenHubState() {
        evenHubPrimed = false
        pageShown = false
        warmedUp = false
        ehSeq = 0; ehMagic = 1; ehSession = 1; sessionJump = false
        tileFingerprint.clear()
        synchronized(pendingAcks) {
            pendingAcks.values.forEach { it.resolve(null) }
            pendingAcks.clear()
        }
    }

    // ---- ack ----

    private class AckBox {
        private val latch = CountDownLatch(1)
        @Volatile var result: Int? = null
        @Volatile var resolved = false

        fun resolve(r: Int?) {
            if (resolved) return
            resolved = true
            result = r
            latch.countDown()
        }

        /** 等到 ack 或超时；超时返回 null。 */
        fun await(timeoutMs: Long): Int? {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return result
        }
    }

    private val pendingAcks = HashMap<Int, AckBox>()

    private fun resolveAck(magic: Int, result: Int?) {
        val box = synchronized(pendingAcks) { pendingAcks.remove(magic) } ?: return
        box.resolve(result)
    }

    /** 发一条 EvenHub 消息到右腿，不等 ack。 */
    private fun fire(pb: ByteArray, magic: Int): AckBox {
        val box = AckBox()
        synchronized(pendingAcks) { pendingAcks[magic] = box }
        val frames = G2Protocol.framePb(nextSeq(), EvenHub.SID, EvenHub.FLAG_REQUEST, pb, chunk)
        writeFrames(right, frames)
        return box
    }

    private fun send(pb: ByteArray, magic: Int, timeoutMs: Long): Int? {
        val box = fire(pb, magic)
        val r = box.await(timeoutMs)
        if (r == null) synchronized(pendingAcks) { if (pendingAcks[magic] === box) pendingAcks.remove(magic) }
        return r
    }

    /** 前奏 + CREATE 页面（文本头 + 2×2 图片容器）。 */
    private fun prime(e: Int): Boolean {
        if (evenHubPrimed) return true
        if (e != epoch || !right.ready) return false
        log("prime: prelude + create page (chunk $chunk)")
        writeTo(right, EvenHub.PRELUDE)
        sleep(500)
        if (e != epoch) return false
        val r = send(EvenHub.createStartupPage(listOf(header), grid, magic = 201), 201, 3000)
        // createOK=0 在线上被省略，没 ack 也当成功；奇数才是失败。
        log("create ack: ${r ?: "(none)"}")
        if (!EvenHub.Result.isOk(r)) return false
        evenHubPrimed = true
        sleep(200)
        return true
    }

    /** CREATE 之后的第一次图片流会被固件丢掉，先推一块全黑的瓦片垫一下。 */
    private fun warmup(e: Int): Boolean {
        if (warmedUp) return true
        val c = grid[0]
        val bmp = Bmp4.black(TILE_W, TILE_H)
        val sid = nextSession()
        var idx = 0
        var off = 0
        while (off < bmp.size) {
            if (e != epoch) return false
            val end = minOf(off + EvenHub.IMAGE_FRAGMENT_BYTES, bmp.size)
            val magic = nextMagic()
            val r = send(EvenHub.imageRawData(c.id, c.name, sid, bmp.size, idx, bmp.copyOfRange(off, end), magic), magic, 10_000)
            if (r == null) { warn("warmup frag $idx: no ack"); return false }
            idx++
            off = end
        }
        warmedUp = true
        log("warmup ok")
        return true
    }

    /** 每个分片都确认成功后才允许缓存瓦片，避免局部缺图被当作已显示。 */
    private fun streamTile(c: EvenHub.ImageContainer, bmp: ByteArray, e: Int): Boolean {
        val sid = nextSession()
        val inFlight = ArrayDeque<AckBox>()
        var allConfirmed = true

        fun drainOne(): Boolean {
            val box = inFlight.removeFirst()
            if (box.await(1000) != EvenHub.Result.IMAGE_OK) allConfirmed = false
            return e == epoch
        }

        var idx = 0
        var off = 0
        while (off < bmp.size) {
            if (e != epoch) return false
            while (inFlight.size >= ACK_WINDOW) {
                if (!drainOne()) return false
            }
            val end = minOf(off + EvenHub.IMAGE_FRAGMENT_BYTES, bmp.size)
            val magic = nextMagic()
            inFlight.addLast(fire(EvenHub.imageRawData(c.id, c.name, sid, bmp.size, idx, bmp.copyOfRange(off, end), magic), magic))
            idx++
            off = end
        }
        while (inFlight.isNotEmpty()) {
            if (!drainOne()) return false
        }
        return allConfirmed
    }

    private fun streamTiles(tiles: List<Bmp4.Tile>, e: Int): Boolean {
        for ((i, t) in tiles.withIndex()) {
            if (i >= grid.size) break
            val c = grid[i]
            val fp = t.bmp.contentHashCode()
            if (tileFingerprint[c.id] == fp) { log("tile ${c.name}: unchanged"); continue }
            // 瓦片之间插一次心跳，别让 ~10 s 的插件看门狗在推图中途触发。
            sendHubHeartbeat()
            if (!streamTile(c, t.bmp, e)) {
                warn("tile ${c.name}: FAILED")
                return false
            }
            tileFingerprint[c.id] = fp
        }
        return true
    }

    private fun recover(e: Int): Boolean {
        warn("recovering image session (shutdown + session jump + re-create)")
        if (evenHubPrimed) {
            val m = nextMagic()
            send(EvenHub.shutDown(m), m, 1500)
            sleep(150)
        }
        evenHubPrimed = false
        warmedUp = false
        tileFingerprint.clear()
        sessionJump = true
        synchronized(pendingAcks) { pendingAcks.values.forEach { it.resolve(null) }; pendingAcks.clear() }
        return prime(e)
    }

    /**
     * 把一张 [PAGE_W]×[PAGE_H] 的位图推到眼镜上（阻塞，必须在 work 线程调用，见 [execute]）。
     * 返回是否成功。
     */
    fun showPage(bitmap: Bitmap): Boolean {
        require(bitmap.width == PAGE_W && bitmap.height == PAGE_H) { "page must be ${PAGE_W}x$PAGE_H" }
        if (!isReady || !left.ready || !right.ready) { warn("showPage: not ready"); return false }
        val e = epoch
        cancelHeartbeat()
        val t0 = System.currentTimeMillis()
        val tiles = Bmp4.tiles(bitmap, TILE_W, TILE_H, 2, 2)
        var ok = prime(e) && warmup(e) && streamTiles(tiles, e)
        if (!ok && e == epoch && right.ready) {
            ok = recover(e) && warmup(e) && streamTiles(tiles, e)
        }
        if (e == epoch) {
            pageShown = ok && evenHubPrimed
            h.post {
                publish()
                if (status == G2Status.READY) startHeartbeat()
            }
        }
        log("showPage: ${if (ok) "ok" else "FAILED"} in ${System.currentTimeMillis() - t0} ms")
        return ok
    }

    /** 关掉我们的页面，眼镜回到自己的仪表盘（阻塞，work 线程）。 */
    fun dismissPage() {
        if (!evenHubPrimed) return
        val e = epoch
        cancelHeartbeat()
        if (right.ready) {
            val m = nextMagic()
            val r = send(EvenHub.shutDown(m), m, 1500)
            log("shutdown ack: ${r ?: "(none)"}")
        }
        if (e == epoch) {
            evenHubPrimed = false
            pageShown = false
            warmedUp = false
            tileFingerprint.clear()
            h.post {
                publish()
                if (status == G2Status.READY) startHeartbeat()
            }
        }
    }
}
