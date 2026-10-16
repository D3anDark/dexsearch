#!/usr/bin/env python3
"""Looks of one Pokémon that differ only in appearance (Tatsugiri's three shapes, Vivillon's 20 wing patterns, female
Pikachu), for the row under its picture. HOME renders from the PokeAPI sprites repo (game art © Nintendo / Game Freak).
Run after gen_data.py: it reads assets/pokemon.tsv and merged.tsv.

assets/looks.tsv, one look per line, in game order: num, file, name
  num   the app's entry, as in pokemon.tsv; only entries with two looks or more
  file  assets/looks/<file>.webp, 256 px and lossy like assets/sprites; "" for the entry's own sprite
  name  "Droopy Form", "Female"

The same looks in the other artwork styles and shiny, where the repo has them, for the app's style setting and shiny
toggle (the app falls back to the HOME render):
  assets/looks-art/<file>.webp          official artwork, like assets/looks
  assets/looks-pixel/<file>.webp        game sprite, cropped and squared like assets/pixel
  assets/looks-shiny/<file>.webp        shiny HOME render
  assets/looks-shiny-art/<file>.webp    shiny official artwork
  assets/looks-shiny-pixel/<file>.webp  shiny game sprite
Official artwork has no female looks.

Gigantamax, Totem and battle-only forms are left out: they are states in battle, not Pokémon one owns. A look whose
render is pixel for pixel another's (Scatterbug's patterns) or that shares another's name (Zygarde's two 50% Formes)
is dropped. Female Indeedee, which has a form of its own, gets no second female look.
"""
import csv
import io
import json
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image

BASE = "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/"
SPRITES = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/"
TREE = "https://api.github.com/repos/PokeAPI/sprites/"
ASSETS = Path(__file__).parent / "assets"
EN = "9"
PX = 256


