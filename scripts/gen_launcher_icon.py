"""Whisper 启动图标生成器。

    py scripts/gen_launcher_icon.py              生成全部方案的预览页 build/icon-preview/index.html
    py scripts/gen_launcher_icon.py --apply A    把方案 A 写成 adaptive icon 放进 app/src/main/res

所有图形都在 108×108 的 adaptive icon 画布上描述，只用 path / group，
以便同一份数据既能输出 SVG 预览，也能原样输出 VectorDrawable。
启动器只显示中间 72×72，关键内容要落在以 (54,54) 为圆心、半径 33 的安全区内。
"""
import argparse
import itertools
import math
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / 'app' / 'src' / 'main' / 'res'
PREVIEW_DIR = ROOT / 'build' / 'icon-preview'

INDIGO = '#3D5AFE'
WHITE = '#FFFFFF'


# ---------------------------------------------------------------- 图形模型

class Linear:
    def __init__(self, x1, y1, x2, y2, *stops):
        self.x1, self.y1, self.x2, self.y2 = x1, y1, x2, y2
        # stops: (offset, '#RRGGBB'[, alpha])
        self.stops = [(s[0], s[1], s[2] if len(s) > 2 else 1.0) for s in stops]


class Path:
    def __init__(self, d, fill=None, stroke=None, width=0.0, alpha=1.0, even_odd=False):
        self.d, self.fill, self.stroke, self.width = d, fill, stroke, width
        self.alpha, self.even_odd = alpha, even_odd


class Group:
    def __init__(self, *children, rotate=0.0, pivot=(54, 54), translate=(0, 0), clip=None):
        self.children, self.rotate, self.pivot = list(children), rotate, pivot
        self.translate, self.clip = translate, clip


class Icon:
    def __init__(self, key, name, desc, background, foreground, monochrome):
        self.key, self.name, self.desc = key, name, desc
        self.background, self.foreground, self.monochrome = background, foreground, monochrome


# ---------------------------------------------------------------- 路径工具

def fmt(v):
    s = f'{v:.2f}'.rstrip('0').rstrip('.')
    return '0' if s in ('-0', '') else s


def d(*cmds):
    return ''.join(c[0] + ' '.join(fmt(v) for v in c[1:]) for c in cmds)


def rect(x, y, w, h):
    return d(('M', x, y), ('H', x + w), ('V', y + h), ('H', x), ('Z',))


def rrect(x, y, w, h, r, ccw=False):
    if not ccw:
        return d(('M', x + r, y), ('H', x + w - r), ('A', r, r, 0, 0, 1, x + w, y + r),
                 ('V', y + h - r), ('A', r, r, 0, 0, 1, x + w - r, y + h),
                 ('H', x + r), ('A', r, r, 0, 0, 1, x, y + h - r),
                 ('V', y + r), ('A', r, r, 0, 0, 1, x + r, y), ('Z',))
    return d(('M', x + r, y), ('A', r, r, 0, 0, 0, x, y + r),
             ('V', y + h - r), ('A', r, r, 0, 0, 0, x + r, y + h),
             ('H', x + w - r), ('A', r, r, 0, 0, 0, x + w, y + h - r),
             ('V', y + r), ('A', r, r, 0, 0, 0, x + w - r, y), ('Z',))


def circle(cx, cy, r, ccw=False):
    s = 0 if ccw else 1
    return d(('M', cx - r, cy), ('A', r, r, 0, 1, s, cx + r, cy),
             ('A', r, r, 0, 1, s, cx - r, cy), ('Z',))


def capsule(cx, cy, h, w, ccw=False):
    """竖直胶囊：两端圆心在 cy±h，半宽 w。"""
    if not ccw:
        return d(('M', cx - w, cy - h), ('A', w, w, 0, 0, 1, cx + w, cy - h),
                 ('V', cy + h), ('A', w, w, 0, 0, 1, cx - w, cy + h), ('Z',))
    return d(('M', cx + w, cy - h), ('A', w, w, 0, 0, 0, cx - w, cy - h),
             ('V', cy + h), ('A', w, w, 0, 0, 0, cx + w, cy + h), ('Z',))


