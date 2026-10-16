"""Ground level rune ribbon with restrained rising lights. Requires Pillow."""
from pathlib import Path
import json
import math
from PIL import Image, ImageDraw, ImageFilter

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

# A narrow continuous line with one cut gemstone per block. No rectangular texture frame.
size=256
rune=Image.new('RGBA',(size,size));draw=ImageDraw.Draw(rune)
draw.line([(0,128),(256,128)],fill=(114,229,204,230),width=7)
draw.polygon([(128,97),(159,128),(128,159),(97,128)],fill=(224,196,113,255))
draw.polygon([(128,107),(149,128),(128,149),(107,128)],fill=(70,155,141,255))
draw.polygon([(128,116),(140,128),(128,140),(116,128)],fill=(217,255,237,255))
halo=rune.filter(ImageFilter.GaussianBlur(5));rune=Image.alpha_composite(halo,rune)
rune.save(root/'Common/Particles/Eternia/PlotRune.png')

for suffix, opacity in [('', .95), ('_Soft', .55), ('_Faint', .2)]:
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
                'FrameIndex': span(0), 'Scale': {'X': span(.10, .12)},
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
    ribbon={
        'Shape':'Cube','RenderMode':'BlendLinear','ParticleRotationInfluence':'None',
        'ParticleRotateWithSpawner':True,'LinearFiltering':True,'LightInfluence':0,
        'LifeSpan':1.2,'MaxConcurrentParticles':1,'ParticleLifeSpan':span(1.35),
        'SpawnBurst':True,'SpawnRate':span(1),'TotalParticles':span(1),
        'InitialVelocity':{'Speed':span(0),'Yaw':span(0),'Pitch':span(0)},
        'Particle':{'Texture':'Particles/Eternia/PlotRune.png','FrameSize':{'Width':256,'Height':256},
            'ScaleRatioConstraint':'OneToOne','SoftParticles':'Disable',
            'InitialAnimationFrame':{'Scale':{'X':span(.125)},'Rotation':{'X':span(90)},'Opacity':1,'Color':'#ffffff'},
            'Animation':{'0':{'Opacity':opacity*.85},'35':{'Opacity':opacity},'80':{'Opacity':opacity*.85},'100':{'Opacity':0}}}
    }
    system = {'Spawners': [{'SpawnerId': name + '_Rune'}, {'SpawnerId': name + '_Spawner'}], 'LifeSpan': 3.7,
              'CullDistance': 8, 'BoundingRadius': 1, 'IsImportant': False}
    folder = root / 'Server/Particles/Eternia'
    (folder / (name + '_Rune.particlespawner')).write_text(json.dumps(ribbon, indent=2) + '\n')
    (folder / (name + '_Spawner.particlespawner')).write_text(json.dumps(spawner, indent=2) + '\n')
    (folder / (name + '.particlesystem')).write_text(json.dumps(system, indent=2) + '\n')
