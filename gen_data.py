#!/usr/bin/env python3
"""Builds the offline Pokédex in assets/ from PokeAPI CSVs, pokemondb.net pages and Pokémon HOME renders.
Run once, commit the output. Sprites already on disk at SPRITE_PX are skipped; the first run downloads ~140 MB.

assets/pokemon.tsv     one species per line, then alternate forms (Alolan, Mega, ...); columns documented at Dex.Mon
assets/merged.tsv      forms left out for sharing their species' types and stats: form id, species id
assets/items.tsv       held items: id, name, generation, short effect (gen_items.py fills those PokeAPI lacks), section
assets/moves.tsv       id, name, type, category, power, accuracy, pp, priority, gen, chance, short effect, effect
assets/abilities.tsv   id, name, gen, short effect, effect
assets/types.tsv       attacking type, then its damage factor (x100) against each type in file order
assets/natures.tsv     name, raised stat, lowered stat ("" when neutral)
assets/games.tsv       version group id, name, dexes "Name=number:species ...;Name=...", TMs "move:TM001 ...", generation
assets/learn/<vg>.tsv  species or form, level-up "level:move ...", TM, egg, tutor, other "move ..."
assets/info/<vg>.tsv   species, where to find, Pokédex entry (pokemondb.net, see scrape_pdb.py)
Pokémon GO is added by gen_go.py, then item icons by gen_items.py.
"""
import csv
import gzip
import io
import re
import runpy
import urllib.error
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from bs4 import BeautifulSoup
from PIL import Image

BASE = "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/"
SPRITES = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/home/"
SPRITE_PX = 256
EN = "9"
STATS = {"1": "HP", "2": "Atk", "3": "Def", "4": "SpA", "5": "SpD", "6": "Spe"}
CATEGORIES = {"1": "status", "2": "physical", "3": "special"}
LEARN_METHODS = {"1": 0, "4": 1, "2": 2, "3": 3, "12": 4}  # level-up, machine, egg, tutor, Champions "train"
SPINOFFS = {"12", "13", "28", "29"}  # Colosseum, XD, Japanese Red/Green and Blue
# Item categories a Pokémon can hold in battle, besides every berry (pocket 5), and the app's section for each
# (Dex.ITEM_GROUPS).
HELD = {"held-items": "Held items", "choice": "Held items", "bad-held-items": "Held items",
        "type-enhancement": "Type boosters", "plates": "Type boosters", "jewels": "Gems",
        "species-specific": "Species items", "memories": "Species items", "mega-stones": "Mega Stones",
        "z-crystals": "Z-Crystals", "effort-training": "Training"}

OUT = Path(__file__).parent / "assets"


def rows(name):
    with urllib.request.urlopen(BASE + name + ".csv", timeout=60) as r:
        return list(csv.DictReader(io.TextIOWrapper(r, "utf-8")))


def english(name, id_col, value="name", lang_col="local_language_id"):
    return {r[id_col]: r[value] for r in rows(name) if r[lang_col] == EN}


def latest_english(name, id_col, order_col):
    """Newest English flavor text per id, whitespace collapsed (game text has hard line breaks)."""
    best = {}
    for r in rows(name):
        if r["language_id"] == EN and (r[id_col] not in best or int(r[order_col]) > best[r[id_col]][0]):
            best[r[id_col]] = (int(r[order_col]), r["flavor_text"])
    return {k: clean(v) for k, (_, v) in best.items()}


def clean(text):
    return re.sub(r"\s+", " ", text.replace("­\n", "").replace("­", "")).strip()


def tsv(path, lines):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for cols in lines:
            f.write("\t".join(str(c).replace("\t", " ").replace("\n", "\\n") for c in cols) + "\n")


types = {r["id"]: r["identifier"] for r in rows("types") if int(r["id"]) <= 18}
items = english("item_names", "item_id")
item_ids = {r["id"]: r["identifier"] for r in rows("items")}
locations = english("location_names", "location_id")
moves_en = english("move_names", "move_id")
triggers = {r["id"]: r["identifier"] for r in rows("evolution_triggers")}
species = {r["id"]: r for r in rows("pokemon_species")}
names, genus = {}, {}
for r in rows("pokemon_species_names"):
    if r["local_language_id"] == EN:
        names[r["pokemon_species_id"]], genus[r["pokemon_species_id"]] = r["name"], r["genus"]