def polar(cx, cy, r, deg):
    a = math.radians(deg)
    return cx + r * math.cos(a), cy - r * math.sin(a)


def arc(cx, cy, r, a0, a1):
    """开放圆弧，角度按数学习惯（逆时针、y 轴朝上），从 a0 画到 a1（a1 > a0）。"""
    x0, y0 = polar(cx, cy, r, a0)
    x1, y1 = polar(cx, cy, r, a1)
    return d(('M', x0, y0), ('A', r, r, 0, 1 if a1 - a0 > 180 else 0, 0, x1, y1))


FULL = rect(0, 0, 108, 108)


# ---------------------------------------------------------------- 方案

def icon_bubble():
    bubble = d(('M', 49, 32), ('H', 59), ('A', 19, 19, 0, 0, 1, 59, 70),
               ('H', 49), ('A', 19, 19, 0, 0, 1, 49, 32), ('Z',))
    tail = d(('M', 47, 68), ('C', 42, 73, 36, 76, 30, 77), ('C', 34, 73, 36, 68, 36, 60), ('Z',))
    xs, hs = [40, 47, 54, 61, 68], [3.5, 8, 12, 8, 3.5]
    bars = ''.join(capsule(x, 51, h, 2.4) for x, h in zip(xs, hs))
    holes = ''.join(capsule(x, 51, h, 2.4, ccw=True) for x, h in zip(xs, hs))
    return Icon(
        'A', '声波气泡', '对话气泡里一段声波：常驻倾听身边的对话。品牌靛蓝，识别度最直接。',
        [Path(FULL, fill=Linear(18, 18, 90, 90, (0, '#6E87FF'), (1, '#2D44E0')))],
        [Group(Path(bubble + tail, fill='#000000', alpha=0.16), translate=(0, 2.5)),
         Path(bubble + tail, fill=WHITE),
         Path(bars, fill=INDIGO)],
        [Path(bubble + tail + holes, fill=WHITE)],
    )


def icon_wave_w():
    w = d(('M', 30, 40), ('L', 42, 68), ('L', 54, 47), ('L', 66, 68), ('L', 78, 40))
    return Icon(
        'B', 'W 声波', '把 Whisper 的首字母 W 写成一段起伏的声波，右侧渐隐，像一句低语。偏品牌字标。',
        [Path(FULL, fill=Linear(18, 90, 90, 18, (0, '#3D5AFE'), (1, '#8C5CFF')))],
        [Group(Path(w, stroke='#000000', width=7.5, alpha=0.14), translate=(0, 2.2)),
         Path(w, stroke=Linear(30, 54, 78, 54, (0, WHITE, 1.0), (1, WHITE, 0.6)), width=7.5)],
        [Path(w, stroke=WHITE, width=7.5)],
    )


def icon_cards():
    card = (39, 33, 30, 41, 6)
    pivot = (54, 76)
    xs, hs = [44.5, 49.25, 54, 58.75, 63.5], [2.5, 6, 9.5, 6, 2.5]
    bars = ''.join(capsule(x, 53.5, h, 1.7) for x, h in zip(xs, hs))
    holes = ''.join(capsule(x, 53.5, h, 1.7, ccw=True) for x, h in zip(xs, hs))
    gap = rrect(card[0] - 2.2, card[1] - 2.2, card[2] + 4.4, card[3] + 4.4, card[4] + 2.2, ccw=True)
    return Icon(
        'C', '记忆卡片', '一叠彩色卡片，最上面一张印着声波：听到的话变成待办和事实卡片，和主界面的卡片堆呼应。',
        [Path(FULL, fill=Linear(54, 18, 54, 90, (0, '#FFFFFF'), (1, '#E4E8F5')))],
        [Group(Path(rrect(*card), fill='#00ACC1'), rotate=-13, pivot=pivot),
         Group(Path(rrect(*card), fill='#FB8C00'), rotate=13, pivot=pivot),
         Group(Path(rrect(*card), fill='#1A237E', alpha=0.18), translate=(0, 2)),
         Path(rrect(*card), fill=INDIGO),
         Path(bars, fill=WHITE)],
        [Group(Group(Path(rrect(*card), fill=WHITE, alpha=0.55), rotate=-13, pivot=pivot),
               Group(Path(rrect(*card), fill=WHITE, alpha=0.55), rotate=13, pivot=pivot),
               clip=FULL + gap),
         Path(rrect(*card) + holes, fill=WHITE)],
    )


