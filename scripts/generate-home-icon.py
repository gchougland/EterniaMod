"""Citadel home button: simple roof, doorway and inset border. Requires Pillow."""
from pathlib import Path
from PIL import Image, ImageDraw

output = Path(__file__).resolve().parents[1] / 'src/main/resources/Common/UI/Custom/EterniaMod/Theme/Surfaces'
for suffix, fill, ink in [('', '#192d31', '#d8b66b'), ('Hover', '#2c4947', '#f0e8d5'), ('Pressed', '#101e22', '#85c5b3')]:
    image = Image.new('RGBA', (176, 176))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((2, 2, 173, 173), radius=18, fill=fill, outline=ink, width=4)
    draw.line([(37, 80), (88, 37), (139, 80)], fill=ink, width=10, joint='curve')
    draw.line([(51, 80), (51, 132), (74, 132), (74, 101), (102, 101), (102, 132), (125, 132), (125, 80)], fill=ink, width=8)
    image.resize((44, 44), Image.Resampling.LANCZOS).save(output / f'Home{suffix}.png')