# --- types ---------------------------------------------------------------------------------------
factor = {(r["damage_type_id"], r["target_type_id"]): r["damage_factor"] for r in rows("type_efficacy")}
type_order = sorted(types, key=int)
tsv(OUT / "types.tsv", [[types[a]] + [factor.get((a, d), 100) for d in type_order] for a in type_order])

# --- natures -------------------------------------------------------------------------------------
nature_names = english("nature_names", "nature_id")
tsv(OUT / "natures.tsv", sorted(
    [nature_names[r["id"]], "" if r["increased_stat_id"] == r["decreased_stat_id"] else STATS[r["increased_stat_id"]],
     "" if r["increased_stat_id"] == r["decreased_stat_id"] else STATS[r["decreased_stat_id"]]]
    for r in rows("natures")))

# --- moves ---------------------------------------------------------------------------------------
effects = {r["move_effect_id"]: r for r in rows("move_effect_prose") if r["local_language_id"] == EN}
move_flavor = latest_english("move_flavor_text", "move_id", "version_group_id")
moves = [m for m in rows("moves") if int(m["id"]) < 10000 and m["id"] in moves_en]
move_lines = []
for m in moves:
    e = effects.get(m["effect_id"])
    short = clean(e["short_effect"]) if e else move_flavor.get(m["id"], "")
    long = e["effect"].strip() if e else short
    move_lines.append([m["id"], moves_en[m["id"]], types[m["type_id"]], CATEGORIES[m["damage_class_id"]], m["power"],
                       m["accuracy"], m["pp"], m["priority"], m["generation_id"], m["effect_chance"], short, long])
tsv(OUT / "moves.tsv", move_lines)

# --- abilities -----------------------------------------------------------------------------------
ability_en = english("ability_names", "ability_id")
ability_prose = {r["ability_id"]: r for r in rows("ability_prose") if r["local_language_id"] == EN}
ability_flavor = latest_english("ability_flavor_text", "ability_id", "version_group_id")
abilities = [a for a in rows("abilities") if a["is_main_series"] == "1"]
tsv(OUT / "abilities.tsv", [
    [a["id"], ability_en[a["id"]], a["generation_id"],
     clean(ability_prose[a["id"]]["short_effect"]) if a["id"] in ability_prose else ability_flavor.get(a["id"], ""),
     ability_prose[a["id"]]["effect"].strip() if a["id"] in ability_prose else ability_flavor.get(a["id"], "")]
    for a in abilities])


# --- evolutions ----------------------------------------------------------------------------------
def method(e):
    """One way to evolve, e.g. "Lv 16", "Thunder Stone", "level up, friendship, night"."""
    if e["minimum_level"]:
        parts = ["Lv " + e["minimum_level"]]
    elif e["trigger_item_id"]:
        parts = [items[e["trigger_item_id"]]]
    else:
        parts = [triggers[e["evolution_trigger_id"]].replace("-", " ")]
    if e["used_move_id"]:
        parts.append(moves_en[e["used_move_id"]] + (" x" + e["minimum_move_count"] if e["minimum_move_count"] else ""))
    for col, text in (("minimum_happiness", "friendship"), ("minimum_affection", "affection"),
                      ("minimum_beauty", "beauty"), ("needs_overworld_rain", "in rain"),
                      ("turn_upside_down", "console upside down"), ("needs_multiplayer", "in Union Circle")):
        if e[col] and e[col] != "0":
            parts.append(text)
    if e["time_of_day"]:
        parts.append(e["time_of_day"])
    if e["gender_id"]:
        parts.append({"1": "female", "2": "male"}[e["gender_id"]])
    if e["held_item_id"]:
        parts.append("holding " + items[e["held_item_id"]])
    if e["known_move_id"]:
        parts.append("knowing " + moves_en[e["known_move_id"]])
    if e["known_move_type_id"]:
        parts.append("knowing a " + types[e["known_move_type_id"]].title() + " move")
    if e["location_id"]:
        parts.append("at " + locations.get(e["location_id"], "special location"))
    if e["party_species_id"]:
        parts.append(names[e["party_species_id"]] + " in party")
    if e["party_type_id"]:
        parts.append(types[e["party_type_id"]].title() + " type in party")
    if e["trade_species_id"]:
        parts.append("for " + names[e["trade_species_id"]])
    if e["relative_physical_stats"]:
        parts.append({"1": "Atk > Def", "0": "Atk = Def", "-1": "Atk < Def"}[e["relative_physical_stats"]])
    if e["minimum_steps"]:
        parts.append(e["minimum_steps"] + " steps")
    if e["minimum_damage_taken"]:
        parts.append(e["minimum_damage_taken"] + " damage taken")
    return ", ".join(parts)


