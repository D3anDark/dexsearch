'use strict';
/**
 * L.kt, Dex.kt and Team.kt for the web app: same TSVs, same rules, so both apps show the same numbers. check.js runs
 * the matching part of Dex.main on this file; keep the two in step when one changes.
 */

const div = (a, b) => Math.trunc(a / b);
const opt = s => (s === '' || s === undefined ? 0 : +s);
const ints = (s, sep) => (s ? s.split(sep).map(Number) : []);

/** UI text: the English text is the key, assets/lang/<lang>.tsv holds the rest. Dex.main checks the keys used here. */
const L = {
  LANGS: ['en', 'pl', 'es', 'ja'],
  lang: 'en',
  table: new Map(),
  read(text) {
    const m = new Map();
    for (const line of text.split('\n')) {
      if (!line) continue;
      const tab = line.indexOf('\t');
      m.set(line.slice(0, tab).replaceAll('\\n', '\n'), line.slice(tab + 1).replaceAll('\\n', '\n'));
    }
    return m;
  },
  t: s => L.table.get(s) ?? s,
  f: (s, ...args) => format(L.t(s), args),
  k: s => s,
};

/** java.util.Formatter's part the tables use: %d, %s, %.1f, %1$s, %%; a decimal comma in Polish and Spanish. */
function format(s, args) {
  let next = 0;
  return s.replace(/%(?:(\d+)\$)?(?:\.(\d+))?([dsf%])/g, (_, pos, digits, c) => {
    if (c === '%') return '%';
    const v = args[pos ? pos - 1 : next++];
    return c === 'f' ? decimal(v, +digits) : String(v);
  });
}

function decimal(v, digits) {
  const s = v.toFixed(digits);
  return L.lang === 'pl' || L.lang === 'es' ? s.replace('.', ',') : s;
}

/** "Mr. Mime" and "mr mime" both "mrmime", "Flabébé" "flabebe", hiragana as katakana; see Dex.fold. */
function fold(s) {
  let b = '';
  const t = s.normalize('NFKD').toLowerCase();
  for (let i = 0; i < t.length; i++) {
    const c = t[i];
    const code = t.charCodeAt(i);
    if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b += c;
    else if (code >= 0x3041 && code <= 0x3096) b += String.fromCharCode(code + 0x60);
    else if (code > 0x7f && /^[\p{L}\p{Nd}]$/u.test(c)) b += c;
  }
  return b;
}

const key = (name, en) => (name === en ? fold(en) : fold(name) + '|' + fold(en));

const STATS = ['HP', 'Atk', 'Def', 'SpA', 'SpD', 'Spe'];
const METHODS = ['Level', 'TM', 'Egg', 'Tutor', 'Other', 'Fast Attack', 'Charged Attack', 'Elite TM'];
const ITEM_GROUPS = ['Held items', 'Berries', 'Type boosters', 'Gems', 'Species items', 'Mega Stones', 'Z-Crystals', 'Training'];
const STARTERS = [1, 152, 252, 387, 495, 650, 722, 810, 906];
const REGIONS = ['Kanto', 'Johto', 'Hoenn', 'Sinnoh', 'Unova', 'Kalos', 'Alola', 'Galar', 'Paldea'];
const WHOLE = 999;
const GO = 100;
const ERA_GAMES = [2, 4, 7, 10, 14, 16, 18, 20, 25];
const SPECIAL = new Set(['fire', 'water', 'grass', 'electric', 'psychic', 'ice', 'dragon', 'dark']);
const NEUTRAL = ['Hardy', 'Docile', 'Bashful', 'Quirky', 'Serious'];
const MOVE_SORTS = ['No.', 'Name', 'Power', 'Accuracy', 'PP'];
/** Labels shown through L.t; `stat` sorts descending, null keeps dex order (No.) or sorts by name. */
const SORTS = [
  { label: 'No.', stat: null }, { label: 'Name', stat: null }, { label: 'BST', stat: m => m.bst },
  ...STATS.map((s, i) => ({ label: s, stat: m => m.stats[i] })),
];

