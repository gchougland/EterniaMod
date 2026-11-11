"""Generate the Chat Dock art: channel icons, emotes and the dock surfaces."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'src/main/resources/Common/UI/Custom/EterniaMod/Chat'
OUT.mkdir(parents=True, exist_ok=True)
S = 8  # supersample
INK, GOLD, SAGE, IVORY, DEEP = '#3a2d12', '#d8b66b', '#85c5b3', '#f0e8d5', '#101e22'
CHANNELS = {'server': '#c9d3cb', 'world': '#85c5b3', 'party': '#8fb8e8', 'guild': '#d8b66b', 'nearby': '#a9d18e'}


def canvas():
    image = Image.new('RGBA', (64 * S, 64 * S), (0, 0, 0, 0))
    return image, ImageDraw.Draw(image)


def save(image, name, size=64):
    image.resize((size, size), Image.Resampling.LANCZOS).save(OUT / (name + '.png'))


def p(points):
    return [(x * S, y * S) for x, y in points]


def box(x0, y0, x1, y1):
    return (x0 * S, y0 * S, x1 * S, y1 * S)


def line(draw, points, fill, width):
    draw.line(p(points), fill=fill, width=int(width * S), joint='curve')
    r = width * S / 2
    for x, y in p(points):
        draw.ellipse((x - r, y - r, x + r, y + r), fill=fill)


# ---- channel icons: one flat colour each, drawn bold so they read at 16 pixels ----
def channel(name, colour):
    image, d = canvas()
    if name == 'server':
        d.ellipse(box(8, 8, 56, 56), outline=colour, width=5 * S)
        d.ellipse(box(22, 8, 42, 56), outline=colour, width=4 * S)
        line(d, [(10, 32), (54, 32)], colour, 4)
    elif name == 'world':
        d.polygon(p([(8, 14), (24, 8), (40, 14), (56, 8), (56, 50), (40, 56), (24, 50), (8, 56)]), fill=colour)
        line(d, [(24, 12), (24, 48)], DEEP, 3)
        line(d, [(40, 16), (40, 52)], DEEP, 3)
    elif name == 'party':
        d.ellipse(box(14, 8, 34, 28), fill=colour)
        d.pieslice(box(6, 30, 42, 74), 180, 360, fill=colour)
        d.ellipse(box(38, 14, 54, 30), fill=colour)
        d.pieslice(box(36, 32, 60, 72), 200, 360, fill=colour)
    elif name == 'guild':
        d.polygon(p([(32, 6), (54, 14), (54, 32), (46, 48), (32, 58), (18, 48), (10, 32), (10, 14)]), fill=colour)
        d.polygon(p([(32, 16), (45, 21), (45, 31), (40, 42), (32, 48), (24, 42), (19, 31), (19, 21)]), fill=DEEP)
        d.polygon(p([(32, 22), (39, 31), (32, 42), (25, 31)]), fill=colour)
    else:
        d.polygon(p([(32, 58), (14, 32), (50, 32)]), fill=colour)
        d.ellipse(box(12, 6, 52, 46), fill=colour)
        d.ellipse(box(24, 18, 40, 34), fill=DEEP)
    save(image, 'Channel_' + name)


for key, value in CHANNELS.items():
    channel(key, value)


# ---- emotes: painted faces and small objects ----
def face(d, shade='#f2c94c'):
    d.ellipse(box(4, 4, 60, 60), fill='#c98f2a')
    d.ellipse(box(5, 4, 59, 57), fill=shade)
    d.ellipse(box(12, 8, 40, 26), fill='#f8dc7a')


def eyes(d, left=True, right=True):
    if left: d.ellipse(box(19, 22, 27, 32), fill=INK)
    if right: d.ellipse(box(37, 22, 45, 32), fill=INK)


def smile(d, y=38, depth=9):
    d.arc(box(18, y - depth, 46, y + depth), 20, 160, fill=INK, width=int(4.5 * S))


def emote(name, paint):
    image, d = canvas()
    paint(d)
    save(image, 'Emote_' + name)


def e_smile(d): face(d); eyes(d); smile(d)
def e_laugh(d):
    face(d)
    line(d, [(17, 28), (23, 23), (29, 28)], INK, 4)
    line(d, [(35, 28), (41, 23), (47, 28)], INK, 4)
    d.pieslice(box(16, 22, 48, 54), 0, 180, fill=INK)
    d.pieslice(box(22, 38, 42, 56), 0, 180, fill='#e6768a')
def e_wink(d):
    face(d); eyes(d, right=False)
    line(d, [(36, 28), (46, 26)], INK, 4)
    smile(d)
def e_wow(d):
    face(d); eyes(d)
    d.ellipse(box(25, 36, 39, 52), fill=INK)
def e_sad(d):
    face(d, '#e9c04a'); eyes(d)
    d.arc(box(19, 40, 45, 58), 200, 340, fill=INK, width=int(4.5 * S))
    d.polygon(p([(46, 32), (50, 40), (46, 44), (42, 40)]), fill='#7cc4e8')
def e_heart(d):
    d.ellipse(box(6, 10, 34, 38), fill='#e6768a')
    d.ellipse(box(30, 10, 58, 38), fill='#e6768a')
    d.polygon(p([(8, 30), (56, 30), (32, 58)]), fill='#e6768a')
    d.ellipse(box(13, 15, 25, 25), fill='#f4a3b1')
def e_star(d):
    d.polygon(p([(32, 4), (40, 23), (60, 25), (45, 39), (50, 59), (32, 48), (14, 59), (19, 39), (4, 25), (24, 23)]), fill=GOLD)
    d.polygon(p([(32, 12), (36, 25), (32, 30), (25, 26)]), fill='#f0d79a')
def e_flame(d):
    d.polygon(p([(32, 4), (46, 24), (52, 40), (44, 56), (32, 60), (20, 56), (12, 40), (18, 26), (24, 32)]), fill='#e8894a')
    d.polygon(p([(32, 26), (40, 40), (38, 52), (32, 56), (26, 52), (24, 42)]), fill='#f6cf6a')
def e_leaf(d):
    d.polygon(p([(10, 54), (10, 36), (18, 20), (32, 11), (54, 8), (52, 30), (44, 44), (28, 53)]), fill='#6fae5f')
    d.polygon(p([(10, 54), (10, 36), (18, 20), (32, 11), (54, 8)]), fill='#8fc97a')
    line(d, [(8, 57), (50, 12)], '#2f5a2a', 3)
    line(d, [(24, 40), (24, 26)], '#2f5a2a', 2)
    line(d, [(24, 40), (40, 40)], '#2f5a2a', 2)
def e_gem(d):
    d.polygon(p([(18, 10), (46, 10), (58, 26), (32, 58), (6, 26)]), fill='#7cc4e8')
    d.polygon(p([(18, 10), (46, 10), (40, 26), (24, 26)]), fill='#b9e2f6')
    d.polygon(p([(24, 26), (40, 26), (32, 58)]), fill='#4f9cc6')
def e_crown(d):
    d.polygon(p([(6, 20), (20, 34), (32, 12), (44, 34), (58, 20), (52, 50), (12, 50)]), fill=GOLD)
    d.rectangle(box(12, 46, 52, 54), fill='#b98f43')
    d.ellipse(box(28, 34, 36, 42), fill='#e6768a')
def e_sword(d):
    d.polygon(p([(52, 6), (58, 12), (26, 44), (20, 38)]), fill='#cfd8dc')
    d.polygon(p([(52, 6), (58, 6), (58, 12)]), fill=IVORY)
    line(d, [(14, 32), (32, 50)], GOLD, 6)
    line(d, [(18, 46), (8, 56)], '#8a5a34', 6)


EMOTES = {'smile': e_smile, 'laugh': e_laugh, 'wink': e_wink, 'wow': e_wow, 'sad': e_sad, 'heart': e_heart,
          'star': e_star, 'flame': e_flame, 'leaf': e_leaf, 'gem': e_gem, 'crown': e_crown, 'sword': e_sword}
for key, paint in EMOTES.items():
    emote(key, paint)


# ---- rank marks beside a name ----
image, d = canvas(); e_crown(d); save(image, 'Rank_founder')
image, d = canvas()
d.polygon(p([(32, 4), (40, 23), (60, 25), (45, 39), (50, 59), (32, 48), (14, 59), (19, 39), (4, 25), (24, 23)]), fill=SAGE)
save(image, 'Rank_officer')


# ---- surfaces: rounded panels, 48 px with a 12 px border ----
def surface(name, fill, outline=None, radius=10, alpha=255, width=2):
    image = Image.new('RGBA', (48 * S, 48 * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(image)
    rgb = tuple(int(fill[i:i + 2], 16) for i in (1, 3, 5))
    d.rounded_rectangle((0, 0, 48 * S - 1, 48 * S - 1), radius=radius * S, fill=rgb + (alpha,),
                        outline=outline, width=width * S if outline else 0)
    image.resize((48, 48), Image.Resampling.LANCZOS).save(OUT / (name + '.png'))


surface('DockLog', '#101e22', '#6e6442', alpha=214)
surface('DockInput', '#192d31', GOLD)
surface('DockTab', '#101e22', alpha=184, radius=8)
surface('DockTabHover', '#233c3e', alpha=235, radius=8)
surface('DockTabActive', '#192d31', GOLD, radius=8)
surface('DockPopup', '#192d31', '#a08a55')
surface('DockTile', '#233c3e', radius=8)
surface('DockTileHover', '#2f5154', GOLD, radius=8)
surface('DockHudLine', '#101e22', alpha=199, radius=14)
surface('DockBadge', '#8fb8e8', radius=22)
for key, value in CHANNELS.items():
    surface('Chip_' + key, value, radius=8)
print('Generated Chat Dock art in', OUT)