# Newest version group wins (Leafeon: Moss Rock, later Leaf Stone); methods within it are alternatives.
evo_rows = defaultdict(list)
for e in rows("pokemon_evolution"):
    evo_rows[e["evolved_species_id"]].append(e)
evo = {}
for sid, es in evo_rows.items():
    newest = max(int(e["version_group_id"]) for e in es)
    evo[sid] = " or ".join(dict.fromkeys(method(e) for e in es if int(e["version_group_id"]) == newest))

# --- games ---------------------------------------------------------------------------------------
mon_types = defaultdict(list)
for r in sorted(rows("pokemon_types"), key=lambda r: int(r["slot"])):
    mon_types[r["pokemon_id"]].append(types[r["type_id"]])
stats = defaultdict(lambda: [0] * 6)
for r in rows("pokemon_stats"):
    if r["stat_id"] in STATS:
        stats[r["pokemon_id"]][int(r["stat_id"]) - 1] = int(r["base_stat"])
pokemon_rows = rows("pokemon")
defaults = sorted((p for p in pokemon_rows if p["is_default"] == "1"), key=lambda p: int(p["species_id"]))
default_of = {p["species_id"]: p["id"] for p in defaults}


def own_stats(p):
    d = default_of[p["species_id"]]
    return (mon_types[p["id"]], stats[p["id"]]) != (mon_types[d], stats[d])


# Alternate forms (Alolan Raichu, Mega Charizard X, Heat Rotom...) are numbered by their own pokemon id, 10001 and
# up; a species keeps its species id. Forms with their species' types and stats (Pikachu in a cap, Gigantamax,
# Totem) are left out; merged.tsv points them at their species so a team saved with one still loads.
forms = sorted((p for p in pokemon_rows if p["is_default"] == "0" and own_stats(p)), key=lambda p: int(p["id"]))
tsv(OUT / "merged.tsv", [[p["id"], p["species_id"]] for p in pokemon_rows if p["is_default"] == "0" and not own_stats(p)])
species_of = {p["id"]: p["species_id"] for p in defaults + forms}
key_of = {**{p["id"]: p["species_id"] for p in defaults}, **{p["id"]: p["id"] for p in forms}}
form_ids = {p["id"] for p in forms}
pokemon_forms = rows("pokemon_forms")
form_en = english("pokemon_form_names", "pokemon_form_id", "pokemon_name")
mega = {f["pokemon_id"] for f in pokemon_forms if f["is_mega"] == "1"}
form_name = {f["pokemon_id"]: form_en[f["id"]] for f in pokemon_forms
             if f["is_default"] == "1" and form_en.get(f["id"]) and f["pokemon_id"] in form_ids}
taken = set(names.values())
for p in forms:  # Koraidon's and Miraidon's ride forms have no pokemon_forms row; two forms are each "Mega Meowstic"
    if form_name.get(p["id"]) in taken | {None}:
        form_name[p["id"]] = p["identifier"].replace("-", " ").title()
    taken.add(form_name[p["id"]])
learn = defaultdict(lambda: defaultdict(lambda: [[], [], [], [], []]))  # vg -> species -> one list per LEARN_METHODS column
for r in rows("pokemon_moves"):
    sid, col = key_of.get(r["pokemon_id"]), LEARN_METHODS.get(r["pokemon_move_method_id"])
    if sid and col is not None and r["move_id"] in moves_en:
        learn[r["version_group_id"]][sid][col].append((int(r["level"] or 0), int(r["order"] or 0), r["move_id"]))

main_dexes = {r["id"] for r in rows("pokedexes") if r["is_main_series"] == "1"}
dex_name = english("pokedex_prose", "pokedex_id")
dex_vgs = defaultdict(list)
for r in rows("pokedex_version_groups"):
    if r["pokedex_id"] in main_dexes:
        dex_vgs[r["pokedex_id"]].append(r["version_group_id"])
vg_dexes = defaultdict(list)
for d, vgs in dex_vgs.items():
    for vg in vgs:
        vg_dexes[vg].append(d)
dex_numbers = defaultdict(list)
for r in rows("pokemon_dex_numbers"):
    if r["species_id"] in names:
        dex_numbers[r["pokedex_id"]].append((int(r["pokedex_number"]), r["species_id"]))

