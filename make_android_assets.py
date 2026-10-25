#!/usr/bin/env python3
"""Génère l'icône, l'écran de démarrage et les couleurs Android de Cabine Momo.

Usage : python3 make_android_assets.py android/app/src/main/res [--preview apercu.png]
Nécessite uniquement Pillow (pip install pillow).
"""
import math
import os
import re
import sys

from PIL import Image, ImageDraw

SS = 4  # suréchantillonnage pour des bords lisses

GREEN_DEEP = (6, 64, 43, 255)
GOLD = (255, 200, 61, 255)
GOLD_DARK = (224, 162, 26, 255)
CREAM = (255, 246, 224, 255)
WHITE_CREAM = (255, 251, 238, 255)

BG_LIGHT = (18, 168, 105)
BG_DARK = (4, 52, 35)


def lerp(a, b, t):
    return a + (b - a) * t


def make_background(w, h):
    """Fond émeraude, lueur claire en haut à gauche, lueur dorée discrète en bas à droite."""
    sw, sh = 96, max(16, round(96 * h / w))
    small = Image.new("RGB", (sw, sh))
    px = small.load()
    for y in range(sh):
        for x in range(sw):
            u, v = x / (sw - 1), y / (sh - 1)
            t = min(1.0, max(0.0, (u * 0.45 + v * 0.55)))
            r = lerp(BG_LIGHT[0], BG_DARK[0], t ** 0.9)
            g = lerp(BG_LIGHT[1], BG_DARK[1], t ** 0.9)
            b = lerp(BG_LIGHT[2], BG_DARK[2], t ** 0.9)
            d1 = math.hypot(u - 0.18, v - 0.12)
            k1 = max(0.0, 1 - d1 / 0.65) ** 2 * 0.28
            r, g, b = lerp(r, 120, k1), lerp(g, 240, k1), lerp(b, 180, k1)
            d2 = math.hypot(u - 0.95, v - 0.98)
            k2 = max(0.0, 1 - d2 / 0.55) ** 2 * 0.10
            r, g, b = lerp(r, 255, k2), lerp(g, 200, k2), lerp(b, 61, k2)
            px[x, y] = (int(r), int(g), int(b))
    return small.resize((w, h), Image.BICUBIC).convert("RGBA")


def overlay(img, draw_fn):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(layer))
    img.alpha_composite(layer)


def draw_booth(img, cx, cy, width):
    """Dessine la cabine (kiosque à auvent rayé). Unités : largeur 100, hauteur ~102."""
    k = width / 100.0
    ox, oy = cx - 50 * k, cy - 55 * k

    def P(x, y):
        return (ox + x * k, oy + y * k)

    def rect(d, x0, y0, x1, y1, r, fill):
        d.rounded_rectangle([P(x0, y0), P(x1, y1)], radius=r * k, fill=fill)

    overlay(img, lambda d: d.ellipse([P(4, 101), P(96, 111)], fill=(0, 0, 0, 80)))
    overlay(img, lambda d: rect(d, 8, 36, 92, 104, 5, CREAM))
    overlay(img, lambda d: d.rectangle([P(8, 38), P(92, 47)], fill=(0, 0, 0, 38)))
    overlay(img, lambda d: rect(d, 20, 46, 80, 82, 5, GREEN_DEEP))
    overlay(img, lambda d: d.polygon([P(27, 46), P(41, 46), P(29, 82), P(20, 82), P(20, 74)], fill=(255, 255, 255, 34)))

    def coin(d):
        d.ellipse([P(37.5, 51.5), P(62.5, 76.5)], fill=GOLD)
        d.ellipse([P(40.5, 54.5), P(59.5, 73.5)], outline=GOLD_DARK, width=max(1, round(1.3 * k)))
        d.rectangle([P(46.2, 58), P(49.4, 71)], fill=GREEN_DEEP)
        d.rectangle([P(46.2, 58), P(55, 60.8)], fill=GREEN_DEEP)
        d.rectangle([P(46.2, 63), P(53, 65.6)], fill=GREEN_DEEP)
    overlay(img, coin)

    def counter(d):
        rect(d, 4, 80, 96, 88, 2.5, GOLD_DARK)
        d.rounded_rectangle([P(5, 80.4), P(95, 82.2)], radius=0.9 * k, fill=(255, 224, 130, 255))
    overlay(img, counter)

    def bars(d):
        for x, hgt in ((40, 4), (47, 7), (54, 10)):
            d.rounded_rectangle([P(x, 100 - hgt), P(x + 5, 100)], radius=1 * k, fill=GREEN_DEEP)
    overlay(img, bars)

    n = 7

    def awning(d):
        for i in range(n):
            col = GOLD if i % 2 == 0 else WHITE_CREAM
            tx0, tx1 = 14 + 72 * i / n, 14 + 72 * (i + 1) / n
            bx0, bx1 = 100 * i / n, 100 * (i + 1) / n
            d.polygon([P(tx0, 10), P(tx1, 10), P(bx1, 38), P(bx0, 38)], fill=col)
            mid, rad = (bx0 + bx1) / 2, (bx1 - bx0) / 2
            d.ellipse([P(mid - rad, 38 - rad), P(mid + rad, 38 + rad)], fill=col)
        rect(d, 12, 4, 88, 12.5, 4, GREEN_DEEP)
    overlay(img, awning)