function mon(f, n) {
  const en = f[1];
  const name = n.get('mon\t' + f[0]) ?? en;
  const stats = ints(f[5], ',');
  return {
    num: +f[0], en, name, genus: n.get('genus\t' + f[21]) ?? f[2], types: f[3].split(','), gen: +f[4], stats,
    bst: stats.reduce((a, b) => a + b, 0), abilities: ints(f[6], ','), hidden: opt(f[7]), height: opt(f[8]),
    weight: opt(f[9]), captureRate: opt(f[10]), genderRate: opt(f[11]),
    eggGroups: f[12].split(', ').map(e => n.get('egg\t' + e) ?? e).join(L.t(", ")), hatchCounter: opt(f[13]),
    happiness: opt(f[14]), growth: f[15], chain: opt(f[16]), games: ints(f[19], ' '), flavor: f[20], species: +f[21],
    key: key(name, en),
  };
}

const isForm = m => m.num !== m.species;

function move(f, name) {
  return {
    id: +f[0], en: f[1], name, type: f[2], category: f[3], power: opt(f[4]), accuracy: opt(f[5]), pp: opt(f[6]),
    priority: opt(f[7]), gen: opt(f[8]), chance: opt(f[9]), shortEffect: f[10], effect: f[11].replaceAll('\\n', '\n'),
    key: key(name, f[1]),
  };
}

class Dex {
  constructor(fetchText) {
    /** path under assets/ to its text, null when the file does not exist. */
    this.fetchText = fetchText;
    this.mons = [];
    this.items = [];
    this.moves = [];
    this.abilities = [];
    this.natures = [];
    this.games = [];
    this.types = [];
    this.national = [];
    this.generations = [];
    this.monByNum = new Map();
    this.forms = new Map();
    this.merged = new Map();
    this.evoInto = new Map();
    this.evoFrom = new Map();
    this.itemById = new Map();
    this.moveById = new Map();
    this.abilityById = new Map();
    this.labels = new Map();
    this.typeGen = new Map();
    this.pastChart = [];
    this.pastTypes = new Map();
    this.pastMoves = new Map();
    /** Per game id, its moves that differ from today's; at most two games, as in Dex.kt. */
    this.eraCache = new Map();
    /** Parsed learn/<game>.tsv, at most two games (a few hundred KB each). */
    this.learnCache = new Map();
  }