# A DLC (The Teal Mask, Mega Dimension) has no learnsets of its own and only dexes its base game also lists:
# fold it into that base game instead of listing it separately.
parent = {}
for vg in sorted({v for vs in dex_vgs.values() for v in vs} | set(learn), key=int):
    shared = [min(dex_vgs[d], key=int) for d in vg_dexes[vg]]
    if vg not in learn and shared and all(int(s) < int(vg) for s in shared):
        parent[vg] = min(shared, key=int)
games = [vg for vg in sorted({v for vs in dex_vgs.values() for v in vs} | set(learn), key=int)
         if vg not in parent and vg not in SPINOFFS]

version_names = english("version_names", "version_id")
game_name = defaultdict(list)
for v in rows("versions"):
    game_name[v["version_group_id"]].append(version_names[v["id"]])

machines = defaultdict(dict)
for r in sorted(rows("machines"), key=lambda r: int(r["machine_number"])):
    vg = parent.get(r["version_group_id"], r["version_group_id"])
    machines[vg].setdefault(r["move_id"], item_ids[r["item_id"]].upper())

# --- pokemondb.net -------------------------------------------------------------------------------
# scrape_pdb.py caches the pages in pdb_cache/. They add learnsets PokeAPI lacks (Legends: Z-A) and, per game,
# where to find each species and its Pokédex entry. ~40 s to parse all 1025 pages.
PDB = Path(__file__).parent / "pdb_cache"
SECTIONS = {"Moves learnt by level up": 0, "Moves learnt on evolution": 0, "Moves learnt by TM": 1, "Egg moves": 2,
            "Move Tutor moves": 3, "Moves learnt by reminder": 4}


def norm(s):
    return re.sub(r"[^a-z0-9]", "", s.lower())


version_vg = {norm(version_names[v["id"]]): parent.get(v["version_group_id"], v["version_group_id"])
              for v in rows("versions")}
move_ids = {m["identifier"]: m["id"] for m in moves}
move_ids["vise-grip"] = move_ids["vice-grip"]
# pokemondb sub-tab name ("Alolan Raichu", "Raichu") to form or species id
pdb_key = {**{(species_of[p], norm(n)): p for p, n in form_name.items()}, **{(s, norm(n)): s for s, n in names.items()}}
api_learn = set(learn)
where, entries = defaultdict(dict), defaultdict(lambda: defaultdict(list))
skipped = defaultdict(int)


def versions(th):
    """(version group, version name) per span.igame in a row header."""
    out = []
    for span in th.select("span.igame"):
        name = span.get_text(strip=True)
        if norm(name) in version_vg:
            out.append((version_vg[norm(name)], name))
        else:
            skipped["version " + name] += 1
    return out


def section(tab, h2_prefix):
    h2 = next((h for h in tab.find_all("h2") if h.get_text(strip=True).startswith(h2_prefix)), None)
    return h2.find_next("table") if h2 else None


for path in sorted(PDB.glob("pokedex_*.html.gz")):
    if path.name == "pokedex_all.html.gz":
        continue
    page = BeautifulSoup(gzip.decompress(path.read_bytes()), "lxml")
    sid = str(int(page.select_one(".vitals-table td strong").get_text()))
    for a in page.select(".sv-tabs-tab-list a[href^='#tab-moves-']"):
        vgs = {version_vg.get(norm(v)) for v in a.get_text(strip=True).split("/")}
        vg = vgs.pop() if len(vgs) == 1 else None  # form tabs ("Alolan Raichu") name no version
        if vg is None or vg in api_learn:
            continue
        tab, heading = page.select_one(a["href"]), None
        sections = defaultdict(dict)  # heading -> species or form id (None: shared by every form) -> rows
        for el in tab.find_all(["h3", "table"]):
            if el.name == "h3":
                heading = el.get_text(strip=True)
                continue
            if heading not in SECTIONS:
                skipped["section " + heading] += 1
                continue
            # A species with forms (Raichu, Alolan Raichu) has one sub-tab, and table, per form.
            panel, key = el.find_parent(class_="sv-tabs-panel"), None
            if panel is not tab:
                name = tab.select_one(f'a[href="#{panel["id"]}"]').get_text(strip=True)
                key = pdb_key.get((sid, norm(name)), "?" + name)
            rows_ = sections[heading].setdefault(key, [])
            head = [th.get_text(strip=True) for th in el.select("thead th")]
            for i, tr in enumerate(el.select("tbody tr")):
                td = tr.find_all("td")
                move = move_ids.get(td[head.index("Move")].a["href"].rsplit("/", 1)[1])
                level = td[head.index("Lv.")].get_text(strip=True) if "Lv." in head else "0"
                if move is None or not level.isdigit():  # "?" levels: the site does not know them yet
                    skipped["move row"] += 1
                    continue
                rows_.append((int(level), i, move))
        for key in {sid} | {k for by_key in sections.values() for k in by_key if k and k[0] != "?"}:
            cols = [[], [], [], [], []]
            for heading, by_key in sections.items():
                got = by_key.get(key, by_key.get(None))
                if got is None and key == sid:  # sub-tab name not recognised: its first table still beats nothing
                    got = next(iter(by_key.values()))
                cols[SECTIONS[heading]] += got or []
            if any(cols):  # species missing from the game still get a tab saying "does not learn any ... moves"
                learn[vg][key] = cols
        for k in {k for by_key in sections.values() for k in by_key if k and k[0] == "?"}:
            skipped["form " + k[1:]] += 1
    table = section(page, "Where to find")
    for tr in table.select("tr") if table else []:
        text = clean(tr.td.get_text())
        for vg, _ in versions(tr.th) if text != "Location data not yet available" else []:
            where[vg].setdefault(sid, text)
    table = section(page, "Pokédex entries")
    for tr in table.select("tr") if table else []:
        names_by_vg = defaultdict(list)
        for vg, name in versions(tr.th):
            names_by_vg[vg].append(name)
        for vg, names_ in names_by_vg.items():
            entries[vg][sid].append((" / ".join(names_), clean(tr.td.get_text())))
