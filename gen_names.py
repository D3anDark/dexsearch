#!/usr/bin/env python3
"""Spanish and Japanese names of Pokémon, moves, abilities, items, natures, types, games and Pokédexes, from PokeAPI.
Run after gen_data.py: it reads assets/pokemon.tsv, moves.tsv, abilities.tsv, items.tsv, natures.tsv and games.tsv.
Polish has no names of its own: Polish copies of the games use the English ones.

assets/names/<lang>.tsv, one name per line: kind, key, name
  mon       app number (pokemon.tsv); a form without a name in the language keeps the English one
  genus     species number: "Flame Pokémon"
  move, ability, item   PokeAPI id
  nature, egg, dex      English name: "Adamant", "Amorphous", "Original Johto"
  type, category        identifier: "fire", "physical"
  game      version group id: "Scarlet / Violet"
Rows equal to English are left out; the app falls back to English for anything missing.
"""
import csv
import io
import unicodedata
import urllib.request
from collections import defaultdict
from pathlib import Path

BASE = "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/"
ASSETS = Path(__file__).parent / "assets"
EN = "9"
# PokeAPI language ids, best first: Japanese in kana (ja-Hrkt) before kanji, then Spain's Spanish.
LANGS = {"ja": ["1", "11"], "es": ["7"]}
# Words in a dex name before its region that the Japanese games leave out: "Original Johto" is ジョウト図鑑.
QUALIFIERS = ("Original ", "Updated ", "New ", "Extended ", "Let’s Go ")


def get(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read()


def rows(name):
    return list(csv.DictReader(io.StringIO(get(BASE + name + ".csv").decode("utf-8"))))


def tsv(name):
    with open(ASSETS / name, encoding="utf-8") as f:
        return [line.rstrip("\n").split("\t") for line in f]


def local(table, key, field="name"):
    """{lang: {key value: name}}, best language id first, NFKC so full-width letters (メガリザードンＸ) become plain."""
    by = defaultdict(dict)
    for r in table:
        by[r["local_language_id"]].setdefault(r[key], r[field])
    out = {}
    for lang, ids in list(LANGS.items()) + [("en", [EN])]:
        out[lang] = {}
        for i in reversed(ids):
            out[lang].update({k: unicodedata.normalize("NFKC", v) for k, v in by[i].items() if v})
    return out


app = tsv("pokemon.tsv")
species = local(rows("pokemon_species_names"), "pokemon_species_id")
genus = local(rows("pokemon_species_names"), "pokemon_species_id", "genus")
forms = {f["pokemon_id"]: f["id"] for f in rows("pokemon_forms") if f["is_default"] == "1"}
form_names = local(rows("pokemon_form_names"), "pokemon_form_id", "form_name")
moves = local(rows("move_names"), "move_id")
abilities = local(rows("ability_names"), "ability_id")
items = local(rows("item_names"), "item_id")


def by_identifier(table, prose, key):
    """{lang: {identifier: name}} for tables keyed by id with an identifier column (types, natures)."""
    ident = {r["id"]: r["identifier"] for r in rows(table)}
    names = local(rows(prose), key)
    return {lang: {ident[k]: v for k, v in n.items()} for lang, n in names.items()}


types = by_identifier("types", "type_names", "type_id")
natures = by_identifier("natures", "nature_names", "nature_id")
categories = by_identifier("move_damage_classes", "move_damage_class_prose", "move_damage_class_id")
eggs = local(rows("egg_group_prose"), "egg_group_id")
versions = defaultdict(list)
for v in sorted(rows("versions"), key=lambda v: int(v["id"])):
    versions[v["version_group_id"]].append(v["id"])
version_names = local(rows("version_names"), "version_id")
dexes = local(rows("pokedex_prose"), "pokedex_id")
regions = local(rows("region_names"), "region_id")


def mon(lang, f):
    num, sp = f[0], f[21]
    name = species[lang].get(sp)
    if num == sp or not name:
        return name
    form = form_names[lang].get(forms.get(num))
    if not form:
        return None
    # Megas carry the whole name ("Mega-Charizard X"); regional and other forms only the form ("Forma de Alola").
    if name in form:
        return form
    return f"{name}({form})" if lang == "ja" else f"{name} ({form})"


def dex(lang, name):
    if lang == "es":
        en = {v: k for k, v in dexes["en"].items()}
        return dexes["es"].get(en.get(name))
    region = next((name[len(q):] for q in QUALIFIERS if name.startswith(q)), name)
    en = {v: k for k, v in regions["en"].items()}
    return regions["ja"].get(en.get(region))


ASSETS.joinpath("names").mkdir(exist_ok=True)
for lang in LANGS:
    out = []

    def add(kind, key, name, english):
        if name and name != english:
            out.append((kind, key, name))

    for f in app:
        add("mon", f[0], mon(lang, f), f[1])
        if f[0] == f[21]:
            add("genus", f[0], genus[lang].get(f[0]), f[2])
    for f in tsv("moves.tsv"):
        add("move", f[0], moves[lang].get(f[0]), f[1])
    for f in tsv("abilities.tsv"):
        add("ability", f[0], abilities[lang].get(f[0]), f[1])
    for f in tsv("items.tsv"):
        add("item", f[0], items[lang].get(f[0]), f[1])
    for f in tsv("natures.tsv"):
        add("nature", f[0], natures[lang].get(f[0].lower()), f[0])
    for t in types["en"]:
        add("type", t, types[lang].get(t), t)
    for c in categories["en"]:
        add("category", c, categories[lang].get(c), c)
    egg_en = {v: k for k, v in eggs["en"].items()}
    for e in sorted({e for f in app for e in f[12].split(", ") if e}):
        add("egg", e, eggs[lang].get(egg_en.get(e)), e)
    dex_names = {d.split("=")[0] for f in tsv("games.tsv") for d in f[2].split(";")}
    for d in sorted(dex_names | {"Kanto", "Johto", "Hoenn", "Sinnoh", "Unova", "Kalos", "Alola", "Galar", "Paldea"}):
        add("dex", d, dex(lang, d), d)
    for f in tsv("games.tsv"):
        names = [version_names[lang].get(v) for v in versions.get(f[0], [])]
        if names and all(names):
            add("game", f[0], " / ".join(names), f[1])
    out = [r for r in out if "\t" not in r[2] and "\n" not in r[2]]
    with open(ASSETS / "names" / f"{lang}.tsv", "w", encoding="utf-8") as fh:
        fh.writelines("\t".join(r) + "\n" for r in out)
    count = defaultdict(int)
    for r in out:
        count[r[0]] += 1
    print(lang, dict(count))

ja = {(r[0], r[1]): r[2] for r in tsv("names/ja.tsv")}
es = {(r[0], r[1]): r[2] for r in tsv("names/es.tsv")}
assert ja[("mon", "6")] == "リザードン" and es[("move", "53")] == "Lanzallamas", (ja.get(("mon", "6")), es.get(("move", "53")))
assert ja[("mon", "10034")] == "メガリザードンX" and es[("mon", "10100")] == "Raichu (Forma de Alola)", \
    (ja.get(("mon", "10034")), es.get(("mon", "10100")))
assert ja[("type", "fire")] == "ほのお" and ja[("dex", "Original Johto")] == "ジョウト", ja.get(("dex", "Original Johto"))