  static async load(fetchText, lang) {
    const d = new Dex(fetchText);
    const files = ['pokemon', 'merged', 'evo', 'moves', 'abilities', 'items', 'natures', 'games', 'types', 'past'];
    const [names, ...texts] = await Promise.all([fetchText(`names/${lang}.tsv`), ...files.map(f => fetchText(f + '.tsv'))]);
    const t = Object.fromEntries(files.map((f, i) => [f, rows(texts[i])]));
    const n = new Map();
    for (const f of rows(names ?? '')) n.set(f[0] + '\t' + f[1], f[2]);
    for (const [k, v] of n) if (k.startsWith('type\t') || k.startsWith('category\t')) d.labels.set(k, v);
    d.names = n;
    for (const f of t.pokemon) {
      const m = mon(f, n);
      d.mons.push(m);
      d.monByNum.set(m.num, m);
      if (isForm(m)) push(d.forms, m.species, m);
    }
    for (const f of t.merged) d.merged.set(+f[0], +f[1]);
    for (const f of t.evo) {
      const e = { from: +f[0], to: +f[1], gender: +f[2], how: f[3] };
      d.evoInto.set(e.to, e);
      push(d.evoFrom, e.from, e);
    }
    for (const f of t.moves) d.moves.push(move(f, n.get('move\t' + f[0]) ?? f[1]));
    for (const f of t.abilities) {
      const name = n.get('ability\t' + f[0]) ?? f[1];
      d.abilities.push({ id: +f[0], en: f[1], name, gen: opt(f[2]), shortEffect: f[3], effect: f[4].replaceAll('\\n', '\n'), key: key(name, f[1]) });
    }
    for (const f of t.items) {
      const name = n.get('item\t' + f[0]) ?? f[1];
      d.items.push({ id: +f[0], en: f[1], name, gen: opt(f[2]), effect: f[3].replace(/^Held: /, ''), group: f[4], key: key(name, f[1]) });
    }
    for (const f of t.natures) d.natures.push({ en: f[0], name: n.get('nature\t' + f[0]) ?? f[0], up: f[1], down: f[2] });
    for (const f of t.games) d.games.push(d.game_(f));
    d.types = t.types.map(f => f[0]);
    d.factors = t.types.map(f => f.slice(1).map(Number));
    const learn = new Map();
    for (const m of d.mons) {
      for (const g of m.games) learn.set(g, (learn.get(g) ?? 0) + 1);
      if (!isForm(m)) for (const x of d.family(m)) d.national.push({ mon: x, number: m.num, dex: '' });
    }
    d.learnGames = [...learn.keys()].sort((a, b) => a - b);
    let widest = null;
    for (const g of d.games) {
      if (!learn.has(g.id) || g.id === GO) continue;
      if (!widest || g.gen > widest.gen || (g.gen === widest.gen && learn.get(g.id) > learn.get(widest.id))) widest = g;
    }
    d.widest = widest;
    d.whole = d.virtual_(WHOLE, L.t("Whole Pokédex"), Math.max(...d.mons.map(m => m.gen)), d.national, 'National');
    REGIONS.forEach((r, i) => d.generations.push(d.virtual_(WHOLE + i + 1, L.f("Gen %d Pokédex", i + 1), i + 1,
      d.national.filter(e => e.mon.gen === i + 1), r, ERA_GAMES[i])));
    d.regions = REGIONS.map(r => d.dexName(r));
    for (const m of d.moves) d.moveById.set(m.id, m);
    for (const a of d.abilities) d.abilityById.set(a.id, a);
    for (const i of d.items) d.itemById.set(i.id, i);
    for (const f of t.past) {
      if (f[0] === 'type') d.typeGen.set(f[1], +f[2]);
      else if (f[0] === 'chart') d.pastChart.push([d.types.indexOf(f[1]), d.types.indexOf(f[2]), +f[3], +f[4]]);
      else if (f[0] === 'mon') push(d.pastTypes, +f[1], [+f[2], f[3].split(',')]);
      else if (f[0] === 'move') {
        const m = d.moveById.get(+f[1]);
        // past.tsv columns: type, power, PP, accuracy, priority.
        if (m) push(d.pastMoves, m.id, [+f[2], { ...m, type: f[3], power: opt(f[4]), accuracy: opt(f[6]), pp: opt(f[5]), priority: opt(f[7]) }]);
      }
    }
    d.pastChart.sort((a, b) => a[3] - b[3]);
    d.names = null;
    return d;
  }

  /** One games.tsv row: regional dexes concatenated, first dex wins, each species followed by its forms in the game. */
  game_(f) {
    const id = +f[0];
    const g = { id, name: this.names.get('game\t' + f[0]) ?? f[1], gen: +f[4], multiDex: f[2].includes(';'), versionGroup: id, virtual: false, dex: [], byNum: new Map(), tms: new Map() };
    for (const dx of f[2].split(';')) {
      const eq = dx.indexOf('=');
      const name = this.dexName(dx.slice(0, eq));
      for (const pair of dx.slice(eq + 1).split(' ')) {
        const p = ints(pair, ':');
        if (g.byNum.has(p[1])) continue;
        for (const m of this.family(this.mon(p[1]))) {
          if (isForm(m) && !m.games.includes(id)) continue;
          const e = { mon: m, number: p[0], dex: name };
          g.dex.push(e);
          g.byNum.set(m.num, e);
        }
      }
    }
    if (f[3]) for (const pair of f[3].split(' ')) {
      const colon = pair.indexOf(':');
      g.tms.set(+pair.slice(0, colon), pair.slice(colon + 1));
    }
    return g;
  }

