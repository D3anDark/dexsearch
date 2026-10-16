#!/usr/bin/env python3
"""Held item icons and missing effects from Pokémon Showdown (bag art © Nintendo / Game Freak).

assets/items/<id>.webp   the item's 24 px icon from Showdown's item sheet, lossless; the app draws it unfiltered
assets/items.tsv         rewritten with Showdown's short description where PokeAPI has no short effect (most
                         Generation 8-9 items: Booster Energy, Covert Cloak, the Legends: Z-A Mega Stones)

Ids and names are those in assets/items.tsv, so gen_data.py runs this last; items match Showdown's by name. The PokeAPI
sprites repo has the same art but no Generation 8-9 items. The app shows no icon for an item missing here.
"""
import io
import re
import urllib.request
from pathlib import Path

from PIL import Image

PLAY = "https://play.pokemonshowdown.com/"
TEXT = "https://raw.githubusercontent.com/smogon/pokemon-showdown/master/data/text/items.ts"
ASSETS = Path(__file__).parent / "assets"
CELL, COLUMNS = 24, 16


def get(url):
    # Showdown answers 403 to urllib's default User-Agent
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "dexsearch-gen"}), timeout=30) as r:
        return r.read()


def entries(js):
    """Top-level fields of each `key:{...}` entry in items.js's one exported object, nested objects left out."""
    depth, body = 0, []
    for ch in js:
        if ch == "}" and depth == 2:
            yield "".join(body)
            body = []
        depth += (ch == "{") - (ch == "}")
        if depth == 2 and ch not in "{}":
            body.append(ch)


def norm(name):
    return re.sub(r"[^a-z0-9]", "", name.lower())


sprite = {}
for body in entries(get(PLAY + "data/items.js").decode("utf-8")):
    name, num = re.search(r'(?:^|,)name:"([^"]*)"', body), re.search(r"(?:^|,)spritenum:(\d+)", body)
    if name and num:
        sprite[norm(name.group(1))] = int(num.group(1))
sheet = Image.open(io.BytesIO(get(PLAY + "sprites/itemicons-sheet.png"))).convert("RGBA")

# One tab deeper than an entry's own fields are per-generation overrides, so only the entry's shortDesc matches.
effect = {}
for name, body in re.findall(r'^\t\w+: \{\n\t\tname: "([^"]*)",\n(.*?)^\t\},', get(TEXT).decode("utf-8"), re.M | re.S):
    desc = re.search(r'^\t\tshortDesc: "((?:[^"\\]|\\.)*)",', body, re.M)
    if desc:
        effect[norm(name)] = desc.group(1).replace('\\"', '"').replace("Pokemon", "Pokémon")

path = ASSETS / "items.tsv"
rows = [line.split("\t") for line in path.read_text(encoding="utf-8").splitlines() if line]
for r in rows:
    r[3] = r[3] or effect.get(norm(r[1]), "")
path.write_text("".join("\t".join(r) + "\n" for r in rows), encoding="utf-8")

out = ASSETS / "items"
out.mkdir(exist_ok=True)
missing = []
for i, name, *_ in rows:
    n = sprite.get(norm(name), 0)
    x, y = n % COLUMNS * CELL, n // COLUMNS * CELL
    icon = sheet.crop((x, y, x + CELL, y + CELL))
    if n == 0 or icon.getbbox() is None:
        missing.append(name)
    else:
        icon.save(out / f"{i}.webp", "WEBP", lossless=True)
print(f"{len(rows) - len(missing)} of {len(rows)} icons, none for: {', '.join(missing)}")
print(f"{sum(1 for r in rows if not r[3])} items without an effect: {', '.join(r[1] for r in rows if not r[3])}")
