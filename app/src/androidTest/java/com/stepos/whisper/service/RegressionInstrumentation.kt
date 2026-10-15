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

/** 无网络，仅合成数据和隔离测试数据库的设备回归检查。 */
class RegressionInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            VoiceprintRegressionInstrumentation().runChecks()
            ImmediateExtractionRegressionChecks.runChecks(targetContext)
            NotificationRegressionChecks.runChecks(this)
            com.stepos.whisper.ui.NotificationCardUiChecks.runChecks(this)
            com.stepos.whisper.ui.CardStackUiChecks.runChecks(this)
            FactMergeRegressionChecks.runChecks(this)
            NotificationMigrationChecks.runChecks(this)
            pendingChecks()
            paginationChecks()
            todoChecks()
            rotationChecks()
            result.putString("stream", "PASS: notification sources/copies/margins, v9-v10 migration, card top/browse/drag, structured placeholders/JSON rejection, merge fields/preview/fallback, voiceprint, pagination, todo text/pixels/BMP, show cancellation\n")
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
        val samples = FactsRenderer.samplePages().flatMap { it.slices }.map { it.layout.text.toString() }
        check(samples.any { it.startsWith("待办：") })
        check(samples.none { it.startsWith("[ ] ") })
        check(FactsRenderer.pages("李四", emptyList()).size == 1)
    }

    private fun todoChecks() {
        for (withExtras in listOf(false, true)) {
            val todo = UserFact().apply {
                kind = UserFact.KIND_TODO
                content = "明天下午开会，记得带合同"
                if (withExtras) {
                    dueTime = "明天下午三点"
                    location = "会议室 A"
                    people = "演示联系人"
                }
            }
            val card = FactCard().apply { fact = todo }
            val page = FactsRenderer.pages("演示联系人", listOf(card)).first()
            val body = page.slices.first { it.layout.text.startsWith("待办：") }
            check(body.layout.text.toString() == "待办：" + com.stepos.whisper.ui.FactFormat.oneLine(todo))
            if (withExtras) {
                check(body.layout.text.contains(todo.dueTime))
                check(body.layout.text.contains(todo.location))
                check(body.layout.text.contains(todo.people))
            }
            check(body.layout.text.contains(todo.content))
            val bitmap = page.render()
            var lit = 0
            for (y in body.y.toInt() until (body.y + body.bottom - body.top).toInt()) {
                for (x in 45 until 566) {
                    if (com.stepos.whisper.glasses.Bmp4.gray4(bitmap.getPixel(x, y)) > 0) lit++
                }
            }
            check(lit > 100) { "todo body missing (divider excluded)" }
            val tiles = com.stepos.whisper.glasses.Bmp4.tiles(bitmap, 288, 128, 2, 2)
            for (tile in tiles) {
                for (y in 0 until tile.h) for (x in 0 until tile.w) {
                    val offset = com.stepos.whisper.glasses.Bmp4.PIXEL_OFFSET + (tile.h - 1 - y) * 144 + x / 2
                    val packed = tile.bmp[offset].toInt() and 255
                    val gray = if (x % 2 == 0) packed shr 4 else packed and 15
                    check(gray == com.stepos.whisper.glasses.Bmp4.gray4(bitmap.getPixel(tile.x + x, tile.y + y)))
                }
            }
            bitmap.recycle()
        }
    }

    private fun rotationChecks() {
        val type = GlassesManager::class.java
        val field = type.getDeclaredField("generation").apply { isAccessible = true }
        val generation = field.get(null) as AtomicLong
        val cancel = type.getDeclaredMethod("cancelShow").apply { isAccessible = true }
        val old = generation.get()
        val token = cancel.invoke(GlassesManager) as Long
        check(token > old)
        val newToken = cancel.invoke(GlassesManager) as Long
        check(newToken > token)
        waitForIdleSync()
        val runnable = type.getDeclaredField("dismissRunnable").apply { isAccessible = true }.get(null)
        check(runnable == null)
    }
}