  /** The Whole Pokédex or a generation's: national numbers, that generation's mechanics. */
  virtual_(id, name, gen, entries, region, versionGroup = id) {
    const dexName = this.dexName(region);
    const g = { id, name, gen, multiDex: false, versionGroup, virtual: true, dex: [], byNum: new Map(), tms: new Map() };
    for (const e of entries) {
      const x = { mon: e.mon, number: e.number, dex: dexName };
      g.dex.push(x);
      g.byNum.set(x.mon.num, x);
    }
    return g;
  }

  dexName(s) {
    return this.names?.get('dex\t' + s) ?? L.t(s);
  }

  mon(num) {
    const m = this.monByNum.get(num);
    if (!m) throw new Error('no Pokémon ' + num);
    return m;
  }

  monOrNull(num) {
    return this.monByNum.get(num) ?? null;
  }

  /** A team saved with a form since left out (Gigantamax Charizard) gets its species instead. */
  known(num) {
    return this.merged.get(num) ?? num;
  }

  family(m) {
    const base = this.mon(m.species);
    return [base, ...(this.forms.get(base.num) ?? [])];
  }

  /** See Dex.neighbor: 0 past either end, -1 when the species is not in `order`. */
  neighbor(order, num, step) {
    const species = this.mon(num).species;
    let j = order.findIndex(x => this.mon(x).species === species);
    if (j < 0) return -1;
    while (j >= 0 && j < order.length && this.mon(order[j]).species === species) j += step;
    if (j < 0 || j >= order.length) return 0;
    const other = this.mon(order[j]).species;
    return order.includes(other) ? other : order[j];
  }

  item(id) {
    return this.itemById.get(id);
  }

  ability(id) {
    return this.abilityById.get(id);
  }

  /** Move `id` as it was in `g`, or today's when `g` is omitted. */
  move(g, id) {
    if (id === undefined) return this.moveById.get(g);
    return this.era(g).get(id) ?? this.moveById.get(id);
  }

  movesIn(g) {
    const era = this.era(g);
    return this.moves.filter(m => m.gen <= g.gen).map(m => era.get(m.id) ?? m);
  }

  era(g) {
    let out = this.eraCache.get(g.id);
    if (out) return lru(this.eraCache, g.id, out);
    out = new Map();
    for (const m of this.moves) {
      if (m.gen > g.gen) continue;
      const old = (this.pastMoves.get(m.id) ?? []).find(p => g.versionGroup < p[0])?.[1] ?? m;
      const category = g.gen >= 4 || old.category === 'status' ? old.category : SPECIAL.has(old.type) ? 'special' : 'physical';
      const x = category === old.category ? old : { ...old, category };
      if (x !== m) out.set(m.id, x);
    }
    lru(this.eraCache, g.id, out);
    return out;
  }

  label(k) {
    return this.labels.get('type\t' + k) ?? this.labels.get('category\t' + k) ?? k[0].toUpperCase() + k.slice(1);
  }

  game(id) {
    if (id === WHOLE) return this.whole;
    return this.games.find(g => g.id === id) ?? this.generations.find(g => g.id === id) ?? this.games[0];
  }

  typesIn(g) {
    return this.types.filter(t => this.typeGen.get(t) <= g.gen);
  }

  typesOf(g, m) {
    return (this.pastTypes.get(m.num) ?? []).find(p => g.gen <= p[0])?.[1] ?? m.types;
  }

  factor(g, attack, defender) {
    const a = this.types.indexOf(attack);
    let f = 100;
    for (const d of defender) {
      const t = this.types.indexOf(d);
      const past = this.pastChart.find(p => p[0] === a && p[1] === t && g.gen <= p[3]);
      f = div(f * (past ? past[2] : this.factors[a][t]), 100);
    }
    return f;
  }

  evo(num) {
    return this.evoInto.get(num) ?? null;
  }

  evolutions(num) {
    return this.evoFrom.get(num) ?? [];
  }

  /** Evolution family in tree order as [num, depth] rows; see Dex.chain. */
  chain(m) {
    const out = [];
    const walk = (num, depth) => {
      out.push([num, depth]);
      for (const e of this.evolutions(num)) walk(e.to, depth + 1);
    };
    for (const x of this.mons) {
      if (x.chain === m.chain && !this.evoInto.has(x.num) && (this.evoFrom.has(x.num) || !isForm(x))) walk(x.num, 0);
    }
    return out.length > 1 ? out : [[m.num, 0]];
  }

