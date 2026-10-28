"""Generate the Journal and NPC desk art: Citadel pictograms, muted tab variants and see-through surfaces.

Same geometric language as generate-citadel-icons.py and generate-citadel-surfaces.py, so new art
matches the existing set. Run with Pillow from the repository root.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageColor

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / 'src/main/resources/Common/UI/Custom/EterniaMod'
ICONS = UI / 'Icons'
SURFACES = UI / 'Theme/Surfaces'
BRASS, SAGE, IVORY, GREEN = '#d8b66b', '#85c5b3', '#f0e8d5', '#233c3e'


def icon(name, parts):
    image = Image.new('RGBA', (512, 512)); draw = ImageDraw.Draw(image)
    svg = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128">']
    for kind, points, fill in parts:
        if kind == 'poly':
            draw.polygon([(x*4, y*4) for x, y in points], fill=fill)
            svg.append('<polygon points="'+' '.join(f'{x},{y}' for x, y in points)+'" fill="'+fill+'"/>')
        else:
            x, y, w, h = points; draw.ellipse((x*4, y*4, (x+w)*4, (y+h)*4), fill=fill)
            svg.append(f'<ellipse cx="{x+w/2}" cy="{y+h/2}" rx="{w/2}" ry="{h/2}" fill="{fill}"/>')
    svg.append('</svg>')
    (ICONS/(name+'.svg')).write_text('\n'.join(svg)+'\n', encoding='utf-8')
    image.resize((128, 128), Image.Resampling.LANCZOS).save(ICONS/(name+'.png'))


def muted(name):
    """A quiet copy for unselected tabs: the same shapes in sage grey, lighter parts kept lighter."""
    source = Image.open(ICONS/(name+'.png')).convert('RGBA')
    low, high = ImageColor.getrgb('#4c625e'), ImageColor.getrgb('#b5c3b8')
    out = Image.new('RGBA', source.size)
    pixels, target = source.load(), out.load()
    for y in range(source.height):
        for x in range(source.width):
            r, g, b, a = pixels[x, y]
            t = min(1.0, (0.3*r + 0.59*g + 0.11*b) / 220)
            target[x, y] = tuple(round(low[i] + (high[i]-low[i])*t) for i in range(3)) + (a,)
    out.save(ICONS/(name+'Muted.png'))


# New pictograms. Existing ones (housing, guild, mail, season, collection, worlds, coins, crown,
# wardrobe, title, pet, plot, palette, path, move, furnishing, addition, charter) are reused.
icon('quests', [('poly', [(34, 20), (96, 20), (96, 104), (34, 104)], IVORY),
                ('poly', [(26, 12), (104, 12), (108, 19), (104, 26), (26, 26), (22, 19)], BRASS),
                ('poly', [(26, 98), (104, 98), (108, 105), (104, 112), (26, 112), (22, 105)], BRASS),
                ('poly', [(46, 38), (84, 38), (84, 45), (46, 45)], GREEN),
                ('poly', [(46, 54), (84, 54), (84, 61), (46, 61)], GREEN),
                ('poly', [(46, 70), (70, 70), (70, 77), (46, 77)], GREEN),
                ('ellipse', (72, 74, 18, 18), SAGE)])
icon('mount', [('poly', [(24, 22), (46, 22), (46, 66), (50, 80), (64, 87), (78, 80), (82, 66), (82, 22), (104, 22), (104, 70), (96, 95), (64, 112), (32, 95), (24, 70)], BRASS),
               ('poly', [(20, 14), (50, 14), (50, 26), (20, 26)], SAGE),
               ('poly', [(78, 14), (108, 14), (108, 26), (78, 26)], SAGE),
               ('ellipse', (31, 36, 8, 8), GREEN), ('ellipse', (31, 56, 8, 8), GREEN),
               ('ellipse', (89, 36, 8, 8), GREEN), ('ellipse', (89, 56, 8, 8), GREEN)])
icon('market', [('poly', [(48, 20), (80, 20), (72, 34), (96, 56), (106, 86), (94, 108), (34, 108), (22, 86), (32, 56), (56, 34)], SAGE),
                ('poly', [(42, 28), (86, 28), (86, 38), (42, 38)], BRASS),
                ('ellipse', (48, 58, 32, 32), BRASS), ('ellipse', (55, 65, 18, 18), GREEN)])
icon('ledger', [('poly', [(24, 14), (98, 14), (106, 22), (106, 114), (30, 114), (24, 108)], BRASS),
                ('poly', [(36, 26), (94, 26), (94, 102), (36, 102)], GREEN),
                ('poly', [(24, 14), (34, 14), (34, 114), (24, 108)], IVORY),
                ('poly', [(65, 38), (82, 64), (65, 90), (48, 64)], SAGE)])
icon('storage', [('poly', [(18, 38), (110, 38), (110, 110), (18, 110)], BRASS),
                 ('poly', [(12, 26), (116, 26), (116, 42), (12, 42)], IVORY),
                 ('poly', [(18, 60), (110, 60), (110, 66), (18, 66)], GREEN),
                 ('poly', [(18, 84), (110, 84), (110, 90), (18, 90)], GREEN),
                 ('poly', [(54, 42), (74, 42), (74, 58), (54, 58)], SAGE)])
icon('toolkit', [('poly', [(24, 20), (82, 20), (92, 30), (92, 48), (24, 48)], BRASS),
                 ('poly', [(52, 48), (70, 48), (74, 114), (48, 114)], SAGE),
                 ('poly', [(50, 62), (72, 62), (72, 70), (50, 70)], IVORY),
                 ('poly', [(24, 20), (40, 20), (40, 48), (24, 48)], IVORY)])
icon('games', [('poly', [(22, 40), (74, 26), (88, 78), (36, 92)], IVORY),
               ('ellipse', (38, 44, 11, 11), GREEN), ('ellipse', (50, 56, 11, 11), GREEN), ('ellipse', (62, 68, 11, 11), GREEN),
               ('poly', [(70, 70), (110, 70), (110, 110), (70, 110)], SAGE),
               ('ellipse', (78, 78, 9, 9), BRASS), ('ellipse', (93, 93, 9, 9), BRASS)])
icon('board', [('poly', [(14, 18), (114, 18), (114, 92), (14, 92)], BRASS),
               ('poly', [(22, 26), (106, 26), (106, 84), (22, 84)], GREEN),
               ('poly', [(30, 34), (60, 34), (60, 64), (30, 64)], IVORY),
               ('poly', [(68, 38), (98, 38), (98, 74), (68, 74)], SAGE),
               ('poly', [(26, 92), (38, 92), (38, 116), (26, 116)], BRASS),
               ('poly', [(90, 92), (102, 92), (102, 116), (90, 116)], BRASS)])
icon('parcel', [('poly', [(20, 42), (108, 42), (108, 110), (20, 110)], SAGE),
                ('poly', [(14, 28), (114, 28), (114, 46), (14, 46)], IVORY),
                ('poly', [(57, 28), (71, 28), (71, 110), (57, 110)], BRASS),
                ('poly', [(20, 70), (108, 70), (108, 80), (20, 80)], BRASS)])
icon('trade', [('poly', [(14, 34), (82, 34), (82, 18), (112, 46), (82, 74), (82, 58), (14, 58)], BRASS),
               ('poly', [(114, 70), (46, 70), (46, 56), (16, 84), (46, 112), (46, 96), (114, 96)], SAGE)])
icon('members', [('ellipse', (68, 18, 30, 30), SAGE),
                 ('poly', [(56, 98), (60, 66), (72, 54), (96, 54), (108, 66), (114, 98)], SAGE),
                 ('ellipse', (26, 28, 34, 34), BRASS),
                 ('poly', [(12, 110), (16, 84), (28, 70), (58, 70), (70, 84), (74, 110)], BRASS)])
icon('close', [('poly', [(28, 18), (64, 54), (100, 18), (110, 28), (74, 64), (110, 100), (100, 110), (64, 74), (28, 110), (18, 100), (54, 64), (18, 28)], IVORY)])
icon('back', [('poly', [(14, 64), (56, 22), (68, 34), (46, 56), (114, 56), (114, 72), (46, 72), (68, 94), (56, 106)], IVORY)])

for name in ['collection', 'quests', 'season', 'mail', 'guild', 'housing',
             'wardrobe', 'title', 'pet', 'mount', 'furnishing', 'palette', 'addition', 'members', 'board', 'parcel', 'charter', 'trade', 'market', 'storage']:
    muted(name)


def clipped(size, cut):
    return [(cut, 0), (size-1-cut, 0), (size-1, cut), (size-1, size-1-cut), (size-1-cut, size-1), (cut, size-1), (0, size-1-cut), (0, cut)]


def glass(name, fill, alpha, rim, rim_alpha=255, width=1, cut=5):
    """Clipped-corner see-through panel. Nine-slice with a 12 px border, like the existing surfaces."""
    size = 64
    im = Image.new('RGBA', (size, size))
    mask = Image.new('L', im.size)
    ImageDraw.Draw(mask).polygon(clipped(size, cut), fill=alpha)
    body = Image.new('RGBA', im.size, ImageColor.getrgb(fill) + (255,))
    im.paste(body, (0, 0), mask)
    draw = ImageDraw.Draw(im)
    outline = clipped(size, cut); outline.append(outline[0])
    draw.line(outline, fill=ImageColor.getrgb(rim) + (rim_alpha,), width=width)
    im.save(SURFACES/(name+'.png'))


def bar(name, alpha, rim_side):
    """Full-width band for the top and bottom of the screen; a 1 px rim on the side facing the content."""
    im = Image.new('RGBA', (32, 32), ImageColor.getrgb('#101e22') + (alpha,))
    draw = ImageDraw.Draw(im)
    y = 31 if rim_side == 'bottom' else 0
    draw.line((0, y, 31, y), fill=ImageColor.getrgb('#45615d') + (255,))
    im.save(SURFACES/(name+'.png'))


def tab(name, strength, underline):
    """Top tab state: a gold wash rising from the bottom edge and a gold underline."""
    w, h = 32, 104
    im = Image.new('RGBA', (w, h))
    draw = ImageDraw.Draw(im)
    gold = ImageColor.getrgb(BRASS)
    for y in range(h):
        draw.line((0, y, w, y), fill=gold + (round(255*strength*(y/(h-1))**1.6),))
    if underline:
        draw.rectangle((0, h-underline, w, h), fill=gold + (255,))
    im.save(SURFACES/(name+'.png'))


def vignette():
    """Stretched across the screen: dark at the sides, lighter in the middle so the world shows."""
    w = 256
    im = Image.new('RGBA', (w, 4))
    draw = ImageDraw.Draw(im)
    base = ImageColor.getrgb('#0a1619')
    for x in range(w):
        d = abs(x - (w-1)/2) / ((w-1)/2)
        a = 0.34 + 0.56 * d**2.2
        draw.line((x, 0, x, 3), fill=base + (round(255*a),))
    im.save(SURFACES/'JournalVignette.png')


def emblem():
    """NPC portrait ring: gold rim with a soft halo around a dark disc."""
    size = 512
    im = Image.new('RGBA', (size, size))
    draw = ImageDraw.Draw(im)
    gold = ImageColor.getrgb(BRASS)
    for r in range(256, 216, -2):
        draw.ellipse((256-r, 256-r, 256+r, 256+r), outline=gold + (round(46*(1-(r-216)/40)),), width=3)
    draw.ellipse((40, 40, 472, 472), fill=gold + (255,))
    draw.ellipse((64, 64, 448, 448), fill=ImageColor.getrgb(GREEN) + (255,))
    im.resize((128, 128), Image.Resampling.LANCZOS).save(SURFACES/'Emblem.png')


glass('JournalPanel', '#101e22', 206, '#45615d')
glass('JournalInset', '#0a1619', 214, '#3c5953')
glass('JournalCard', '#192d31', 236, '#45615d')
glass('JournalCardHover', '#233c3e', 245, '#a58e5d')
glass('JournalCardPrimary', '#233c3e', 250, BRASS, width=2)
glass('JournalCardPrimaryHover', '#2c4849', 255, '#e8ca87', width=2)
glass('SideItem', '#101e22', 0, '#101e22', rim_alpha=0)
glass('SideItemHover', '#233c3e', 150, '#45615d', rim_alpha=120)
glass('SideItemSelected', '#233c3e', 245, '#45615d')
glass('Chip', '#233c3e', 232, '#45615d', cut=3)
glass('Keycap', '#233c3e', 255, '#6e8580', cut=3)
glass('IconTile', '#233c3e', 255, '#45615d', cut=3)
glass('IconTilePrimary', '#2c4849', 255, BRASS, width=2, cut=3)
bar('JournalTopBar', 240, 'bottom')
bar('JournalFootBar', 240, 'top')
tab('TabActive', 0.2, 3)
tab('TabHover', 0.1, 0)
vignette()
emblem()
print('Generated Journal pictograms, muted tab icons and see-through surfaces.')
