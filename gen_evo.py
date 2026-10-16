#!/usr/bin/env python3
"""Evolutions between species and forms, from PokeAPI's pokemon_evolution table, so the app can draw Galarian Meowth
into Perrserker, Pikachu into Alolan Raichu in Alola, and Kirlia into Gallade (males only). Run after gen_data.py:
it reads assets/pokemon.tsv, merged.tsv and moves.tsv for names and form ids.

assets/evo.tsv, one evolution per line: from, to, gender, how
  from, to  species number or form id, as in pokemon.tsv; a form the app merges into its species counts as the species
  gender    1 only females evolve this way, 2 only males, 0 either
  how       "Lv 16", "Dawn Stone", "Level up with high friendship at night"; alternatives joined by " or "
"""
import csv
import io
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

BASE = "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/"
ASSETS = Path(__file__).parent / "assets"
EN = "9"
REGIONS = {"7": "Alola", "8": "Galar", "9": "Hisui", "10": "Paldea"}
TIMES = {"day": "during the day", "night": "at night", "dusk": "at dusk", "full-moon": "under a full moon"}
# PokeAPI nature ids, the order nature_bitmask's bits follow
NATURES = ["Hardy", "Bold", "Modest", "Calm", "Timid", "Lonely", "Docile", "Mild", "Gentle", "Hasty", "Adamant", "Impish",
           "Bashful", "Careful", "Rash", "Jolly", "Naughty", "Lax", "Quirky", "Naive", "Brave", "Relaxed", "Quiet", "Sassy",
           "Serious"]


def rows(name):
    with urllib.request.urlopen(BASE + name + ".csv", timeout=60) as r:
        return list(csv.DictReader(io.TextIOWrapper(r, "utf-8")))


def tsv(name):
    with open(ASSETS / name, encoding="utf-8") as f:
        return [line.rstrip("\n").split("\t") for line in f]


app = {f[0]: f[1] for f in tsv("pokemon.tsv")}
merged = {f[0]: f[1] for f in tsv("merged.tsv")}
moves = {f[0]: f[1] for f in tsv("moves.tsv")}
items = {r["item_id"]: r["name"] for r in rows("item_names") if r["local_language_id"] == EN}
locations = {r["location_id"]: r["name"] for r in rows("location_names") if r["local_language_id"] == EN}
types = {r["id"]: r["identifier"].title() for r in rows("types")}
species_from = {r["id"]: r["evolves_from_species_id"] for r in rows("pokemon_species")}
species_of = {r["id"]: r["species_id"] for r in rows("pokemon")}
forms = {r["id"]: r for r in rows("pokemon_forms")}
# "Sandy Cloak", or the whole name where the form has none of its own ("Own Tempo Rockruff")
form_names = {r["pokemon_form_id"]: r["form_name"] or r["pokemon_name"] for r in rows("pokemon_form_names")
              if r["local_language_id"] == EN}


def node(pokemon):
    """The app's number for a pokemon id: the form if the app lists it, else its species."""
    return pokemon if pokemon in app else merged.get(pokemon, species_of[pokemon])


# Forms per app entry: Burmy has 3 cloaks and Basculin 3 stripes on one entry, Small Pumpkaboo an entry to itself.
shared = Counter(node(f["pokemon_id"]) for f in forms.values())


def form_label(form):
    """ "Sandy Cloak": the form's name where its entry in the app stands for more than one form, else ""."""
    return form_names.get(form, "") if shared[node(forms[form]["pokemon_id"])] > 1 else ""