def icon_ring():
    levels = [0.55, 0.85, 1.0, 0.7, 0.4, 0.6, 0.92, 0.72, 0.38, 0.28, 0.55, 0.88, 0.62, 0.34,
              0.5, 0.78, 0.58, 0.3, 0.45, 0.8, 0.98, 0.64, 0.36, 0.52, 0.84, 0.6, 0.32, 0.42]
    r0 = 15.5
    segs = []
    for i, v in enumerate(levels):
        deg = 90 - i * 360 / len(levels)
        segs.append(('M', *polar(54, 54, r0, deg)))
        segs.append(('L', *polar(54, 54, r0 + 2.5 + 11 * v, deg)))
    bars = d(*segs)
    return Icon(
        'D', '声纹圆环', '一圈长短不一的声纹包住中心点：声纹识别每一个说话的人。深色科技感，在浅色桌面上很跳。',
        [Path(FULL, fill=Linear(18, 18, 90, 90, (0, '#1F2A78'), (1, '#090C26')))],
        [Path(bars, stroke=Linear(30, 80, 80, 28, (0, '#5B7BFF'), (1, '#3FE6FF')), width=2.4),
         Path(circle(54, 54, 6.5), fill=WHITE)],
        [Path(bars, stroke=WHITE, width=2.4), Path(circle(54, 54, 6.5), fill=WHITE)],
    )


def icon_person():
    head = circle(49, 45, 9.5)
    body = d(('M', 33, 75), ('C', 33, 64.5, 40.5, 59, 49, 59), ('C', 57.5, 59, 65, 64.5, 65, 75), ('Z',))
    waves = arc(49, 45, 16.5, -36, 36) + arc(49, 45, 24, -36, 36)
    return Icon(
        'E', '认出熟人', '人像加两道声波：一开口就知道是谁、和 TA 约过什么。青绿色，和应用里“已读”的配色同色系。',
        [Path(FULL, fill=Linear(18, 18, 90, 90, (0, '#26C9AE'), (1, '#00796B')))],
        [Group(Path(head + body, fill='#000000', alpha=0.14), translate=(0, 2)),
         Path(head + body, fill=WHITE),
         Path(waves, stroke=WHITE, width=3.8)],
        [Path(head + body, fill=WHITE), Path(waves, stroke=WHITE, width=3.8)],
    )


def icon_glasses():
    green = '#4ADE80'
    lenses = rrect(28, 57, 22, 16, 8) + rrect(58, 57, 22, 16, 8)
    bridge = d(('M', 50, 62.5), ('Q', 54, 59.5, 58, 62.5))
    hud = d(('M', 63, 62.5), ('H', 75), ('M', 63, 67.5), ('H', 70))
    bubble = rrect(40, 32, 28, 16, 8)
    tail = d(('M', 47.5, 46), ('L', 45.5, 52.5), ('L', 53, 47.5), ('Z',))
    dots = ''.join(circle(x, 40, 2) for x in (47, 54, 61))
    dot_holes = ''.join(circle(x, 40, 2, ccw=True) for x in (47, 54, 61))
    return Icon(
        'F', '眼镜耳语', '一句低语出现在镜片上：配合 Even G2 眼镜把提醒悄悄推到眼前。绿色取自 G2 的显示色。',
        [Path(FULL, fill=Linear(18, 18, 90, 90, (0, '#323846'), (1, '#12151B')))],
        [Path(lenses + bridge, stroke=WHITE, width=3.2),
         Path(hud, stroke=green, width=2.6),
         Path(bubble + tail, fill=green),
         Path(dots, fill='#12151B')],
        [Path(lenses + bridge, stroke=WHITE, width=3.2),
         Path(hud, stroke=WHITE, width=2.6),
         Path(bubble + tail + dot_holes, fill=WHITE)],
    )


