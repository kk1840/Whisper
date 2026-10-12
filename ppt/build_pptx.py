# -*- coding: utf-8 -*-
"""生成 Apple 发布会风格的项目路演 PPT（纯黑底 · 大字号 · 高对比 · 克制配色）"""
import copy
from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE
from pptx.oxml.ns import qn

# ---------------- Design tokens ----------------
BG      = RGBColor(0x00, 0x00, 0x00)
TEXT    = RGBColor(0xF5, 0xF5, 0xF7)
TEXT2   = RGBColor(0xA1, 0xA1, 0xA6)
TEXT3   = RGBColor(0x6E, 0x6E, 0x73)
BLUE    = RGBColor(0x0A, 0x84, 0xFF)
CYAN    = RGBColor(0x64, 0xD2, 0xFF)
VIOLET  = RGBColor(0x5E, 0x5C, 0xE6)
LENS    = RGBColor(0x8A, 0xFF, 0xC1)
GREEN   = RGBColor(0x30, 0xD1, 0x58)

CARD    = RGBColor(0x0C, 0x0C, 0x0E)
CARDB   = RGBColor(0x09, 0x19, 0x2F)   # 蓝调高亮卡
STROKE  = RGBColor(0x22, 0x22, 0x26)
STROKEB = RGBColor(0x1B, 0x3E, 0x70)

CN = "微软雅黑"
EN = "Microsoft YaHei"

W, H = 13.333, 7.5
ML, MR, MT = 1.0, 1.0, 0.82
CW = W - ML - MR          # 11.333

# px(1600x900 舞台) -> pt
def px(v): return Pt(v * 0.75)


# ---------------- primitives ----------------
def blank(prs):
    return prs.slides.add_slide(prs.slide_layouts[6])


def solid(slide, color):
    bg = slide.background.fill
    bg.solid()
    bg.fore_color.rgb = color


def rect(slide, x, y, w, h, fill=None, line=None, radius=0.16, lw=1.0):
    sp = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(x), Inches(y), Inches(w), Inches(h))
    sp.adjustments[0] = min(0.5, radius / max(w, h) * 2)
    sp.shadow.inherit = False
    if fill is None:
        sp.fill.background()
    else:
        sp.fill.solid(); sp.fill.fore_color.rgb = fill
    if line is None:
        sp.line.fill.background()
    else:
        sp.line.color.rgb = line
        sp.line.width = Pt(lw)
    sp.text_frame.text = ""
    return sp


def tbox(slide, x, y, w, h, anchor=MSO_ANCHOR.TOP):
    tb = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = tb.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
    tf.vertical_anchor = anchor
    return tf


def _spacing(rPr, pts):
    """设置字距（单位 1/100 pt）"""
    if pts:
        rPr.set("spc", str(int(pts * 100)))


def para(tf, text, size=px(20), color=TEXT, bold=False, align=PP_ALIGN.LEFT,
         first=False, space_before=0, space_after=0, line=1.35, spc=0,
         font=CN, italic=False):
    p = tf.paragraphs[0] if first else tf.add_paragraph()
    p.alignment = align
    p.space_before = Pt(space_before)
    p.space_after = Pt(space_after)
    p.line_spacing = line
    r = p.add_run(); r.text = text
    r.font.size = size
    r.font.bold = bold
    r.font.italic = italic
    r.font.color.rgb = color
    r.font.name = font
    # 东亚字体
    rPr = r._r.get_or_add_rPr()
    ea = rPr.find(qn("a:ea"))
    if ea is None:
        ea = rPr.makeelement(qn("a:ea"), {}); rPr.append(ea)
    ea.set("typeface", CN)
    _spacing(rPr, spc)
    return p


def notes(slide, text):
    slide.notes_slide.notes_text_frame.text = text


# ---------------- composites ----------------
def eyebrow(slide, y, text):
    tf = tbox(slide, ML, y, CW, 0.3)
    para(tf, text, size=px(15), color=TEXT3, bold=True, first=True, spc=2.6)
    return y + 0.42


def title(slide, y, text, size=px(56), grad_word=None):
    tf = tbox(slide, ML, y, CW, 1.5)
    if grad_word and grad_word in text:
        head, tail = text.split(grad_word, 1)
        p = tf.paragraphs[0]
        p.line_spacing = 1.12
        for seg, col in ((head, TEXT), (grad_word, BLUE), (tail, TEXT)):
            if not seg: continue
            r = p.add_run(); r.text = seg
            r.font.size = size; r.font.bold = True; r.font.color.rgb = col; r.font.name = CN
            rPr = r._r.get_or_add_rPr()
            ea = rPr.makeelement(qn("a:ea"), {}); rPr.append(ea); ea.set("typeface", CN)
    else:
        para(tf, text, size=size, color=TEXT, bold=True, first=True, line=1.12)
    return y + size.pt / 72.0 * 1.18


def card(slide, x, y, w, h, num=None, head=None, body=None, hi=False,
         numcolor=None, headsize=px(27), bodysize=px(18.5)):
    rect(slide, x, y, w, h, fill=CARDB if hi else CARD,
         line=STROKEB if hi else STROKE, radius=0.16)
    pad = 0.26
    ty = y + pad
    if num:
        tf = tbox(slide, x + pad, ty, w - pad * 2, 0.24)
        para(tf, num, size=px(13), color=numcolor or (CYAN if hi else BLUE), bold=True, first=True, spc=1.6)
        ty += 0.30
    if head:
        tf = tbox(slide, x + pad, ty, w - pad * 2, 0.5)
        para(tf, head, size=headsize, color=TEXT, bold=True, first=True, line=1.22)
        ty += headsize.pt / 72.0 * 1.28
    if body:
        tf = tbox(slide, x + pad, ty + 0.08, w - pad * 2, h - (ty - y) - pad)
        para(tf, body, size=bodysize, color=TEXT2, first=True, line=1.55)


def foot(slide, text):
    tf = tbox(slide, ML, H - 0.62, CW - 1.2, 0.3)
    para(tf, text, size=px(15), color=TEXT3, first=True, line=1.4)


def section_slide(prs, no, head, lead, note):
    s = blank(prs); solid(s, BG)
    tf = tbox(s, ML, 1.9, CW, 2.0)
    para(tf, no, size=Pt(120), color=RGBColor(0x2A, 0x2A, 0x2E), bold=True, first=True, line=1.0)
    tf = tbox(s, ML, 3.25, CW, 2.2)
    para(tf, head, size=px(88), color=TEXT, bold=True, first=True, line=1.08)
    tf = tbox(s, ML, 5.35, CW, 1.2)
    para(tf, lead, size=px(26), color=TEXT2, first=True, line=1.55)
    notes(s, note)
    return s