def render_icon(size, kind):
    big = size * SS
    if kind == "bg":
        return make_background(size, size)
    if kind == "fg":
        img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
        draw_booth(img, big / 2, big / 2, big * 0.44)
        return img.resize((size, size), Image.LANCZOS)
    img = make_background(big, big)
    draw_booth(img, big / 2, big * 0.5, big * 0.60)
    mask = Image.new("L", (big, big), 0)
    md = ImageDraw.Draw(mask)
    if kind == "round":
        md.ellipse([0, 0, big - 1, big - 1], fill=255)
    else:
        md.rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * 0.22), fill=255)
    img.putalpha(mask)
    return img.resize((size, size), Image.LANCZOS)


def render_splash(w, h):
    img = make_background(w, h)
    big = Image.new("RGBA", (w * 2, h * 2), (0, 0, 0, 0))
    draw_booth(big, w, h, min(w, h) * 2 * 0.34)
    big = big.resize((w, h), Image.LANCZOS)
    img.alpha_composite(big)
    return img.convert("RGB")


DENS = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_bg"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
"""

COLORS_XML = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="momo_bg">#04281B</color>
    <color name="momo_bar">#06402B</color>
</resources>
"""


def patch_styles(res):
    path = os.path.join(res, "values", "styles.xml")
    if not os.path.exists(path):
        print("styles.xml introuvable : thème non modifié")
        return
    s = open(path, encoding="utf-8").read()
    if "momo_bar" in s:
        return

    def add(match, items):
        return match.group(0) + "\n" + "\n".join("        " + i for i in items)

    s = re.sub(
        r'<style name="AppTheme\.NoActionBar"[^>]*>',
        lambda m: add(m, [
            '<item name="android:statusBarColor">@color/momo_bar</item>',
            '<item name="android:navigationBarColor">@color/momo_bar</item>',
            '<item name="android:windowBackground">@color/momo_bg</item>',
        ]),
        s, count=1)
    if re.search(r'<style name="AppTheme\.NoActionBarLaunch"[^>]*parent="Theme\.SplashScreen"', s):
        s = re.sub(
            r'<style name="AppTheme\.NoActionBarLaunch"[^>]*>',
            lambda m: add(m, ['<item name="windowSplashScreenBackground">@color/momo_bg</item>']),
            s, count=1)
    open(path, "w", encoding="utf-8").write(s)


def main():
    argv = sys.argv[1:]
    preview = None
    if "--preview" in argv:
        i = argv.index("--preview")
        preview = argv[i + 1]
        del argv[i:i + 2]
    if preview:
        sheet = Image.new("RGBA", (512 * 3 + 80, 512 + 40), (240, 240, 240, 255))
        sheet.alpha_composite(render_icon(512, "legacy"), (20, 20))
        sheet.alpha_composite(render_icon(512, "round"), (512 + 40, 20))
        fg = Image.new("RGBA", (512, 512), (4, 80, 52, 255))
        fg.alpha_composite(render_icon(512, "fg"))
        sheet.alpha_composite(fg, (512 * 2 + 60, 20))
        sheet.save(preview)
        print("aperçu :", preview)
    if not argv:
        return
    res = argv[0]
    if not os.path.isdir(res):
        sys.exit("Dossier introuvable : " + res)
    for name, f in DENS.items():
        d = os.path.join(res, "mipmap-" + name)
        os.makedirs(d, exist_ok=True)
        render_icon(round(48 * f), "legacy").save(os.path.join(d, "ic_launcher.png"))
        render_icon(round(48 * f), "round").save(os.path.join(d, "ic_launcher_round.png"))
        render_icon(round(108 * f), "fg").save(os.path.join(d, "ic_launcher_foreground.png"))
        render_icon(round(108 * f), "bg").convert("RGB").save(os.path.join(d, "ic_launcher_bg.png"))
    any26 = os.path.join(res, "mipmap-anydpi-v26")
    os.makedirs(any26, exist_ok=True)
    for n in ("ic_launcher.xml", "ic_launcher_round.xml"):
        open(os.path.join(any26, n), "w", encoding="utf-8").write(ADAPTIVE_XML)
    found = []
    for root, _, files in os.walk(res):
        for fn in files:
            if fn == "splash.png":
                found.append(os.path.join(root, fn))
    for p in found:
        w, h = Image.open(p).size
        render_splash(w, h).save(p)
    if not found:
        os.makedirs(os.path.join(res, "drawable"), exist_ok=True)
        render_splash(1080, 1920).save(os.path.join(res, "drawable", "splash.png"))
    os.makedirs(os.path.join(res, "values"), exist_ok=True)
    open(os.path.join(res, "values", "momo_colors.xml"), "w", encoding="utf-8").write(COLORS_XML)
    patch_styles(res)
    print("Icône, écran de démarrage et thème générés dans", res)


if __name__ == "__main__":
    main()