ICONS = [icon_bubble(), icon_wave_w(), icon_cards(), icon_ring(), icon_person(), icon_glasses()]


# ---------------------------------------------------------------- SVG（预览）

_ids = itertools.count()


def _svg_paint(paint, defs, override):
    if override:
        return override, 1.0
    if isinstance(paint, Linear):
        gid = f'g{next(_ids)}'
        stops = ''.join(f'<stop offset="{fmt(o)}" stop-color="{c}" stop-opacity="{fmt(a)}"/>'
                        for o, c, a in paint.stops)
        defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{fmt(paint.x1)}" '
                    f'y1="{fmt(paint.y1)}" x2="{fmt(paint.x2)}" y2="{fmt(paint.y2)}">{stops}</linearGradient>')
        return f'url(#{gid})', 1.0
    return paint, 1.0


def _svg_nodes(nodes, defs, override=None):
    out = []
    for n in nodes:
        if isinstance(n, Group):
            tf = []
            if n.translate != (0, 0):
                tf.append(f'translate({fmt(n.translate[0])} {fmt(n.translate[1])})')
            if n.rotate:
                tf.append(f'rotate({fmt(n.rotate)} {fmt(n.pivot[0])} {fmt(n.pivot[1])})')
            attrs = f' transform="{" ".join(tf)}"' if tf else ''
            if n.clip:
                cid = f'c{next(_ids)}'
                defs.append(f'<clipPath id="{cid}"><path d="{n.clip}" clip-rule="nonzero"/></clipPath>')
                attrs += f' clip-path="url(#{cid})"'
            out.append(f'<g{attrs}>{_svg_nodes(n.children, defs, override)}</g>')
            continue
        a = [f'd="{n.d}"']
        if n.fill:
            fill, _ = _svg_paint(n.fill, defs, override)
            a.append(f'fill="{fill}"')
            if n.alpha < 1:
                a.append(f'fill-opacity="{fmt(n.alpha)}"')
            if n.even_odd:
                a.append('fill-rule="evenodd"')
        else:
            a.append('fill="none"')
        if n.stroke:
            stroke, _ = _svg_paint(n.stroke, defs, override)
            a += [f'stroke="{stroke}"', f'stroke-width="{fmt(n.width)}"',
                  'stroke-linecap="round"', 'stroke-linejoin="round"']
            if n.alpha < 1:
                a.append(f'stroke-opacity="{fmt(n.alpha)}"')
        out.append(f'<path {" ".join(a)}/>')
    return ''.join(out)


def svg(layers, view='18 18 72 72', override=None, extra=''):
    defs = []
    body = ''.join(_svg_nodes(nodes, defs, override) for nodes in layers)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{view}">'
            f'<defs>{"".join(defs)}</defs>{body}{extra}</svg>')


# ---------------------------------------------------------------- VectorDrawable

def _argb(color, alpha=1.0):
    return f'#{round(alpha * 255):02X}{color[1:].upper()}'


def _xml_paint(attr, paint, ind):
    if isinstance(paint, Linear):
        items = ''.join(f'{ind}        <item android:offset="{fmt(o)}" android:color="{_argb(c, a)}" />\n'
                        for o, c, a in paint.stops)
        return (f'{ind}    <aapt:attr name="android:{attr}">\n'
                f'{ind}      <gradient android:type="linear" android:startX="{fmt(paint.x1)}" '
                f'android:startY="{fmt(paint.y1)}" android:endX="{fmt(paint.x2)}" android:endY="{fmt(paint.y2)}">\n'
                f'{items}{ind}      </gradient>\n{ind}    </aapt:attr>\n')
    return None