# =========================================================
prs = Presentation()
prs.slide_width = Inches(W)
prs.slide_height = Inches(H)

# ---------- 1 封面 ----------
s = blank(prs); solid(s, BG)
ring = s.shapes.add_shape(MSO_SHAPE.OVAL, Inches(W / 2 - 0.52), Inches(1.35), Inches(1.04), Inches(1.04))
ring.fill.solid(); ring.fill.fore_color.rgb = RGBColor(0x0A, 0x84, 0xFF)
ring.line.fill.background(); ring.shadow.inherit = False
tf = tbox(s, ML, 2.75, CW, 1.4, MSO_ANCHOR.TOP)
para(tf, "Whisper", size=px(104), color=TEXT, bold=True, align=PP_ALIGN.CENTER, first=True, line=1.0)
tf = tbox(s, ML, 4.15, CW, 0.8)
para(tf, "听见每一次对话，记住每一个人。", size=px(36), color=TEXT, align=PP_ALIGN.CENTER, first=True, line=1.3)
tf = tbox(s, ML, 5.05, CW, 0.5)
para(tf, "全天候对话记忆助手  ×  Even G2 智能眼镜", size=px(22), color=TEXT3, align=PP_ALIGN.CENTER, first=True)
tf = tbox(s, ML, 6.05, CW, 0.4)
para(tf, "参 赛 路 演   ·   2 0 2 6", size=px(16), color=RGBColor(0x4A, 0x4A, 0x4E), align=PP_ALIGN.CENTER, first=True, spc=3)
tf = tbox(s, ML, 6.5, CW, 0.35)
para(tf, "团队名称 / 汇报人（待填）", size=px(15), color=RGBColor(0x3A, 0x3A, 0x3C), align=PP_ALIGN.CENTER, first=True)
notes(s, "开场：一句话定位——听见每一次对话，记住每一个人。先抛产品，再讲为什么。")

# ---------- 2 开场引入 ----------
s = blank(prs); solid(s, BG)
tf = tbox(s, ML, 2.35, CW, 2.2)
para(tf, "上次见面，", size=px(80), color=TEXT, bold=True, align=PP_ALIGN.CENTER, first=True, line=1.08)
para(tf, "他跟你说了什么？", size=px(80), color=TEXT, bold=True, align=PP_ALIGN.CENTER, line=1.08)
tf = tbox(s, ML, 4.95, CW, 1.2)
para(tf, "我们每天听到上万句话。", size=px(25), color=TEXT2, align=PP_ALIGN.CENTER, first=True, line=1.6)
para(tf, "真正该记住的那几句，往往三天后就忘了。", size=px(25), color=TEXT, align=PP_ALIGN.CENTER, line=1.6)
notes(s, "停顿两秒再翻页。让这个问题留在观众脑子里，后面所有内容都是在回答它。")

# ---------- 3 项目定位 ----------
s = blank(prs); solid(s, BG)
y = eyebrow(s, MT, "项 目 定 位")
y = title(s, y, "Introducing Whisper.", size=px(80), grad_word="Whisper.")
tf = tbox(s, ML, y + 0.22, CW, 1.3)
para(tf, "一个一直在听、认得出人、会替你记事，", size=px(27), color=TEXT2, first=True, line=1.55)
para(tf, "并在合适的时机提醒你的私人记忆助手。", size=px(27), color=TEXT, line=1.55)
cy = 4.62
cw = (CW - 0.44) / 3
for i, (k, h, b) in enumerate([
    ("手机", "卡片 + 实时转写", "事项卡片堆与按人着色的转写，一屏看全。"),
    ("灵动岛", "系统级实况通知", "不在应用里也能看见、也能操作。"),
    ("EVEN G2 眼镜", "抬眼即见", "提醒出现在镜片上，不打断正在进行的对话。"),
]):
    card(s, ML + i * (cw + 0.22), cy, cw, 1.72, num=k, head=h, body=b, hi=(i == 2))
notes(s, "三个出口：手机、灵动岛、眼镜。眼镜是加分项，不是全部——这一点要讲清楚。")

# ---------- 4 核心价值 ----------
s = blank(prs); solid(s, BG)
tf = tbox(s, ML, 1.75, CW, 2.3)
para(tf, "最好的笔记，", size=px(80), color=TEXT, bold=True, align=PP_ALIGN.CENTER, first=True, line=1.08)
para(tf, "是你从不需要打开的笔记。", size=px(80), color=BLUE, bold=True, align=PP_ALIGN.CENTER, line=1.08)
tf = tbox(s, ML, 4.15, CW, 1.1)
para(tf, "记忆应该被动发生、自动整理，", size=px(25), color=TEXT2, align=PP_ALIGN.CENTER, first=True, line=1.6)
para(tf, "并在你需要的那一刻，出现在眼前。", size=px(25), color=TEXT2, align=PP_ALIGN.CENTER, line=1.6)
cw = (CW - 0.44) / 3
for i, (k, h, b) in enumerate([
    ("01 · 被动", "不用你操作", "不需要打开、不需要按下录音键。它一直在听。"),
    ("02 · 自动", "不用你整理", "大模型提炼待办与事实，去重、完成、过期全自动。"),
    ("03 · 恰好", "在对的时机出现", "熟人再次开口时才提醒——不是每句话都打扰你。"),
]):
    card(s, ML + i * (cw + 0.22), 5.35, cw, 1.42, num=k, head=h, body=b,
         headsize=px(24), bodysize=px(17))
notes(s, "核心价值一句话：记忆不该是一项任务。被动、自动、恰好——三个词记住就行。")

# ---------- 5 章节：用户与痛点 ----------
section_slide(prs, "02", "为什么\n需要它", "记忆依赖人主动去做。\n而人，恰恰做不到。",
              "进入痛点。先让观众承认：这事儿我也做不到。")

