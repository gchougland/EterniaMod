"""Generate the static textures of the claim map (hex hover, selection, shape preview, legend swatches, map controls).

The live map picture itself is drawn at run time by ClaimMapRenderer.java. These sizes follow ClaimMapGeometry:
2.5 pixels per block, so a 32 x 36 block cell is 80 x 90 logical pixels and a row is 70 pixels. Every PNG is drawn
at twice its logical size (8x supersampled) so it stays crisp on large windows. Colours match
HexLandView.suggestedColor; change both together.

    python scripts/generate-claim-map-art.py
"""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'src/main/resources/Common/UI/Custom/EterniaMod/ClaimMap'
OUT.mkdir(parents=True, exist_ok=True)

SS = 8          # supersampling while drawing
DENSITY = 2     # shipped pixels per logical pixel
IVORY, BRASS, SAGE, DEEP = (240, 232, 213), (216, 182, 107), (133, 197, 179), (16, 30, 34)
WHITE = (248, 250, 252)
STATES = {
    'Mine': (0x2E, 0xC4, 0xB6), 'MyGuild': (0xE9, 0xB9, 0x49), 'OtherPlayer': (0x8F, 0xB8, 0xDE),
    'OtherGuild': (0x9B, 0x7E, 0xD9), 'FrontageFree': (0x6C, 0xC5, 0x6C), 'Free': (0x5E, 0x6B, 0x57),
    'Street': (0xA3, 0x9E, 0x93), 'Reserved': (0xE5, 0x8E, 0x4C),
}


def hex_points(cx, cy, w=80.0, tip=20.0, body=50.0, inset=0.0):
    """Pointy top hexagon (north and south tips) around a centre, in logical pixels, pulled in by inset."""
    half_h = tip + body / 2
    pts = [(0, -half_h), (w / 2, -body / 2), (w / 2, body / 2), (0, half_h), (-w / 2, body / 2), (-w / 2, -body / 2)]
    out = []
    for x, y in pts:
        d = (x * x + y * y) ** 0.5
        k = max(0.0, d - inset) / d
        out.append((cx + x * k, cy + y * k))
    return out


class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.image = Image.new('RGBA', (int(w * SS * DENSITY), int(h * SS * DENSITY)), (0, 0, 0, 0))
        self.draw = ImageDraw.Draw(self.image)

    def s(self, pts):
        k = SS * DENSITY
        return [(x * k, y * k) for x, y in pts]

    def poly(self, pts, fill=None, outline=None, width=0.0):
        if fill:
            self.draw.polygon(self.s(pts), fill=fill)
        if outline and width > 0:
            closed = self.s(pts) + [self.s(pts)[0], self.s(pts)[1]]
            self.draw.line(closed, fill=outline, width=max(1, round(width * SS * DENSITY)), joint='curve')

    def ellipse(self, box, fill=None, outline=None, width=0.0):
        k = SS * DENSITY
        self.draw.ellipse([v * k for v in box], fill=fill, outline=outline, width=max(0, round(width * k)))

    def save(self, name):
        size = (round(self.w * DENSITY), round(self.h * DENSITY))
        self.image.resize(size, Image.Resampling.LANCZOS).save(OUT / name)


def brick(fill_alpha, line_alpha, name):
    """Hover and press highlight for one clickable brick (80 x 70): the hexagon, tips cut by the brick."""
    c = Canvas(80, 70)
    pts = hex_points(40, 35, inset=1.5)
    c.poly(pts, fill=WHITE + (fill_alpha,), outline=WHITE + (line_alpha,), width=2.0)
    c.save(name + '.png')
    full = Image.open(OUT / (name + '.png'))
    half = full.width // 2
    # A half brick at the west edge shows the east half of its cell, and the other way round.
    full.crop((half, 0, full.width, full.height)).save(OUT / (name + 'West.png'))
    full.crop((0, 0, half, full.height)).save(OUT / (name + 'East.png'))