  tmGame(g) {
    return g === this.whole ? this.widest : g;
  }

  learnGame(g, m) {
    if (g === this.whole && m.games.includes(this.widest.id)) return this.widest;
    return this.pick(g, m.games);
  }

  /** Game whose learners to list for a move; see Dex.learnGame(Game, Move). */
  async moveGame(g, mv) {
    if (g !== this.whole) return this.pick(g, this.learnGames);
    if ((await this.learners(this.widest, mv.id)).length) return this.widest;
    for (let i = this.learnGames.length - 1; i >= 0; i--) {
      const x = this.game(this.learnGames[i]);
      if (x.id === GO) continue;
      if (x.gen < mv.gen) break;
      if ((await this.learners(x, mv.id)).length) return x;
    }
    return this.widest;
  }

  pick(g, ids) {
    let pick = 0;
    for (const id of ids) if (id <= g.versionGroup && (id !== GO || g.id === GO)) pick = id;
    if (pick === 0 && ids.length) pick = ids[0];
    return pick === 0 ? null : this.game(pick);
  }

  async learnset(g, m) {
    const all = await this.learnsets(g.id);
    return all.get(m.num) ?? all.get(m.species) ?? null;
  }

  /** Rows of [species, method, level], by method, level, number. */
  async learners(g, id) {
    const out = [];
    for (const [num, c] of await this.learnsets(g.id)) {
      for (let i = 0; i < c[0].length; i += 2) {
        if (c[0][i + 1] === id) {
          out.push([num, 0, c[0][i]]);
          break;
        }
      }
      for (let k = 1; k < c.length; k++) for (const x of c[k]) if (x === id) out.push([num, k, 0]);
    }
    return out.sort((a, b) => a[1] - b[1] || a[2] - b[2] || a[0] - b[0]);
  }

  async learnsets(game) {
    const hit = this.learnCache.get(game);
    if (hit) return lru(this.learnCache, game, hit);
    const out = new Map();
    for (const f of rows((await this.fetchText(`learn/${game}.tsv`)) ?? '')) {
      out.set(+f[0], METHODS.map((_, k) => (k === 0 ? ints(f[1] ?? '', /[ :]/) : ints(f[k + 1] ?? '', ' '))));
    }
    return lru(this.learnCache, game, out);
  }

  /** [where to find, Pokédex entry] from pokemondb, both '' when the game has none. */
  async info(g, m) {
    const k = m.species + '\t';
    for (const line of ((await this.fetchText(`info/${g.versionGroup}.tsv`)) ?? '').split('\n')) {
      if (line.startsWith(k)) {
        const f = line.split('\t');
        return [f[1], f[2].replaceAll('\\n', '\n')];
      }
    }
    return ['', ''];
  }

  /** Types are AND, generations OR, `minBst` 0 for any; a number ("6", "#006") prefixes the shown dex number. */
  pokedex(g, source, text, types, gens, minBst, sort) {
    const q = fold(text);
    const num = /^\d+$/.test(q) ? q.replace(/^0+(?=\d)/, '') : null;
    const out = source.filter(e => {
      const m = e.mon;
      if (!(q === '' || m.key.includes(q) || (num !== null && String(e.number).startsWith(num)))) return false;
      if (gens.size && !gens.has(m.gen)) return false;
      if (m.bst < minBst) return false;
      const own = this.typesOf(g, m);
      return [...types].every(t => own.includes(t));
    });
    if (sort.label === 'Name') return out.sort((a, b) => (a.mon.key < b.mon.key ? -1 : a.mon.key > b.mon.key ? 1 : 0));
    return sort.stat ? out.sort((a, b) => sort.stat(b.mon) - sort.stat(a.mon)) : out;
  }

