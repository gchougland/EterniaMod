"""Reparent Prowl's untouched shapes onto Hytale's player bone hierarchy.

Run with Python 3: scripts/rig-prowl.py [--check] [--assets path/to/Assets.zip].
Only the separate Eternia rig and its atlas-safe passive clips are generated.
"""
import argparse
import copy
import hashlib
import json
import math
import os
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
COMMON = ROOT / 'src/main/resources/Common'
FOLDER = COMMON / 'NPC/Eternia/Prowl'

def vec(value):
    return tuple(value.get(k, 0) for k in 'xyz')

def quat(value):
    q = tuple(value.get(k, 1 if k == 'w' else 0) for k in 'xyzw')
    norm = math.sqrt(sum(v * v for v in q))
    return tuple(v / norm for v in q)

def mul(a, b):
    x,y,z,w=a; X,Y,Z,W=b
    return (w*X+x*W+y*Z-z*Y,w*Y-x*Z+y*W+z*X,w*Z+x*Y-y*X+z*W,w*W-x*X-y*Y-z*Z)

def inverse(q): return (-q[0],-q[1],-q[2],q[3])
def rotate(q,p): return mul(mul(q,(*p,0)),inverse(q))[:3]
def add(a,b): return tuple(x+y for x,y in zip(a,b))
def sub(a,b): return tuple(x-y for x,y in zip(a,b))
IDENTITY=((0,0,0),(0,0,0,1))

def index(model):
    """Frames are shape centers: child transforms inherit the rotated shape offset.

    This also applies to shape.type=none. Omitting a shapeless parent's offset
    silently shortens Prowl's legs and torso when its meshes are reparented.
    """
    result={}
    def visit(node,parent,world):
        p,q=world; local=quat(node.get('orientation',{}))
        orientation=mul(q,local)
        position=add(p,rotate(q,vec(node.get('position',{}))))
        absolute=(add(position,rotate(orientation,vec(node.get('shape',{}).get('offset',{})))),orientation)
        result[node['id']]=(node,parent,absolute)
        for child in node.get('children',[]): visit(child,node,absolute)
    for node in model['nodes']: visit(node,None,IDENTITY)
    return result

def localize(node,world,parent):
    p,q=world; P,Q=parent
    orientation=mul(inverse(Q),q)
    position=sub(rotate(inverse(Q),sub(p,P)),rotate(orientation,vec(node.get('shape',{}).get('offset',{}))))
    node['position']=dict(zip('xyz',position))
    node['orientation']=dict(zip('xyzw',orientation))

def pivot(entry):
    node,_,(p,q)=entry
    return sub(p,rotate(q,vec(node.get('shape',{}).get('offset',{}))))

def bounds(model):
    points=[]
    for node,_,(p,q) in index(model).values():
        shape=node.get('shape',{})
        if shape.get('type','none')=='none' or not shape.get('visible',True): continue
        size=shape.get('settings',{}).get('size',{});stretch=shape.get('stretch',{})
        for x in (-1,1):
            for y in (-1,1):
                for z in (-1,1): points.append(add(p,rotate(q,tuple(s*size.get(k,0)*stretch.get(k,1)/2 for s,k in zip((x,y,z),'xyz')))))
    return tuple(tuple(operation(p[i] for p in points) for i in range(3)) for operation in (min,max))

def sample(keys,time,orientation=False):
    keys=sorted(keys,key=lambda key:key['time'])
    read=quat if orientation else vec
    if time<=keys[0]['time']: return read(keys[0]['delta'])
    if time>=keys[-1]['time']: return read(keys[-1]['delta'])
    for left,right in zip(keys,keys[1:]):
        if left['time']<=time<=right['time']:
            a=read(left['delta']);b=read(right['delta']);t=(time-left['time'])/(right['time']-left['time'])
            if left.get('interpolationType')=='smooth': t=t*t*(3-2*t)
            if left.get('interpolationType')=='step': t=0
            if orientation:
                dot=sum(x*y for x,y in zip(a,b))
                if dot<0: b=tuple(-v for v in b);dot=-dot
                if dot<0.9995:
                    theta=math.acos(min(1,dot));sine=math.sin(theta)
                    return tuple((math.sin((1-t)*theta)*x+math.sin(t*theta)*y)/sine for x,y in zip(a,b))
            result=tuple(x+(y-x)*t for x,y in zip(a,b))
            if orientation:
                norm=math.sqrt(sum(v*v for v in result));result=tuple(v/norm for v in result)
            return result

