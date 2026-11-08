"""Generate the Citadel pictograms of the stats page (Icons/Stats*.svg and native PNG renditions).

Same flat brass, sage, ivory and deep green shapes as generate-citadel-icons.py, drawn at 4x and scaled down.
"""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'src/main/resources/Common/UI/Custom/EterniaMod/Icons'
OUT.mkdir(parents=True, exist_ok=True)
BRASS, SAGE, IVORY, GREEN, RUST = '#d8b66b', '#85c5b3', '#f0e8d5', '#233c3e', '#e69085'
DARK_BRASS = '#a9753b'


def icon(name, parts):
    image = Image.new('RGBA', (512, 512)); draw = ImageDraw.Draw(image)
    svg = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128">']
    for kind, points, fill in parts:
        if kind == 'poly':
            draw.polygon([(x * 4, y * 4) for x, y in points], fill=fill)
            svg.append('<polygon points="' + ' '.join(f'{x},{y}' for x, y in points) + '" fill="' + fill + '"/>')
        else:
            x, y, w, h = points; draw.ellipse((x * 4, y * 4, (x + w) * 4, (y + h) * 4), fill=fill)
            svg.append(f'<ellipse cx="{x + w / 2}" cy="{y + h / 2}" rx="{w / 2}" ry="{h / 2}" fill="{fill}"/>')
    svg.append('</svg>')
    (OUT / (name + '.svg')).write_text('\n'.join(svg) + '\n', encoding='utf-8')
    image.resize((128, 128), Image.Resampling.LANCZOS).save(OUT / (name + '.png'))