  /** Kind (English) to matches, at most `limit` each; prefix matches first. */
  search(g, text, limit) {
    const q = fold(text);
    if (!q) return [];
    const number = /^\d+$/.test(q);
    const n = number ? q.replace(/^0+(?=\d)/, '') : q;
    const out = [
      ['Types', number ? [] : best(this.typesIn(g), q, t => key(this.label(t), t), limit)],
      ['Pokémon', best(this.mons, n, m => (number ? String(m.species) : m.key), limit)],
      ['Moves', number ? [] : best(this.movesIn(g), q, m => m.key, limit)],
      ['Abilities', number ? [] : best(this.abilities, q, a => a.key, limit)],
      ['Items', number ? [] : best(this.items, q, i => i.key, limit)],
    ];
    return out.filter(([, list]) => list.length);
  }

  /** Name (or machine label, "tm12") contains the query; type and category sets are OR, empty means any. */
  static moves(source, text, types, categories, sort, label) {
    const q = fold(text);
    const out = source.filter(m => (q === '' || m.key.includes(q) || fold(label(m)).includes(q))
      && (!types.size || types.has(m.type)) && (!categories.size || categories.has(m.category)));
    const by = { Power: 'power', Accuracy: 'accuracy', PP: 'pp' }[sort];
    if (sort === 'Name') return out.sort((a, b) => (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));
    return by ? out.sort((a, b) => b[by] - a[by]) : out;
  }

  /** natures indices as the games' 5 x 5 chart: row raises STATS[r + 1], column lowers; neutral on the diagonal. */
  natureChart() {
    return [1, 2, 3, 4, 5].map(up => [1, 2, 3, 4, 5].map(down => this.natures.findIndex(n =>
      up === down ? n.en === NEUTRAL[down - 1] : n.up === STATS[up] && n.down === STATS[down])));
  }
}

function best(source, q, k, limit) {
  const starts = [];
  const contains = [];
  for (const t of source) {
    const s = k(t);
    if (s.startsWith(q)) starts.push(t);
    else if (s.includes(q)) contains.push(t);
  }
  return [...starts, ...contains].slice(0, limit);
}

function rows(text) {
  return text.split('\n').filter(l => l).map(l => l.split('\t'));
}

function push(map, k, v) {
  const list = map.get(k);
  if (list) list.push(v);
  else map.set(k, [v]);
}

/** Puts `k` last in insertion order and drops the oldest past two entries; returns `v`. */
function lru(map, k, v) {
  map.delete(k);
  map.set(k, v);
  if (map.size > 2) map.delete(map.keys().next().value);
  return v;
}

function height(dm, imperial) {
  if (!imperial) return decimal(dm / 10, 1) + ' m';
  const inches = Math.round(dm * 3.937);
  return `${div(inches, 12)}'${String(inches % 12).padStart(2, '0')}"`;
}

function weight(hg, imperial) {
  return imperial ? decimal(hg * 0.220462, 1) + ' lbs' : decimal(hg / 10, 1) + ' kg';
}

/** Up to six Pokémon for one game; stored in the same one-line text as the Android app's teams. */
class Team {
  static SIZE = 6;
  static MOVES = 4;
  static EV_STAT = 252;
  static EV_TOTAL = 510;
  static IV_MAX = 31;
  static LEGENDS = [[144, 146], [150, 151], [243, 245], [249, 251], [377, 386], [480, 494], [638, 649], [716, 721],
    [772, 773], [785, 809], [888, 898], [905, 905], [984, 995], [1001, 1010], [1014, 1017], [1020, 1025]];

  constructor(name, game) {
    this.name = name;
    this.game = game;
    this.members = [];
  }

  static member(num, ability, level) {
    return { num, ability, level, item: 0, nature: -1, tera: -1, evs: [0, 0, 0, 0, 0, 0], ivs: Array(6).fill(Team.IV_MAX), moves: [0, 0, 0, 0] };
  }

  static evRoom(x, stat) {
    return Math.min(Team.EV_STAT, Team.EV_TOTAL - x.evs.reduce((a, b) => a + b, 0) + x.evs[stat]);
  }

  static legend(species) {
    return Team.LEGENDS.some(([a, b]) => species >= a && species <= b);
  }