def pose(model,animation,time):
    """Preview additive native tracks; a regression probe, not a client renderer."""
    output=copy.deepcopy(model)
    for node,_,_ in index(output).values():
        tracks=animation.get('nodeAnimations',{}).get(node['name'],{})
        if tracks.get('position'): node['position']=dict(zip('xyz',add(vec(node.get('position',{})),sample(tracks['position'],time))))
        if tracks.get('orientation'): node['orientation']=dict(zip('xyzw',mul(quat(node.get('orientation',{})),sample(tracks['orientation'],time,True))))
        if tracks.get('shapeStretch'):
            stretch=node['shape'].get('stretch',{});delta=sample(tracks['shapeStretch'],time)
            node['shape']['stretch']={k:stretch.get(k,1)*delta[i] for i,k in enumerate('xyz')}
    return output

def validate_poses(model,archive):
    """Check standing/locomotion proportions using the real installed clips."""
    by_name=lambda m:{n['name']:entry for entry in index(m).values() for n in [entry[0]]}
    reference=by_name(model);base=bounds(model);report={}
    distance=lambda a,b:math.sqrt(sum(v*v for v in sub(a,b)))
    for clip in ('Idle','Walk'):
        animation=json.loads(archive.read('Common/Characters/Animations/Default/'+clip+'.blockyanim'))
        limits=[];maximum_motion=0
        for time in range(0,animation['duration']+1,5):
            posed=pose(model,animation,time);nodes=by_name(posed);limits.append(bounds(posed))
            for side in ('L-','R-'):
                for upper,lower in (('Thigh','Calf'),('Calf','Foot')):
                    before=distance(pivot(reference[side+upper]),pivot(reference[side+lower]))
                    after=distance(pivot(nodes[side+upper]),pivot(nodes[side+lower]))
                    expected=distance(vec(nodes[side+lower][0]['position']),(0,0,0))
                    # A few Idle foot keys contain tiny corrective translations.
                    # Those authored offsets are allowed; inherited scale is not.
                    assert abs(expected-after)<1e-8,(clip,time,side,upper,expected,after)
                    assert after>before*0.95,(clip,time,side,upper,before,after)
                    maximum_motion=max(maximum_motion,abs(before-after))
            height=limits[-1][1][1]-limits[-1][0][1]
            assert height>(base[1][1]-base[0][1])*(0.9 if clip=='Idle' else 0.75),(clip,time,height)
        report[clip]={'sampleCount':len(limits),'minimumHeight':round(min(high[1]-low[1] for low,high in limits),6),'maximumHeight':round(max(high[1]-low[1] for low,high in limits),6),'maximumAuthoredLegAdjustment':round(maximum_motion,6),'legSegmentTransformErrorLimit':1e-8}
    eye_animation=json.loads(archive.read('Common/Characters/Animations/Flavor/Eye_Look_Left.blockyanim'))
    moved=0
    for time in range(0,eye_animation['duration']+1):
        nodes=by_name(pose(model,eye_animation,time))
        for side in ('L-','R-'): moved=max(moved,distance(nodes[side+'Eye'][2][0],reference[side+'Eye'][2][0]))
    assert moved>0.5,'Native eye targets did not move the actual eye meshes'
    report['eyeLookDisplacement']=round(moved,6)
    return report