print("pokemondb skipped:", dict(skipped))
# ponytail: pokemondb gives Megas no moves of their own, so in a pokemondb-only game (Z-A) every Mega whose species
# is there counts as available. Exact per-game Mega lists would need another source.
for vg in [v for v in learn if v not in api_learn]:
    for pid in mega:
        if species_of[pid] in learn[vg]:
            learn[vg].setdefault(pid, learn[vg][species_of[pid]])
vg_gen = {r["id"]: r["generation_id"] for r in rows("version_groups")}

# A game that loses its data (Z-A has no pokemondb locations yet) must not keep last run's file.
for p in [*OUT.glob("learn/*.tsv"), *OUT.glob("info/*.tsv")]:
    p.unlink()
game_lines = []
games_of = defaultdict(list)
for vg in games:
    dexes = []
    for d in sorted(vg_dexes[vg], key=int):
        dexes.append(dex_name[d] + "=" + " ".join(f"{n}:{s}" for n, s in sorted(dex_numbers[d])))
    if not dexes:  # Champions has a roster but no Pokédex: list what it has learnsets for, by national number
        dexes.append("Roster=" + " ".join(f"{s}:{s}" for s in sorted((s for s in learn[vg] if s in names), key=int)))
    else:  # FireRed's dex is Kanto only, yet trades and the Sevii Islands bring in Gen 2 and 3: add those by national number
        regional = {s for d in vg_dexes[vg] for _, s in dex_numbers[d]}
        rest = sorted((s for s in learn[vg] if s in names and s not in regional), key=int)
        if rest:
            dexes.append("National=" + " ".join(f"{s}:{s}" for s in rest))
    tms = " ".join(f"{m}:{label}" for m, label in sorted(machines[vg].items(), key=lambda x: x[1]))
    game_lines.append([vg, " / ".join(game_name[vg]), ";".join(dexes), tms, vg_gen[vg]])
    line_of = {}
    for key in learn[vg]:
        games_of[key].append(vg)
        cols = [sorted(set(c)) for c in learn[vg][key]]
        line_of[key] = ([" ".join(dict.fromkeys(f"{lv}:{m}" for lv, _, m in cols[0]))]
                        + [" ".join(dict.fromkeys(m for _, _, m in c)) for c in cols[1:]])
    # A form that learns exactly what its species does (most Megas) is left out: Dex falls back to the species.
    lines = [[k] + c for k, c in sorted(line_of.items(), key=lambda x: int(x[0]))
             if k in names or c != line_of.get(species_of[k])]
    if lines:
        tsv(OUT / "learn" / f"{vg}.tsv", lines)
    # One entry is shown as is; FireRed and LeafGreen differ, so each gets its version name in front.
    info = [[sid, where[vg].get(sid, ""),
             "\n\n".join(text if len(entries[vg][sid]) == 1 else f"{label}: {text}" for label, text in entries[vg][sid])]
            for sid in sorted(set(where[vg]) | set(entries[vg]), key=int)]
    if info:
        tsv(OUT / "info" / f"{vg}.tsv", info)
tsv(OUT / "games.tsv", game_lines)

