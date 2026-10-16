#!/usr/bin/env python3
"""What changed between generations, from PokeAPI's past-value tables, so each game shows its own era.

assets/past.tsv, one record per line, the first column naming its kind:
  type   type, generation that introduced it
  chart  attacking type, defending type, damage factor (x100), last generation it applied
  mon    Pokémon or form id, last generation, its types then ("normal" for Clefairy up to Gen 5)
  move   move id, version group it changed in, then its type, power, pp, accuracy, priority before that version group
A move's rows ascend by version group and each holds every value from before it, not just the one that changed.
"""
import csv
import io
import urllib.request
from pathlib import Path

BASE = "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/"
OUT = Path(__file__).parent / "assets" / "past.tsv"
SPINOFFS = {"12", "13", "28", "29"}  # Colosseum, XD, Japanese Red/Green and Blue: ids out of release order
MOVE_FIELDS = ("type_id", "power", "pp", "accuracy", "priority")


def rows(name):
    with urllib.request.urlopen(BASE + name + ".csv", timeout=60) as r:
        return list(csv.DictReader(io.TextIOWrapper(r, "utf-8")))


types = {r["id"]: r for r in rows("types") if int(r["id"]) <= 18}
name = {k: r["identifier"] for k, r in types.items()}
out = [["type", r["identifier"], r["generation_id"]] for r in types.values()]
out += [["chart", name[r["damage_type_id"]], name[r["target_type_id"]], r["damage_factor"], r["generation_id"]]
        for r in rows("type_efficacy_past")]

past_types = {}
for r in sorted(rows("pokemon_types_past"), key=lambda r: int(r["slot"])):
    past_types.setdefault((int(r["pokemon_id"]), int(r["generation_id"])), []).append(name[r["type_id"]])
out += [["mon", pid, gen, ",".join(t)] for (pid, gen), t in sorted(past_types.items())]

# The app orders games by version group id (Dex.Game.versionGroup), so ids must follow release order.
order = {r["id"]: int(r["order"]) for r in rows("version_groups") if r["id"] not in SPINOFFS}
ids = sorted(order, key=int)
assert [order[i] for i in ids] == sorted(order.values()), "version group ids out of release order"

moves = {r["id"]: r for r in rows("moves")}
changes = {}
for r in rows("move_changelog"):
    assert r["changed_in_version_group_id"] in order, r
    changes.setdefault(int(r["move_id"]), []).append(r)
for mid, rs in sorted(changes.items()):
    state = {f: moves[str(mid)][f] for f in MOVE_FIELDS}
    lines = []
    for r in sorted(rs, key=lambda r: -order[r["changed_in_version_group_id"]]):
        # ??? (Curse's type in Gens 2 to 4) is not one of the app's 18 types: Curse stays Ghost there.
        state.update({f: r[f] for f in MOVE_FIELDS if r[f] and (f != "type_id" or r[f] in name)})
        lines.append(["move", mid, r["changed_in_version_group_id"], name[state["type_id"]], state["power"], state["pp"],
                      state["accuracy"], state["priority"]])
    out += reversed(lines)

with OUT.open("w", encoding="utf-8") as f:
    for cols in out:
        f.write("\t".join(str(c) for c in cols) + "\n")
print(f"{OUT}: {len(out)} records")
