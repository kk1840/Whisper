package com.stepos.whisper.glasses

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import com.stepos.whisper.Settings
import java.util.concurrent.atomic.AtomicLong
import com.stepos.whisper.data.FactCard

/**
 * 应用级单例：持有唯一的 [G2Connection]，录音服务和配对界面操作的是同一条连接。
 * 负责把事项渲染成图片推到眼镜，并在 [PAGE_TIMEOUT_MS] 后自动关掉页面。
 */
object GlassesManager {
    private const val TAG = "G2"
    /** 推送的页面在眼镜上停留多久。 */
    private const val PAGE_TIMEOUT_MS = 60_000L

    @Volatile private var connection: G2Connection? = null
    private val main = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null
    private const val PAGE_INTERVAL_MS = 8_000L
    private val generation = AtomicLong()
    private var turnPage: Runnable? = null
    private var activePages: List<FactsRenderer.Page> = emptyList()
    private var activeIndex = 0
    private var pagesShown = 0
    private var visibleMs = 0L

    private fun cancelRotation(): Long {
        val token = generation.incrementAndGet()
        main.post {
            if (generation.get() == token) {
                turnPage?.let { main.removeCallbacks(it) }
                turnPage = null
                activePages = emptyList()
                dismissRunnable?.let { main.removeCallbacks(it) }
                dismissRunnable = null
            }
        }
        return token
    }

    /** 在 Application.onCreate 调用一次。 */
    @JvmStatic
    fun init(context: Context) {
        if (connection != null) return
        synchronized(this) {
            if (connection == null) connection = G2Connection(context.applicationContext)
        }
    }

    private fun conn(): G2Connection = connection ?: error("GlassesManager.init() not called")

    @JvmStatic
    val state: LiveData<G2State> get() = conn().state

    @JvmStatic
    val scanResults: LiveData<List<ScanArm>> get() = conn().scanResults

    @JvmStatic
    val isReady: Boolean get() = connection?.isReady == true

    /** 打开 / 关闭眼镜推送（同时写入设置）。 */
    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean) {
        Settings.setGlassesPushEnabled(context, enabled)
        applyEnabled(context)
    }

    /** 按设置启停连接；录音服务启动时调用。 */
    @JvmStatic
    fun applyEnabled(context: Context) {
        init(context)
        if (Settings.isGlassesPushEnabled(context)) conn().start() else {
            cancelRotation()
            conn().stop()
        }
    }

    @JvmStatic fun scanNow() = conn().scanNow()
    @JvmStatic fun disconnect() { cancelRotation(); conn().disconnect() }
    @JvmStatic fun forget() { cancelRotation(); conn().forget() }
    @JvmStatic fun assignSide(address: String, side: Side) = conn().assignSide(address, side)

    /**
     * 把 [name] 的待办和事实推到眼镜上。眼镜没连好就直接返回 false；
     * 推送在 BLE 工作线程上异步进行，结果回调在主线程。
     */
    @JvmStatic
    @JvmOverloads
    fun show(name: String, cards: List<FactCard>, onResult: ((Boolean) -> Unit)? = null, isNew: Boolean = false): Boolean {
        val c = connection ?: return false
        if (!c.isReady || cards.isEmpty()) return false
        val token = cancelRotation()
        c.execute {
            var pages: List<FactsRenderer.Page> = emptyList()
            val ok = runCatching {
                if (generation.get() != token) return@runCatching false
                pages = FactsRenderer.pages(name, cards, newItems = isNew)
                sendPage(c, pages.first(), token)
            }.onFailure { Log.e(TAG, "show failed", it) }.getOrDefault(false)
            main.post {
                if (generation.get() == token) beginRotation(c, pages, token, ok)
                onResult?.invoke(ok && generation.get() == token)
            }
        }
        return true
    }

    /** 刚提取出的新待办/事实：页面带“新增”标记，供 Java 侧语义化调用。 */
    @JvmStatic
    fun showNew(name: String, cards: List<FactCard>): Boolean =
        show(name, cards, isNew = true)

    /** 推一张内置示例图，验证链路。 */
    @JvmStatic
    fun pushTest(onResult: (Boolean, String?) -> Unit) {
        val c = connection
        if (c == null || !c.isReady) {
            onResult(false, null)
            return
        }
        val token = cancelRotation()
        c.execute {
            var err: String? = null
            var pages: List<FactsRenderer.Page> = emptyList()
            val ok = runCatching {
                if (generation.get() != token) return@runCatching false
                pages = FactsRenderer.samplePages()
                sendPage(c, pages.first(), token)
            }.onFailure { Log.e(TAG, "pushTest failed", it); err = it.message }.getOrDefault(false)
            main.post {
                if (generation.get() == token) beginRotation(c, pages, token, ok)
                onResult(ok && generation.get() == token, err)
            }
        }
    }

    /** 立即关掉眼镜上的页面。 */
    @JvmStatic
    fun dismiss() {
        val token = cancelRotation()
        val c = connection ?: return
        c.execute {
            if (generation.get() == token) {
                runCatching { c.dismissPage() }.onFailure { Log.e(TAG, "dismiss failed", it) }
            }
        }
    }

    private fun sendPage(c: G2Connection, page: FactsRenderer.Page, token: Long): Boolean {
        if (generation.get() != token || !c.isReady) return false
        val bitmap = page.render()
        return try { c.showPage(bitmap) } finally { bitmap.recycle() }
    }

    private fun beginRotation(c: G2Connection, pages: List<FactsRenderer.Page>, token: Long, ok: Boolean) {
        if (!ok) { dismiss(); return }
        activePages = pages
        activeIndex = 0
        pagesShown = 1
        visibleMs = 0
        if (pages.size == 1) {
            val timeout = Runnable { if (generation.get() == token) dismiss() }
            dismissRunnable = timeout
            main.postDelayed(timeout, PAGE_TIMEOUT_MS)
        } else scheduleNext(c, token)
    }

    private fun scheduleNext(c: G2Connection, token: Long) {
        val next = Runnable {
            if (generation.get() != token) return@Runnable
            visibleMs += PAGE_INTERVAL_MS
            if (pagesShown >= activePages.size && visibleMs >= PAGE_TIMEOUT_MS) {
                dismiss()
                return@Runnable
            }
            activeIndex = (activeIndex + 1) % activePages.size
            val page = activePages[activeIndex]
            c.execute {
                val ok = runCatching { sendPage(c, page, token) }
                    .onFailure { Log.e(TAG, "turn page failed", it) }.getOrDefault(false)
                main.post {
                    if (generation.get() == token) {
                        if (ok) { pagesShown++; scheduleNext(c, token) } else dismiss()
                    }
                }
            }
        }
        turnPage = next
        main.postDelayed(next, PAGE_INTERVAL_MS)
    }
}
