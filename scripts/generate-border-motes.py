"""Small geometric light particles; no tiled walls or animation atlas. Requires Pillow."""
from pathlib import Path
import json
import math
from PIL import Image

root = Path(__file__).resolve().parents[1] / 'src/main/resources'
texture = root / 'Common/Particles/Eternia/PlotMote.png'
texture.parent.mkdir(parents=True, exist_ok=True)
image = Image.new('RGBA', (64, 64))
for y in range(64):
    for x in range(64):
        u, v = (x - 31.5) / 31.5, (y - 31.5) / 31.5
        radius = math.hypot(u, v)
        diamond = max(0, 1 - (abs(u) + abs(v)) / .43) ** 1.3
        halo = .25 * max(0, 1 - radius) ** 3
        alpha = round(255 * min(1, diamond + halo))
        image.putpixel((x, y), (255, 255, 255, alpha) if alpha else (0, 0, 0, 0))
image.save(texture)

def span(lo, hi=None):
    return {'Min': lo, 'Max': lo if hi is None else hi}

for suffix, opacity in [('', .85), ('_Soft', .44), ('_Faint', .16)]:
    name = 'Eternia_Plot_Border_Motes' + suffix
    spawner = {
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
                'FrameIndex': span(0), 'Scale': {'X': span(.08, .12)},
                'Opacity': 1, 'Color': '#ffffff', 'Rotation': {'Z': span(-30, 30)}
            },
            'Animation': {
                '0': {'Opacity': 0, 'Color': '#6cc9b5'},
                '3': {'Opacity': opacity, 'Color': '#8ddfca'},
                '60': {'Opacity': opacity * .8, 'Color': '#d5c78d'},
                '100': {'Opacity': 0, 'Color': '#e6ba71', 'Rotation': {'Z': span(80, 120)}}
            }
        }
    }
    system = {'Spawners': [{'SpawnerId': name + '_Spawner'}], 'LifeSpan': 3.7,
              'CullDistance': 8, 'BoundingRadius': 1, 'IsImportant': False}
    folder = root / 'Server/Particles/Eternia'
    (folder / (name + '_Spawner.particlespawner')).write_text(json.dumps(spawner, indent=2) + '\n')
    (folder / (name + '.particlesystem')).write_text(json.dumps(system, indent=2) + '\n')
