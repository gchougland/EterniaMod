"""Generate the Corn Buddy pet model and texture, and its golden crown attachment.

Writes Common/NPC/Eternia/Pets/Corn_Buddy/Model.blockymodel and Texture.png, and
Attachments/Crown.blockymodel and Crown_Texture.png beside them.
Animations are authored separately (see docs/implementation/content-authoring.md).
Requires Python 3 with Pillow. Units: 64 per block, model faces +Z, feet at y 0.
"""
import json
import math
import os
from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "Common", "NPC", "Eternia", "Pets", "Corn_Buddy")
TEX = 128

# ---------------------------------------------------------------- colours

def hexrgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))

def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))

def shade(c, amount):
    """amount > 0 lightens toward warm cream, < 0 darkens toward a cool deep tone."""
    if amount >= 0:
        return mix(c, (255, 246, 196), amount)
    return mix(c, (70, 40, 60), -amount)

KERNEL = [hexrgb("#ffd02a"), hexrgb("#ffc21f"), hexrgb("#ffd93a")]
KERNEL_LIGHT = hexrgb("#ffec6a")
KERNEL_DARK = hexrgb("#f2a316")
CREASE = hexrgb("#d9821c")
HUSK = hexrgb("#5f9e38")
HUSK_LIGHT = hexrgb("#9acb52")
HUSK_DARK = hexrgb("#3c7430")
LEG = hexrgb("#4f8d33")
LEG_DARK = hexrgb("#356b2a")
FOOT = hexrgb("#447f2e")
EYE_WHITE = hexrgb("#f6f2e4")
EYE_SHADOW = hexrgb("#d9d3c2")
EYE_RIM = hexrgb("#3b2414")
PUPIL = hexrgb("#1f1a2c")
PUPIL_SHINE = hexrgb("#fbfaf2")
SILK = hexrgb("#ead27c")
SILK_DARK = hexrgb("#c8a24a")
GOLD = hexrgb("#f4bd35")
GOLD_LIGHT = hexrgb("#ffe98a")
GOLD_DARK = hexrgb("#b8701c")
RUBY = hexrgb("#d23a4e")
SAPPHIRE = hexrgb("#3d7fd4")

FACE_LIGHT = {"top": .14, "bottom": -.28, "front": 0, "back": -.06, "left": -.03, "right": -.03}

# ---------------------------------------------------------------- painters
# Each painter returns rows of RGBA tuples, row 0 at the top of the face.

def vertical(face, y, h):
    """Light from above on side faces: lighter at the top, darker toward the bottom."""
    base = FACE_LIGHT.get(face, 0)
    if face in ("top", "bottom"):
        return base
    t = y / max(1, h - 1)
    return base + .07 - .17 * t

