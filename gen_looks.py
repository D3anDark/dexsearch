#!/usr/bin/env python3
"""Looks of one Pokémon that differ only in appearance (Tatsugiri's three shapes, Vivillon's 20 wing patterns, female
Pikachu), for the strip on its Info tab. HOME renders from the PokeAPI sprites repo (game art © Nintendo / Game Freak).
Run after gen_data.py: it reads assets/pokemon.tsv and merged.tsv.

assets/looks.tsv, one look per line, in game order: num, file, name
  num   the app's entry, as in pokemon.tsv; only entries with two looks or more
  file  assets/looks/<file>.webp, 256 px and lossy like assets/sprites; "" for the entry's own sprite
  name  "Droopy Form", "Female"

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
HOME = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/home/"
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
# file names in the HOME folder, so a missing render is skipped without a request
other = json.loads(get(TREE + "contents/sprites/pokemon/other"))
sha = next(e["sha"] for e in other if e["name"] == "home")
home = {e["path"] for e in json.loads(get(f"{TREE}git/trees/{sha}?recursive=1"))["tree"]}


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
    if path not in home:
        continue
    groups[num].append(["" if own else f["id"], path, names.get(f["id"]) or app[num]])
    if own and p["species_id"] in gendered and f"female/{p['id']}.png" in home:
        groups[num][-1][2] = "Male"
        groups[num].append([f"{p['id']}-female", f"female/{p['id']}.png", "Female"])
groups = {k: v for k, v in groups.items() if len(v) > 1}


def render(path):
    img = Image.open(io.BytesIO(get(HOME + path))).convert("RGBA")
    img.thumbnail((PX, PX), Image.Resampling.LANCZOS)
    return img


with ThreadPoolExecutor(16) as pool:
    imgs = dict(zip([look[1] for v in groups.values() for look in v],
                    pool.map(render, [look[1] for v in groups.values() for look in v])))

out = []
(ASSETS / "looks").mkdir(exist_ok=True)
for num in sorted(groups, key=int):
    seen = set()
    looks = []
    for stem, path, name in groups[num]:
        pixels = imgs[path].tobytes()
        if pixels not in seen and name not in seen:
            seen.update((pixels, name))
            looks.append([num, stem, name])
            if stem:
                imgs[path].save(ASSETS / "looks" / f"{stem}.webp", "WEBP", quality=80, method=6)
    if len(looks) > 1:
        out += looks
keep = {f"{r[1]}.webp" for r in out}
for f in (ASSETS / "looks").glob("*.webp"):
    if f.name not in keep:
        f.unlink()

by = defaultdict(list)
for r in out:
    by[r[0]].append(r)
assert [r[2] for r in by["978"]] == ["Curly Form", "Droopy Form", "Stretchy Form"], by["978"]
assert by["978"][0][1] == "" and not {"664", "718", "774", "876"} & by.keys(), (by["978"], by.keys())
assert all(r[2] for r in out) and by["649"][0][2] == "Genesect", by["649"]
assert [r[2] for r in by["25"][:2]] == ["Male", "Female"], by["25"]
with open(ASSETS / "looks.tsv", "w", encoding="utf-8") as f:
    f.writelines("\t".join(r) + "\n" for r in out)
print(len(out), "looks for", len(by), "Pokémon")
