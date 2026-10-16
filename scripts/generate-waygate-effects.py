"""Citadel portal effects: geometric lines, cut gems and a slowly turning compass.

Requires Pillow. Native particles use 32 texture pixels per world unit.
The public portal and guild waygate share these assets.
"""
from pathlib import Path
import json
import math
import os
import zipfile
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources'
ART = ROOT / 'Common/Particles/Eternia'
FX = ROOT / 'Server/Particles/Eternia/Waygate'
ART.mkdir(parents=True, exist_ok=True)
FX.mkdir(parents=True, exist_ok=True)
TEAL = (114, 229, 204, 255)
GOLD = (224, 196, 113, 255)
IVORY = (217, 255, 237, 255)

def span(lo, hi=None):
    return {'Min': lo, 'Max': lo if hi is None else hi}

def polygon(cx, cy, radius, sides=8, phase=-math.pi/2):
    return [(cx+radius*math.cos(phase+n*math.tau/sides), cy+radius*math.sin(phase+n*math.tau/sides)) for n in range(sides)]

def gem(draw, x, y, radius):
    draw.polygon(polygon(x,y,radius,4), fill=GOLD)
    draw.polygon(polygon(x,y,radius*.67,4), fill=(42,102,94,255))
    draw.polygon(polygon(x,y,radius*.34,4), fill=IVORY)

def save(image, name, glow=3):
    # Geometry supplies the silhouette. Only its narrow halo is blurred.
    image=Image.alpha_composite(image.filter(ImageFilter.GaussianBlur(glow)),image)
    image.save(ART/(name+'.png'))

frame=Image.new('RGBA',(512,512));d=ImageDraw.Draw(frame)
outline=[(106,458),(106,156),(174,86),(256,40),(338,86),(406,156),(406,458),(106,458)]
d.line(outline,fill=TEAL,width=5,joint='curve')
inner=[(122,442),(122,163),(184,100),(256,58),(328,100),(390,163),(390,442),(122,442)]
d.line(inner,fill=(114,229,204,150),width=2)
for x,y in [(106,340),(406,340),(106,156),(406,156),(256,40)]:gem(d,x,y,14)
for x in [142,370]:
    d.line([(x,458),(x,426)],fill=GOLD,width=4)
    d.line([(x-12,434),(x,422),(x+12,434)],fill=GOLD,width=3)
save(frame,'WaygateFrame')

veil=Image.new('RGBA',(512,512));d=ImageDraw.Draw(veil)
perimeter=[(130,438),(130,168),(188,108),(256,68),(324,108),(382,168),(382,438)]
colors=[(27,112,105,100),(42,151,133,86),(84,182,155,110),(28,121,116,90),(50,160,141,105),(30,120,109,90),(76,171,150,80)]
for n,point in enumerate(perimeter):d.polygon([point,perimeter[(n+1)%len(perimeter)],(256,272)],fill=colors[n])
veil.save(ART/'WaygateVeil.png')

compass=Image.new('RGBA',(256,256));d=ImageDraw.Draw(compass)
for radius,color,width in [(98,TEAL,2),(84,(224,196,113,160),1)]:
    pts=polygon(128,128,radius);d.line(pts+[pts[0]],fill=color,width=width)
for n in range(8):
    a=n*math.tau/8; x,y=128+98*math.cos(a),128+98*math.sin(a)
    gem(d,x,y,7 if n%2 else 10)
save(compass,'WaygateCompass',2)

seal=Image.new('RGBA',(256,256));d=ImageDraw.Draw(seal)
pts=polygon(128,128,108);d.line(pts+[pts[0]],fill=TEAL,width=3)
for n in range(8):
    a=n*math.tau/8;gem(d,128+108*math.cos(a),128+108*math.sin(a),8)
save(seal,'WaygateSeal',2)

def write(name, data, extension):
    (FX/(name+'.'+extension)).write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')

def layer(texture, size, scale_x, scale_y, opacity=1):
    return {
        'Shape':'Cube','RenderMode':'BlendLinear','ParticleRotationInfluence':'None',
        'ParticleRotateWithSpawner':True,'LinearFiltering':True,'LightInfluence':0,
        # A single long-lived surface cannot brighten from overlapping copies.
        'MaxConcurrentParticles':1,'ParticleLifeSpan':span(3600),
        'SpawnBurst':False,'SpawnRate':span(60),
        'InitialVelocity':{'Speed':span(0),'Yaw':span(0),'Pitch':span(0)},
        'Particle':{'Texture':'Particles/Eternia/'+texture+'.png',
            'FrameSize':{'Width':size,'Height':size},'ScaleRatioConstraint':'None','SoftParticles':'Disable',
            'InitialAnimationFrame':{'Scale':{'X':span(scale_x),'Y':span(scale_y)},'Opacity':opacity,'Color':'#ffffff'},
            'Animation':{'0':{'Opacity':opacity},'100':{'Opacity':opacity}}}}