# ---------- 6 痛点 ----------
s = blank(prs); solid(s, BG)
y = eyebrow(s, MT, "目 标 用 户 与 痛 点")
y = title(s, y, "记忆，是社交里最贵的成本。", grad_word="最贵的成本")
cw = (CW - 0.44) / 3
for i, (k, h, b) in enumerate([
    ("痛点 01", "答应的事，忘了", "口头约定没有落进日程。下次见面对方问起，才想起来。"),
    ("痛点 02", "对方说过的，忘了", "忌口、近况、家人、生日……散落在闲谈与聊天记录里，没有一个地方能看全。"),
    ("痛点 03", "记录，太重了", "录音笔、备忘录、纪要 App 都要主动打开、主动整理。没有人坚持得了。"),
]):
    card(s, ML + i * (cw + 0.22), 2.72, cw, 2.5, num=k, head=h, body=b)
foot(s, "三个痛点指向同一个事实：记忆依赖人主动去做，而人恰恰做不到。")
notes(s, "三个痛点逐条讲，每条一句话场景。最后落到：不是工具不够，是人做不到。")

# ---------- 7 目标用户 ----------
s = blank(prs); solid(s, BG)
y = eyebrow(s, MT, "给 谁 用")
y = title(s, y, "为「要记住很多人」的人而做。")
cw = (CW - 0.66) / 4
for i, (h, b) in enumerate([
    ("商务 / 销售 / BD", "客户随口说的每个细节，下次见面都记得。"),
    ("医生 / 教师 / 社工", "每天面对很多人，记住每个人的近况。"),
    ("记忆力下降的长辈", "谁来了、上次说了什么、答应了什么。"),
    ("不想忘记朋友的人", "朋友说过的忌口、生日、烦恼，不再只靠脑子。"),
]):
    card(s, ML + i * (cw + 0.22), 2.72, cw, 2.1, head=h, body=b, headsize=px(23))
foot(s, "市场规模数据（TAM / SAM / SOM）请按比赛要求补充。")
notes(s, "四类人群共同点：要记住很多人。市场数据按比赛要求另行补充。")

# ---------- 8 章节：产品 ----------
section_slide(prs, "03", "它怎么\n工作", "从听见，到记住，\n再到出现在你眼前。",
              "进入产品。准备切到演示。")

# ---------- 9 四件事 ----------
s = blank(prs); solid(s, BG)
y = eyebrow(s, MT, "产 品 功 能")
y = title(s, y, "四件事，它都替你做了。")
cw = (CW - 0.66) / 4
for i, (k, h, b) in enumerate([
    ("01", "听得懂", "端侧双引擎识别。边说边出字，整句再精修、加标点，全程不联网。"),
    ("02", "认得出", "端侧声纹识别。自动区分你和身边的每个人，没见过的声音自动建档。"),
    ("03", "记得住", "大模型从对话与聊天截图提炼待办和事实，自动去重、完成、过期。"),
    ("04", "提醒得巧", "熟人隔 5 分钟再开口时，TA 的事项同时出现在手机、灵动岛和眼镜上。"),
]):
    card(s, ML + i * (cw + 0.22), 2.66, cw, 3.0, num=k, head=h, body=b,
         hi=(i == 3), headsize=px(25))
foot(s, "全程无需主动操作——你只需要正常说话。")
notes(s, "四个动词：听得懂、认得出、记得住、提醒得巧。第四个是差异化所在，多讲 20 秒。")

# ---------- 10 一周演示 ----------
s = blank(prs); solid(s, BG)
y = eyebrow(s, MT, "产 品 演 示")
tf = tbox(s, ML, y, CW, 1.0)
para(tf, "一个真实的一周。", size=px(56), color=TEXT, bold=True, first=True, line=1.12)
rows = [
    ("周一 10:02", "李四：“下周三下午三点来公司开会，记得带合同。”", "→ 1 分钟内提炼为待办：下周三 15:00 · 会议室 A · 带合同"),
    ("周一 10:03", "李四：“我最近胃不太舒服，别约火锅了。”", "→ 记为事实：最近胃不舒服，暂时不吃火锅"),
    ("周三 14:52", "李四走进会议室，开口说了第一句话", "→ 距上次开口 > 5 分钟，眼镜浮现：李四 · 待办 2 · 事实 3"),
    ("周三 15:40", "你：“合同我带来了，咱们别点火锅，去旁边那家粤菜。”", "→ 会后交给 AI，待办「带合同」自动勾掉"),
]
top = 2.55
for i, (t, c, r) in enumerate(rows):
    yy = top + i * 1.02
    ln = rect(s, ML, yy, CW, 0.008, fill=STROKE, line=None, radius=0)
    tf = tbox(s, ML, yy + 0.2, 1.5, 0.4)
    para(tf, t, size=px(20), color=CYAN, bold=True, first=True)
    tf = tbox(s, ML + 1.62, yy + 0.18, CW - 1.62, 0.75)
    para(tf, c, size=px(23), color=TEXT, first=True, line=1.4)
    para(tf, r, size=px(19), color=TEXT3, line=1.4)
rect(s, ML, top + 4 * 1.02, CW, 0.008, fill=STROKE, line=None, radius=0)
notes(s, "这一页是全场的故事线。讲完这四步，产品就讲明白了。演示时可直接对着手机走一遍。")

# ---------- 11 眼镜视觉页 ----------
s = blank(prs); solid(s, BG)
y = eyebrow(s, MT, "EVEN G2 · 佩 戴 者 视 角（示 意）")
y = title(s, y, "抬眼即见。", grad_word="即见")
lx, ly, lw, lh = ML, y + 0.35, 6.9, 3.0
rect(s, lx, ly, lw, lh, fill=RGBColor(0x05, 0x05, 0x05), line=RGBColor(0x1E, 0x6B, 0x48), radius=0.2, lw=1.5)
lines = [
    ("李四 · 待办 2 · 事实 3", px(25), True, 0.34),
    ("—", px(18), False, 0.72),
    ("待办", px(19), True, 0.94),
    ("☐  下周三 15:00 来公司开会，带合同", px(20), False, 1.24),
    ("☐  把修订后的报价单发给他", px(20), False, 1.58),
    ("事实", px(19), True, 1.98),
    ("•  最近胃不太舒服，暂时不吃火锅", px(20), False, 2.28),
    ("•  女儿今年上小学，开学在即", px(20), False, 2.62),
]
for t, sz, bd, dy in lines:
    tf = tbox(s, lx + 0.34, ly + dy, lw - 0.68, 0.35)
    para(tf, t, size=sz, color=LENS if t != "—" else RGBColor(0x2E, 0x8B, 0x5E),
         bold=bd, first=True, font="Consolas")
