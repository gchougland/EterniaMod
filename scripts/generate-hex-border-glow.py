"""Hex plot border glow, one colour per relation to the viewer. Requires Pillow.

Same look as generate-border-motes.py (a ground rune line with one cut gem per block and a few rising lights),
recoloured with the shared hex palette (HexLandView.suggestedColor) so the glow matches the world map overlay and
the claim map. Claimable hexagons get a cheaper faint dash every second block and no lights.
"""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFilter

root = Path(__file__).resolve().parents[1] / 'src/main/resources'
textures = root / 'Common/Particles/Eternia'
particles = root / 'Server/Particles/Eternia'

# Keep in step with HexLandView.suggestedColor and HexBorderRelation.
RELATIONS = {
    'Mine': (0x2E, 0xC4, 0xB6),        # teal
    'Guild': (0xE9, 0xB9, 0x49),       # gold
    'Neighbor': (0x8F, 0xB8, 0xDE),    # soft blue
    'Rival': (0x9B, 0x7E, 0xD9),       # violet
}
CLAIMABLE = (0x6C, 0xC5, 0x6C)         # claimable green


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def hexcolor(c):
    return '#%02x%02x%02x' % c


def span(lo, hi=None):
    return {'Min': lo, 'Max': lo if hi is None else hi}


def rune(color, name):
    size = 256
    img = Image.new('RGBA', (size, size))
    d = ImageDraw.Draw(img)
    light, deep, core = mix(color, (255, 255, 255), .35), mix(color, (16, 24, 32), .45), mix(color, (255, 255, 255), .8)
    d.line([(0, 128), (256, 128)], fill=color + (230,), width=7)
    d.polygon([(128, 97), (159, 128), (128, 159), (97, 128)], fill=light + (255,))
    d.polygon([(128, 107), (149, 128), (128, 149), (107, 128)], fill=deep + (255,))
    d.polygon([(128, 116), (140, 128), (128, 140), (116, 128)], fill=core + (255,))
    halo = img.filter(ImageFilter.GaussianBlur(5))
    img = Image.alpha_composite(halo, img)
    img.save(textures / name)


def dash(color, name):
    """A short soft dash centred in a one block frame, so every second block reads as a dotted outline."""
    size = 256
    img = Image.new('RGBA', (size, size))
    d = ImageDraw.Draw(img)
    d.line([(72, 128), (184, 128)], fill=color + (210,), width=6)
    d.ellipse([(118, 118), (138, 138)], fill=mix(color, (255, 255, 255), .55) + (235,))
    halo = img.filter(ImageFilter.GaussianBlur(4))
    img = Image.alpha_composite(halo, img)
    img.save(textures / name)


def ribbon(texture, opacity, life=1.35):
    return {
        'Shape': 'Cube', 'RenderMode': 'BlendLinear', 'ParticleRotationInfluence': 'None',
        'ParticleRotateWithSpawner': True, 'LinearFiltering': True, 'LightInfluence': 0,
        'LifeSpan': 1.2, 'MaxConcurrentParticles': 1, 'ParticleLifeSpan': span(life),
        'SpawnBurst': True, 'SpawnRate': span(1), 'TotalParticles': span(1),
        'InitialVelocity': {'Speed': span(0), 'Yaw': span(0), 'Pitch': span(0)},
        'Particle': {
            'Texture': 'Particles/Eternia/' + texture, 'FrameSize': {'Width': 256, 'Height': 256},
            'ScaleRatioConstraint': 'OneToOne', 'SoftParticles': 'Disable',
            'InitialAnimationFrame': {'Scale': {'X': span(.125)}, 'Rotation': {'X': span(90)}, 'Opacity': 1, 'Color': '#ffffff'},
            'Animation': {'0': {'Opacity': opacity * .85}, '35': {'Opacity': opacity}, '80': {'Opacity': opacity * .85}, '100': {'Opacity': 0}},
        },
    }


def motes(color, opacity):
    start, glow, end = mix(color, (0, 0, 0), .1), mix(color, (255, 255, 255), .3), mix(color, (255, 255, 255), .6)
    return {
        'Shape': 'Cube', 'RenderMode': 'BlendLinear',
        'ParticleRotationInfluence': 'Billboard', 'ParticleRotateWithSpawner': False,
        'LinearFiltering': True, 'LightInfluence': 0,
        'EmitOffset': {'X': span(-.36, .36), 'Y': span(0, .025), 'Z': span(-.018, .018)},
        'LifeSpan': 1.2, 'MaxConcurrentParticles': 6,
        'ParticleLifeSpan': span(1.6, 2.4), 'SpawnRate': span(4, 5),
        'SpawnBurst': False, 'TotalParticles': span(6),
        'InitialVelocity': {'Yaw': span(0, 360), 'Pitch': span(90), 'Speed': span(.10, .18)},
        'Particle': {
            'Texture': 'Particles/Eternia/PlotMote.png', 'FrameSize': {'Width': 64, 'Height': 64},
            'ScaleRatioConstraint': 'OneToOne', 'SoftParticles': 'Enable',
            'InitialAnimationFrame': {
                'FrameIndex': span(0), 'Scale': {'X': span(.10, .12)},
                'Opacity': 1, 'Color': '#ffffff', 'Rotation': {'Z': span(-30, 30)},
            },
            'Animation': {
                '0': {'Opacity': 0, 'Color': hexcolor(start)},
                '3': {'Opacity': opacity, 'Color': hexcolor(glow)},
                '60': {'Opacity': opacity * .8, 'Color': hexcolor(glow)},
                '100': {'Opacity': 0, 'Color': hexcolor(end), 'Rotation': {'Z': span(80, 120)}},
            },
        },
    }


def write(name, data):
    (particles / name).write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8', newline='\n')


for relation, color in RELATIONS.items():
    texture = 'HexRune' + relation + '.png'
    rune(color, texture)
    for suffix, opacity in [('', .95), ('_Soft', .55), ('_Faint', .2)]:
        name = 'Eternia_Hex_Border_' + relation + suffix
        write(name + '_Rune.particlespawner', ribbon(texture, opacity))
        write(name + '_Spawner.particlespawner', motes(color, opacity))
        write(name + '.particlesystem', {
            'Spawners': [{'SpawnerId': name + '_Rune'}, {'SpawnerId': name + '_Spawner'}],
            'LifeSpan': 3.7, 'CullDistance': 8, 'BoundingRadius': 1, 'IsImportant': False})

dash(CLAIMABLE, 'HexRuneClaimable.png')
name = 'Eternia_Hex_Border_Claimable'
write(name + '_Rune.particlespawner', ribbon('HexRuneClaimable.png', .45))
write(name + '.particlesystem', {
    'Spawners': [{'SpawnerId': name + '_Rune'}],
    'LifeSpan': 3.7, 'CullDistance': 14, 'BoundingRadius': 1, 'IsImportant': False})