def kernels(w, h, face, seed=0):
    rows = []
    for y in range(h):
        row = []
        for x in range(w):
            kx, ky = (x + seed) // 4, (y + (2 if ((x + seed) // 4) % 2 else 0)) // 4
            lx, ly = (x + seed) % 4, (y + (2 if ((x + seed) // 4) % 2 else 0)) % 4
            base = KERNEL[(kx * 7 + ky * 3 + seed) % 3]
            if lx == 3 or ly == 3:
                c = mix(CREASE, base, .35)
            elif lx == 0 and ly == 0:
                c = mix(base, KERNEL_LIGHT, .7)
            elif lx == 2 and ly == 2:
                c = mix(base, KERNEL_DARK, .6)
            elif lx == 0 or ly == 0:
                c = mix(base, KERNEL_LIGHT, .3)
            else:
                c = base
            # The cob is round: faces darken softly toward their side edges.
            edge = abs((x + .5) / w - .5) * 2
            row.append(shade(c, vertical(face, y, h) - .12 * edge * edge) + (255,))
        rows.append(row)
    return rows

def leg(w, h, face, color=LEG, dark=LEG_DARK):
    rows = []
    for y in range(h):
        t = y / max(1, h - 1)
        row = []
        for x in range(w):
            c = mix(color, dark, .55 * t)
            if face not in ("top", "bottom") and (x == 0 or x == w - 1):
                c = mix(c, dark, .25)
            row.append(shade(c, FACE_LIGHT.get(face, 0) + (.06 if face not in ("top", "bottom") else 0)) + (255,))
        rows.append(row)
    return rows

def flat(w, h, face, color):
    return [[shade(color, vertical(face, y, h)) + (255,) for x in range(w)] for y in range(h)]

def husk(w, h, notch, tip="flat"):
    """A leaf flap: green with lighter tips and soft vertical veins.
    notch: depth of a V cut into the top edge (front and back flaps).
    tip: 'point' narrows the top into a leaf tip (side flaps)."""
    rows = []
    for y in range(h):
        row = []
        t = y / max(1, h - 1)
        for x in range(w):
            u = x / max(1, w - 1)
            cut = False
            if notch:
                depth = notch * (1 - abs(u - .5) * 2)
                if y < depth:
                    cut = True
            if tip == "point":
                half = (w / 2) * min(1, (y + 1) / (h * .55))
                if abs(x + .5 - w / 2) > half:
                    cut = True
            if cut:
                row.append((0, 0, 0, 0))
                continue
            c = mix(HUSK_LIGHT, HUSK, min(1, t * 1.6))
            c = mix(c, HUSK_DARK, max(0, t - .55) * 1.1)
            vein = (x % 5 == 2)
            if vein:
                c = mix(c, HUSK_DARK, .22)
            row.append(c + (255,))
        rows.append(row)
    return rows

def husk_cup(w, h, face):
    """The husk wrapped under the cob: leafy green sides, a darker inside on top."""
    if face == "top":
        return flat(w, h, face, HUSK_DARK)
    rows = []
    for y in range(h):
        row = []
        t = y / max(1, h - 1)
        for x in range(w):
            c = mix(HUSK_LIGHT, HUSK, min(1, .35 + t))
            c = mix(c, HUSK_DARK, max(0, t - .5) * .9)
            if x % 5 == 2:
                c = mix(c, HUSK_DARK, .22)
            row.append(shade(c, FACE_LIGHT.get(face, 0)) + (255,))
        rows.append(row)
    return rows

def eye_socket(w, h, face):
    """Plain white eye set into the kernels: no dark outline, a soft shadow along the top."""
    if face != "front":
        return flat(w, h, face, KERNEL_DARK)
    rows = []
    for y in range(h):
        row = []
        for x in range(w):
            corner = (x in (0, w - 1)) and (y in (0, h - 1))
            if corner:
                row.append(KERNEL[0] + (255,))
            elif y == 0:
                row.append(EYE_SHADOW + (255,))
            elif x in (0, w - 1) or y == h - 1:
                row.append(mix(EYE_WHITE, EYE_SHADOW, .5) + (255,))
            else:
                row.append(EYE_WHITE + (255,))
        rows.append(row)
    return rows

def pupil(w, h):
    rows = []
    for y in range(h):
        row = []
        for x in range(w):
            corner = (x in (0, w - 1)) and (y in (0, h - 1))
            if corner:
                row.append((0, 0, 0, 0))
            elif (x, y) == (1, 1):
                row.append(PUPIL_SHINE + (255,))
            else:
                row.append(mix(PUPIL, (60, 50, 80), y / (h * 2)) + (255,))
        rows.append(row)
    return rows

def eyelid(w, h):
    """Kernel coloured lid with a closed, curved lash line near its lower edge."""
    rows = kernels(w, h, "front", 2)
    cx = (w - 1) / 2
    for x in range(w):
        d = abs(x - cx) / cx
        y = h - 2 - round(2 * d * d)
        rows[y][x] = EYE_RIM + (255,)
        for yy in range(y + 1, h):
            rows[yy][x] = mix(KERNEL[1], KERNEL_DARK, .5) + (255,)
    return rows

def happy(w, h):
    """A closed, smiling eye: an upward arc."""
    rows = [[(0, 0, 0, 0)] * w for _ in range(h)]
    cx = (w - 1) / 2
    for x in range(w):
        d = abs(x - cx) / cx
        y = round((h - 2) * d * d)
        for yy in (y, y + 1):
            if 0 <= yy < h:
                rows[yy][x] = EYE_RIM + (255,)
    return rows

def silk(w, h):
    rows = [[(0, 0, 0, 0)] * w for _ in range(h)]
    strands = [1, 3, 5, 7, 9, 10]
    for i, sx in enumerate(strands):
        top = [4, 0, 2, 1, 3, 5][i]
        for y in range(top, h):
            bend = round(math.sin((h - y) / h * 2.2 + i) * .8)
            x = min(w - 1, max(0, sx + bend))
            t = (y - top) / max(1, h - top)
            c = mix(SILK, SILK_DARK, t * .8)
            rows[y][x] = c + (255,)
    return rows

def gold(w, h, face):
    """Polished gold: bright along the top edge, warm brown shadow at the bottom."""
    if face == "top":
        return flat(w, h, face, mix(GOLD, GOLD_LIGHT, .5))
    if face == "bottom":
        return flat(w, h, face, GOLD_DARK)
    rows = []
    for y in range(h):
        t = y / max(1, h - 1)
        c = mix(GOLD_LIGHT, GOLD, min(1, t * 2)) if t < .5 else mix(GOLD, GOLD_DARK, (t - .5) * 1.6)
        rows.append([shade(c, FACE_LIGHT.get(face, 0)) + (255,) for x in range(w)])
    return rows

def gem(color):
    def paint(w, h, face):
        rows = []
        for y in range(h):
            row = []
            for x in range(w):
                c = mix(color, (255, 255, 240), .45) if (x, y) == (0, 0) and face == "front" else mix(color, (60, 20, 60), y / (h * 3))
                row.append(shade(c, FACE_LIGHT.get(face, 0)) + (255,))
            rows.append(row)
        return rows
    return paint

# ---------------------------------------------------------------- packing

class Atlas:
    def __init__(self, size):
        self.img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        self.size = size
        self.x = self.y = self.row = 0

    def place(self, pixels):
        h, w = len(pixels), len(pixels[0])
        if self.x + w > self.size:
            self.x, self.y, self.row = 0, self.y + self.row + 1, 0
        if self.y + h > self.size:
            raise SystemExit("texture atlas full")
        ox, oy = self.x, self.y
        for y, row in enumerate(pixels):
            for x, c in enumerate(row):
                self.img.putpixel((ox + x, oy + y), c)
        self.x += w + 1
        self.row = max(self.row, h)
        return ox, oy

ATLAS = Atlas(TEX)
NEXT_ID = [0]

def layout(offset):
    return {"offset": {"x": offset[0], "y": offset[1]}, "mirror": {"x": False, "y": False}, "angle": 0}

def quat(x=0, y=0, z=0):
    """Degrees about X, then Y, then Z (intrinsic), as the animation tools use."""
    cx, sx = math.cos(math.radians(x) / 2), math.sin(math.radians(x) / 2)
    cy, sy = math.cos(math.radians(y) / 2), math.sin(math.radians(y) / 2)
    cz, sz = math.cos(math.radians(z) / 2), math.sin(math.radians(z) / 2)
    qx = (sx, 0, 0, cx); qy = (0, sy, 0, cy); qz = (0, 0, sz, cz)
    def mul(a, b):
        ax, ay, az, aw = a; bx, by, bz, bw = b
        return (aw * bx + ax * bw + ay * bz - az * by,
                aw * by - ax * bz + ay * bw + az * bx,
                aw * bz + ax * by - ay * bx + az * bw,
                aw * bw - ax * bx - ay * by - az * bz)
    q = mul(mul(qx, qy), qz)
    return {"x": round(q[0], 5), "y": round(q[1], 5), "z": round(q[2], 5), "w": round(q[3], 5)}

def node(name, pos=(0, 0, 0), rot=(0, 0, 0), children=(), shape=None):
    """pos is relative to the parent node's pivot. The format places children relative to the
    parent's shape centre (pivot plus shape offset), so that offset is taken back out here."""
    NEXT_ID[0] += 1
    shape = shape or empty_shape()
    for child in children:
        for axis in "xyz":
            child["position"][axis] -= shape["offset"][axis]
    return {
        "id": str(NEXT_ID[0]),
        "name": name,
        "position": {"x": pos[0], "y": pos[1], "z": pos[2]},
        "orientation": quat(*rot),
        "shape": shape,
        "children": list(children),
    }

def empty_shape():
    return {"type": "none", "offset": {"x": 0, "y": 0, "z": 0}, "stretch": {"x": 1, "y": 1, "z": 1},
            "settings": {"isPiece": False}, "textureLayout": {}, "unwrapMode": "custom", "visible": True,
            "doubleSided": False, "shadingMode": "flat"}

def box(size, offset, painter, stretch=(1, 1, 1)):
    w, h, d = size
    dims = {"front": (w, h), "back": (w, h), "left": (d, h), "right": (d, h), "top": (w, d), "bottom": (w, d)}
    tl = {face: layout(ATLAS.place(painter(fw, fh, face))) for face, (fw, fh) in dims.items()}
    return {"type": "box", "offset": dict(zip("xyz", offset)), "stretch": dict(zip("xyz", stretch)),
            "settings": {"isPiece": False, "size": {"x": w, "y": h, "z": d}}, "textureLayout": tl,
            "unwrapMode": "custom", "visible": True, "doubleSided": False, "shadingMode": "flat"}

def quad(size, offset, pixels, normal="+Z", double=False, visible=True, stretch=(1, 1, 1)):
    return {"type": "quad", "offset": dict(zip("xyz", offset)), "stretch": dict(zip("xyz", stretch)),
            "settings": {"isPiece": False, "size": {"x": size[0], "y": size[1]}, "normal": normal},
            "textureLayout": {"front": layout(ATLAS.place(pixels))}, "unwrapMode": "custom", "visible": visible,
            "doubleSided": double, "shadingMode": "flat"}

# ---------------------------------------------------------------- the model

def eye(side):
    s = 1 if side == "L" else -1
    return node(f"{side}-Eye-Socket", (5 * s, 0, .5), shape=box((8, 9, 1), (0, 0, 0), eye_socket), children=[
        node(f"{side}-Eye", (0, -.5, .55), shape=quad((5, 6), (0, 0, 0), pupil(5, 6))),
        # Stretch scales a shape about its centre. At rest the lid is a thin strip hidden inside the
        # socket; animations bring it forward (z + 0.85) and lower it by 0.5 * stretch - 0.5 as it closes.
        node(f"{side}-Eyelid", (0, 4, -.2), shape=quad((8, 10), (0, 0, 0), eyelid(8, 10), stretch=(1, .1, 1))),
    ])

def happy_eye(side):
    # A sibling of the socket, not a child, so hiding the socket and pupil never hides it.
    s = 1 if side == "L" else -1
    return node(f"{side}-Eye-Happy", (5 * s, .5, 1.2), shape=quad((8, 5), (0, 0, 0), happy(8, 5), visible=False))

def leg_node(side):
    s = 1 if side == "L" else -1
    return node(f"{side}-Leg", (5 * s, 0, 0), shape=box((5, 10, 5), (0, -5, 0), leg), children=[
        node(f"{side}-Foot", (0, -10, 0), shape=box((6, 3, 8), (0, -.5, 1.5), lambda w, h, f: leg(w, h, f, FOOT, LEG_DARK))),
    ])

def build():
    silk_pixels = silk(12, 12)
    # Cob is the husk cup under the kernels: the cob never shows below or between the leaves.
    cob = node("Cob", (0, 0, 0), shape=box((22, 8, 18), (0, 4, 0), husk_cup), children=[
        node("Cob-Main", (0, 4, 0), shape=box((20, 20, 16), (0, 10, 0), kernels), children=[
            node("Face", (0, 13, 8), children=[eye("L"), eye("R"), happy_eye("L"), happy_eye("R")]),
        ]),
        node("Cob-Upper", (0, 24, 0), shape=box((18, 6, 14), (0, 3, 0), lambda w, h, f: kernels(w, h, f, 2)), children=[
            node("Cob-Tip", (0, 6, 0), shape=box((12, 4, 10), (0, 2, 0), lambda w, h, f: kernels(w, h, f, 3)), children=[
                # Attachments named Crown-Anchor (the golden crown) hang from here.
                node("Crown-Anchor", (0, 4, 0)),
                node("Silk", (0, 4, 0), children=[
                    node("Silk-A", (0, 0, 0), shape=quad((12, 12), (0, 6, 0), silk_pixels, double=True)),
                    node("Silk-B", (0, 0, 0), (0, 90, 0), shape=quad((12, 12), (0, 6, 0), silk_pixels, double=True)),
                ]),
            ]),
        ]),
        node("Husk-Front", (0, 1, 9.1), (15, 0, 0), shape=quad((22, 14), (0, 7, 0), husk(22, 14, 5), double=True)),
        node("Husk-Back", (0, 1, -9.1), (-12, 0, 0), shape=quad((22, 22), (0, 11, 0), husk(22, 22, 8), double=True)),
        node("L-Husk", (11.1, 1, 0), (0, 0, -25), shape=quad((18, 16), (0, 8, 0), husk(18, 16, 0, "point"), normal="+X", double=True)),
        node("R-Husk", (-11.1, 1, 0), (0, 0, 25), shape=quad((18, 16), (0, 8, 0), husk(18, 16, 0, "point"), normal="-X", double=True)),
    ])
    pelvis = node("Pelvis", (0, 12, 0), children=[cob, leg_node("L"), leg_node("R")])
    return {"nodes": [node("Origin", children=[pelvis])], "lod": "auto"}

def build_crown():
    """The crown sits on the cob tip (12 x 10), tilted a little to one side."""
    # The band spans y -1 to 2, so every point stands on y 2.
    def spikes(prefix, z):
        return [node(f"{prefix}-{i}", (x, 2, z), shape=box((2, h, 1), (0, h / 2, 0), gold))
                for i, (x, h) in enumerate(((-6, 3), (0, 4), (6, 3)))]
    crown = node("Crown", (0, -.5, 0), (0, 0, -8), children=[
        node("Crown-Band-Front", (0, 0, 5.5), shape=box((14, 3, 1), (0, .5, 0), gold)),
        node("Crown-Band-Back", (0, 0, -5.5), shape=box((14, 3, 1), (0, .5, 0), gold)),
        node("L-Crown-Band", (6.5, 0, 0), shape=box((1, 3, 10), (0, .5, 0), gold)),
        node("R-Crown-Band", (-6.5, 0, 0), shape=box((1, 3, 10), (0, .5, 0), gold)),
        *spikes("Crown-Point-Front", 5.5),
        *spikes("Crown-Point-Back", -5.5),
        node("L-Crown-Point", (6.5, 2, 0), shape=box((1, 3, 2), (0, 1.5, 0), gold)),
        node("R-Crown-Point", (-6.5, 2, 0), shape=box((1, 3, 2), (0, 1.5, 0), gold)),
        node("Crown-Ruby", (0, .5, 6.3), shape=box((2, 2, 1), (0, 0, 0), gem(RUBY))),
        node("L-Crown-Sapphire", (7.3, .5, 0), shape=box((1, 2, 2), (0, 0, 0), gem(SAPPHIRE))),
        node("R-Crown-Sapphire", (-7.3, .5, 0), shape=box((1, 2, 2), (0, 0, 0), gem(SAPPHIRE))),
    ])
    # isPiece marks the root as a piece: the client hangs it on the pet's node of the same name.
    anchor = node("Crown-Anchor", children=[crown])
    anchor["shape"]["settings"]["isPiece"] = True
    return {"nodes": [anchor], "lod": "auto"}

if __name__ == "__main__":
    os.makedirs(ROOT, exist_ok=True)
    model = build()
    with open(os.path.join(ROOT, "Model.blockymodel"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(model, f, indent=2)
    ATLAS.img.save(os.path.join(ROOT, "Texture.png"))
    ATLAS = Atlas(64)
    crown = build_crown()
    os.makedirs(os.path.join(ROOT, "Attachments"), exist_ok=True)
    with open(os.path.join(ROOT, "Attachments", "Crown.blockymodel"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(crown, f, indent=2)
    ATLAS.img.save(os.path.join(ROOT, "Attachments", "Crown_Texture.png"))
    print("wrote", os.path.abspath(ROOT))
