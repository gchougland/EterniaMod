"""Catalog previews from prefab renders: python scripts/catalog-previews.py <render.png> <kind> <id>

<render.png> is a prefab_render picture of one view with a transparent background (background "#00000000"). The render
draws the origin's axis lines; they are one pixel wide and are removed by opening the alpha channel. Writes, next to the
other catalog art (docs/implementation/content-authoring.md, "Item icons and previews"):
  src/main/resources/Common/UI/Custom/EterniaMod/Catalog/<kind>/<id>/icon.png        256 x 256, transparent
  src/main/resources/Common/UI/Custom/EterniaMod/Catalog/<kind>/<id>/screenshot.png  1280 x 800, on the Citadel slate
"""
import sys
from pathlib import Path
from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
SLATE = (24, 31, 38, 255)


def clean(image: Image.Image) -> Image.Image:
    image = image.convert("RGBA")
    alpha = image.getchannel("A").point(lambda a: 255 if a > 8 else 0)
    # An opening (erode, then dilate) drops anything thinner than three pixels: the axis lines.
    opened = alpha.filter(ImageFilter.MinFilter(3)).filter(ImageFilter.MaxFilter(3))
    keep = Image.composite(image.getchannel("A"), Image.new("L", image.size, 0), opened)
    image.putalpha(keep)
    box = keep.getbbox()
    if box is None:
        raise SystemExit("The render is empty")
    return image.crop(box)


def fit(image: Image.Image, width: int, height: int, margin: float) -> Image.Image:
    scale = min(width * (1 - 2 * margin) / image.width, height * (1 - 2 * margin) / image.height)
    return image.resize((max(1, round(image.width * scale)), max(1, round(image.height * scale))), Image.LANCZOS)


def main() -> None:
    render, kind, item = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
    subject = clean(Image.open(render))
    out = ROOT / "src/main/resources/Common/UI/Custom/EterniaMod/Catalog" / kind / item
    out.mkdir(parents=True, exist_ok=True)
    icon = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    small = fit(subject, 256, 256, 0.06)
    icon.alpha_composite(small, ((256 - small.width) // 2, (256 - small.height) // 2))
    icon.save(out / "icon.png")
    shot = Image.new("RGBA", (1280, 800), SLATE)
    large = fit(subject, 1280, 800, 0.08)
    shot.alpha_composite(large, ((1280 - large.width) // 2, (800 - large.height) // 2))
    shot.convert("RGB").save(out / "screenshot.png")
    print(out)


if __name__ == "__main__":
    main()
