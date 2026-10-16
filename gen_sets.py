#!/usr/bin/env python3
"""Competitive sets for the team builder's "Load a set": assets/sets/<file>.tsv, read by Dex.sets().

Source: Smogon University's sets as published by github.com/pkmn/smogon (data.pkmn.cc). Run after gen_data.py and gen_go.py,
since ids come from the assets they write. Files: gen1 ... gen9, plus letsgo (Let's Go OU) and bdsp (BDSP OU), which
only those games read.

One line per set, grouped by format in FORMATS order: num, format, set name, level, ability id (0 = keep the
Pokémon's own), item id (0 = none), nature index, tera index (-1 = none), 6 EVs, 6 IVs, 4 move ids (comma separated).
Where Smogon lists alternatives (a move slot, two items, EV spreads), the first one is taken.
Items this app does not list (Red Orb, Z-Crystals for one Pokémon, gen 2 Pink Bow) load as no item.
"""
import csv
import io
import json
import re
import urllib.request
from collections import defaultdict
from pathlib import Path

SETS = "https://pkmn.github.io/smogon/data/sets/gen{}.json"
POKEAPI = "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/"
OUT = Path(__file__).parent / "assets"
# Formats kept, in the order the picker shows them. Gimmick metagames (Hackmons, STABmons, Godly Gift, Mix and Mega,
# CAP and similar) and one-off cups are left out: their sets are illegal or meaningless in the games themselves.
FORMATS = {
    "ou": "OU", "ubers": "Ubers", "uu": "UU", "ru": "RU", "nu": "NU", "pu": "PU", "zu": "ZU", "ubersuu": "Ubers UU",
    "lc": "LC", "nfe": "NFE", "doublesou": "Doubles OU", "vgc2025": "VGC 2025", "vgc2024": "VGC 2024",
    "vgc2023": "VGC 2023", "vgc2022": "VGC 2022", "vgc2021": "VGC 2021", "vgc2020": "VGC 2020", "vgc2019": "VGC 2019",
    "vgc2018": "VGC 2018", "vgc2017": "VGC 2017", "vgc2016": "VGC 2016", "vgc2014": "VGC 2014", "vgc2013": "VGC 2013",
    "vgc2012": "VGC 2012", "vgc2011": "VGC 2011", "vgc2010": "VGC 2010", "vgc2009": "VGC 2009",
    "battlestadiumsingles": "Battle Stadium Singles", "battlestadiumdoubles": "Battle Stadium Doubles",
    "battlespotsingles": "Battle Spot Singles", "battlespotdoubles": "Battle Spot Doubles", "1v1": "1v1",
    "monotype": "Monotype", "anythinggoes": "Anything Goes", "nationaldex": "National Dex",
    "nationaldexubers": "National Dex Ubers", "nationaldexuu": "National Dex UU", "nationaldexru": "National Dex RU",
    "nationaldexmonotype": "National Dex Monotype", "nationaldexdoubles": "National Dex Doubles",
    "letsgoou": "Let's Go OU", "bdspou": "BDSP OU",
}
OWN_FILE = {"letsgoou": "letsgo", "bdspou": "bdsp"}
LEVEL_50 = ("vgc", "battlestadium", "battlespot")
# Smogon name endings PokeAPI does not use: Tauros-Paldea-Blaze is tauros-paldea-blaze-breed
ALIASES = [(r"-F$", "-female"), (r"-M$", "-male"), (r"$", "-breed"), (r"$", "-mask"), (r"-Mane$", ""), (r"-Wings$", ""),
           (r"$", "-plumage"), (r"$", "-standard")]


def fold(s):
    return re.sub(r"[^a-z0-9]", "", s.lower().replace("é", "e"))


def rows(name):
    with (OUT / name).open(encoding="utf-8") as f:
        return [line.rstrip("\n").split("\t") for line in f]


def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read().decode("utf-8")


def first(v):
    return v[0] if isinstance(v, list) else v