  format() {
    const members = this.members.map(m => [m.num, m.ability, m.item, m.nature, m.tera, m.level, ...m.evs, ...m.ivs, ...m.moves].join(','));
    return `${this.name.replace(/[\t\n]/g, ' ')}\t${this.game}\t${members.join(';')}`;
  }

  static parse(line) {
    const f = line.split('\t');
    const t = new Team(f[0], +f[1]);
    if (!f[2]) return t;
    for (const s of f[2].split(';')) {
      const v = s.split(',').map(Number);
      if (v.length !== 22 || v.some(Number.isNaN)) throw new Error('member has ' + v.length + ' fields: ' + s);
      const m = Team.member(v[0], v[1], v[5]);
      m.item = v[2];
      m.nature = v[3];
      m.tera = v[4];
      m.evs = v.slice(6, 12);
      m.ivs = v.slice(12, 18);
      m.moves = v.slice(18, 22);
      t.members.push(m);
    }
    return t;
  }

  static parseAll(text) {
    return text.split('\n').filter(l => l.trim()).map(Team.parse);
  }

  static formatAll(teams) {
    return teams.map(t => t.format()).join('\n');
  }

  /** Showdown text, in English whatever the app's language; no Ability line before Generation 3. */
  export(d) {
    const abilities = d.game(this.game).gen >= 3;
    const spread = (label, values, skip) => {
      const parts = values.map((v, i) => [v, i]).filter(([v]) => v !== skip).map(([v, i]) => `${v} ${STATS[i]}`);
      return parts.length ? `\n${label}: ` + parts.join(' / ') : '';
    };
    let b = '';
    for (const x of this.members) {
      b += d.mon(x.num).en;
      if (x.item) b += ' @ ' + d.item(x.item).en;
      if (abilities && x.ability) b += '\nAbility: ' + d.ability(x.ability).en;
      b += '\nLevel: ' + x.level;
      if (x.tera >= 0) b += '\nTera Type: ' + d.types[x.tera][0].toUpperCase() + d.types[x.tera].slice(1);
      b += spread('EVs', x.evs, 0);
      if (x.nature >= 0) b += '\n' + d.natures[x.nature].en + ' Nature';
      b += spread('IVs', x.ivs, Team.IV_MAX);
      for (const id of x.moves) if (id) b += '\n- ' + d.move(id).en;
      b += '\n\n';
    }
    return b.trim();
  }

  /** Per attacking type: [members it hits super effectively, members that resist or are immune]. Types only. */
  defense(d) {
    const g = d.game(this.game);
    return d.typesIn(g).map(t => {
      const row = [0, 0];
      for (const m of this.members) {
        const f = d.factor(g, t, d.typesOf(g, d.mon(m.num)));
        if (f > 100) row[0]++;
        else if (f < 100) row[1]++;
      }
      return row;
    });
  }

  offense(d) {
    const g = d.game(this.game);
    return d.typesIn(g).map(t => this.members.filter(m => m.moves.some(id => {
      if (!id) return false;
      const mv = d.move(g, id);
      return mv.category !== 'status' && d.factor(g, mv.type, [t]) > 100;
    })).length);
  }

  attacks(d) {
    return this.members.some(m => m.moves.some(id => id && d.move(id).category !== 'status'));
  }

  /** What the team lacks, as indices into the game's types; see Team.gaps. */
  gaps(d) {
    const g = d.game(this.game);
    const types = d.typesIn(g);
    const def = this.defense(d);
    const off = this.attacks(d) ? this.offense(d) : null;
    const idx = def.map((_, i) => i);
    const counter = idx.filter(i => off && off[i] === 0 && def[i][1] === 0);
    const stacked = idx.filter(i => !counter.includes(i) && def[i][0] * 2 >= this.members.length && def[i][0] > def[i][1]);
    const uncovered = idx.filter(i => !counter.includes(i) && off && off[i] === 0);
    const bestOf = (targets, good) => {
      let pick = -1;
      let most = 0;
      types.forEach((_, x) => {
        const n = targets.filter(t => good(x, t)).length;
        if (n > most) [pick, most] = [x, n];
      });
      return pick;
    };
    return {
      counter, stacked, uncovered,
      wall: bestOf([...counter, ...stacked], (w, t) => d.factor(g, types[t], [types[w]]) < 100),
      move: bestOf([...counter, ...uncovered], (a, t) => d.factor(g, types[a], [types[t]]) > 100),
    };
  }

