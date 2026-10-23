#!/usr/bin/env python3
"""
Векторные иконки Android из геометрии logo.svg (координаты 512×512):

  res/drawable/ic_logo.xml                 — логотип целиком (шапки экранов)
  res/drawable/ic_launcher_background.xml  — фон адаптивной иконки (#17212B)
  res/drawable/ic_launcher_foreground.xml  — диск + самолётик + ▶ (в safe zone)
  res/drawable/ic_launcher_monochrome.xml  — силуэт для тематических иконок (Android 13+)

VectorDrawable не умеет stroke-dasharray, поэтому пунктир следа
пересчитывается в кружки вдоль кривой. Рядом кладутся SVG-превью
(branding/preview-*.svg) из тех же примитивов — чтобы проверить глазами.

    /tmp/img/bin/python make_android_icons.py
"""
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "android", "app", "src", "main", "res")

PLANE = ("M84,26 L24,50 C21.5,51 21.7,54.4 24.3,55.2 L38,59.4 L44,77.5 C44.8,79.9 47.9,80.3 49.4,78.2 "
         "L56.6,68.4 L68.5,77.3 C70.7,78.9 73.8,77.7 74.4,75.1 L86.8,29.6 C87.4,27.2 85.6,25.1 84,26 Z")
PLANE_FOLD = "M38,59.4 L72,36 L48.5,66.5 L44,77.5 Z"
# translate(262 262) scale(3.5) translate(-54.5 -52.5)
PLANE_S, PLANE_TX, PLANE_TY = 3.5, 262 - 3.5 * 54.5, 262 - 3.5 * 52.5


def circle(cx, cy, r):
    return f"M{cx - r:g},{cy:g} a{r:g},{r:g} 0 1,0 {2 * r:g},0 a{r:g},{r:g} 0 1,0 {-2 * r:g},0 Z"


def trail_dots():
    """Пунктир Q(104,402)-(150,352)-(212,336): точка r=7.5 каждые 35 единиц."""
    p0, p1, p2 = (104, 402), (150, 352), (212, 336)
    pts, prev, acc = [], None, 0.0
    for i in range(0, 2001):
        t = i / 2000
        x = (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0]
        y = (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]
        if prev is None:
            pts.append((x, y))
        else:
            acc += math.dist(prev, (x, y))
            if acc >= 35:
                pts.append((x, y))
                acc = 0.0
        prev = (x, y)
    return " ".join(circle(round(x, 1), round(y, 1), 7.5) for x, y in pts)


TRAIL = trail_dots()
DISC = circle(256, 262, 178)
BADGE_RING = circle(392, 128, 56)
BADGE = circle(392, 128, 50)
PLAY = "M377,104 L413,128 L377,152 Z"
TILE = "M112,0 H400 A112,112 0 0 1 512,112 V400 A112,112 0 0 1 400,512 H112 A112,112 0 0 1 0,400 V112 A112,112 0 0 1 112,0 Z"

GRAD_BG = ("linear", 0, 0, 0, 512, "#182533", "#0E1621")
GRAD_DISC = ("linear", 131.4, 119.6, 380.6, 422.2, "#5288C1", "#2B5278")


def logo_layers(tile=True, badge_hole="#0E1621"):
    """Слои: (pathData, fill | gradient, group) — group=None или 'plane'."""
    L = []
    if tile:
        L.append((TILE, GRAD_BG, None))
    L += [(DISC, GRAD_DISC, None),
          (TRAIL, "#FBBF24", None),
          (PLANE, "#F5F6F7", "plane"),
          (PLANE_FOLD, "#C9D7E6", "plane"),
          (BADGE_RING, badge_hole, None),
          (BADGE, "#FBBF24", None),
          (PLAY, "#0E1621", None)]
    return L


# ---------------- VectorDrawable ----------------

def v_path(d, fill, indent):
    pad = " " * indent
    if isinstance(fill, tuple):
        _, x1, y1, x2, y2, c1, c2 = fill
        return (f'{pad}<path android:pathData="{d}">\n'
                f'{pad}    <aapt:attr name="android:fillColor">\n'
                f'{pad}        <gradient android:type="linear" android:startX="{x1:g}" android:startY="{y1:g}"\n'
                f'{pad}            android:endX="{x2:g}" android:endY="{y2:g}"\n'
                f'{pad}            android:startColor="{c1}" android:endColor="{c2}" />\n'
                f'{pad}    </aapt:attr>\n'
                f'{pad}</path>\n')
    extra = (f' android:strokeColor="{fill if fill == "#FFFFFF" else "#0E1621"}" android:strokeWidth="6"'
             ' android:strokeLineJoin="round"') if d == PLAY else ""
    return f'{pad}<path android:fillColor="{fill}"{extra} android:pathData="{d}" />\n'