tf = tbox(s, lx + 0.34, ly + 2.66, lw - 0.68, 0.3)
para(tf, "…还有 1 条", size=px(18), color=RGBColor(0x4E, 0xB0, 0x86), first=True, font="Consolas")
rx = ML + 7.15
for i, (h, b) in enumerate([
    ("黑底发光文字", "手机用系统字体渲染成图片再推送，不依赖眼镜固件是否带中文字库。"),
    ("576 × 256 画布", "4 bpp · 16 级灰度 · 2×2 瓦片分块推送。"),
    ("60 秒后自动消失", "整屏推送约 5～10 秒，无需任何点击。"),
]):
    card(s, rx, ly + i * 1.06, CW - 7.15, 0.94, head=h, body=b,
         headsize=px(22), bodysize=px(16.5))
foot(s, "镜片为单色波导显示（示意呈现为绿色），实际由 16 级灰度位图驱动。")
notes(s, "这页是视觉锚点。如果现场有眼镜，这里直接抬头让人看镜片；没有就讲示意。")

# ---------- 12 为什么是眼镜 ----------
s = blank(prs); solid(s, BG)
y = eyebrow(s, MT, "为 什 么 是 眼 镜")
y = title(s, y, "提醒，应该长在视野边缘。", grad_word="视野边缘")
cw = (CW - 0.44) / 3
for i, (k, h, b) in enumerate([
    ("01", "不打断", "不用掏手机、不用低头。对话继续，提醒在视野边缘出现。"),
    ("02", "只有你看见", "提醒私密地出现在你的镜片里，对方毫无察觉。这是手机屏幕做不到的。"),
    ("03", "零操作", "认出熟人开口就推送，60 秒后自动消失。整屏推送约 5～10 秒，无需点击。"),
]):
    card(s, ML + i * (cw + 0.22), 2.72, cw, 2.5, num=k, head=h, body=b)
foot(s, "手机上的提醒照常显示，眼镜是叠加的一层——信息在你最需要的位置出现。")
notes(s, "强调眼镜不是替代手机，而是多加一层。私密性是手机做不到的那一点。")

# ---------- 13 章节：技术 ----------
section_slide(prs, "04", "技术\n亮点", "感知在端侧，提炼在云端，\n记忆在本机。",
              "进入技术。一句话总结架构：端侧感知、云端提炼、本机记忆。")

# ---------- 14 七个端侧模型 ----------
s = blank(prs); solid(s, BG)
y = eyebrow(s, MT, "端 侧 模 型")
y = title(s, y, "七个模型，一个都不上云。", grad_word="一个都不上云")
tbl_top = y + 0.25
rows = [
    ("语音活动检测", "Silero VAD", "0.6 MB"),
    ("实时字幕", "Zipformer 流式 Transducer（int8）", "167 MB"),
    ("整句识别", "Paraformer-zh（int8）", "243 MB"),
    ("标点恢复", "CT-Transformer（int8）", "75 MB"),
    ("声纹向量", "3D-Speaker ERes2NetV2", "71 MB"),
    ("文字检测", "PP-OCRv6 det", "62 MB"),
    ("文字识别", "PP-OCRv6 rec", "77 MB"),
]
tw = 6.7
rect(s, ML, tbl_top, tw, 0.008, fill=STROKEB, line=None, radius=0)
tf = tbox(s, ML, tbl_top + 0.12, tw, 0.3)
para(tf, "任务", size=px(14), color=TEXT3, bold=True, first=True, spc=1.6)
tf = tbox(s, ML + 1.55, tbl_top + 0.12, tw - 1.55, 0.3)
para(tf, "模型", size=px(14), color=TEXT3, bold=True, first=True, spc=1.6)
tf = tbox(s, ML + tw - 1.1, tbl_top + 0.12, 1.1, 0.3)
para(tf, "体积", size=px(14), color=TEXT3, bold=True, align=PP_ALIGN.RIGHT, first=True, spc=1.6)
for i, (a, b, c) in enumerate(rows):
    yy = tbl_top + 0.44 + i * 0.44
    rect(s, ML, yy + 0.40, tw, 0.006, fill=STROKE, line=None, radius=0)
    tf = tbox(s, ML, yy, 1.55, 0.34)
    para(tf, a, size=px(20), color=TEXT, bold=True, first=True)
    tf = tbox(s, ML + 1.55, yy, tw - 2.7, 0.34)
    para(tf, b, size=px(20), color=TEXT2, first=True)
    tf = tbox(s, ML + tw - 1.1, yy, 1.1, 0.34)
    para(tf, c, size=px(19), color=CYAN, align=PP_ALIGN.RIGHT, first=True, font="Consolas")
sx = ML + 7.05
sw = CW - 7.05
rect(s, sx, tbl_top, sw, 1.35, fill=CARDB, line=STROKEB, radius=0.16)
tf = tbox(s, sx + 0.28, tbl_top + 0.24, sw - 0.56, 0.8)
para(tf, "7", size=px(74), color=TEXT, bold=True, first=True, line=1.0)
para(tf, "个端侧模型 · 全部 CPU 推理", size=px(18), color=TEXT2, line=1.35)
for i, (v, l) in enumerate([("697 MB", "模型总量 · 预置系统目录，不打包进 APK"),
                            ("100 %", "音频与图像不出手机")]):
    rect(s, sx, tbl_top + 1.5 + i * 1.16, sw, 1.02, fill=CARD, line=STROKE, radius=0.16)
    tf = tbox(s, sx + 0.28, tbl_top + 1.66 + i * 1.16, sw - 0.56, 0.7)
    para(tf, v, size=px(40), color=TEXT, bold=True, first=True, line=1.05)
    para(tf, l, size=px(16), color=TEXT2, line=1.35)
foot(s, "运行时：sherpa-onnx 1.13.8 承载语音模型；OCR 经 JNI 直调 onnxruntime C API。模型目录 /system/etc/whisper/models")
notes(s, "重点是：全部 CPU、int8 量化、模型不打包进 APK（系统预置）。这是工程上最硬的一块。")