def generate(archive):
    source=json.loads((FOLDER/'prowl_hytale.blockymodel').read_text())
    player=json.loads(archive.read('Common/Characters/Player.blockymodel'))
    original=index(source); native=index(player)
    names={node['name']:(node,parent,world) for node,parent,world in original.values()}
    limb={'Shoulder','Arm','Forearm','Hand','Attachment','Thigh','Calf','Foot'}
    def swap(name):
        return ('R-' if name.startswith('L-') else 'L-')+name[2:] if name[:2] in ('L-','R-') and name[2:] in limb else name
    bones={}; worlds={}; count=10000
    def build(node,parent=None):
        nonlocal count
        name=node['name']; count+=1
        result={'id':str(count),'name':name,'shape':copy.deepcopy(source['nodes'][0]['shape']),'children':[]}
        old=names.get(swap(name))
        if name=='Neck': old=names['neck--C1']
        if name=='Pelvis': old=names['Pelvis--C1']
        # Player orientations, Prowl's anatomical joint positions. This corrects the
        # original zero-height pelvis without imposing different body proportions.
        if old: p=pivot(old)
        else:
            p=add(worlds[parent][0],rotate(worlds[parent][1],vec(node.get('position',{})))) if parent else (0,0,0)
        q=native[node['id']][2][1]
        worlds[name]=(p,q);bones[name]=result
        localize(result,worlds[name],worlds[parent] if parent else IDENTITY)
        for child in node.get('children',[]): result['children'].append(build(child,name))
        return result
    output={'nodes':[build(node) for node in player['nodes']], 'format':source['format'],'lod':source['lod']}
    mapping={}
    def target(node):
        name=node['name']
        if name in ('Neck','Ear1') or name.startswith(('hair--','earRight--','earLeft--','eyeball')): return 'Head'
        if name=='neck--C1': return 'Neck'
        if name=='Mouth': return 'Mouth-Attachment'
        if name in ('L-Eye-Attachment','R-Eye-Attachment','L-Eyebrow-Attachment','R-Eyebrow-Attachment'): return name
        if name in ('L-Eyelid','R-Eyelid','L-Eyelid-Bot','R-Eyelid-Bot'):
            # Original eyelid labels are reversed relative to the player hierarchy.
            return ('R-' if name.startswith('L-') else 'L-')+name[2:]
        if swap(name) in bones: return swap(name)
        parent=original[node['id']][1]
        return target(parent) if parent else 'Origin'
    for node,parent,world in original.values():
        if node.get('shape',{}).get('type','none')=='none': continue
        destination=target(node); shape=copy.deepcopy(node);shape.pop('children',None)
        shape['name']='ProwlMesh_'+node['id']
        if node['name']=='Mouth': shape['name']='Mouth'
        for facial in ('Eye','Eyebrow'):
            if node['name'] in ('L-'+facial+'-Attachment','R-'+facial+'-Attachment'): shape['name']=node['name'].removesuffix('-Attachment')
        if 'Eyelid' in destination:
            # Shape-stretch animation acts on the named bone itself, not children.
            bone=bones[destination]; bone['id']=node['id']; bone['shape']=copy.deepcopy(node['shape'])
            localize(bone,world,worlds['Head']);worlds[destination]=world
        else:
            localize(shape,world,worlds[destination]);bones[destination]['children'].append(shape)
        mapping[node['id']]=destination
    rebuilt=index(output); maximum=0
    for ident,destination in mapping.items():
        before=original[ident];after=rebuilt[ident]
        assert before[0]['shape']==after[0]['shape'], 'Shape/UV changed: '+ident
        # A rigid affine transform agrees for all vertices if its origin and basis
        # agree. Also check all eight corners of each original stretched shape.
        shape=before[0]['shape']; size=shape.get('settings',{}).get('size',{})
        stretch=shape.get('stretch',{})
        points=[(0,0,0),(1,0,0),(0,1,0),(0,0,1)]
        for x in (-1,1):
            for y in (-1,1):
                for z in (-1,1): points.append(tuple(s*size.get(k,0)*stretch.get(k,1)/2 for s,k in zip((x,y,z),'xyz')))
        for point in points:
            a=add(before[2][0],rotate(before[2][1],point)); b=add(after[2][0],rotate(after[2][1],point))
            maximum=max(maximum,max(abs(v-w) for v,w in zip(a,b)))
    assert maximum<1e-9, maximum
    assert len([n for n,_,_ in rebuilt.values() if n.get('shape',{}).get('type','none')!='none'])==len(mapping)
    # Stable coordinates make it impossible for an equally wrong source/output
    # evaluator to silently bless the previous half-height pelvis again.
    assert abs(worlds['Pelvis'][0][1]-50)<1e-9
    assert abs(worlds['Head'][0][1]-88.5)<1e-9
    assert abs(bounds(output)[1][1]-122)<1e-9
    posed_proof=validate_poses(output,archive)
    files={FOLDER/'Prowl_PlayerRig.blockymodel':output}
    asset=json.loads((ROOT/'src/main/resources/Server/Models/Eternia/Eternia_Prowl.json').read_text())
    native_model=json.loads(archive.read('Server/Models/Human/Player.json'))
    passive=copy.deepcopy(native_model['AnimationSets']['IdlePassive'])
    for entry in passive['Animations']:
        animation=json.loads(archive.read('Common/'+entry['Animation']))
        if not any('Eyelid' in n for n in animation.get('nodeAnimations',{})): continue
        # Native eyelids use seven/five-unit quads at 0.1 bind stretch. Preserve
        # Prowl's atlas and retarget the *visible* height change, not raw size.
        for name,tracks in animation['nodeAnimations'].items():
            if 'Eyelid' not in name: continue
            shape=bones[name]['shape'];height=shape['settings']['size'].get('y',1)*shape.get('stretch',{}).get('y',1)
            native_shape=next(n['shape'] for n,_,_ in native.values() if n['name']==name)
            native_height=native_shape['settings']['size'].get('y',1)*native_shape.get('stretch',{}).get('y',1)
            for key in tracks.get('shapeStretch',[]): key['delta']['y']=1+(key['delta']['y']-1)*native_height/height
        filename=Path(entry['Animation']).name
        files[FOLDER/'Animations'/filename]=animation
        entry['Animation']='NPC/Eternia/Prowl/Animations/'+filename
    asset['Model']='NPC/Eternia/Prowl/Prowl_PlayerRig.blockymodel'
    asset['RandomAttachmentSets']={};asset['MinScale']=asset['MaxScale']=1
    asset['Camera']={'Pitch':{'TargetNodes':['Head'],'AngleRange':{'Min':-35,'Max':30}},'Yaw':{'TargetNodes':['Head'],'AngleRange':{'Min':-50,'Max':50}}}
    asset.setdefault('AnimationSets',{})['IdlePassive']=passive
    files[ROOT/'src/main/resources/Server/Models/Eternia/Eternia_Prowl.json']=asset
    files[FOLDER/'Prowl_PlayerRig.proof.json']={'sourceSha256':hashlib.sha256((FOLDER/'prowl_hytale.blockymodel').read_bytes()).hexdigest(),'textureSha256':hashlib.sha256((FOLDER/'prowl_hytale.png').read_bytes()).hexdigest(),'shapeCount':len(mapping),'maximumWorldVertexError':maximum,'shapeParents':mapping,'quaternionConvention':'unit normalized; composition parent * child','childFrameConvention':'parent shape center, including rotated offset even for shapeless nodes','hierarchySource':'Common/Characters/Player.blockymodel','pivotPolicy':'native player orientations and parent hierarchy, Prowl anatomical joint positions','bindBounds':bounds(output),'posePreview':posed_proof}
    return files

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');parser.add_argument('--assets',type=Path,default=Path(os.environ.get('APPDATA',''))/'Hytale/install/release/package/game/latest/Assets.zip');args=parser.parse_args()
    with zipfile.ZipFile(args.assets) as archive: files=generate(archive)
    for path,value in files.items():
        data=json.dumps(value,indent=2,ensure_ascii=False)+'\n'
        if args.check: assert path.read_text(encoding='utf-8')==data,'Generated asset is stale: '+str(path)
        else: path.parent.mkdir(parents=True,exist_ok=True);path.write_text(data,encoding='utf-8')
    print('Prowl rig: all original shapes and UVs preserved; bind-world vertex error < 1e-9; '+str(len(files))+' generated files verified.')

if __name__=='__main__': main()
