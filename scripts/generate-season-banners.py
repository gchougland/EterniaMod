"""Deterministic season pass banners and crests for the Banner Hall screens.

Each pass style gets a hanging banner (normal and active), and a crest badge.
Text is drawn by the game on top, so the art only holds cloth, rims and the crest.
Run with Pillow: python scripts/generate-season-banners.py
"""
import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageColor

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'src/main/resources/Common/UI/Custom/EterniaMod/SeasonArt'
OUT.mkdir(parents=True, exist_ok=True)

SS = 8  # drawing pixels per logical pixel; saved at 2x and 1x
W, H = 280, 560

STYLES = {
    'Hearth': '#d8b66b',
    'Tide': '#6fc3cf',
    'Ember': '#e58b5a',
    'Grove': '#93c47d',
}

def rgba(hex_color, alpha=255):
    r, g, b = ImageColor.getrgb(hex_color)
    return (r, g, b, alpha)

def bez(p0, p1, p2, p3, steps=24):
    out = []
    for i in range(steps + 1):
        t = i / steps
        a = (1 - t) ** 3; b = 3 * (1 - t) ** 2 * t; c = 3 * (1 - t) * t * t; d = t ** 3
        out.append((a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0], a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1]))
    return out

def arc(cx, cy, r, start, end, steps=32):
    return [(cx + r * math.cos(math.radians(start + (end - start) * i / steps)), cy + r * math.sin(math.radians(start + (end - start) * i / steps))) for i in range(steps + 1)]

def wave(x0, x1, y, amp=3, period=16):
    return [(x, y - amp * math.sin((x - x0) / period * 2 * math.pi)) for x in [x0 + i * 0.5 for i in range(int((x1 - x0) * 2) + 1)]]

def crest_shapes(style):
    """Lines and filled polygons in a 0..100 box."""
    lines, fills = [], []
    if style == 'Hearth':
        lines += [[(24, 82)] + arc(50, 48, 26, 180, 360) + [(76, 82)],
                  [(35, 82)] + arc(50, 50, 15, 180, 360) + [(65, 82)],
                  [(14, 82), (86, 82)],
                  [(24, 62), (35, 62)], [(65, 62), (76, 62)], [(24, 72), (35, 72)], [(65, 72), (76, 72)]]
        fills += [[(45, 18), (55, 18), (53, 28), (47, 28)]]
    elif style == 'Tide':
        lines += [[(42, 70), (46, 30), (54, 30), (58, 70), (42, 70)],
                  [(44, 30), (44, 22), (56, 22), (56, 30)],
                  [(50, 12), (50, 17)], [(30, 20), (40, 24)], [(70, 20), (60, 24)],
                  [(44, 44), (56, 44)], [(43, 57), (57, 57)],
                  wave(12, 88, 77), wave(18, 82, 88)]
    elif style == 'Ember':
        flame = (bez((50, 12), (60, 26), (70, 34), (66, 48)) + bez((66, 48), (64, 56), (58, 60), (50, 60)) +
                 bez((50, 60), (42, 60), (36, 56), (34, 48)) + bez((34, 48), (32, 38), (42, 32), (44, 22)) +
                 bez((44, 22), (48, 30), (52, 30), (50, 12)))
        inner = bez((50, 40), (55, 46), (56, 52), (50, 56)) + bez((50, 56), (44, 52), (46, 46), (50, 40))
        anvil = [(22, 68), (78, 68), (72, 75), (61, 75), (61, 83), (69, 83), (69, 88), (31, 88), (31, 83), (39, 83), (39, 75), (28, 75), (22, 68)]
        lines += [flame, inner, anvil]
    elif style == 'Grove':
        lines += [[(50, 88), (50, 56)], [(50, 68), (40, 60)], [(50, 63), (61, 54)],
                  arc(50, 37, 26, 0, 360, 64),
                  [(50, 22), (50, 50)], [(38, 34), (50, 42), (62, 34)],
                  [(50, 88), (40, 92)], [(50, 88), (60, 92)]]
    return lines, fills

def draw_crest(draw, style, color, cx, cy, size, stroke):
    lines, fills = crest_shapes(style)
    scale = size / 100
    to = lambda p: (cx + (p[0] - 50) * scale, cy + (p[1] - 50) * scale)
    for pts in fills:
        draw.polygon([to(p) for p in pts], fill=color)
    for pts in lines:
        mapped = [to(p) for p in pts]
        draw.line(mapped, fill=color, width=stroke, joint='curve')
        for end in (mapped[0], mapped[-1]):
            r = stroke / 2
            draw.ellipse((end[0] - r, end[1] - r, end[0] + r, end[1] + r), fill=color)