# ---------- 15 语音链路 ----------
s = blank(prs); solid(s, BG)
y = eyebrow(s, MT, "语 音 识 别")
y = title(s, y, "两套引擎，一个目的。", grad_word="一个目的")
tf = tbox(s, ML, y + 0.16, CW, 0.5)
para(tf, "边说边出字，整句再精修。全部在手机上完成。", size=px(22), color=TEXT2, first=True)
cw = (CW - 0.88) / 5
steps = [("01 · 采集", "麦克风", "16 kHz 单声道\n每 100 ms 一帧"),
         ("02 · 断句", "Silero VAD", "静音 0.5 s 断句\n0.25 ～ 20 s 一句"),
         ("03 · 实时", "流式 Zipformer", "边说边出字幕\n噪声 2 s 自动清除"),
         ("04 · 定稿", "Paraformer-zh", "整句最终识别\nint8 · CPU 2 线程"),
         ("05 · 润色", "CT-Transformer", "标点恢复\n空句直接丢弃")]
for i, (k, h, b) in enumerate(steps):
    x = ML + i * (cw + 0.22)
    rect(s, x, 2.85, cw, 1.85, fill=CARD, line=STROKE, radius=0.16)
    tf = tbox(s, x + 0.22, 3.03, cw - 0.44, 0.24)
    para(tf, k, size=px(12.5), color=CYAN, bold=True, first=True, spc=1.4)
    tf = tbox(s, x + 0.22, 3.28, cw - 0.44, 0.35)
    para(tf, h, size=px(22), color=TEXT, bold=True, first=True, line=1.2)
    tf = tbox(s, x + 0.22, 3.68, cw - 0.44, 0.9)
    para(tf, b, size=px(16), color=TEXT2, first=True, line=1.55)
cw2 = (CW - 0.44) / 3
for i, (k, b) in enumerate([("100%", "端侧推理，音频不出手机"),
                            ("INT8", "量化模型，CPU 即可流畅运行"),
                            ("30 天", "转写自动清理，不长期留存")]):
    card(s, ML + i * (cw2 + 0.22), 5.05, cw2, 0.92, num=k, body=b, bodysize=px(17))
notes(s, "双引擎是关键：流式给实时反馈，非流式给定稿精度。两者都跑在 CPU 上。")

# ---------- 16 声纹 ----------
s = blank(prs); solid(s, BG)
y = eyebrow(s, MT, "说 话 人 识 别")
y = title(s, y, "它认得出是谁在说话。", grad_word="是谁在说话")
tf = tbox(s, ML, y + 0.16, CW, 0.5)
para(tf, "端侧声纹识别，一次录入，越用越准。", size=px(22), color=TEXT2, first=True)
steps = [("01 · 录入", "跟读一段短文", "实时高亮，容忍错字与同音字"),
         ("02 · 提取", "ERes2NetV2", "≥ 1.5 s 的句子才提取\n向量归一化"),
         ("03 · 比对", "余弦相似度检索", "阈值默认 0.50，可 0.3–0.8 调节"),
         ("04 · 建档", "陌生人 N", "自动建档并保留 15 s 声音\n可命名 / 关联 / 合并"),
         ("05 · 自适应", "滚动更新声纹", "≥ 3 s 且高置信度\n每人最多 6 条额外声纹")]
for i, (k, h, b) in enumerate(steps):
    x = ML + i * (cw + 0.22)
    rect(s, x, 2.85, cw, 1.85, fill=CARD, line=STROKE, radius=0.16)
    tf = tbox(s, x + 0.22, 3.03, cw - 0.44, 0.24)
    para(tf, k, size=px(12.5), color=CYAN, bold=True, first=True, spc=1.4)
    tf = tbox(s, x + 0.22, 3.28, cw - 0.44, 0.35)
    para(tf, h, size=px(22), color=TEXT, bold=True, first=True, line=1.2)
    tf = tbox(s, x + 0.22, 3.68, cw - 0.44, 0.9)
    para(tf, b, size=px(16), color=TEXT2, first=True, line=1.55)
foot(s, "关联到已有的人后，原声纹作为额外声纹保留——同一个人压低声音、感冒时也能认出来。")
notes(s, "声纹是「记得住每个人」的基础。讲的是闭环：录入→比对→建档→自适应。")

# ---------- 17 记忆提炼 ----------
s = blank(prs); solid(s, BG)
y = eyebrow(s, MT, "记 忆 提 炼")
y = title(s, y, "把对话，变成结构化的记忆。", grad_word="结构化的记忆")
cw3 = (CW - 0.26) / 2
rect(s, ML, y + 0.3, cw3, 1.62, fill=CARD, line=STROKE, radius=0.16)
tf = tbox(s, ML + 0.28, y + 0.5, cw3 - 0.56, 0.3)
para(tf, "输出一 · 待办", size=px(13), color=BLUE, bold=True, first=True, spc=1.6)
tf = tbox(s, ML + 0.28, y + 0.8, cw3 - 0.56, 0.4)
para(tf, "要做的事、双方的约定", size=px(26), color=TEXT, bold=True, first=True, line=1.2)
tf = tbox(s, ML + 0.28, y + 1.24, cw3 - 0.56, 0.6)
para(tf, "time 时间 · location 地点 · people 人物 · event 事情，≤ 40 字",
     size=px(17), color=TEXT2, first=True, font="Consolas", line=1.5)
para(tf, "类型：待办 / 约定", size=px(16), color=TEXT3, line=1.4)
rect(s, ML + cw3 + 0.26, y + 0.3, cw3, 1.62, fill=CARD, line=STROKE, radius=0.16)
tf = tbox(s, ML + cw3 + 0.54, y + 0.5, cw3 - 0.56, 0.3)
para(tf, "输出二 · 事实", size=px(13), color=BLUE, bold=True, first=True, spc=1.6)
tf = tbox(s, ML + cw3 + 0.54, y + 0.8, cw3 - 0.56, 0.4)
para(tf, "关于这位熟人的事", size=px(26), color=TEXT, bold=True, first=True, line=1.2)
tf = tbox(s, ML + cw3 + 0.54, y + 1.24, cw3 - 0.56, 0.6)
para(tf, "content 一句能单独看懂的话，≤ 40 字", size=px(17), color=TEXT2, first=True, font="Consolas", line=1.5)
para(tf, "类型：近况 / 偏好 / 信息；不再成立 → 自动过期", size=px(16), color=TEXT3, line=1.4)
cw2 = (CW - 0.44) / 3
for i, (k, b) in enumerate([("闭环", "已完成的待办、失效的事实，由模型返回 id 自动关闭"),
                            ("去重", "已有条目作为上下文送入，避免重复记录"),
                            ("克制", "只记有后续价值的内容，寒暄不记、不硬凑待办")]):
    card(s, ML + i * (cw2 + 0.22), y + 2.12, cw2, 1.0, num=k, body=b, bodysize=px(17))
