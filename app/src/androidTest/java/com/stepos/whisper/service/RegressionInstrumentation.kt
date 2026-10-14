package com.stepos.whisper.service

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import com.stepos.whisper.data.FactCard
import com.stepos.whisper.data.UserFact
import com.stepos.whisper.glasses.FactsRenderer
import com.stepos.whisper.glasses.GlassesManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/** 无网络、无数据库写入的设备回归检查。 */
class RegressionInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            pendingChecks()
            paginationChecks()
            rotationChecks()
            result.putString("stream", "PASS: pending resolution, manual/late result, stop sweep, full text pagination, bounds, rotation cancellation\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun pendingChecks() {
        val pending = "待识别"
        val unknown = "未知"
        ServiceState.clearLines()
        val id = ServiceState.addLine(1, 2, pending, "检查", TranscriptLine.NO_USER, 0, 0)
        check(ServiceState.resolvePendingLine(id, pending, unknown))
        check(ServiceState.latestLine().speaker == unknown)
        check(!ServiceState.resolvePendingLine(id, pending, unknown))
        // 超时后成功结果仍可落地。
        ServiceState.relabelLine(id, "张三", 3, 123)
        check(ServiceState.latestLine().userId == 3L)
        val manual = ServiceState.addLine(3, 4, pending, "手动指认", TranscriptLine.NO_USER, 0, 0)
        ServiceState.relabelLine(manual, "李四", 4, 456)
        check(!ServiceState.resolvePendingLine(manual, pending, unknown))
        ServiceState.addLine(5, 6, pending, "停止", TranscriptLine.NO_USER, 0, 0)
        ServiceState.resolveAllPending(pending, unknown)
        check(ServiceState.latestLine().speaker == unknown)
        check(!ServiceState.resolvePendingLine(manual, pending, unknown))
        ServiceState.clearLines()
    }

    private fun paginationChecks() {
        val content = (1..80).joinToString("\n") { "第${it}条完整中文内容：会议事项不能省略，ABCDEFGHIJKLMNOPQRSTUVWXYZ。" }
        val fact = UserFact().apply { kind = UserFact.KIND_FACT; this.content = content }
        val card = FactCard().apply { this.fact = fact }
        val pages = FactsRenderer.pages("张三", listOf(card))
        check(pages.size > 8)
        val bodies = pages.flatMap { it.slices }.filter { it.layout.text.toString().startsWith("• ") }
        val layout = bodies.first().layout
        var expectedTop = 0
        for (slice in bodies) {
            check(slice.layout === layout)
            check(slice.top == expectedTop) { "missing/duplicate lines" }
            expectedTop = slice.bottom
        }
        check(expectedTop == layout.height)
        check(layout.text.toString() == "• $content")
        for ((index, page) in pages.withIndex()) {
            check(page.number == index + 1 && page.total == pages.size)
            for (slice in page.slices) {
                check(slice.y >= 48f)
                check(slice.y + slice.bottom - slice.top <= 222f)
            }
            val bitmap = page.render()
            check(bitmap.width == 576 && bitmap.height == 256)
            if (index < 2) {
                val bytes = ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes)
                sendStatus(0, Bundle().apply {
                    putString("page$index", android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP))
                })
            }
            bitmap.recycle()
        }
        check(FactsRenderer.samplePages().isNotEmpty())
        check(FactsRenderer.pages("李四", emptyList()).size == 1)
    }

    private fun rotationChecks() {
        val type = GlassesManager::class.java
        val field = type.getDeclaredField("generation").apply { isAccessible = true }
        val generation = field.get(null) as AtomicLong
        val cancel = type.getDeclaredMethod("cancelRotation").apply { isAccessible = true }
        val old = generation.get()
        val token = cancel.invoke(GlassesManager) as Long
        check(token > old)
        val newToken = cancel.invoke(GlassesManager) as Long
        check(newToken > token)
        waitForIdleSync()
        val pages = type.getDeclaredField("activePages").apply { isAccessible = true }.get(null) as List<*>
        check(pages.isEmpty())
        val runnable = type.getDeclaredField("turnPage").apply { isAccessible = true }.get(null)
        check(runnable == null)
    }
}
