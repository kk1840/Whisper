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

/**
 * 把某个人的待办和事实画成一张黑底白字的位图（[G2Connection.PAGE_W]×[G2Connection.PAGE_H]），
 * 用系统字体渲染中文，绕开眼镜固件字体是否带 CJK 的问题。镜片是单色绿光，只用灰度。
 */
object FactsRenderer {
    private const val W = G2Connection.PAGE_W
    private const val H = G2Connection.PAGE_H
    private const val PAD = 10f
    private const val TITLE_SIZE = 26f
    private const val SECTION_SIZE = 20f
    private const val BODY_SIZE = 21f
    private const val LINE_GAP = 2f

    private fun paint(size: Float, bold: Boolean, gray: Int): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(gray, gray, gray)
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /** 画 [name] 的事项页。待办在前、事实在后，放不下的用“…还有 N 条”收尾。
     *  [newItems] 为 true 时是“刚提取出的新事项”页，标题加“新增”与全量卡片页区分。 */
    fun render(name: String, cards: List<FactCard>, newItems: Boolean = false): Bitmap {
        val todos = cards.filter { it.fact.kind != UserFact.KIND_FACT }.map { it.fact }
        val facts = cards.filter { it.fact.kind == UserFact.KIND_FACT }.map { it.fact }
        val title = buildString {
            append(name)
            if (newItems) append(" · 新增")
            if (todos.isNotEmpty()) append(" · 待办 ").append(todos.size)
            if (facts.isNotEmpty()) append(" · 事实 ").append(facts.size)
        }
        val sections = ArrayList<Pair<String, List<String>>>()
        if (todos.isNotEmpty()) sections.add("待办" to todos.map { "☐ " + FactFormat.oneLine(it) })
        if (facts.isNotEmpty()) sections.add("事实" to facts.map { "• " + it.content })
        return renderSections(title, sections)
    }

    /** 自检用的示例页。 */
    fun renderSample(): Bitmap = renderSections(
        "测试 · Whisper → Even G2",
        listOf(
            "待办" to listOf("☐ 周三下午三点去公司开会（会议室 A · 张三）", "☐ 把合同发给李四"),
            "事实" to listOf("• 最近在准备考研", "• 不吃辣", "• 这一行是用来测试自动换行的，长度故意超过一行的宽度看看效果"),
        )
    )

    private fun renderSections(title: String, sections: List<Pair<String, List<String>>>): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val contentW = (W - PAD * 2).toInt()

        val titlePaint = paint(TITLE_SIZE, true, 255)
        val sectionPaint = paint(SECTION_SIZE, true, 170)
        val bodyPaint = paint(BODY_SIZE, false, 235)
        val dimPaint = paint(BODY_SIZE, false, 150)

        var y = PAD
        // 标题一行，超长省略。
        val titleText = TextUtils.ellipsize(title, titlePaint, contentW.toFloat(), TextUtils.TruncateAt.END)
        c.drawText(titleText, 0, titleText.length, PAD, y - titlePaint.ascent(), titlePaint)
        y += titlePaint.fontSpacing + 4f
        // 分割线
        c.drawRect(PAD, y, W - PAD, y + 1f, sectionPaint)
        y += 6f

        val bodyLine = bodyPaint.fontSpacing + LINE_GAP
        val bottom = H - PAD
        var remaining = sections.sumOf { it.second.size }
        var truncated = false

        outer@ for ((heading, items) in sections) {
            if (y + sectionPaint.fontSpacing + bodyLine > bottom) { truncated = true; break }
            c.drawText(heading, PAD, y - sectionPaint.ascent(), sectionPaint)
            y += sectionPaint.fontSpacing + 2f
            for (item in items) {
                val layout = StaticLayout.Builder.obtain(item, 0, item.length, bodyPaint, contentW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(LINE_GAP, 1f)
                    .setIncludePad(false)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build()
                // 最后一行留给“还有 N 条”提示。
                val reserve = if (remaining > 1) bodyLine else 0f
                if (y + layout.height + reserve > bottom) { truncated = true; break@outer }
                c.save()
                c.translate(PAD, y)
                layout.draw(c)
                c.restore()
                y += layout.height + LINE_GAP
                remaining--
            }
            y += 4f
        }
        if (truncated && remaining > 0) {
            val more = "…还有 $remaining 条"
            c.drawText(more, PAD, bottom - dimPaint.descent(), dimPaint)
        }
        return bmp
    }
}