compass_fx=layer('WaygateCompass',256,.20,.20,.85)
compass_fx['MaxConcurrentParticles']=1
compass_fx['ParticleLifeSpan']=span(12);compass_fx['SpawnRate']=span(20)
compass_fx['Particle']['InitialAnimationFrame']['Rotation']={'Z':span(0)}
compass_fx['Particle']['Animation']={'0':{'Rotation':{'Z':span(0)}},'100':{'Rotation':{'Z':span(360)}}}
write('Eternia_Waygate_Compass',compass_fx,'particlespawner')
write('Eternia_Waygate_Veil',layer('WaygateVeil',512,.20,.25),'particlespawner')
sparks={
    'Shape':'Cube','RenderMode':'BlendLinear','ParticleRotationInfluence':'Billboard',
    'ParticleRotateWithSpawner':True,'LinearFiltering':True,'LightInfluence':0,
    'EmitOffset':{'X':span(-.92,.92),'Y':span(-1.55,-1.35),'Z':span(-.10,.10)},
    'MaxConcurrentParticles':12,'ParticleLifeSpan':span(2.5,4),'SpawnRate':span(3),
    'InitialVelocity':{'Speed':span(.4,.6),'Pitch':span(90),'Yaw':span(0)},
    'Particle':{'Texture':'Particles/Eternia/PlotMote.png','FrameSize':{'Width':64,'Height':64},
        'ScaleRatioConstraint':'OneToOne','SoftParticles':'Enable',
        'InitialAnimationFrame':{'Scale':{'X':span(.08,.12)},'Opacity':1,'Color':'#e0c471'},
        'Animation':{'0':{'Opacity':0},'10':{'Opacity':.9},'70':{'Opacity':.9},'100':{'Opacity':0}}}}
write('Eternia_Waygate_Sparks',sparks,'particlespawner')
write('Eternia_Waygate',{'Spawners':[
    {'SpawnerId':'Eternia_Waygate_Veil'},
    {'SpawnerId':'Eternia_Waygate_Sparks'}],
    'CullDistance':64,'BoundingRadius':4,'IsImportant':True},'particlesystem')

# The outline and seal have no particle lifetime. The translucent center uses
# one particle because model quads are alpha-tested by the current client.
assets=Path(os.environ.get('HYTALE_ASSETS_SRC',Path.home()/'AppData/Roaming/Hytale/install/release/package/game/latest/Assets.zip'))
with zipfile.ZipFile(assets) as archive:
    import io
    model=json.loads(archive.read('Common/Blocks/Miscellaneous/Platform_Magic_Exit.blockymodel'))
    base=Image.open(io.BytesIO(archive.read('Common/Blocks/Miscellaneous/Platform_Magic_Blue2.png'))).convert('RGBA')
atlas=Image.new('RGBA',(1024,1024));atlas.paste(base,(0,0))
# Use crisp geometry here. A particle halo becomes a dark fringe when the
# block renderer alpha-tests its nearly transparent edge pixels.
atlas.paste(frame,(256,0));atlas.paste(seal,(256,512));atlas.paste(compass,(512,512))
def quad(node_id,name,size,stretch,position,uv,normal='+Z'):
    return {'id':node_id,'name':name,'position':dict(zip(('x','y','z'),position)),
        'orientation':{'x':0,'y':0,'z':0,'w':1},
        'shape':{'type':'quad','offset':{'x':0,'y':0,'z':0},'stretch':dict(zip(('x','y','z'),stretch)),
            'settings':{'size':{'x':size,'y':size},'normal':normal},'visible':True,'doubleSided':True,
            'shadingMode':'fullbright','unwrapMode':'custom','textureLayout':{'front':{
                'offset':dict(zip(('x','y'),uv)),'mirror':{'x':False,'y':False},'angle':0}}},'children':[]}
model['nodes'].append(quad('100','CitadelArch',512,(.20,.25,1),(0,64,0),(256,0)))
model['nodes'].append(quad('101','CitadelSeal',256,(.30,1,.30),(0,16.5,0),(256,512),'+Y'))
# The ring is a looping model surface, so completing a turn never retires it.
model['nodes'].append(quad('102','CitadelCompass',256,(.20,.20,1),(0,64,.8),(512,512)))
folder=ROOT/'Common/Blocks/Eternia';folder.mkdir(parents=True,exist_ok=True)
(folder/'Waygate.blockymodel').write_text(json.dumps(model,indent=2)+'\n',encoding='utf-8')
atlas.save(folder/'Waygate.png')

# Native block animations run at 60 frames per second. All five channels must
# exist, including empty ones: omitting them makes the client parser throw.
rotation=[{'time':n*180,'delta':{'x':0,'y':0,'z':math.sin(n*math.pi/4),'w':math.cos(n*math.pi/4)},'interpolationType':'linear'} for n in range(5)]
compass_animation={'position':[],'orientation':rotation,'shapeStretch':[],'shapeVisible':[],'shapeUvOffset':[]}
(folder/'Waygate_Idle.blockyanim').write_text(json.dumps({'formatVersion':1,'duration':720,'holdLastKeyframe':False,'nodeAnimations':{'CitadelCompass':compass_animation}},indent=2)+'\n',encoding='utf-8')