def _xml_nodes(nodes, ind='    '):
    out = []
    for n in nodes:
        if isinstance(n, Group):
            a = []
            if n.rotate:
                a += [f'android:rotation="{fmt(n.rotate)}"', f'android:pivotX="{fmt(n.pivot[0])}"',
                      f'android:pivotY="{fmt(n.pivot[1])}"']
            if n.translate != (0, 0):
                a += [f'android:translateX="{fmt(n.translate[0])}"', f'android:translateY="{fmt(n.translate[1])}"']
            out.append(f'{ind}<group{"".join(" " + x for x in a)}>\n')
            if n.clip:
                out.append(f'{ind}    <clip-path android:pathData="{n.clip}" />\n')
            out.append(_xml_nodes(n.children, ind + '    '))
            out.append(f'{ind}</group>\n')
            continue
        a = [f'android:pathData="{n.d}"']
        children = ''
        if n.fill:
            g = _xml_paint('fillColor', n.fill, ind)
            if g:
                children += g
            else:
                a.append(f'android:fillColor="{_argb(n.fill)}"')
            if n.alpha < 1:
                a.append(f'android:fillAlpha="{fmt(n.alpha)}"')
            if n.even_odd:
                a.append('android:fillType="evenOdd"')
        if n.stroke:
            g = _xml_paint('strokeColor', n.stroke, ind)
            if g:
                children += g
            else:
                a.append(f'android:strokeColor="{_argb(n.stroke)}"')
            a += [f'android:strokeWidth="{fmt(n.width)}"',
                  'android:strokeLineCap="round"', 'android:strokeLineJoin="round"']
            if n.alpha < 1:
                a.append(f'android:strokeAlpha="{fmt(n.alpha)}"')
        attrs = ''.join(f'\n{ind}    {x}' for x in a)
        if children:
            out.append(f'{ind}<path{attrs}>\n{children}{ind}</path>\n')
        else:
            out.append(f'{ind}<path{attrs} />\n')
    return ''.join(out)


def vector_xml(nodes):
    body = _xml_nodes(nodes)
    aapt = '\n    xmlns:aapt="http://schemas.android.com/aapt"' if 'aapt:attr' in body else ''
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android"{aapt}\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
            f'{body}</vector>\n')


