#!/usr/bin/env python3
"""Adds Pokémon GO (game id 100, Dex.GO) to assets/. gen_data.py runs it last, since it rewrites the files edited here.

Source: PvPoke's gamemaster.json (MIT, github.com/pvpoke/pvpoke): released Pokémon only, shadows left out.
assets/games.tsv      the GO line: national numbering, no TMs
assets/learn/100.tsv  Fast, Charged and Elite TM moves in the last three columns (Dex.METHODS)
assets/pokemon.tsv    100 in the games column of every species and form in GO
"""
import json
import re
import urllib.request
from collections import defaultdict
from pathlib import Path

GO = "100"
URL = "https://raw.githubusercontent.com/pvpoke/pvpoke/master/src/data/gamemaster.json"
OUT = Path(__file__).parent / "assets"
# Words a form may have on one side only: PvPoke's "Tauros (Aqua)" is our "Paldean Tauros (Aqua Breed)"
LOOSE = {"form", "forme", "breed", "paldean", "standard", "wings", "mane", "sword", "shield"}


def rows(name):
    with (OUT / name).open(encoding="utf-8") as f:
        return [line.rstrip("\n").split("\t") for line in f]


def write(name, lines):
    with (OUT / name).open("w", encoding="utf-8") as f:
        f.writelines("\t".join(map(str, cols)) + "\n" for cols in lines)


def words(name):
    return set(name.lower().replace("’", "'").replace("(", " ").replace(")", " ").split())


def key(name):
    return re.sub(r"[^a-z0-9]", "", name.lower())


with urllib.request.urlopen(URL, timeout=60) as r:
    gm = json.load(r)

move_id = {key(f[1]): int(f[0]) for f in rows("moves.tsv")}


def our_move(name):
    # "Hidden Power (Bug)" is Hidden Power, "Water Gun Fast Blastoise" is Water Gun: the longest word prefix we know
    w = re.sub(r"\s*\(.*\)$", "", name).split()
    return next((move_id[key("".join(w[:n]))] for n in range(len(w), 0, -1) if key("".join(w[:n])) in move_id), None)


go_move = {m["moveId"]: our_move(m["name"]) for m in gm["moves"]}
print("GO moves with no match:", sorted(k for k, v in go_move.items() if v is None))

pokemon = rows("pokemon.tsv")
name_of = {f[0]: f[1] for f in pokemon}
forms_of = defaultdict(list)  # species: (form id, words beyond the species name)
for f in pokemon:
    if f[0] != f[-1]:
        forms_of[f[-1]].append((f[0], words(f[1]) - words(name_of[f[-1]])))

# Per species: PvPoke entries without a form label, entries per matched form, entries whose form we do not have
plain, matched, loose = defaultdict(list), defaultdict(lambda: defaultdict(list)), defaultdict(list)
for p in gm["pokemon"]:
    sid, label = str(p["dex"]), re.search(r"\((.*)\)$", p["speciesName"])
    if not p.get("released") or "shadow" in p.get("tags", []) or sid not in name_of:
        continue
    if not label:
        plain[sid].append(p)
        continue
    want = words(label[1])
    hits = [num for num, extra in forms_of[sid] if not (want ^ extra) - LOOSE]
    (matched[sid][hits[0]] if hits else loose[sid]).append(p)


def learn(entries):
    cols = [set(), set(), set()]
    for p in entries:
        for c, field in enumerate(("fastMoves", "chargedMoves", "eliteMoves")):
            cols[c].update(go_move.get(m) for m in p.get(field, []))
    return [" ".join(map(str, sorted(c - {None}))) for c in cols]


# ponytail: a species with no plain entry (Giratina) takes its unmatched forms (Altered), else all its forms (Zygarde);
# costumes we have no form for (Flying Pikachu) are dropped when the plain entry exists
lines, in_go = {}, set()
for sid in plain.keys() | matched.keys() | loose.keys():
    lines[sid] = learn(plain[sid] or loose[sid] or [p for ps in matched[sid].values() for p in ps])
    in_go.add(sid)
    for num, ps in matched[sid].items():
        in_go.add(num)
        cols = learn(ps)
        if cols != lines[sid]:  # same as Dex: a form without a row of its own learns what its species does
            lines[num] = cols
write(f"learn/{GO}.tsv", [[k, "", "", "", "", ""] + c for k, c in sorted(lines.items(), key=lambda x: int(x[0]))])

for f in pokemon:
    games = [g for g in f[19].split() if g != GO] + ([GO] if f[0] in in_go else [])
    f[19] = " ".join(sorted(games, key=int))
write("pokemon.tsv", pokemon)

species = sorted((s for s in in_go if int(s) < 10000), key=int)
gen = max(int(f[4]) for f in pokemon if f[0] in species)
games = [f for f in rows("games.tsv") if f[0] != GO]
games.append([GO, "Pokémon GO", "National=" + " ".join(f"{s}:{s}" for s in species), "", gen])
write("games.tsv", games)
print(f"Pokémon GO: {len(species)} species, {len(in_go) - len(species)} forms, {len(lines)} learnset rows, gen {gen}")
