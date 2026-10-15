package com.stepos.whisper.glasses

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.stepos.whisper.data.FactCard
import com.stepos.whisper.data.UserFact
import com.stepos.whisper.ui.FactFormat

/** 中文按实际行高分页，跨页条目保留完整布局，不省略正文。 */
object FactsRenderer {
    private const val W = G2Connection.PAGE_W
    private const val H = G2Connection.PAGE_H
    private const val PAD = 10f
    private const val BODY_TOP = 48f
    private const val FOOTER_H = 24f
    private const val LINE_GAP = 2f

    private fun paint(size: Float, bold: Boolean, gray: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(gray, gray, gray)
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    data class Slice(val layout: StaticLayout, val top: Int, val bottom: Int, val y: Float)
    data class Page(val title: String, val slices: List<Slice>, val number: Int, val total: Int) {
        fun render(): Bitmap {
            val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)
            val titlePaint = paint(26f, true, 255)
            val titleText = TextUtils.ellipsize(title, titlePaint, W - PAD * 2, TextUtils.TruncateAt.END)
            canvas.drawText(titleText, 0, titleText.length, PAD, PAD - titlePaint.ascent(), titlePaint)
            canvas.drawRect(PAD, BODY_TOP - 7, W - PAD, BODY_TOP - 6, paint(20f, true, 170))
            for (slice in slices) {
                canvas.save()
                canvas.clipRect(PAD, slice.y, W - PAD, slice.y + slice.bottom - slice.top)
                canvas.translate(PAD, slice.y - slice.top)
                slice.layout.draw(canvas)
                canvas.restore()
            }
            if (total > 1) {
                val footer = paint(18f, false, 170)
                canvas.drawText("$number / $total", PAD, H - PAD - footer.descent(), footer)
            }
            return bitmap
        }
    }

    fun pages(name: String, cards: List<FactCard>, newItems: Boolean = false): List<Page> {
        val todos = cards.filter { it.fact.kind != UserFact.KIND_FACT }.map { it.fact }
        val facts = cards.filter { it.fact.kind == UserFact.KIND_FACT }.map { it.fact }
        val sections = ArrayList<Pair<String, List<String>>>()
        if (todos.isNotEmpty()) sections.add("待办" to todos.map { "[ ] " + FactFormat.oneLine(it) })
        if (facts.isNotEmpty()) sections.add("事实" to facts.map { "• " + it.content })
        return paginate(name + if (newItems) " · 新增" else "", sections)
    }

    fun samplePages(): List<Page> = paginate(
        "张三",
        listOf(
            "待办" to listOf("[ ] 周三下午三点去公司开会（会议室 A · 张三）", "[ ] 把合同发给李四"),
            "事实" to listOf("• 最近在准备考研", "• 不吃辣", "• 这一行是用来测试自动换行的，长度故意超过一行的宽度看看效果"),
        )
    )

    internal fun paginate(title: String, sections: List<Pair<String, List<String>>>): List<Page> {
        val width = (W - PAD * 2).toInt()
        val bottom = H - PAD - FOOTER_H
        val bodyPaint = paint(21f, false, 235)
        val headingPaint = paint(20f, true, 170)
        val completed = ArrayList<List<Slice>>()
        var slices = ArrayList<Slice>()
        var y = BODY_TOP
        fun nextPage() {
            if (slices.isNotEmpty()) completed.add(slices)
            slices = ArrayList()
            y = BODY_TOP
        }
        fun layout(text: String, p: TextPaint): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(p), width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(LINE_GAP, 1f)
                .setIncludePad(false)
                .build()
        for ((heading, items) in sections) {
            val head = layout(heading, headingPaint)
            if (y + head.height + bodyPaint.fontSpacing + LINE_GAP > bottom) nextPage()
            slices.add(Slice(head, 0, head.height, y))
            y += head.height + LINE_GAP
            for (item in items) {
                val body = layout(item, bodyPaint)
                var first = 0
                while (first < body.lineCount) {
                    val top = body.getLineTop(first)
                    var end = first
                    while (end < body.lineCount && y + body.getLineBottom(end) - top <= bottom) end++
                    if (end == first) {
                        nextPage()
                        continue
                    }
                    val lastBottom = body.getLineBottom(end - 1)
                    slices.add(Slice(body, top, lastBottom, y))
                    y += lastBottom - top + LINE_GAP
                    first = end
                    if (first < body.lineCount) nextPage()
                }
            }
            y += 4f
        }
        nextPage()
        if (completed.isEmpty()) completed.add(emptyList())
        return completed.mapIndexed { index, blocks -> Page(title, blocks, index + 1, completed.size) }
    }
}