ADAPTIVE = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
'''


def apply(icon):
    drawable = RES / 'drawable'
    (drawable / 'ic_launcher_background.xml').write_text(vector_xml(icon.background), encoding='utf-8')
    (drawable / 'ic_launcher_foreground.xml').write_text(vector_xml(icon.foreground), encoding='utf-8')
    (drawable / 'ic_launcher_monochrome.xml').write_text(vector_xml(icon.monochrome), encoding='utf-8')
    for name in ('ic_launcher.xml', 'ic_launcher_round.xml'):
        (RES / 'mipmap-anydpi' / name).write_text(ADAPTIVE, encoding='utf-8')
    print(f'已应用方案 {icon.key}「{icon.name}」')


# ---------------------------------------------------------------- 预览页

SAFE_ZONE = ('<rect x="0" y="0" width="108" height="108" fill="none" stroke="#f0f" stroke-width=".5"/>'
             '<rect x="18" y="18" width="72" height="72" fill="none" stroke="#0ff" stroke-width=".5"/>'
             '<circle cx="54" cy="54" r="33" fill="none" stroke="#f0f" stroke-width=".5" stroke-dasharray="2 2"/>')

PAGE = '''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>Whisper 启动图标方案</title>
<style>
  * {{ box-sizing: border-box; }}
  body {{ margin: 0 auto; max-width: 1240px; padding: 28px 32px 40px; background: #EEF0F6; color: #1B1F2A;
         font: 14px/1.5 -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; }}
  h1 {{ margin: 0 0 4px; font-size: 22px; }}
  .sub {{ margin: 0 0 22px; color: #5B6275; }}
  .grid {{ display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }}
  .card {{ background: #fff; border-radius: 18px; padding: 14px 16px; box-shadow: 0 2px 10px rgba(30,40,90,.07); }}
  .title {{ font-size: 17px; font-weight: 600; margin: 0 0 2px; }}
  .title b {{ display: inline-block; width: 26px; height: 26px; line-height: 26px; text-align: center;
             border-radius: 8px; background: #3D5AFE; color: #fff; margin-right: 8px; font-size: 14px; }}
  .desc {{ color: #5B6275; font-size: 13px; min-height: 40px; margin: 6px 0 14px; }}
  .row {{ display: flex; align-items: flex-end; gap: 12px; }}
  .shape {{ text-align: center; font-size: 11px; color: #8A90A2; }}
  .shape svg {{ display: block; margin-bottom: 6px; }}
  .big svg {{ width: 92px; height: 92px; }}
  .mid svg {{ width: 56px; height: 56px; }}
  .circle svg {{ border-radius: 50%; }}
  .squircle svg {{ border-radius: 27%; }}
  .themed svg {{ border-radius: 50%; background: #DCE2FF; }}
  .dock {{ margin-top: 26px; border-radius: 22px; padding: 26px 30px 22px;
          background: linear-gradient(135deg, #2B3A67 0%, #6A4C93 55%, #E07A5F 100%); }}
  .dock.light {{ background: linear-gradient(135deg, #F7E8D0 0%, #D9E8F5 60%, #C8D8C0 100%); }}
  .dock h2 {{ margin: 0 0 16px; font-size: 14px; color: rgba(255,255,255,.85); font-weight: 500; }}
  .dock.light h2 {{ color: #4A5060; }}
  .apps {{ display: flex; justify-content: space-around; }}
  .app {{ width: 72px; text-align: center; color: #fff; font-size: 12px; text-shadow: 0 1px 2px rgba(0,0,0,.4); }}
  .dock.light .app {{ color: #222; text-shadow: none; }}
  .app svg {{ width: 56px; height: 56px; border-radius: 27%; display: block; margin: 0 auto 6px;
             box-shadow: 0 2px 6px rgba(0,0,0,.18); }}
  .app small {{ display: block; opacity: .7; font-size: 11px; }}
  .debug {{ display: none; }}
  body.dbg .debug {{ display: flex; gap: 12px; margin-top: 12px; }}
  .debug svg {{ width: 130px; height: 130px; background: #fff; }}
</style></head>
<body>
<h1>Whisper 启动图标方案</h1>
<p class="sub">每个方案都是 adaptive icon（矢量前景 + 背景 + 主题单色层）。下面分别是圆形 / 圆角方形遮罩、Android 13 主题图标效果，以及桌面上的实际尺寸。</p>
<div class="grid">{cards}</div>
<div class="dock"><h2>深色壁纸 · 桌面实际大小</h2><div class="apps">{apps}</div></div>
<div class="dock light"><h2>浅色壁纸 · 桌面实际大小</h2><div class="apps">{apps}</div></div>
<script>if (location.search.includes('debug')) document.body.classList.add('dbg');</script>
</body></html>
'''


def preview():
    cards, apps = [], []
    for ic in ICONS:
        full = [ic.background, ic.foreground]
        cards.append(
            f'<div class="card"><div class="title"><b>{ic.key}</b>{ic.name}</div>'
            f'<div class="desc">{ic.desc}</div>'
            f'<div class="row">'
            f'<div class="shape big circle">{svg(full)}圆形</div>'
            f'<div class="shape big squircle">{svg(full)}圆角方形</div>'
            f'<div class="shape mid themed">{svg([ic.monochrome], override="#24357A")}主题图标</div>'
            f'</div>'
            f'<div class="debug">{svg(full, view="0 0 108 108", extra=SAFE_ZONE)}'
            f'{svg([ic.monochrome], view="0 0 108 108", override="#000", extra=SAFE_ZONE)}</div>'
            f'</div>')
        apps.append(f'<div class="app">{svg(full)}Whisper<small>{ic.key} · {ic.name}</small></div>')
    PREVIEW_DIR.mkdir(parents=True, exist_ok=True)
    out = PREVIEW_DIR / 'index.html'
    out.write_text(PAGE.format(cards=''.join(cards), apps=''.join(apps)), encoding='utf-8')
    print(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--apply', metavar='KEY', help='把指定方案写入 app/src/main/res')
    args = ap.parse_args()
    if args.apply:
        icon = next((i for i in ICONS if i.key == args.apply.upper()), None)
        if not icon:
            ap.error(f'没有方案 {args.apply}，可选：{", ".join(i.key for i in ICONS)}')
        apply(icon)
    else:
        preview()


if __name__ == '__main__':
    main()