ours = {int(r[0]) for r in rows("pokemon.tsv")}
merged = {int(r[0]): int(r[1]) for r in rows("merged.tsv")}
mons = {}
for r in csv.DictReader(io.StringIO(fetch(POKEAPI + "pokemon.csv"))):
    mons[fold(r["identifier"])] = int(r["id"])
for r in csv.DictReader(io.StringIO(fetch(POKEAPI + "pokemon_species.csv"))):
    mons.setdefault(fold(r["identifier"]), int(r["id"]))
moves = {fold(r[1]): int(r[0]) for r in rows("moves.tsv")}
items = {fold(r[1]): int(r[0]) for r in rows("items.tsv")}
abilities = {fold(r[1]): int(r[0]) for r in rows("abilities.tsv")}
natures = [r[0] for r in rows("natures.tsv")]
types = [r[0] for r in rows("types.tsv")]
missing = defaultdict(set)


def mon(name):
    """Pokémon number for a Smogon species name; an unknown form falls back to its species (Arceus-Fire is Arceus)."""
    tries = [name] + [re.sub(a, b, name) for a, b in ALIASES]
    parts = name.split("-")
    tries += ["-".join(parts[:i]) for i in range(len(parts) - 1, 0, -1)]
    for t in tries:
        num = mons.get(fold(t))
        if num is None:
            continue
        if t != name and t not in tries[1:len(ALIASES) + 1]:
            missing["form"].add(name)
        return num if num in ours else merged.get(num, num)
    missing["species"].add(name)
    return None


def lookup(table, kind, name):
    if name is None:
        return 0
    key = fold(re.sub(r"^Hidden Power .*", "Hidden Power", name))
    if key not in table:
        missing[kind].add(name)
        return 0
    return table[key]


def line(gen, num, fmt, name, s):
    level = s.get("level") or (50 if fmt.startswith(LEVEL_50) else 5 if fmt == "lc" else 100)
    evs = first(s.get("evs") or {})
    ivs = first(s.get("ivs") or {})
    # ponytail: gen 1-2 stat experience is not modeled (Team.stats), so their sets load with no EVs
    ev = [0 if gen <= 2 else evs.get(k, 0) for k in ("hp", "atk", "def", "spa", "spd", "spe")]
    iv = [ivs.get(k, 31) for k in ("hp", "atk", "def", "spa", "spd", "spe")]
    picked = []
    for slot in s.get("moves", [])[:4]:
        options = slot if isinstance(slot, list) else [slot]
        ids = [lookup(moves, "move", o) for o in options]
        picked.append(next((i for i in ids if i and i not in picked), 0))
    picked += [0] * (4 - len(picked))
    nature = first(s.get("nature"))
    tera = (first(s.get("teratypes")) or "").lower()
    cols = [num, FORMATS[fmt], name.replace("\t", " "), level, lookup(abilities, "ability", first(s.get("ability"))),
            lookup(items, "item", first(s.get("item"))), natures.index(nature) if nature in natures else -1,
            types.index(tera) if tera in types else -1, ",".join(map(str, ev)), ",".join(map(str, iv)),
            ",".join(map(str, picked))]
    return "\t".join(map(str, cols)) + "\n"


(OUT / "sets").mkdir(exist_ok=True)
for gen in range(1, 10):
    data = json.loads(fetch(SETS.format(gen)))
    files = defaultdict(list)
    for fmt in FORMATS:
        for species, by_format in data.items():
            num = mon(species) if fmt in by_format else None
            if num is None:
                continue
            for name, s in by_format[fmt].items():
                files[OWN_FILE.get(fmt, f"gen{gen}")].append(line(gen, num, fmt, name, s))
    for file, lines in files.items():
        (OUT / "sets" / f"{file}.tsv").write_text("".join(lines), encoding="utf-8")
        print(f"{file}: {len(lines)} sets")

for kind, names in missing.items():
    print(f"{kind} not matched ({len(names)}):", ", ".join(sorted(names)))
chomp = [l for l in (OUT / "sets" / "gen9.tsv").read_text(encoding="utf-8").splitlines() if l.startswith("445\tOU\t")]
assert chomp and all(len(l.split("\t")) == 11 for l in chomp), "Garchomp has gen 9 OU sets"
print("sets OK")