def overlay(name, fill, ring, ring_width, halo):
    """Marks drawn over the map picture, 92 x 102 so the stroke can spill past the cell edge."""
    c = Canvas(92, 102)
    if halo:
        c.poly(hex_points(46, 51, inset=-1.0), outline=DEEP + (150,), width=ring_width + 3.5)
    c.poly(hex_points(46, 51, inset=0.5), fill=fill, outline=ring, width=ring_width)
    c.save(name)


def swatch(name, rgb):
    c = Canvas(20, 22)
    # Same hex proportions at legend size: 20 wide, 22.5 tall.
    pts = hex_points(10, 11, w=20, tip=5, body=12, inset=0.6)
    if name == 'Free':
        c.poly(pts, fill=(40, 52, 46, 255), outline=rgb + (255,), width=1.2)
    elif name == 'FrontageFree':
        c.poly(pts, fill=rgb + (120,), outline=rgb + (255,), width=1.8)
    else:
        c.poly(pts, fill=rgb + (235,), outline=tuple(min(255, v + (255 - v) // 4) for v in rgb) + (255,), width=1.2)
    c.save('Swatch' + name + '.png')


def chevron(name, direction):
    c = Canvas(24, 24)
    pts = {'N': [(4, 16), (12, 7), (20, 16), (16.5, 18.5), (12, 13.2), (7.5, 18.5)]}['N']
    if direction != 'N':
        turn = {'E': 1, 'S': 2, 'W': 3}[direction]
        for _ in range(turn):
            pts = [(24 - y, x) for x, y in pts]
    c.poly(pts, fill=IVORY + (255,))
    c.save(name)


def compass():
    c = Canvas(44, 44)
    c.ellipse((3, 3, 41, 41), fill=DEEP + (210,), outline=BRASS + (255,), width=1.6)
    c.poly([(22, 7), (27, 22), (22, 19.5), (17, 22)], fill=BRASS + (255,))
    c.poly([(22, 37), (27, 22), (22, 24.5), (17, 22)], fill=SAGE + (150,))
    c.save('Compass.png')


def recentre():
    c = Canvas(24, 24)
    c.ellipse((5, 5, 19, 19), outline=IVORY + (255,), width=2.0)
    c.ellipse((9.5, 9.5, 14.5, 14.5), fill=BRASS + (255,))
    for (x0, y0, x1, y1) in [(12, 1, 12, 5), (12, 19, 12, 23), (1, 12, 5, 12), (19, 12, 23, 12)]:
        c.draw.line([(x0 * SS * DENSITY, y0 * SS * DENSITY), (x1 * SS * DENSITY, y1 * SS * DENSITY)], fill=IVORY + (255,), width=2 * SS * DENSITY)
    c.save('Recentre.png')


def loading():
    """Shown until the first map picture arrives: the same hatching the renderer uses for unexplored ground."""
    w, h = 440, 385
    image = Image.new('RGB', (w, h), (0x1B, 0x2A, 0x2B))
    draw = ImageDraw.Draw(image)
    for d in range(-h, w, 7):
        draw.line([(d, h), (d + h, 0)], fill=(0x22, 0x35, 0x36), width=1)
    image.save(OUT / 'MapLoading.png')


brick(40, 200, 'HexHover')
brick(72, 235, 'HexPress')
overlay('HexSelected.png', None, WHITE + (255,), 3.2, True)
overlay('HexPick.png', WHITE + (70,), WHITE + (235,), 2.4, True)
overlay('HexPickBad.png', (230, 144, 133, 40), (230, 144, 133, 235), 2.4, True)
for state, rgb in STATES.items():
    swatch(state, rgb)
for d in 'NESW':
    chevron('Arrow' + d + '.png', d)
compass()
recentre()
loading()
print('Wrote', len(list(OUT.glob('*.png'))), 'textures to', OUT)
