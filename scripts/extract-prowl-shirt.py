"""Keep Prowl's cloth UVs, fit to player clothing anchors, and add body clearance.
Run with Python 3. Requires the legally installed Hytale Assets.zip.
"""
import copy
import json
import os
from pathlib import Path
from zipfile import ZipFile

repo = Path(__file__).resolve().parents[1]
common = repo / 'src/main/resources/Common'
assets = Path(os.environ['APPDATA']) / 'Hytale/install/release/package/game/latest/Assets.zip'
with ZipFile(assets) as archive:
    shirt = json.loads(archive.read('Common/Cosmetics/Undertops/Tshirt.blockymodel'))
    player = json.loads(archive.read('Common/Characters/Player.blockymodel'))
prowl = json.loads((common / 'NPC/Eternia/Prowl/Prowl_PlayerRig.blockymodel').read_text(encoding='utf-8'))

def nodes(items):
    for item in items:
        yield item
        yield from nodes(item.get('children', []))

source = {n['name']: n for n in nodes(prowl['nodes'])}
parts = {
    'BellyCostume': ('Belly', (26.6, 16.5, 18.6)),
    'ChestPart': ('ProwlMesh_7--C1', (28.6, 22.5, 20.6)),
    'L-ArmCostume': ('ProwlMesh_17--C1', (10.8, 12.8, 14.8)),
    'R-ArmCostume': ('ProwlMesh_10--C1', (10.8, 12.8, 14.8)),
}
for node in nodes(shirt['nodes']):
    if node['name'] not in parts:
        continue
    name, target = parts[node['name']]
    offset = node['shape']['offset']
    node['shape'] = copy.deepcopy(source[name]['shape'])
    node['shape']['offset'] = offset
    size = node['shape']['settings']['size']
    node['shape']['settings'] = {'size': size}
    node['shape']['stretch'] = {axis: extent / size[axis] for axis, extent in zip('xyz', target)}
    if node['name'] == 'ChestPart':
        uv = node['shape']['textureLayout']
        uv['front'], uv['back'] = uv['back'], uv['front']
    if node['name'].endswith('ArmCostume'):
        # Equipment uses the arm joint directly, unlike character creator clothing.
        side = 1 if node['name'].startswith('L-') else -1
        node['position'] = {'x': side - offset['x'], 'y': -4 - offset['y'], 'z': -offset['z']}
# Match the current player's shoulder hierarchy so sleeves inherit arm animation.
player_nodes = {n['name']: n for n in nodes(player['nodes'])}
chest = next(n for n in nodes(shirt['nodes']) if n['name'] == 'Chest')
for arm in list(chest['children']):
    if arm['name'] not in ('L-Arm', 'R-Arm'):
        continue
    shoulder = copy.deepcopy(player_nodes[arm['name'].replace('Arm', 'Shoulder')])
    shoulder['id'] = 'prowl_' + shoulder['name']
    shoulder['children'] = [arm]
    arm['position'] = {'x': 0, 'y': 0, 'z': 0}
    arm['orientation'] = {'w': 1, 'x': 0, 'y': 0, 'z': 0}
    chest['children'][chest['children'].index(arm)] = shoulder
out = common / 'Items/Cosmetics/Eternia/Prowl_Shirt.blockymodel'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(shirt, indent=2) + '\n', encoding='utf-8')
print(out)
