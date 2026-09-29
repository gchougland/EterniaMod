"""Generate the shared vector-source Citadel pictograms and native PNG renditions."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'src/main/resources/Common/UI/Custom/EterniaMod/Icons'
OUT.mkdir(parents=True, exist_ok=True)
BRASS, SAGE, IVORY, GREEN = '#d8b66b', '#85c5b3', '#f0e8d5', '#233c3e'

def icon(name, parts):
    image = Image.new('RGBA', (512, 512)); draw = ImageDraw.Draw(image)
    svg = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128">']
    for kind, points, fill in parts:
        if kind == 'poly':
            draw.polygon([(x*4,y*4) for x,y in points], fill=fill)
            svg.append('<polygon points="'+' '.join(f'{x},{y}' for x,y in points)+'" fill="'+fill+'"/>')
        else:
            x,y,w,h=points;draw.ellipse((x*4,y*4,(x+w)*4,(y+h)*4),fill=fill)
            svg.append(f'<ellipse cx="{x+w/2}" cy="{y+h/2}" rx="{w/2}" ry="{h/2}" fill="{fill}"/>')
    svg.append('</svg>')
    (OUT/(name+'.svg')).write_text('\n'.join(svg)+'\n',encoding='utf-8')
    image.resize((128,128),Image.Resampling.LANCZOS).save(OUT/(name+'.png'))

icon('housing', [('poly',[(12,64),(64,18),(116,64),(106,74),(64,37),(22,74)],BRASS),('poly',[(29,69),(64,40),(99,69),(99,110),(29,110)],SAGE),('poly',[(54,76),(75,76),(75,110),(54,110)],GREEN),('poly',[(37,72),(47,72),(47,84),(37,84)],IVORY)])
icon('guild', [('poly',[(22,24),(64,12),(106,24),(104,76),(90,98),(64,118),(38,98),(24,76)],BRASS),('poly',[(32,32),(64,22),(96,32),(94,72),(82,91),(64,106),(46,91),(34,72)],GREEN),('poly',[(48,42),(48,79),(40,79),(40,88),(88,88),(88,79),(80,79),(80,42),(71,42),(71,51),(57,51),(57,42)],SAGE)])
icon('mail', [('poly',[(16,35),(112,35),(112,101),(16,101)],IVORY),('poly',[(16,35),(64,73),(112,35)],BRASS),('poly',[(16,101),(51,69),(64,80),(77,69),(112,101)],SAGE),('ellipse',(54,64,20,20),BRASS)])
icon('season', [('poly',[(29,16),(99,16),(99,113),(64,93),(29,113)],SAGE),('poly',[(64,31),(72,50),(93,51),(77,66),(82,86),(64,75),(46,86),(51,66),(35,51),(56,50)],BRASS)])
icon('collection', [('poly',[(64,14),(108,47),(91,102),(37,102),(20,47)],BRASS),('poly',[(64,27),(91,49),(81,88),(47,88),(37,49)],GREEN),('poly',[(64,34),(83,52),(74,80),(54,80),(45,52)],SAGE)])
icon('worlds', [('ellipse',(18,18,92,92),BRASS),('ellipse',(25,25,78,78),GREEN),('poly',[(64,30),(74,54),(98,64),(74,74),(64,98),(54,74),(30,64),(54,54)],IVORY),('poly',[(64,30),(64,64),(98,64),(74,54)],SAGE)])
icon('crown', [('poly',[(20,38),(44,55),(64,24),(84,55),(108,38),(95,93),(33,93)],BRASS),('poly',[(35,101),(93,101),(93,111),(35,111)],SAGE),('ellipse',(57,64,14,14),GREEN)])
print('Generated 7 Citadel SVG sources and 7 native PNG icons.')