def vector(layers, size_dp, viewport, scale=1.0, tx=0.0, ty=0.0, comment=""):
    out = ['<?xml version="1.0" encoding="utf-8"?>\n',
           f'<!-- {comment} Сгенерировано branding/make_android_icons.py — не править руками. -->\n',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n',
           '    xmlns:aapt="http://schemas.android.com/aapt"\n',
           f'    android:width="{size_dp}dp" android:height="{size_dp}dp"\n',
           f'    android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">\n',
           f'    <group android:scaleX="{scale:g}" android:scaleY="{scale:g}" '
           f'android:translateX="{tx:g}" android:translateY="{ty:g}">\n']
    in_plane = False
    for d, fill, grp in layers:
        if grp == "plane" and not in_plane:
            out.append(f'        <group android:scaleX="{PLANE_S:g}" android:scaleY="{PLANE_S:g}" '
                       f'android:translateX="{PLANE_TX:g}" android:translateY="{PLANE_TY:g}">\n')
            in_plane = True
        if grp != "plane" and in_plane:
            out.append('        </group>\n')
            in_plane = False
        out.append(v_path(d, fill, 12 if in_plane else 8))
    if in_plane:
        out.append('        </group>\n')
    out.append('    </group>\n</vector>\n')
    return "".join(out)


# ---------------- SVG-превью из тех же слоёв ----------------

def svg(layers, viewport, scale=1.0, tx=0.0, ty=0.0, bg=None, mask=False):
    defs, body = [], []
    for i, (d, fill, grp) in enumerate(layers):
        if isinstance(fill, tuple):
            _, x1, y1, x2, y2, c1, c2 = fill
            defs.append(f'<linearGradient id="g{i}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">'
                        f'<stop offset="0" stop-color="{c1}"/><stop offset="1" stop-color="{c2}"/></linearGradient>')
            f = f"url(#g{i})"
        else:
            f = fill
        extra = (f' stroke="{f if f == "#FFFFFF" else "#0E1621"}" stroke-width="6"'
                 ' stroke-linejoin="round"') if d == PLAY else ""
        p = f'<path d="{d}" fill="{f}"{extra}/>'
        if grp == "plane":
            p = f'<g transform="translate({PLANE_TX} {PLANE_TY}) scale({PLANE_S})">{p}</g>'
        body.append(p)
    back = f'<rect width="{viewport}" height="{viewport}" fill="{bg}"/>' if bg else ""
    clip_open, clip_close = "", ""
    if mask:  # маска лаунчера — круг 72dp из 108
        defs.append(f'<clipPath id="m"><circle cx="54" cy="54" r="36"/></clipPath>')
        clip_open, clip_close = '<g clip-path="url(#m)">', "</g>"
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {viewport} {viewport}">'
            f'<defs>{"".join(defs)}</defs>{clip_open}{back}'
            f'<g transform="translate({tx} {ty}) scale({scale})">{"".join(body)}</g>{clip_close}</svg>')


def main():
    drawable = os.path.join(RES, "drawable")
    os.makedirs(drawable, exist_ok=True)
    # Лаунчер: крайняя точка (значок ▶) ≈240 ед. от центра → в круг ~34dp.
    s = 0.143
    cx, cy = 260, 256
    tx, ty = 54 - cx * s, 54 - cy * s
    fg = logo_layers(tile=False, badge_hole="#17212B")
    # силуэт: самолётик, след и ▶ (без кругов — иначе значок сливается с носом)
    mono = [(d, "#FFFFFF", g) for d, f, g in fg if d not in (DISC, BADGE_RING, BADGE, PLANE_FOLD)]
    files = {
        "ic_logo.xml": vector(logo_layers(), 48, 512, comment="Логотип BotControl."),
        "ic_launcher_foreground.xml": vector(fg, 108, 108, s, tx, ty, comment="Иконка: передний план."),
        "ic_launcher_monochrome.xml": vector(mono, 108, 108, s, tx, ty, comment="Иконка: монохром (Android 13+)."),
        "ic_launcher_background.xml": (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Иконка: фон (палитра Telegram). Сгенерировано branding/make_android_icons.py. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="108" android:viewportHeight="108">\n'
            '    <path android:fillColor="#17212B" android:pathData="M0,0h108v108h-108z" />\n'
            '</vector>\n'),
    }
    for name, xml in files.items():
        with open(os.path.join(drawable, name), "w", encoding="utf-8") as f:
            f.write(xml)
        print("→ res/drawable/" + name)
    previews = {
        "preview-launcher.svg": svg(fg, 108, s, tx, ty, bg="#17212B", mask=True),
        "preview-mono.svg": svg(mono, 108, s, tx, ty, bg="#33475A", mask=True),
        "preview-logo.svg": svg(logo_layers(), 512),
    }
    for name, x in previews.items():
        with open(os.path.join("/tmp", name), "w", encoding="utf-8") as f:
            f.write(x)
    try:
        import resvg_py
        for name, x in previews.items():
            png = resvg_py.svg_to_bytes(svg_string=x, width=324, height=324)
            with open(os.path.join("/tmp", name.replace(".svg", ".png")), "wb") as f:
                f.write(bytes(png))
        print("превью: /tmp/preview-*.png")
    except ImportError:
        pass


if __name__ == "__main__":
    main()