foot(s, "每 1 分钟自动把未识别聊天块按说话人分组投递；队列落库，断网不丢。")
notes(s, "大模型只做一件事：找出有后续价值的内容。闭环核销是亮点——会勾掉、会过期。")

# ---------- 18 截图 ----------
s = blank(prs); solid(s, BG)
y = eyebrow(s, MT, "聊 天 截 图")
y = title(s, y, "聊天截图，也是记忆。", grad_word="也是记忆")
tf = tbox(s, ML, y + 0.16, CW, 0.5)
para(tf, "截一张微信 / QQ / 飞书 / 钉钉的聊天界面，剩下的交给它。", size=px(22), color=TEXT2, first=True)
steps = [("01 · 监听", "MediaStore", "按 _id 只处理新截图\n文件名识别来源应用"),
         ("02 · OCR", "PP-OCRv6", "端侧检测 + 识别\n输出 [左]/[右]/[中] 版式"),
         ("03 · 还原", "大模型整理对话", "右 = 机主，左 = 对方\n去掉时间戳与系统提示"),
         ("04 · 对应", "昵称 → 用户", "指定过 / 同名自动\n相似则预选推荐，其余询问"),
         ("05 · 提炼", "按人分别提取", "待办与事实分别落库\n进度在灵动岛显示")]
for i, (k, h, b) in enumerate(steps):
    x = ML + i * (cw + 0.22)
    rect(s, x, 2.85, cw, 1.85, fill=CARD, line=STROKE, radius=0.16)
    tf = tbox(s, x + 0.22, 3.03, cw - 0.44, 0.24)
    para(tf, k, size=px(12.5), color=CYAN, bold=True, first=True, spc=1.4)
    tf = tbox(s, x + 0.22, 3.28, cw - 0.44, 0.35)
    para(tf, h, size=px(22), color=TEXT, bold=True, first=True, line=1.2)
    tf = tbox(s, x + 0.22, 3.68, cw - 0.44, 0.9)
    para(tf, b, size=px(16), color=TEXT2, first=True, line=1.55)
foot(s, "为什么要先做版式：OCR 丢掉了气泡的左右排版，而「谁说的」恰恰藏在排版里——靠右是自己，靠左是对方。")
notes(s, "版式还原是这一页的技术巧思：OCR 丢掉了左右排版，而「谁说的」就藏在排版里。")

# ---------- 19 BLE 推图 ----------
s = blank(prs); solid(s, BG)
y = eyebrow(s, MT, "EVEN G2 · BLE 协 议")
y = title(s, y, "一张图，怎么推到镜片上。", grad_word="推到镜片上")
steps = [("01 · 渲染", "FactsRenderer", "576 × 256 位图\n系统字体画中文"),
         ("02 · 切块", "Bmp4 瓦片", "288 × 128 × 4 块\n4 bpp · 16 级灰度"),
         ("03 · 打包", "EvenHub proto3", "1 文本 + 4 图片容器\n分片 ≤ 3800 B"),
         ("04 · 分帧", "BLE 232 B / 帧", "CRC16 追加末片\n双镜腿独立连接"),
         ("05 · 显示", "整屏 5～10 秒", "60 秒自动关闭\n心跳 2 秒一次")]
for i, (k, h, b) in enumerate(steps):
    x = ML + i * (cw + 0.22)
    rect(s, x, 2.72, cw, 1.85, fill=CARD, line=STROKE, radius=0.16)
    tf = tbox(s, x + 0.22, 2.9, cw - 0.44, 0.24)
    para(tf, k, size=px(12.5), color=CYAN, bold=True, first=True, spc=1.4)
    tf = tbox(s, x + 0.22, 3.15, cw - 0.44, 0.35)
    para(tf, h, size=px(22), color=TEXT, bold=True, first=True, line=1.2)
    tf = tbox(s, x + 0.22, 3.55, cw - 0.44, 0.9)
    para(tf, b, size=px(16), color=TEXT2, first=True, line=1.55)
cw2 = (CW - 0.44) / 3
for i, (k, b) in enumerate([("坑 01", "CREATE 后第一次图片流会被固件丢掉 → 先推全黑瓦片预热"),
                            ("坑 02", "纯图片页会 ack 但不绘制 → 顶部必须保留 32 px 文本容器"),
                            ("优化", "瓦片内容指纹去重，没变化的块不重发")]):
    card(s, ML + i * (cw2 + 0.22), 4.85, cw2, 1.0, num=k, body=b, bodysize=px(16.5))
notes(s, "这页讲工程深度。踩坑清单最有说服力——说明真机调通过，不是 PPT 产品。")

# ---------- 20 隐私 ----------
s = blank(prs); solid(s, BG)
y = eyebrow(s, MT, "隐 私")
y = title(s, y, "你的声音，不出手机。", grad_word="不出手机")
items = [("01", "端侧处理", "录音、识别、声纹、OCR 全在手机上完成，音频与图片从不上传。"),
         ("02", "最小上传", "只有文字（转写、OCR 文本）发给大模型。"),
         ("03", "自动清理", "转写 30 天删除，截图任务 3 天；声纹可随时删除。"),
         ("04", "眼镜只写不读", "只发显示内容与心跳，不读眼镜麦克风。"),
         ("05", "不要位置", "蓝牙扫描声明 neverForLocation。"),
         ("06", "随时可控", "录音可暂停，字幕 / 截图 / 眼镜推送各自独立开关。")]
