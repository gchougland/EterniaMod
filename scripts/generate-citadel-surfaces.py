"""Deterministic geometric nine-slice surfaces; no painted textures or generated art.

Run with Pillow after downloading the licensed fonts in website/web/fonts.
The 12px native patch border preserves the fine rims and clipped corners at any size.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageColor, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'src/main/resources/Common/UI/Custom/EterniaMod/Theme/Surfaces'
OUT.mkdir(parents=True, exist_ok=True)

def surface(name, top, bottom, rim, highlight):
    size = 64
    im = Image.new('RGBA', (size, size))
    draw = ImageDraw.Draw(im)
    a, b = ImageColor.getrgb(top), ImageColor.getrgb(bottom)
    for y in range(size):
        draw.line((0,y,size,y), fill=tuple(round(a[i]+(b[i]-a[i])*y/(size-1)) for i in range(3)))
    mask = Image.new('L', im.size)
    ImageDraw.Draw(mask).polygon([(6,0),(57,0),(63,6),(63,57),(57,63),(6,63),(0,57),(0,6)], fill=255)
    im.putalpha(mask)
    draw.line([(6,0),(57,0),(63,6),(63,57),(57,63),(6,63),(0,57),(0,6),(6,0)], fill=rim, width=2)
    draw.line([(8,3),(55,3),(60,8)], fill=highlight, width=1)
    draw.line([(3,8),(3,55),(8,60)], fill=highlight, width=1)
    for x,y in [(7,7),(56,7),(7,56),(56,56)]:
        draw.polygon([(x,y-2),(x+2,y),(x,y+2),(x-2,y)], fill=rim)
    im.save(OUT / (name+'.png'))

surface('Primary', '#e0bf79', '#b89855', '#eed79e', '#f8e5b5')
surface('PrimaryHover', '#f0d394', '#cbae6d', '#fff0c8', '#fff4d9')
surface('PrimaryPressed', '#76ae9e', '#4e8679', '#a3d5c4', '#b2e2d1')
surface('Secondary', '#2c4849', '#1c3236', '#68847a', '#899b85')
surface('SecondaryHover', '#3c5a57', '#294644', '#bcac79', '#dac38c')
surface('SecondaryPressed', '#15272c', '#1e3739', '#85c5b3', '#a3d5c4')
surface('Disabled', '#203235', '#192a2e', '#405550', '#50665d')
surface('Panel', '#20363a', '#172b30', '#506b62', '#778978')
surface('Inset', '#112226', '#192e32', '#3c5953', '#526f61')
surface('Header', '#304947', '#20373b', '#a58e5d', '#d8b66b')

# A static wordmark is an ordinary image, not a pretend client font registration.
font = ImageFont.truetype(str(ROOT/'website/web/fonts/Cinzel.ttf'), 54)
im = Image.new('RGBA', (360,80))
d = ImageDraw.Draw(im)
d.text((180,36), 'ETERNIA', font=font, anchor='mm', fill='#d8b66b')
d.line((24,69,154,69), fill='#68847a', width=2)
d.line((206,69,336,69), fill='#68847a', width=2)
d.polygon([(180,63),(187,69),(180,75),(173,69)], fill='#85c5b3')
im.save(OUT/'EterniaWordmark.png')
print('Generated 10 nine-slice surfaces and the Cinzel wordmark.')