  overlaps(d) {
    const g = d.game(this.game);
    const types = this.members.map(m => d.typesOf(g, d.mon(m.num)));
    const out = [];
    for (let a = 0; a < types.length; a++) for (let b = a + 1; b < types.length; b++) {
      const weak = Team.overlap(d, g, types[a], types[b]);
      if (weak) out.push({ a, b, weak });
    }
    return out;
  }

  /** Final-stage Pokémon of the game for slot `skip` (-1: a new one), best first, at most 30; see Team.suggest. */
  suggest(d, skip) {
    const g = d.game(this.game);
    const rest = new Team(this.name, this.game);
    rest.members = this.members.filter((_, i) => i !== skip);
    const gp = rest.gaps(d);
    const all = d.typesIn(g);
    const need = [...gp.counter, ...gp.stacked, ...gp.uncovered].map(i => all[i]);
    const have = rest.members.map(m => d.typesOf(g, d.mon(m.num)));
    const species = new Set(rest.members.map(m => d.mon(m.num).species));
    const out = [];
    for (const { mon: m } of g.dex) {
      if (d.evolutions(m.num).length || (isForm(m) && !d.evo(m.num)) || species.has(m.species) || Team.legend(m.species)) continue;
      const t = d.typesOf(g, m);
      if (have.some(h => Team.overlap(d, g, h, t))) continue;
      let score = 0;
      for (const x of need) {
        const f = d.factor(g, x, t);
        score += (f < 100 ? 1 : f > 100 ? -1 : 0) + (t.some(a => d.factor(g, a, [x]) > 100) ? 1 : 0);
      }
      out.push([m, score]);
    }
    return out.sort((a, b) => b[1] - a[1] || b[0].bst - a[0].bst).slice(0, 30).map(p => p[0]);
  }

  /** Gen 3+ formula; Shedinja has 1 HP. Gen 1-2 DVs, AVs and effort levels are not modeled. */
  static stats(d, x) {
    const m = d.mon(x.num);
    const n = x.nature < 0 ? null : d.natures[x.nature];
    return STATS.map((s, i) => {
      const core = div((2 * m.stats[i] + x.ivs[i] + div(x.evs[i], 4)) * x.level, 100);
      if (i === 0) return m.species === 292 ? 1 : core + x.level + 10;
      const boost = !n ? 100 : n.up === s ? 110 : n.down === s ? 90 : 100;
      return div((core + 5) * boost, 100);
    });
  }

  /** Lowest and highest roll, Gen 5+ formula, `factor` x100; at least 1 unless immune. */
  static damage(level, power, atk, def, stab, factor) {
    const base = div(div((div(2 * level, 5) + 2) * power * atk, def), 50) + 2;
    return [85, 100].map(roll => {
      let x = div(base * roll, 100);
      if (stab) x = div(x * 6144 + 2047, 4096);
      x = div(x * factor, 100);
      return factor > 0 ? Math.max(1, x) : 0;
    });
  }

  /** Attacking types (indices) both are weak to, if they double up: two or more, or one plus a shared type. */
  static overlap(d, g, a, b) {
    const types = d.typesIn(g);
    const weak = types.map((_, i) => i).filter(i => d.factor(g, types[i], a) > 100 && d.factor(g, types[i], b) > 100);
    return weak.length >= 2 || (weak.length && a.some(t => b.includes(t))) ? weak : null;
  }
}

if (typeof module !== 'undefined') module.exports = { L, Dex, Team, fold, format, best, height, weight, isForm, STATS, METHODS, ITEM_GROUPS, STARTERS, REGIONS, SORTS, MOVE_SORTS, WHOLE, GO };