cw4 = (CW - 0.3) / 2
for i, (k, h, b) in enumerate(items):
    x = ML + (i % 2) * (cw4 + 0.3)
    yy = y + 0.35 + (i // 2) * 1.36
    rect(s, x, yy, cw4, 1.2, fill=CARD, line=STROKE, radius=0.16)
    d = s.shapes.add_shape(MSO_SHAPE.OVAL, Inches(x + 0.26), Inches(yy + 0.28), Inches(0.34), Inches(0.34))
    d.fill.solid(); d.fill.fore_color.rgb = RGBColor(0x0A, 0x22, 0x3E)
    d.line.color.rgb = RGBColor(0x1B, 0x54, 0x94); d.line.width = Pt(0.75); d.shadow.inherit = False
    tf = d.text_frame; tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
    tf.vertical_anchor = MSO_ANCHOR.MIDDLE
    para(tf, k, size=px(13), color=CYAN, bold=True, align=PP_ALIGN.CENTER, first=True)
    tf = tbox(s, x + 0.72, yy + 0.22, cw4 - 0.98, 0.34)
    para(tf, h, size=px(21), color=TEXT, bold=True, first=True)
    tf = tbox(s, x + 0.72, yy + 0.62, cw4 - 0.98, 0.55)
    para(tf, b, size=px(17), color=TEXT2, first=True, line=1.5)
notes(s, "全天候录音一定会遇到隐私质疑。这页必须讲，而且要主动讲、讲在前面。")

# ---------- 21 章节：进展 ----------
section_slide(prs, "05", "进展与\n数据", "已经做到的，\n和用数字说话。",
              "进入进展。先亮数字，再列已完成清单。")

# ---------- 22 数字墙 ----------
s = blank(prs); solid(s, BG)
y = eyebrow(s, MT, "关 键 数 据")
y = title(s, y, "用数字说话。")
stats = [("7", "", "端侧模型\n全部 CPU 推理，零云端成本"),
         ("697", "MB", "模型总量\n预置系统目录，不打包进 APK"),
         ("100", "%", "语音与图像端侧处理\n音频从不出手机"),
         ("11,963", "", "行源码\nJava 9,741 + Kotlin 2,222 · 76 个文件"),
         ("5", "min", "会话窗口\n熟人隔 5 分钟再开口即触发提醒"),
         ("60", "s", "眼镜页面自动消失\n整屏推送约 5～10 秒")]
cw5 = (CW - 0.5) / 3
for i, (v, u, l) in enumerate(stats):
    x = ML + (i % 3) * (cw5 + 0.25)
    yy = y + 0.4 + (i // 3) * 1.72
    rect(s, x, yy, cw5, 1.56, fill=CARD, line=STROKE, radius=0.18)
    tf = tbox(s, x + 0.3, yy + 0.3, cw5 - 0.6, 0.75)
    p = tf.paragraphs[0]; p.line_spacing = 1.0
    r = p.add_run(); r.text = v
    r.font.size = px(74); r.font.bold = True; r.font.color.rgb = TEXT; r.font.name = "Consolas"
    if u:
        r2 = p.add_run(); r2.text = u
        r2.font.size = px(30); r2.font.bold = True; r2.font.color.rgb = TEXT; r2.font.name = "Consolas"
    tf = tbox(s, x + 0.3, yy + 1.02, cw5 - 0.6, 0.5)
    for j, ln in enumerate(l.split("\n")):
        para(tf, ln, size=px(17), color=TEXT if j == 0 else TEXT2,
             bold=(j == 0), first=(j == 0), line=1.45)
notes(s, "六个数字，逐条念一遍就够。重点是 100% 端侧和 11,963 行——说明是真做出来的。")

# ---------- 23 已完成 ----------
s = blank(prs); solid(s, BG)
y = eyebrow(s, MT, "当 前 进 展")
y = title(s, y, "已经做到的。")
done = ["端侧 VAD + 双引擎中文识别 + 标点",
        "端侧声纹：录入、识别、陌生人建档、合并、自适应",
        "会话感知提醒 + 1 分钟自动提炼",
        "大模型提炼待办 / 事实，自动完成与过期",
        "聊天截图：端侧 OCR + 版式 + 昵称对应",
        "灵动岛 7 类实况通知",
        "Even G2 双镜腿连接、鉴权、心跳、推图、自动重连",
        "系统级集成：platform 签名 + 灵动岛 + 悬浮窗"]
cw6 = (CW - 0.4) / 2
for i, t in enumerate(done):
    x = ML + (i % 2) * (cw6 + 0.4)
    yy = y + 0.4 + (i // 2) * 0.72
    d = s.shapes.add_shape(MSO_SHAPE.OVAL, Inches(x + 0.05), Inches(yy + 0.06), Inches(0.3), Inches(0.3))
    d.fill.solid(); d.fill.fore_color.rgb = RGBColor(0x0A, 0x2A, 0x16)
    d.line.color.rgb = RGBColor(0x1E, 0x6B, 0x38); d.line.width = Pt(0.75); d.shadow.inherit = False
    tf = d.text_frame; tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
    tf.vertical_anchor = MSO_ANCHOR.MIDDLE
    para(tf, "✓", size=px(14), color=GREEN, bold=True, align=PP_ALIGN.CENTER, first=True)
    tf = tbox(s, x + 0.48, yy + 0.02, cw6 - 0.5, 0.45)
    para(tf, t, size=px(20), color=TEXT, first=True, line=1.4)
foot(s, "系统应用（android.uid.system）· arm64-v8a · minSdk 28 · Room schema v7")
notes(s, "八项已完成，全部可现场演示。讲的时候强调「能跑」，不是「计划做」。")

# ---------- 24 团队 ----------
s = blank(prs); solid(s, BG)
y = eyebrow(s, MT, "团 队")
y = title(s, y, "做这件事的人。")
cw7 = (CW - 0.66) / 4
team = [("创建者", "liuziqian", "项目创建 · 端侧与系统集成\nstepx.com（依据仓库提交记录）"),
        ("待填", "姓名", "角色 / 分工\n算法 · 端侧模型"),
        ("待填", "姓名", "角色 / 分工\nAndroid · 交互设计"),
        ("待填", "姓名", "角色 / 分工\n硬件 · BLE 协议")]
for i, (k, h, b) in enumerate(team):
    x = ML + i * (cw7 + 0.22)
    rect(s, x, y + 0.55, cw7, 2.3, fill=CARD, line=STROKE, radius=0.16)
    a = s.shapes.add_shape(MSO_SHAPE.OVAL, Inches(x + 0.3), Inches(y + 0.85), Inches(0.72), Inches(0.72))
    a.fill.solid(); a.fill.fore_color.rgb = RGBColor(0x14, 0x14, 0x18)
    a.line.color.rgb = STROKE; a.line.width = Pt(0.75); a.shadow.inherit = False
    a.text_frame.text = ""
    tf = tbox(s, x + 0.3, y + 1.72, cw7 - 0.6, 0.28)
    para(tf, k, size=px(13), color=BLUE, bold=True, first=True, spc=1.6)
    tf = tbox(s, x + 0.3, y + 2.0, cw7 - 0.6, 0.35)
    para(tf, h, size=px(26), color=TEXT, bold=True, first=True, line=1.2)
    tf = tbox(s, x + 0.3, y + 2.38, cw7 - 0.6, 0.6)
    for j, ln in enumerate(b.split("\n")):
        para(tf, ln, size=px(17), color=TEXT2 if j == 0 else TEXT3, first=(j == 0), line=1.45)
foot(s, "核心能力覆盖：端侧 AI 推理 · Android 系统级开发 · BLE 协议逆向 · 大模型应用 · 交互设计。成员信息待补充。")
notes(s, "团队页信息待补充。强调能力组合：端侧 AI + 系统级开发 + BLE 逆向 + 大模型应用。")

# ---------- 25 商业模式 ----------
s = blank(prs); solid(s, BG)
y = eyebrow(s, MT, "商 业 模 式")
y = title(s, y, "三种变现路径。", grad_word="变现路径")
cw = (CW - 0.44) / 3
for i, (k, h, b) in enumerate([
    ("路径 01 · 硬件绑定", "预装合作", "与带屏智能眼镜 / 手机厂商合作预装，作为眼镜的「记忆」能力出厂即有。"),
    ("路径 02 · 订阅制", "端侧免费 + 提炼订阅", "端侧识别免费，大模型提炼与长期记忆按月订阅；企业版支持更长留存。"),
    ("路径 03 · B 端集成", "CRM / 随访打通", "面向销售团队与医疗机构，把对话里的承诺自动变成任务。"),
]):
    card(s, ML + i * (cw + 0.22), 2.72, cw, 2.5, num=k, head=h, body=b)
rect(s, ML, 5.5, CW, 0.86, fill=CARD, line=STROKE, radius=0.16)
tf = tbox(s, ML + 0.32, 5.66, 1.3, 0.3)
para(tf, "成本结构", size=px(13), color=BLUE, bold=True, first=True, spc=1.6)
tf = tbox(s, ML + 1.7, 5.63, CW - 2.1, 0.6)
para(tf, "端侧模型零推理成本；云端仅文字请求、按 token 计费，单次提炼成本极低。主要成本在大模型调用与硬件合作。",
     size=px(18), color=TEXT2, first=True, line=1.5)
foot(s, "以上为方向性设想，可按比赛要求取舍。")
notes(s, "成本结构要讲：端侧零推理成本，云端只发文字。这是单位经济能成立的关键。")

# ---------- 26 未来规划 ----------
s = blank(prs); solid(s, BG)
y = eyebrow(s, MT, "路 线 图")
y = title(s, y, "接下来，要做的事。")
cw8 = (CW - 0.26) / 2
blocks = [("短期 · 0–6 个月", ["眼镜交互：点按翻页 / 关闭 / 标记完成",
                              "眼镜麦克风接入，摘下手机也能听",
                              "对方知情提示与隐私合规机制"]),
          ("中期 · 6–18 个月", ["端侧小模型提炼，离线也能记",
                               "多语种识别与提炼",
                               "日历 / CRM 同步与分享",
                               "多设备同步与备份"])]
for i, (k, items) in enumerate(blocks):
    x = ML + i * (cw8 + 0.26)
    hgt = 0.62 + len(items) * 0.46
    rect(s, x, y + 0.4, cw8, hgt, fill=CARD, line=STROKE, radius=0.16)
    tf = tbox(s, x + 0.3, y + 0.62, cw8 - 0.6, 0.28)
    para(tf, k, size=px(13), color=CYAN, bold=True, first=True, spc=1.6)
    for j, it in enumerate(items):
        yy = y + 1.0 + j * 0.46
        ar = s.shapes.add_shape(MSO_SHAPE.OVAL, Inches(x + 0.3), Inches(yy + 0.03), Inches(0.26), Inches(0.26))
        ar.fill.background(); ar.line.color.rgb = STROKE; ar.line.width = Pt(0.75); ar.shadow.inherit = False
        tf = ar.text_frame; tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
        tf.vertical_anchor = MSO_ANCHOR.MIDDLE
        para(tf, "→", size=px(12), color=TEXT3, align=PP_ALIGN.CENTER, first=True)
        tf = tbox(s, x + 0.66, yy, cw8 - 0.96, 0.34)
        para(tf, it, size=px(18), color=TEXT, first=True, line=1.35)
cw2 = (CW - 0.44) / 3
risks = [("风险", "G2 协议为社区逆向，固件升级可能失效 → 跟进社区、争取官方 SDK"),
         ("风险", "嘈杂环境影响识别与声纹 → VAD 门槛 + 多条额外声纹"),
         ("风险", "提炼依赖网络 → 本地队列 + 指数退避，路线图补端侧小模型")]
for i, (k, b) in enumerate(risks):
    card(s, ML + i * (cw2 + 0.22), 5.32, cw2, 1.05, num=k, body=b, bodysize=px(16.5),
         numcolor=RGBColor(0xFF, 0x9F, 0x0A))
notes(s, "规划 + 风险一起讲，显得清醒。三个风险都有对应策略，不要回避。")

# ---------- 27 结尾 ----------
s = blank(prs); solid(s, BG)
tf = tbox(s, ML, 1.9, CW, 0.5)
para(tf, "W H I S P E R", size=px(26), color=TEXT3, bold=True, align=PP_ALIGN.CENTER, first=True, spc=6)
tf = tbox(s, ML, 2.85, CW, 2.0)
para(tf, "让每一次相遇，", size=px(80), color=TEXT, bold=True, align=PP_ALIGN.CENTER, first=True, line=1.08)
para(tf, "都有记忆。", size=px(80), color=BLUE, bold=True, align=PP_ALIGN.CENTER, line=1.08)
btn = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(W / 2 - 2.1), Inches(5.35), Inches(4.2), Inches(0.72))
btn.adjustments[0] = 0.5
btn.fill.solid(); btn.fill.fore_color.rgb = TEXT
btn.line.fill.background(); btn.shadow.inherit = False
tf = btn.text_frame; tf.margin_left = tf.margin_right = 0
tf.vertical_anchor = MSO_ANCHOR.MIDDLE
para(tf, "谢谢 · 欢迎提问与上手体验", size=px(21), color=BG, bold=True, align=PP_ALIGN.CENTER, first=True)
notes(s, "收尾一句话，然后停在 CTA 页等提问。如果时间允许，现场邀请上手体验。")

out = r"E:\Whisper\ppt\Whisper_路演_Apple风格.pptx"
prs.save(out)
print("saved:", out, "slides:", len(prs.slides.__iter__.__self__._sldIdLst))