def get(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read()


def rows(name):
    return list(csv.DictReader(io.StringIO(get(BASE + name + ".csv").decode("utf-8"))))


def tsv(name):
    with open(ASSETS / name, encoding="utf-8") as f:
        return [line.rstrip("\n").split("\t") for line in f]


app = {f[0]: f[1] for f in tsv("pokemon.tsv")}
merged = {f[0]: f[1] for f in tsv("merged.tsv")}
pokemon = {r["id"]: r for r in rows("pokemon")}
female_form = {p["species_id"] for p in pokemon.values() if p["identifier"].endswith("-female")}
gendered = {r["id"] for r in rows("pokemon_species") if r["has_gender_differences"] == "1"} - female_form
names = {r["pokemon_form_id"]: r["form_name"] or r["pokemon_name"] for r in rows("pokemon_form_names")
         if r["local_language_id"] == EN}


def files(sha, prefix):
    t = json.loads(get(f"{TREE}git/trees/{sha}?recursive=1"))
    assert not t["truncated"], prefix
    return {prefix + e["path"] for e in t["tree"] if e["type"] == "blob"}


# file paths under sprites/pokemon a look can use, so a missing image is skipped without a request; the root is
# listed alone because its versions/ folder is too big for one recursive listing
root = json.loads(get(f"{TREE}git/trees/" + next(e["sha"] for e in json.loads(get(TREE + "contents/sprites"))
                                                if e["name"] == "pokemon")))["tree"]
sub = {e["path"]: e["sha"] for e in root if e["type"] == "tree"}
other = {e["path"]: e["sha"] for e in json.loads(get(f"{TREE}git/trees/{sub['other']}"))["tree"]}
have = ({e["path"] for e in root if e["type"] == "blob"} | files(sub["female"], "female/") | files(sub["shiny"], "shiny/")
        | files(other["home"], "other/home/") | files(other["official-artwork"], "other/official-artwork/"))
# folder: (source folder under sprites/pokemon, game sprite)
STYLES = {"looks-art": ("other/official-artwork/", False), "looks-pixel": ("", True),
          "looks-shiny": ("other/home/shiny/", False), "looks-shiny-art": ("other/official-artwork/shiny/", False),
          "looks-shiny-pixel": ("shiny/", True)}


def node(pid):
    return pid if pid in app else merged.get(pid, pokemon[pid]["species_id"])


groups = defaultdict(list)  # entry: [file stem, HOME path, name]
for f in sorted(rows("pokemon_forms"), key=lambda f: int(f["order"])):
    p = pokemon[f["pokemon_id"]]
    if p["identifier"].endswith("-gmax") or "-totem" in p["identifier"] or f["is_battle_only"] == "1":
        continue
    num = node(p["id"])
    own = p["id"] == num and f["is_default"] == "1"
    path = f"{p['id']}.png" if f["is_default"] == "1" else f"{p['species_id']}-{f['form_identifier']}.png"
    if "other/home/" + path not in have:
        continue
    groups[num].append(["" if own else f["id"], path, names.get(f["id"]) or app[num]])
    if own and p["species_id"] in gendered and f"other/home/female/{p['id']}.png" in have:
        groups[num][-1][2] = "Male"
        groups[num].append([f"{p['id']}-female", f"female/{p['id']}.png", "Female"])
groups = {k: v for k, v in groups.items() if len(v) > 1}


def render(src, pixel=False):
    img = Image.open(io.BytesIO(get(SPRITES + src))).convert("RGBA")
    if not pixel:
        img.thumbnail((PX, PX), Image.Resampling.LANCZOS)
        return img
    img = img.crop(img.getchannel("A").getbbox())
    side = max(img.size)
    square = Image.new("RGBA", (side, side))
    square.paste(img, ((side - img.width) // 2, (side - img.height) // 2))
    return square


def save(img, dst, pixel):
    if pixel:
        img.save(dst, "WEBP", lossless=True, quality=100, method=6)
    else:
        img.save(dst, "WEBP", quality=80, method=6)


pool = ThreadPoolExecutor(16)
imgs = dict(zip([look[1] for v in groups.values() for look in v],
                pool.map(lambda path: render("other/home/" + path), [look[1] for v in groups.values() for look in v])))

out = []
paths = {}  # file: HOME path, of the looks kept
(ASSETS / "looks").mkdir(exist_ok=True)
for num in sorted(groups, key=int):
    seen = set()
    looks = []
    for stem, path, name in groups[num]:
        pixels = imgs[path].tobytes()
        if pixels not in seen and name not in seen:
            seen.update((pixels, name))
            looks.append([num, stem, name, path])
    if len(looks) > 1:
        out += [look[:3] for look in looks]
        paths.update((look[1], look[3]) for look in looks if look[1])
for stem, path in paths.items():
    save(imgs[path], ASSETS / "looks" / f"{stem}.webp", False)
keep = {f"{stem}.webp" for stem in paths}
for folder, (src, pixel) in STYLES.items():
    (ASSETS / folder).mkdir(exist_ok=True)
    todo = [stem for stem in paths if src + paths[stem] in have]
    for stem, img in zip(todo, pool.map(lambda stem: render(src + paths[stem], pixel), todo)):
        save(img, ASSETS / folder / f"{stem}.webp", pixel)
    print(f"{folder}: {len(todo)}/{len(paths)}")
for folder in ["looks", *STYLES]:
    for f in (ASSETS / folder).glob("*.webp"):
        if f.name not in keep:
            f.unlink()

by = defaultdict(list)
for r in out:
    by[r[0]].append(r)
assert [r[2] for r in by["978"]] == ["Curly Form", "Droopy Form", "Stretchy Form"], by["978"]
assert by["978"][0][1] == "" and not {"664", "718", "774", "876"} & by.keys(), (by["978"], by.keys())
assert all(r[2] for r in out) and by["649"][0][2] == "Genesect", by["649"]
assert [r[2] for r in by["25"][:2]] == ["Male", "Female"], by["25"]
assert all((ASSETS / d / "25-female.webp").exists() for d in ("looks-pixel", "looks-shiny", "looks-shiny-pixel"))
with open(ASSETS / "looks.tsv", "w", encoding="utf-8") as f:
    f.writelines("\t".join(r) + "\n" for r in out)
print(len(out), "looks for", len(by), "Pokémon")
