#!/usr/bin/env python3
"""Type label images for the "Type labels" setting, from the PokeAPI sprites repo (game art © Nintendo / Game Freak).

assets/types/pixel/<type>.png  Sun / Moon badge, 48 x 18, drawn as is with nearest-neighbor scaling
assets/types/icon/<type>.png   Scarlet / Violet glyph, white on transparent, tinted by the app to match its pill
"""
import csv
import io
import urllib.request
from pathlib import Path

from PIL import Image

SPRITES = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/types/"
TYPES = "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/types.csv"
OUT = Path(__file__).parent / "assets" / "types"


def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read()


def glyph(img):
    """White glyph on a type-colored square: alpha from how close each pixel is to white versus the square's color.
    The square has a faint gradient, so the bottom quarter of that scale counts as background."""
    img = img.convert("RGBA")
    opaque = [img.getpixel((x, y)) for y in range(img.height) for x in range(img.width)]
    opaque = [p for p in opaque if p[3] == 255]
    bg = max(set(opaque), key=opaque.count)
    span = sum((255 - c) ** 2 for c in bg[:3]) ** 0.5
    out = Image.new("RGBA", img.size)
    for xy in ((x, y) for y in range(img.height) for x in range(img.width)):
        p = img.getpixel(xy)
        near = (0.75 - sum((255 - c) ** 2 for c in p[:3]) ** 0.5 / span) / 0.75
        out.putpixel(xy, (255, 255, 255, round(max(0.0, min(1.0, near)) * p[3])))
    return out.crop(out.getbbox())


types = {r["id"]: r["identifier"] for r in csv.DictReader(io.StringIO(fetch(TYPES).decode())) if int(r["id"]) <= 18}
for sub in ("pixel", "icon"):
    (OUT / sub).mkdir(parents=True, exist_ok=True)
for tid, name in types.items():
    (OUT / "pixel" / f"{name}.png").write_bytes(fetch(f"{SPRITES}generation-vii/sun-moon/{tid}.png"))
    small = Image.open(io.BytesIO(fetch(f"{SPRITES}generation-ix/scarlet-violet/small/{tid}.png")))
    glyph(small).save(OUT / "icon" / f"{name}.png", optimize=True)
assert len(list((OUT / "icon").glob("*.png"))) == 18 and len(list((OUT / "pixel").glob("*.png"))) == 18
print("types OK:", ", ".join(types.values()))