# --- pokémon -------------------------------------------------------------------------------------
mon_abilities, hidden = defaultdict(list), {}
for r in sorted(rows("pokemon_abilities"), key=lambda r: int(r["slot"])):
    if r["is_hidden"] == "1":
        hidden[r["pokemon_id"]] = r["ability_id"]
    else:
        mon_abilities[r["pokemon_id"]].append(r["ability_id"])
egg_group_names = english("egg_group_prose", "egg_group_id")
egg_groups = defaultdict(list)
for r in rows("pokemon_egg_groups"):
    egg_groups[r["species_id"]].append(egg_group_names[r["egg_group_id"]])
growth = english("growth_rate_prose", "growth_rate_id")
flavor = latest_english("pokemon_species_flavor_text", "species_id", "version_id")

form_gen = {f["pokemon_id"]: vg_gen[f["introduced_in_version_group_id"]] for f in pokemon_forms
            if f["is_default"] == "1"}
mon_lines = []
for p in defaults + forms:  # a form shares its species' Pokédex data and has its own types, stats and abilities
    sid, pid = p["species_id"], p["id"]
    s, num = species[sid], key_of[pid]
    gen = form_gen.get(pid, s["generation_id"]) if num != sid else s["generation_id"]  # Alolan Raichu: 7, not 1
    mon_lines.append([num, form_name.get(pid, names[sid]), genus[sid], ",".join(mon_types[pid]), gen,
                      ",".join(map(str, stats[pid])), ",".join(mon_abilities[pid]), hidden.get(pid, ""),
                      p["height"], p["weight"], s["capture_rate"], s["gender_rate"], ", ".join(egg_groups[sid]),
                      s["hatch_counter"], s["base_happiness"], growth[s["growth_rate_id"]].title(),
                      s["evolution_chain_id"], s["evolves_from_species_id"], evo.get(sid, ""),
                      " ".join(games_of[num]), flavor.get(sid, ""), sid])
tsv(OUT / "pokemon.tsv", mon_lines)

# --- items ---------------------------------------------------------------------------------------
item_cat = {r["id"]: r for r in rows("item_categories")}
item_gen = {}
for r in rows("item_game_indices"):
    item_gen[r["item_id"]] = min(item_gen.get(r["item_id"], 99), int(r["generation_id"]))
item_prose = {r["item_id"]: r for r in rows("item_prose") if r["local_language_id"] == EN}
held = [i for i in rows("items") if i["id"] in items
        and (item_cat[i["category_id"]]["pocket_id"] == "5" or item_cat[i["category_id"]]["identifier"] in HELD)]
# Items new in Legends: Z-A (most of its Mega Stones) have no game index yet: generation 9.
tsv(OUT / "items.tsv", sorted(([i["id"], items[i["id"]], item_gen.get(i["id"], 9),
                                clean(item_prose[i["id"]]["short_effect"]) if i["id"] in item_prose else "",
                                HELD.get(item_cat[i["category_id"]]["identifier"], "Berries")]
                               for i in held), key=lambda line: line[1]))
print(f"wrote {len(mon_lines)} Pokémon and forms, {len(held)} items, {len(move_lines)} moves, {len(abilities)} abilities, {len(games)} games")

# --- sprites -------------------------------------------------------------------------------------
sprites = OUT / "sprites"
sprites.mkdir(exist_ok=True)


def sprite(sid):
    dst = sprites / f"{sid}.webp"
    if dst.exists() and Image.open(dst).width == SPRITE_PX:
        return True
    try:
        with urllib.request.urlopen(SPRITES + sid + ".png", timeout=30) as r:
            img = Image.open(io.BytesIO(r.read())).convert("RGBA")
    except urllib.error.HTTPError as e:
        print(f"no sprite for #{sid}: HTTP {e.code}")
        return False
    img.thumbnail((SPRITE_PX, SPRITE_PX), Image.Resampling.LANCZOS)
    img.save(dst, "WEBP", quality=80, method=6)
    return True


with ThreadPoolExecutor(16) as pool:
    ok = sum(pool.map(sprite, [str(line[0]) for line in mon_lines]))
print(f"sprites: {ok}/{len(mon_lines)} in {sprites}")
keep = {f"{line[0]}.webp" for line in mon_lines}
for f in sprites.glob("*.webp"):
    if f.name not in keep:
        f.unlink()

runpy.run_path(str(Path(__file__).with_name("gen_go.py")))
runpy.run_path(str(Path(__file__).with_name("gen_items.py")))
