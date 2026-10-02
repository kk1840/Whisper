package com.stepos.whisper.glasses

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.stepos.whisper.R
import com.stepos.whisper.Settings
import com.stepos.whisper.databinding.ActivityGlassesPairingBinding
import com.stepos.whisper.databinding.ItemGlassesArmBinding

/**
 * Even G2 配对页：总开关、连接状态、扫描 / 断开 / 忘记 / 测试、扫描结果列表（可手动指定左右），
 * 以及一段完整的「配对步骤与注意事项」说明。
 */
class GlassesPairingActivity : AppCompatActivity() {
    private lateinit var b: ActivityGlassesPairingBinding
    private val adapter = ArmAdapter()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshGuidance(); if (hasBlePermissions()) GlassesManager.applyEnabled(this) }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshGuidance() }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshGuidance()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityGlassesPairingBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        GlassesManager.init(this)

        b.enableSwitch.isChecked = Settings.isGlassesPushEnabled(this)
        b.enableSwitch.setOnCheckedChangeListener { _, checked ->
            GlassesManager.setEnabled(this, checked)
            if (checked && !hasBlePermissions()) requestBlePermissions()
        }

        b.scan.setOnClickListener {
            when {
                !hasBlePermissions() -> requestBlePermissions()
                !isBluetoothOn() -> requestEnableBluetooth()
                else -> {
                    if (!b.enableSwitch.isChecked) b.enableSwitch.isChecked = true
                    GlassesManager.scanNow()
                }
            }
        }
        b.disconnect.setOnClickListener { GlassesManager.disconnect() }
        b.forget.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.glasses_forget_confirm)
                .setPositiveButton(R.string.glasses_action_forget) { _, _ -> GlassesManager.forget() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
        b.test.setOnClickListener { sendTest() }
        b.dismiss.setOnClickListener { GlassesManager.dismiss() }
        b.grant.setOnClickListener { requestBlePermissions() }
        b.enableBt.setOnClickListener { requestEnableBluetooth() }

        b.scanList.layoutManager = LinearLayoutManager(this)
        b.scanList.adapter = adapter

        GlassesManager.state.observe(this, ::render)
        GlassesManager.scanResults.observe(this) { list ->
            adapter.submitList(list)
            b.scanEmpty.visible = list.isEmpty()
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        refreshGuidance()
    }

    override fun onStop() {
        unregisterReceiver(btReceiver)
        super.onStop()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ---------------------------------------------------------------- 渲染

    private fun render(s: G2State) {
        val enabled = s.status != G2Status.OFF
        b.statusText.text = getString(
            when (s.status) {
                G2Status.OFF -> R.string.glasses_status_off
                G2Status.DISCONNECTED -> R.string.glasses_status_disconnected
                G2Status.SCANNING -> R.string.glasses_status_scanning
                G2Status.CONNECTING -> R.string.glasses_status_connecting
                G2Status.AUTHENTICATING -> R.string.glasses_status_authenticating
                G2Status.READY -> R.string.glasses_status_ready
            }
        )
        val dot = when (s.status) {
            G2Status.READY -> Color.parseColor("#FF43A047")
            G2Status.OFF, G2Status.DISCONNECTED -> Color.parseColor("#FF9E9E9E")
            else -> Color.parseColor("#FFFFB300")
        }
        b.statusDot.backgroundTintList = ColorStateList.valueOf(dot)
        b.leftText.text = armText(s.left)
        b.rightText.text = armText(s.right)

        val leftName = Settings.getGlassesLeftName(this) ?: Settings.getGlassesLeftAddress(this)
        val rightName = Settings.getGlassesRightName(this) ?: Settings.getGlassesRightAddress(this)
        b.rememberedText.text = if (leftName != null && rightName != null)
            getString(R.string.glasses_remembered, leftName, rightName)
        else getString(R.string.glasses_remembered_none)

        b.pageShownText.visible = s.pageShown
        b.errorText.visible = s.error != null
        b.errorText.text = s.error?.let { getString(R.string.glasses_last_error, it) }

        val busy = s.status == G2Status.SCANNING || s.status == G2Status.CONNECTING ||
            s.status == G2Status.AUTHENTICATING
        b.disconnect.isEnabled = enabled && (busy || s.status == G2Status.READY)
        b.forget.isEnabled = leftName != null || rightName != null
        b.test.isEnabled = s.isReady
        b.dismiss.isEnabled = s.isReady && s.pageShown
        b.scan.isEnabled = s.status != G2Status.SCANNING
    }

    private fun armText(a: ArmState): String {
        val who = a.name ?: a.address ?: return getString(R.string.glasses_arm_none)
        return when {
            a.ready -> getString(R.string.glasses_arm_ready, who)
            a.connected -> getString(R.string.glasses_arm_connected, who)
            a.found -> getString(R.string.glasses_arm_found, who)
            else -> who
        }
    }

    /** 权限 / 蓝牙开关的引导行。 */
    private fun refreshGuidance() {
        b.permissionRow.visible = !hasBlePermissions()
        b.btRow.visible = hasBlePermissions() && !isBluetoothOn()
    }

    // ---------------------------------------------------------------- 动作

    private fun sendTest() {
        if (!GlassesManager.isReady) {
            Toast.makeText(this, R.string.glasses_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        b.test.isEnabled = false
        GlassesManager.pushTest { ok, err ->
            if (isFinishing || isDestroyed) return@pushTest
            b.test.isEnabled = GlassesManager.isReady
            val msg = if (ok) getString(R.string.glasses_test_sent)
            else getString(R.string.glasses_test_failed, err ?: getString(R.string.glasses_status_disconnected))
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun blePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasBlePermissions(): Boolean =
        blePermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requestBlePermissions() = permissionLauncher.launch(blePermissions())

    private fun isBluetoothOn(): Boolean =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true

    private fun requestEnableBluetooth() {
        if (!hasBlePermissions()) { requestBlePermissions(); return }
        enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    private fun pickSide(arm: ScanArm) {
        val sides = arrayOf(getString(R.string.glasses_arm_left), getString(R.string.glasses_arm_right))
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.glasses_pick_side_title, arm.name))
            .setItems(sides) { _, which ->
                GlassesManager.assignSide(arm.address, if (which == 0) Side.LEFT else Side.RIGHT)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- 列表

    private inner class ArmAdapter : ListAdapter<ScanArm, ArmAdapter.Holder>(object : DiffUtil.ItemCallback<ScanArm>() {
        override fun areItemsTheSame(a: ScanArm, b: ScanArm) = a.address == b.address
        override fun areContentsTheSame(a: ScanArm, b: ScanArm) = a == b
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemGlassesArmBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val arm = getItem(position)
            val v = holder.b
            v.name.text = arm.name
            val sideText = when (arm.side) {
                Side.LEFT -> getString(R.string.glasses_side_left)
                Side.RIGHT -> getString(R.string.glasses_side_right)
                null -> getString(R.string.glasses_side_unknown)
            }
            val assignedText = when (arm.assigned) {
                Side.LEFT -> getString(R.string.glasses_arm_left)
                Side.RIGHT -> getString(R.string.glasses_arm_right)
                null -> "—"
            }
            v.meta.text = getString(R.string.glasses_scan_item_meta, arm.address, arm.rssi, assignedText)
            v.sideBadge.text = sideText
            val badge = when (arm.assigned ?: arm.side) {
                Side.LEFT -> Color.parseColor("#FF3D5AFE")
                Side.RIGHT -> Color.parseColor("#FF00897B")
                null -> Color.parseColor("#FF9E9E9E")
            }
            v.sideBadge.backgroundTintList = ColorStateList.valueOf(badge)
            holder.itemView.setOnClickListener { pickSide(arm) }
        }

        inner class Holder(val b: ItemGlassesArmBinding) : RecyclerView.ViewHolder(b.root)
    }
}

/** core-ktx 没有引入，自己写一个。 */
private var View.visible: Boolean
    get() = visibility == View.VISIBLE
    set(v) { visibility = if (v) View.VISIBLE else View.GONE }