def save(im, name, logical):
    im.resize((logical[0] * 2, logical[1] * 2), Image.LANCZOS).save(OUT / (name + '@2x.png'))
    im.resize(logical, Image.LANCZOS).save(OUT / (name + '.png'))

def banner(style, color_hex, active):
    s = SS
    im = Image.new('RGBA', (W * s, H * s), (0, 0, 0, 0))
    color = rgba(color_hex)
    top, bottom = ((0x27, 0x42, 0x44), (0x18, 0x2d, 0x31)) if active else ((0x20, 0x37, 0x3a), (0x15, 0x27, 0x2b))
    cloth = [(14, 12), (266, 12), (266, 494), (140, 552), (14, 494)]
    grad = Image.new('RGBA', im.size)
    gd = ImageDraw.Draw(grad)
    for y in range(H * s):
        t = y / (H * s - 1)
        gd.line((0, y, W * s, y), fill=tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,))
    mask = Image.new('L', im.size, 0)
    ImageDraw.Draw(mask).polygon([(x * s, y * s) for x, y in cloth], fill=255)
    im.paste(grad, (0, 0), mask)
    d = ImageDraw.Draw(im)
    rim = 3 if active else 2
    d.line([(x * s, y * s) for x, y in cloth + [cloth[0]]], fill=color, width=rim * s, joint='curve')
    inner = [(25, 23), (255, 23), (255, 486), (140, 538), (25, 486), (25, 23)]
    d.line([(x * s, y * s) for x, y in inner], fill=rgba(color_hex, 90), width=int(1.5 * s), joint='curve')
    # Rod and finials.
    d.rounded_rectangle((2 * s, 4 * s, 278 * s, 15 * s), radius=5 * s, fill=rgba('#8c7443'))
    d.line((6 * s, 6 * s, 274 * s, 6 * s), fill=rgba('#b99a5c'), width=s)
    for x in (8, 272):
        d.ellipse(((x - 8) * s, (9.5 - 8) * s, (x + 8) * s, (9.5 + 8) * s), fill=color)
    # Status pill frame.
    d.rounded_rectangle((65 * s, 44 * s, 215 * s, 70 * s), radius=13 * s, outline=color, width=int(1.5 * s), fill=rgba('#101e22', 160))
    # Crest medallion.
    cx, cy, r = 140, 150, 60
    d.ellipse(((cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s), fill=rgba('#101e22'), outline=color, width=2 * s)
    d.ellipse(((cx - r + 6) * s, (cy - r + 6) * s, (cx + r - 6) * s, (cy + r - 6) * s), outline=rgba(color_hex, 70), width=s)
    draw_crest(d, style, color, cx * s, cy * s, 86 * s, int(3.2 * 0.86 * s))
    # Divider above the next reward.
    d.line((44 * s, 358 * s, 236 * s, 358 * s), fill=rgba('#45615d'), width=s)
    d.polygon([(140 * s, 354 * s), (144 * s, 358 * s), (140 * s, 362 * s), (136 * s, 358 * s)], fill=color)
    save(im, 'Banner' + style + ('Active' if active else ''), (W, H))

def crest(style, color_hex):
    s, size = SS, 96
    im = Image.new('RGBA', (size * s, size * s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    color = rgba(color_hex)
    c, r = size / 2, size / 2 - 2
    d.ellipse(((c - r) * s, (c - r) * s, (c + r) * s, (c + r) * s), fill=rgba('#101e22'), outline=color, width=2 * s)
    draw_crest(d, style, color, c * s, c * s, 64 * s, int(3.2 * 0.64 * s) + 2)
    save(im, 'Crest' + style, (size, size))

def marker():
    s, size = SS, 28
    im = Image.new('RGBA', (size * s, size * s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse((0, 0, size * s - 1, size * s - 1), fill=rgba('#d8b66b'))
    d.ellipse((4 * s, 4 * s, (size - 4) * s, (size - 4) * s), fill=rgba('#f0e8d5'))
    save(im, 'Marker', (size, size))

for name, hex_color in STYLES.items():
    banner(name, hex_color, False)
    banner(name, hex_color, True)
    crest(name, hex_color)
marker()
print('Generated', len(STYLES) * 3 + 1, 'season images in', OUT)