def how(e, labels=True):
    """One way to evolve in words: "Lv 36", "Trade holding King's Rock", "Use Psyshield Bash 20 times in Agile Style"."""
    t = e["evolution_trigger_id"]
    move = moves.get(e["used_move_id"], "")
    times = e["minimum_move_count"]
    head = {
        "2": "Trade",
        "4": "Lv 20 Nincada, with a free party slot and a Poké Ball",
        "5": "Spin around holding a Sweet",
        "6": "Train in the Tower of Darkness",
        "7": "Train in the Tower of Waters",
        "8": "Land 3 critical hits in one battle",
        "9": f"Lose {e['minimum_damage_taken']}+ HP, then walk under the stone bridge",
        "11": f"Use {move} {times} times in Agile Style",
        "12": f"Use {move} {times} times in Strong Style",
        "13": f"Lose {e['minimum_damage_taken']}+ HP from recoil",
        "14": f"Use {move} {times} times",
        "15": "Defeat 3 Bisharp holding Leader's Crest",
        "16": "Level up with 999 Gimmighoul Coins",
        "17": "Feed 400 Meltan Candy (Pokémon GO)",
    }.get(t)
    if head is None:
        head = items[e["trigger_item_id"]] if t == "3" else "Lv " + e["minimum_level"] if e["minimum_level"] else "Level up"
    if t == "5":
        return head  # Alcremie: 63 rows of spin direction, time and Sweet, one line here
    parts = [head]
    if e["minimum_happiness"]:
        parts.append("with high friendship")
    if e["held_item_id"]:
        parts.append("holding " + items[e["held_item_id"]])
    if e["known_move_id"]:
        parts.append("knowing " + moves[e["known_move_id"]])
    if e["known_move_type_id"]:
        parts.append(f"knowing a {types[e['known_move_type_id']]} move")
    if e["trade_species_id"]:
        parts.append("for " + app[e["trade_species_id"]])
    if e["party_species_id"]:
        parts.append(f"with {app[e['party_species_id']]} in the party")
    if e["party_type_id"]:
        parts.append(f"with a {types[e['party_type_id']]} type in the party")
    if e["relative_physical_stats"]:
        parts.append({"1": "with Attack higher than Defense", "0": "with Attack equal to Defense",
                      "-1": "with Attack lower than Defense"}[e["relative_physical_stats"]])
    if e["minimum_steps"]:
        parts.append(f"after walking {int(e['minimum_steps']):,} steps with it following")
    if e["needs_overworld_rain"] == "1":
        parts.append("in rain")
    if e["turn_upside_down"] == "1":
        parts.append("holding the console upside down")
    if e["needs_multiplayer"] == "1":
        parts.append("in a Union Circle")
    if e["time_of_day"]:
        parts.append(TIMES[e["time_of_day"]])
    if e["location_id"]:
        parts.append("at " + locations[e["location_id"]])
    if e["region_id"]:
        parts.append("in " + REGIONS[e["region_id"]])
    if e["nature_bitmask"]:
        bits = int(e["nature_bitmask"])
        parts.append("with a " + ", ".join(n for i, n in enumerate(NATURES) if bits >> i & 1) + " nature")
    text = " ".join(parts)
    if e["percentage_chance"] and e["percentage_chance"] != "100":
        text += f", {e['percentage_chance']}% chance"
    if not labels:
        return text
    names = [form_label(e["required_pokemon_form_id"]) if e["required_pokemon_form_id"] else ""]
    if e["evolved_pokemon_form_id"] and not e["gender_id"]:  # Meowstic's female form is in the gender column
        names.append(form_label(e["evolved_pokemon_form_id"]))
    names = list(dict.fromkeys(x for x in names if x))  # Phony Sinistea into Phony Polteageist: name it once
    return text + (f" ({' into '.join(names)})" if names else "")


# Per evolution, the rows marked default in their newest version group (Leafeon: Leaf Stone, not the Moss Rock rows
# of older games); Overqwil has only non-default rows, so it keeps those.
by_pair = defaultdict(list)
for e in rows("pokemon_evolution"):
    sid = e["evolved_species_id"]
    src = node(forms[e["required_pokemon_form_id"]]["pokemon_id"]) if e["required_pokemon_form_id"] else species_from[sid]
    dst = node(forms[e["evolved_pokemon_form_id"]]["pokemon_id"]) if e["evolved_pokemon_form_id"] else sid
    by_pair[src, dst].append(e)

out = []
for (src, dst), es in by_pair.items():
    if any(e["is_default"] == "1" for e in es):
        es = [e for e in es if e["is_default"] == "1"]
    newest = max(int(e["version_group_id"]) for e in es)
    es = [e for e in es if int(e["version_group_id"]) == newest]
    genders = {e["gender_id"] for e in es}
    gender = genders.pop() if len(genders) == 1 and "" not in genders else "0"
    # Vivillon's 20 patterns or Gastrodon's two seas all evolve alike: one method, no form names
    bare = {how(e, False) for e in es}
    text = bare.pop() if len(es) > 1 and len(bare) == 1 else " or ".join(dict.fromkeys(how(e) for e in es))
    out.append([src, dst, gender or "0", text])
out.sort(key=lambda r: (int(r[0]), int(r[1])))

parents = defaultdict(set)
for src, dst, _, _ in out:
    parents[dst].add(src)
assert all(len(p) == 1 for p in parents.values()), {k: v for k, v in parents.items() if len(v) > 1}
by = {(r[0], r[1]): r for r in out}
assert by["281", "475"][2:] == ["2", "Dawn Stone"], by["281", "475"]
assert by["25", "10100"][3] == "Thunder Stone in Alola", by["25", "10100"]
assert by["10161", "863"][3] == "Lv 28", by["10161", "863"]
assert by["677", "678"][2] == "0", by["677", "678"]
with open(ASSETS / "evo.tsv", "w", encoding="utf-8") as f:
    f.writelines("\t".join(r) + "\n" for r in out)
print(len(out), "evolutions")