# A treasure chest: lid, body, brass bands and a lock.
icon('StatsChest', [
    ('poly', [(18, 50), (110, 50), (110, 108), (18, 108)], DARK_BRASS),
    ('poly', [(22, 30), (36, 20), (92, 20), (106, 30), (110, 50), (18, 50)], BRASS),
    ('poly', [(18, 50), (110, 50), (110, 58), (18, 58)], GREEN),
    ('poly', [(30, 20), (40, 20), (40, 108), (30, 108)], SAGE),
    ('poly', [(88, 20), (98, 20), (98, 108), (88, 108)], SAGE),
    ('poly', [(54, 44), (74, 44), (74, 72), (54, 72)], IVORY),
    ('ellipse', (59, 52, 10, 10), GREEN),
])
# A walking boot with a dotted trail: travel.
icon('StatsTravel', [
    ('poly', [(40, 10), (70, 10), (70, 62), (96, 66), (108, 80), (108, 90), (36, 90)], BRASS),
    ('poly', [(40, 10), (70, 10), (70, 20), (40, 20)], DARK_BRASS),
    ('poly', [(46, 30), (64, 30), (64, 36), (46, 36)], GREEN), ('poly', [(46, 44), (64, 44), (64, 50), (46, 50)], GREEN),
    ('poly', [(32, 90), (112, 90), (112, 102), (32, 102)], SAGE),
    ('ellipse', (8, 108, 10, 10), IVORY), ('ellipse', (26, 108, 10, 10), IVORY), ('ellipse', (8, 90, 10, 10), IVORY),
])
# A sword: creatures defeated.
icon('StatsCreature', [
    ('poly', [(98, 14), (114, 14), (114, 30), (56, 88), (40, 72)], IVORY),
    ('poly', [(98, 14), (114, 14), (114, 30), (77, 67), (73, 63)], SAGE),
    ('poly', [(28, 66), (36, 58), (70, 92), (62, 100)], BRASS),
    ('poly', [(40, 84), (48, 92), (28, 112), (20, 104)], DARK_BRASS),
    ('ellipse', (12, 106, 14, 14), BRASS),
])
# A horned crown over a shield: bosses.
icon('StatsBoss', [
    ('poly', [(14, 22), (34, 44), (30, 58), (18, 48)], IVORY),
    ('poly', [(114, 22), (94, 44), (98, 58), (110, 48)], IVORY),
    ('poly', [(30, 40), (98, 40), (96, 82), (64, 112), (32, 82)], GREEN),
    ('poly', [(38, 48), (90, 48), (88, 78), (64, 102), (40, 78)], RUST),
    ('poly', [(36, 16), (50, 30), (64, 12), (78, 30), (92, 16), (90, 44), (38, 44)], BRASS),
    ('ellipse', (48, 62, 12, 12), IVORY), ('ellipse', (68, 62, 12, 12), IVORY),
])
# A gravestone with a laurel: times fallen.
icon('StatsFallen', [
    ('poly', [(36, 40), (44, 22), (64, 14), (84, 22), (92, 40), (92, 104), (36, 104)], SAGE),
    ('poly', [(44, 44), (50, 28), (64, 22), (78, 28), (84, 44), (84, 96), (44, 96)], GREEN),
    ('poly', [(52, 44), (76, 44), (76, 52), (52, 52)], IVORY), ('poly', [(52, 60), (76, 60), (76, 66), (52, 66)], IVORY),
    ('poly', [(52, 74), (70, 74), (70, 80), (52, 80)], IVORY),
    ('poly', [(18, 102), (110, 102), (114, 114), (14, 114)], BRASS),
])
# A globe with a path: worlds explored.
icon('StatsWorld', [
    ('ellipse', (16, 16, 96, 96), BRASS),
    ('ellipse', (23, 23, 82, 82), SAGE),
    ('poly', [(34, 40), (56, 30), (62, 46), (48, 60), (54, 76), (40, 84), (30, 64)], GREEN),
    ('poly', [(70, 58), (90, 50), (98, 68), (88, 92), (72, 86)], GREEN),
    ('ellipse', (58, 12, 12, 12), IVORY),
    ('poly', [(63, 22), (65, 22), (65, 40), (63, 40)], IVORY),
])
# An hourglass: time in Eternia.
icon('StatsTime', [
    ('poly', [(24, 12), (104, 12), (104, 24), (24, 24)], BRASS),
    ('poly', [(24, 104), (104, 104), (104, 116), (24, 116)], BRASS),
    ('poly', [(34, 24), (94, 24), (94, 34), (70, 62), (70, 66), (94, 94), (94, 104), (34, 104), (34, 94), (58, 66), (58, 62), (34, 34)], IVORY),
    ('poly', [(44, 34), (84, 34), (64, 56)], SAGE),
    ('poly', [(64, 76), (88, 100), (40, 100)], SAGE),
    ('poly', [(62, 58), (66, 58), (66, 92), (62, 92)], SAGE),
])
# A trophy: server firsts.
icon('StatsFirst', [
    ('ellipse', (10, 24, 30, 36), BRASS), ('ellipse', (17, 31, 16, 22), GREEN),
    ('ellipse', (88, 24, 30, 36), BRASS), ('ellipse', (95, 31, 16, 22), GREEN),
    ('poly', [(28, 14), (100, 14), (96, 52), (82, 72), (46, 72), (32, 52)], BRASS),
    ('poly', [(56, 72), (72, 72), (74, 92), (54, 92)], DARK_BRASS),
    ('poly', [(38, 92), (90, 92), (94, 114), (34, 114)], SAGE),
    ('poly', [(64, 24), (70, 38), (84, 39), (73, 48), (77, 62), (64, 54), (51, 62), (55, 48), (44, 39), (58, 38)], IVORY),
])
# An open journal with a quill: the page's own badge.
icon('StatsJourney', [
    ('poly', [(12, 30), (62, 24), (64, 110), (12, 104)], IVORY),
    ('poly', [(116, 30), (66, 24), (64, 110), (116, 104)], IVORY),
    ('poly', [(60, 24), (68, 24), (68, 112), (60, 112)], BRASS),
    ('poly', [(22, 44), (52, 40), (52, 46), (22, 50)], GREEN), ('poly', [(22, 60), (52, 56), (52, 62), (22, 66)], GREEN),
    ('poly', [(22, 76), (44, 73), (44, 79), (22, 82)], GREEN),
    ('poly', [(110, 8), (118, 14), (84, 82), (78, 78)], SAGE),
    ('poly', [(78, 78), (84, 82), (76, 92)], GREEN),
])
print('Generated stats pictograms.')
