'use strict';
/**
 * The Android app's screens for the browser and the iPhone home screen, over dex.js. Pages stack like Activities and
 * mirror history, so the browser's back closes one; dialogs are <dialog> sheets. Teams and settings live in
 * localStorage under "dex.", teams in the Android app's one-line text format.
 */

let d = null;
/** Open screens, home first: { el, resume }; resume rebuilds a page when the one above it closes. */
const pages = [];
/** Rows added to a list at a time; the next batch loads as the end nears (see fill). */
const CHUNK = 60;
const BST = [0, 300, 400, 450, 500, 525, 550, 600];
const ROMAN = ['I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX'];
const BACK = 'M15 5l-7 7 7 7';
const NEXT = 'M9 5l7 7-7 7';

const TYPE_COLORS = {
  normal: 'A8A77A', fire: 'EE8130', water: '6390F0', electric: 'F7D02C', grass: '7AC74C', ice: '96D9D6',
  fighting: 'C22E28', poison: 'A33EA1', ground: 'E2BF65', flying: 'A98FF3', psychic: 'F95587', bug: 'A6B91A',
  rock: 'B6A136', ghost: '735797', dragon: '6F35FC', dark: '705746', steel: 'B7B7CE', fairy: 'D685AD',
  physical: 'C92112', special: '4F5870', status: '8C888C',
};
/** Version colors by game id, two for paired releases; see GamesActivity. */
const GAME_COLORS = {
  1: ['DA3B2F', '2E64C8'], 2: ['E0A800'], 3: ['C9A227', '8C96A3'], 4: ['3FA7C9'], 5: ['C0182E', '1F4FB5'], 6: ['1E9E5A'],
  7: ['F05A28', '4CA53C'], 8: ['5E7FD0', 'C97D9C'], 9: ['7E7E8E'], 10: ['C9971A', '8FA0B2'], 11: ['2B2B2B', '7D838C'],
  14: ['24476B', 'C0504D'], 15: ['2A6FDB', 'D3263E'], 16: ['B3122B', '1C54B2'], 17: ['F29B1D', '5B4FC4'], 18: ['E8641B', '6A2FA0'],
  19: ['E6B400', 'A0703D'], 20: ['1E9BD7', 'D62F6B'], 23: ['4C7BD9', 'D07FA6'], 24: ['3F7C84'], 25: ['D4302B', '7D3CB5'],
  30: ['2E9E62'], 32: ['33475B'], 100: ['2A75BB', '1FA59A'], 999: ['D03A3A', '33475B'],
};
const MASCOTS = {
  1: [6, 9], 2: [25], 3: [250, 249], 4: [245], 5: [383, 382], 6: [384], 7: [6, 3], 8: [483, 484], 9: [487], 10: [250, 249],
  11: [643, 644], 14: [646], 15: [716, 717], 16: [383, 382], 17: [791, 792], 18: [800], 19: [25, 133], 20: [888, 889],
  23: [483, 484], 24: [493], 25: [1007, 1008], 30: [718], 100: [25, 808], 999: [151, 493],
};

// --- storage ---------------------------------------------------------------------------------

const store = {
  get(k, fallback) {
    try {
      const v = localStorage.getItem('dex.' + k);
      return v === null ? fallback : JSON.parse(v);
    } catch (e) {
      return fallback;
    }
  },
  set(k, v) {
    try {
      localStorage.setItem('dex.' + k, JSON.stringify(v));
    } catch (e) {
      toast(L.t("Could not save on this device."));
    }
  },
  remove(k) {
    try {
      localStorage.removeItem('dex.' + k);
    } catch (e) {
      // storage blocked: nothing was saved under it either
    }
  },
};

/**
 * Workspaces keep playthroughs apart, as Ui.space: each has its own caught marks, teams and selected game. "spaces"
 * holds [id, name] per workspace; workspace 0 uses the keys from before workspaces existed.
 */
const spaceId = () => store.get('space', 0);
const spaceKey = (base, id = spaceId()) => (id === 0 ? base : base + '.' + id);
const spaces = () => store.get('spaces', null) ?? [[0, L.t("Main")]];
const spaceName = () => spaces().find(s => s[0] === spaceId())?.[1] ?? '';

const game = () => d.game(store.get(spaceKey('game'), 25));

/** Numbers marked caught in this workspace: about 8 KB for all 1300 entries, read fresh by each screen. */
const caughtSet = () => new Set(store.get(spaceKey('caught'), []));

function setCaught(num, on) {
  const s = caughtSet();
  if (on) s.add(num);
  else s.delete(num);
  store.set(spaceKey('caught'), [...s]);
}

/** About 3 KB for 20 full teams, read fresh on every screen that shows them. */
function loadTeams() {
  const teams = Team.parseAll(store.get(spaceKey('teams'), ''));
  for (const t of teams) for (const x of t.members) x.num = d.known(x.num);
  return teams;
}

const saveTeams = teams => store.set(spaceKey('teams'), Team.formatAll(teams));
const newMember = m => Team.member(m.num, m.abilities.length ? m.abilities[0] : m.hidden, 50);

// --- colors, as Ui.kt ------------------------------------------------------------------------

const rgb = s => [0, 2, 4].map(i => parseInt(s.slice(i, i + 2), 16));
const css = c => '#' + c.map(v => v.toString(16).padStart(2, '0')).join('');
const color = t => rgb(TYPE_COLORS[t] ?? '888888');
const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t));
const solid = c => `linear-gradient(${css(c)}, ${css(c)})`;

function lum(c) {
  const [r, g, b] = c.map(v => {
    const x = v / 255;
    return x <= 0.04045 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** Darkened until white text reads on it. */
function deep(c) {
  let x = c;
  for (let f = 1; lum(x) > 0.18 && f > 0;) {
    f -= 0.04;
    x = c.map(v => Math.round(v * f));
  }
  return x;
}

function lighten(c) {
  let x = c;
  for (let f = 0; lum(x) < 0.3 && f < 1;) {
    f += 0.05;
    x = mix(c, [255, 255, 255], f);
  }
  return x;
}

const on = c => (lum(c) > 0.2 ? '#15171A' : '#fff');
const tier = v => (v < 50 ? '#F34444' : v < 80 ? '#FF7F0F' : v < 100 ? '#FFC107' : v < 120 ? '#A0E515' : '#23CD5E');
const effect = f => ({ 0: 'x0', 25: 'x1/4', 50: 'x1/2', 100: 'x1', 200: 'x2', 400: 'x4' })[f] ?? 'x' + f / 100;
const national = n => '#' + String(n).padStart(4, '0');

/** Text in `c`, dark enough by day and light enough by night. */
function ink(el, c) {
  el.classList.add('ink');
  el.style.setProperty('--ink-d', css(deep(c)));
  el.style.setProperty('--ink-n', css(lighten(c)));
  return el;
}

function tonal(el, c) {
  el.classList.add('tonal');
  el.style.setProperty('--c', css(c));
  return el;
}

// --- elements --------------------------------------------------------------------------------

/** createElement with a class string or props (class, style, on*, attributes); null, false and '' children skipped. */
function h(tag, props, ...kids) {
  const e = document.createElement(tag);
  if (typeof props === 'string') e.className = props;
  else if (props) {
    for (const [k, v] of Object.entries(props)) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') e.className = v;
      else if (k === 'style') e.style.cssText = v;
      else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
      else e.setAttribute(k, v === true ? '' : v);
    }
  }
  for (const k of kids.flat(Infinity)) if (k !== null && k !== undefined && k !== false && k !== '') e.append(k);
  return e;
}

function icon(path, size) {
  const t = document.createElement('template');
  t.innerHTML = `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${path}"/></svg>`;
  return t.content.firstChild;
}

/** Asset folder per artwork style (Settings: HOME, official, pixel), as in Ui.kt. */
const ART_DIRS = ['sprites', 'art', 'pixel'];
const SHINY_DIRS = ['shiny', 'shiny-art', 'shiny-pixel'];
const LOOK_DIRS = ['looks', 'looks-art', 'looks-pixel'];
const SHINY_LOOK_DIRS = ['looks-shiny', 'looks-shiny-art', 'looks-shiny-pixel'];

/**
 * In the Settings artwork style unless `style` is given. A form without its own picture shows its species', a style
 * without one (or one not downloaded yet, offline) the HOME render. Pixel sprites scale unfiltered.
 */
function sprite(num, cls, shiny = false, style = store.get('art', 0)) {
  const dirs = shiny ? SHINY_DIRS : ART_DIRS;
  const s = d.monOrNull(num)?.species ?? num;
  return picture([...new Set([style, 0])].flatMap(k => [...new Set([num, s])].map(n => `assets/${dirs[k]}/${n}.webp`)), cls);
}

/** Look `file` of looks.tsv in the Settings artwork style, else its HOME render. */
function look(file, cls, shiny = false) {
  const dirs = shiny ? SHINY_LOOK_DIRS : LOOK_DIRS;
  return picture([...new Set([store.get('art', 0), 0])].map(k => `assets/${dirs[k]}/${file}.webp`), cls);
}

/** The first of `tries` that loads; hidden when none does. */
function picture(tries, cls) {
  const img = h('img', { class: cls, alt: '', loading: 'lazy', decoding: 'async' });
  let i = 0;
  const load = () => {
    img.classList.toggle('pixel', tries[i].includes('pixel/'));
    img.src = tries[i];
  };
  img.onerror = () => {
    if (++i < tries.length) load();
    else img.style.visibility = 'hidden';
  };
  load();
  return img;
}

/** The shiny mark: a large and a small four-pointed star, as Ui.Sparkle. */
function sparkle() {
  const t = document.createElement('template');
  t.innerHTML = '<svg viewBox="0 0 20 20" width="20" height="20" fill="currentColor" aria-hidden="true"><path d="M8 4Q8 12 16 12Q8 12 8 20Q8 12 0 12Q8 12 8 4ZM16 0Q16 4 20 4Q16 4 16 8Q16 4 12 4Q16 4 16 0Z"/></svg>';
  return t.content.firstChild;
}

/** Asks the service worker to keep the picked artwork style for offline use; the HOME renders always are. */
function keepArt() {
  const k = store.get('art', 0);
  if (k) navigator.serviceWorker?.ready.then(r => [ART_DIRS[k], LOOK_DIRS[k]].forEach(art => r.active?.postMessage({ art })));
}

function itemIcon(id) {
  const img = h('img', { src: `assets/items/${id}.webp`, alt: '', loading: 'lazy', decoding: 'async' });
  img.onerror = () => { img.style.visibility = 'hidden'; };
  return img;
}

const chip = (label, act, cls) => h('button', { class: cls ? 'chip ' + cls : 'chip', onclick: act }, label);
const setChip = (c, label, active) => {
  c.textContent = label;
  c.classList.toggle('on', active);
};
const panel = (title, cls = 'wide mb12') => h('section', 'panel ' + cls, title ? h('h2', null, title) : null);
const backButton = () => h('button', { class: 'back', 'aria-label': L.t("Back"), onclick: close }, icon(BACK, 22));
const head = (title, ...extra) => h('div', 'head', backButton(), h('h1', 'title', title), extra);

/** Type label style picked in Settings, as Ui.labels: 0 name, 1 glyph and name, 2 pixel badge, 3 glyph alone. */
const LABEL_STYLES = [0, 1, 3, 2];
/** The 18 types with a glyph and a pixel badge in assets/types; Stellar and move categories have neither. */
const TYPE_ART = new Set(Object.keys(TYPE_COLORS).slice(0, 18));

/** Type `t`'s label into pill `el` in `style`. The glyph is a mask over the text color, so it takes the pill's ink. */
function typeLabel(el, t, style = store.get('labels', 0)) {
  const name = d.label(t);
  if (!style || !TYPE_ART.has(t)) return el.append(name);
  el.setAttribute('aria-label', name);
  if (style === 2) {
    el.classList.add('px');
    el.style.background = 'none';
    return el.append(h('img', { class: 'pixel', src: `assets/types/pixel/${t}.png`, alt: '' }));
  }
  const url = `url(assets/types/icon/${t}.png)`;
  el.classList.add('ic');
  el.classList.toggle('only', style === 3);
  el.append(h('i', { class: 'glyph', style: `-webkit-mask-image:${url};mask-image:${url}` }));
  if (style !== 3) el.append(h('span', null, name));
}

function pill(t, tag = 'span', style) {
  const c = color(t);
  const el = h(tag, { class: 'pill', style: `background:${css(c)};color:${on(c)}` });
  typeLabel(el, t, style);
  return el;
}

function glass(t) {
  const el = h('button', { class: 'pill glass big', onclick: () => openRef(t) });
  typeLabel(el, t);
  return el;
}

/** `perRow` pills a line, the last line padded with blanks so all share one width. */
function typePills(types, perRow) {
  const box = h('div', 'pills');
  for (let i = 0; i < types.length; i += perRow) {
    const line = h('div');
    for (let k = i; k < i + perRow; k++) line.append(k < types.length ? pill(types[k]) : h('span', 'pill blank'));
    box.append(line);
  }
  return box;
}

/** Background with the Poké Ball outline over flat `c`. */
function hero(c) {
  const el = h('div', 'hero ball', backButton());
  el.style.setProperty('--g', solid(c));
  return el;
}

function seg(labels, current, picked) {
  const buttons = labels.map((label, i) => h('button', {
    class: i === current ? 'on' : null, 'aria-pressed': String(i === current),
    onclick: () => {
      buttons.forEach((b, k) => {
        b.classList.toggle('on', k === i);
        b.setAttribute('aria-pressed', String(k === i));
      });
      picked(i);
    },
  }, label));
  return h('div', 'seg', buttons);
}

function searchBox(hint, changed) {
  const input = h('input', { type: 'search', placeholder: hint, 'aria-label': hint, autocomplete: 'off', autocorrect: 'off', autocapitalize: 'off', spellcheck: 'false', enterkeyhint: 'search' });
  const x = h('button', { class: 'x', type: 'button', 'aria-label': L.t("Clear"), hidden: true }, icon('M6 6l12 12M18 6L6 18', 22));
  const el = h('div', 'search', icon('M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14M16 16l5 5', 22), input, x);
  const sync = () => {
    el.classList.toggle('filled', input.value !== '');
    x.hidden = input.value === '';
    changed(input.value);
  };
  input.oninput = sync;
  input.onkeydown = e => { if (e.key === 'Enter') input.blur(); };
  x.onclick = () => {
    input.value = '';
    sync();
    input.focus();
  };
  return { el, input };
}

/** Number box: empty counts as `min` until it loses focus; more than 3 digits is `max()`. */
function numberBox(value, min, max, changed, label) {
  const e = h('input', { class: 'num tnum', type: 'text', inputmode: 'numeric', pattern: '[0-9]*', value: String(value), 'aria-label': label });
  e.oninput = () => {
    const digits = e.value.replace(/\D/g, '');
    const v = digits === '' ? min : digits.length > 3 ? max() : Math.min(max(), Math.max(min, +digits));
    if (digits !== '' && String(v) !== e.value) e.value = String(v);
    changed(v);
  };
  e.onfocus = () => e.select();
  e.onblur = () => { if (e.value === '') e.value = String(min); };
  return e;
}

/** A hold of 500 ms runs `act`; the click that ends the hold is dropped. */
function onHold(el, act) {
  let timer = 0;
  let held = false;
  let x = 0;
  let y = 0;
  const stop = () => clearTimeout(timer);
  el.addEventListener('pointerdown', e => {
    held = false;
    x = e.clientX;
    y = e.clientY;
    timer = setTimeout(() => {
      held = true;
      act();
    }, 500);
  });
  el.addEventListener('pointerup', stop);
  el.addEventListener('pointercancel', stop);
  el.addEventListener('pointermove', e => { if (Math.abs(e.clientX - x) + Math.abs(e.clientY - y) > 10) stop(); });
  el.addEventListener('click', e => {
    if (!held) return;
    held = false;
    e.stopImmediatePropagation();
  }, true);
  el.addEventListener('contextmenu', e => e.preventDefault());
}

let toastTimer = 0;
function toast(s) {
  document.querySelector('.toast')?.remove();
  clearTimeout(toastTimer);
  const t = h('div', { class: 'toast', role: 'status' }, s);
  document.body.append(t);
  toastTimer = setTimeout(() => t.remove(), 2000);
}

// --- list rows, as Ui.Item -------------------------------------------------------------------

const entry = (sprite, badge, title, sub, pills, ref) => ({ sprite, badge, title, sub, pills, ref });
const header = title => entry(0, '', title, '', [], null);
const monEntry = (m, types, badge, sub) => entry(m.num, badge, m.name, sub, types, m);
const moveEntry = (m, badge) => entry(0, badge, m.name, L.f("Pow %s  ·  Acc %s  ·  PP %d", m.power || '-', m.accuracy || '-', m.pp), [m.type, m.category], m);
const isMon = ref => !!ref && ref.species !== undefined;
const nums = items => [...new Set(items.filter(it => isMon(it.ref)).map(it => it.ref.num))];
const byKey = (a, b) => (a.key < b.key ? -1 : a.key > b.key ? 1 : 0);

function row(it, tap) {
  if (it.ref === null) return h('div', 'hdr caption', it.title);
  const item = !!it.ref && it.ref.group !== undefined;
  const art = it.sprite !== 0 || item;
  const r = h('button', { class: 'row', onclick: tap });
  if (art) {
    const a = h('div', item ? 'art item' : 'art', item ? itemIcon(it.ref.id) : sprite(it.sprite));
    a.style.setProperty('--c', css(color(it.pills[0] ?? '')));
    r.append(a);
  } else if (it.badge) r.append(h('div', 'box', it.badge));
  r.append(h('div', 'texts', art && it.badge ? h('div', 'caption', it.badge) : null, h('div', 't', it.title), it.sub ? h('div', 's', it.sub) : null));
  if (it.pills.length) r.append(h('div', 'pl', it.pills.map(p => pill(p))));
  return r;
}

function card(it, tap) {
  const el = h('button', { class: 'card ball', onclick: tap },
    h('div', 'name', it.title), h('span', 'num', it.badge),
    h('div', 'types', it.pills.map(t => h('span', 'pill glass', d.label(t)))),
    it.sub ? h('div', 's', it.sub) : null, sprite(it.sprite));
  el.style.setProperty('--g', solid(deep(color(it.pills[0]))));
  return el;
}

/**
 * `items` as rows, CHUNK at a time: the batch after the last loads when a marker past it nears `scroller`'s view.
 * `keep` starts with as many rows as `box` had, so a page returned to stays where it was scrolled.
 */
function fill(box, items, make, scroller, keep = false) {
  box._more?.disconnect();
  const had = keep ? box._n ?? 0 : 0;
  box.replaceChildren();
  let n = 0;
  const end = h('div');
  const add = (count = CHUNK) => {
    end.remove();
    const part = document.createDocumentFragment();
    for (const it of items.slice(n, n + count)) part.append(make(it));
    n += count;
    box._n = n;
    box.append(part);
    if (n >= items.length) return;
    box.append(end);
    // observe() reports the current state, so a marker still in view after this batch loads the next one.
    box._more.unobserve(end);
    box._more.observe(end);
  };
  box._more = new IntersectionObserver(es => { if (es.some(e => e.isIntersecting)) add(); }, { root: scroller, rootMargin: '800px' });
  add(Math.max(CHUNK, had));
}

function itemSections(items, make) {
  const out = [];
  for (const g of ITEM_GROUPS) {
    const some = items.filter(i => i.group === g).sort(byKey);
    if (some.length) out.push(header(L.t(g)), ...some.map(make));
  }
  return out;
}

// --- pages -----------------------------------------------------------------------------------

function mount(build) {
  const p = { el: h('div', pages.length ? 'page in' : 'page'), resume: null };
  pages.at(-1)?.el.classList.add('under');
  pages.push(p);
  document.body.append(p.el);
  build(p);
  return p;
}

function open(build) {
  history.pushState({ depth: pages.length }, '');
  mount(build);
}

function close() {
  history.back();
}

addEventListener('popstate', e => {
  for (const s of document.querySelectorAll('dialog[open]')) s.close();
  const depth = e.state?.depth ?? 0;
  if (depth >= pages.length - 1) return;
  while (pages.length - 1 > depth) pages.pop().el.remove();
  const top = pages.at(-1);
  top.el.classList.remove('under', 'in');
  top.resume?.();
});

document.addEventListener('keydown', e => {
  if ((e.key === 'Enter' || e.key === ' ') && e.target.getAttribute?.('role') === 'button') {
    e.preventDefault();
    e.target.click();
  }
});

function openRef(ref) {
  if (typeof ref === 'string') open(p => pokedex(p, ref));
  else if (isMon(ref)) openMon(ref.num, null);
  else if (ref?.category !== undefined) open(p => moveScreen(p, ref.id));
  else if (ref?.shortEffect !== undefined) open(p => abilityScreen(p, ref.id));
}

const openMon = (num, list) => open(p => monScreen(p, num, list));

// --- sheets ----------------------------------------------------------------------------------

/** Heading, body, buttons at the end; a tap outside closes it. Removed from the page once closed. */
function sheet(heading) {
  const dlg = h('dialog', 'sheet');
  const title = h('h2', { tabindex: '-1' }, heading);
  const body = h('div', 'in');
  const btns = h('div', 'btns');
  dlg.append(title, body);
  dlg.addEventListener('click', e => { if (e.target === dlg) dlg.close(); });
  dlg.addEventListener('close', () => dlg.remove());
  const s = {
    body,
    /** false when already closed, so a quick second tap does not act twice. */
    close() {
      if (!dlg.open) return false;
      dlg.close();
      return true;
    },
    button(label, main, act, keep) {
      if (!btns.isConnected) dlg.append(btns);
      btns.append(h('button', { class: main ? 'chip on' : 'chip', onclick: () => { if (keep || s.close()) act?.(); } }, label));
      return s;
    },
    show() {
      // The heading takes focus unless a field asks for it, so the keyboard does not cover a list.
      if (!body.querySelector('[autofocus]')) title.setAttribute('autofocus', '');
      document.body.append(dlg);
      dlg.showModal();
      return s;
    },
  };
  return s;
}

function pickOne(heading, labels, current, picked) {
  const s = sheet(heading);
  labels.forEach((label, i) => s.body.append(h('button', { class: i === current ? 'opt on' : 'opt', onclick: () => { if (s.close()) picked(i); } }, label)));
  return s.show();
}

/** Type pills three to a line in `into`; `tapped` gets the index and the pill. */
function typeGrid(into, keys, tapped) {
  into.classList.add('pick');
  const box = h('div', 'pills');
  into.append(box);
  let line = null;
  const out = keys.map((k, i) => {
    if (i % 3 === 0) box.append(line = h('div'));
    const b = pill(k, 'button');
    b.onclick = () => tapped(i, b);
    line.append(b);
    return b;
  });
  for (let k = keys.length % 3; k && k < 3; k++) line.append(h('span', 'pill blank'));
  return out;
}

/** `changed` runs on every tap, so the list behind updates live. */
function pickMany(heading, keys, selected, changed) {
  const s = sheet(heading);
  const pills = typeGrid(s.body, keys, (i, b) => {
    if (!selected.delete(keys[i])) selected.add(keys[i]);
    b.classList.toggle('on', selected.has(keys[i]));
    changed();
  });
  pills.forEach((b, i) => b.classList.toggle('on', selected.has(keys[i])));
  s.button(L.t("Clear"), false, () => {
    selected.clear();
    pills.forEach(b => b.classList.remove('on'));
    changed();
  }, true);
  s.button(L.t("Done"), true).show();
}

function pickType(heading, keys, current, picked) {
  const s = sheet(heading);
  const pills = typeGrid(s.body, keys, i => { if (s.close()) picked(i); });
  if (current >= 0) pills[current].classList.add('on');
  s.show();
}

function askName(heading, start, done) {
  const s = sheet(heading);
  const e = h('input', { class: 'field', value: start, autocapitalize: 'words', enterkeyhint: 'done', autofocus: true, 'aria-label': heading });
  const save = () => {
    const t = e.value.trim();
    if (t && s.close()) done(t);
  };
  e.onkeydown = ev => { if (ev.key === 'Enter') save(); };
  s.body.append(e);
  s.button(L.t("Cancel")).button(L.t("Save"), true, save, true).show();
  e.select();
}

function confirm(heading, message, action, yes) {
  const s = sheet(heading);
  s.body.append(h('p', { class: 'muted', style: 'font-size:15px;padding:0 4px' }, message));
  s.button(L.t("Cancel")).button(action, true, yes).show();
}

/** A search over long lists, in tabs when there are two; rows match on their shown or English name. */
function pickSearch(heading, tabs, lists, picked) {
  const s = sheet(heading);
  let tab = 0;
  const list = h('div', 'list');
  const filter = () => {
    const q = fold(box.input.value);
    let items = lists[tab];
    if (q) {
      // A list may repeat an entry in a section of suggestions on top.
      const seen = new Set();
      items = items.filter(it => it.ref !== null && (it.ref?.key ?? fold(it.title)).includes(q) && !seen.has(it.ref) && seen.add(it.ref));
    }
    list.scrollTop = 0;
    fill(list, items, it => row(it, () => { if (s.close()) picked(it.ref); }), list);
  };
  const box = searchBox(L.t("Search"), filter);
  box.el.classList.add('mb8');
  if (tabs.length > 1) s.body.append(seg(tabs, 0, k => { tab = k; filter(); }), h('div', 'mb8'));
  s.body.append(box.el, list);
  s.button(L.t("Cancel")).show();
  filter();
}

/** Every saved team's members under the team's name, or null without teams. */
function teamsTab(g) {
  const teams = loadTeams();
  if (!teams.length) return null;
  return [L.t("Teams"), teams.flatMap(t => [header(t.name), ...t.members.map(x => {
    const m = d.mon(x.num);
    return entry(m.num, L.f("Lv %d", x.level), m.name, '', d.typesOf(g, m), x);
  })])];
}

const teamMonEntry = (g, m) => monEntry(m, d.typesOf(g, m), g.byNum.has(m.num) ? '#' + String(g.byNum.get(m.num).number).padStart(3, '0') : '', '');

/** A Pokémon of `g` in tabs: `first`, then the caught ones (Box), then All; `picked` gets a team member, new unless one was tapped. */
function pickMon(g, heading, first, picked) {
  const all = g.dex.map(e => teamMonEntry(g, e.mon));
  const have = caughtSet();
  const tabs = [...(first ? [first] : []), [L.t("Box"), all.filter(it => have.has(it.ref.num))], [L.t("All"), all]];
  pickSearch(heading, tabs.map(t => t[0]), tabs.map(t => t[1]), ref => picked(ref.evs ? ref : newMember(ref)));
}

// --- home ------------------------------------------------------------------------------------

/** The width where style.css's wide layout starts. */
const WIDE = matchMedia('(min-width: 900px)');

/** Set when a new build's service worker waits; the home screen then offers it. */
let waiting = null;
let updating = false;

function home(p) {
  p.el.classList.add('fixed');
  const results = h('div', 'rows');
  const box = h('div', 'homebox');
  const dex = h('div');
  const below = h('div', 'homedex', h('div', 'hdr caption', L.t("Pokédex")), dex);
  const search = searchBox(L.t("Pokémon, moves, abilities, types"), q => find(q));
  search.el.classList.add('wide', 'mb10');
  p.el.append(h('div', 'col broad', h('div', 'head home', h('h1', 'title', 'DexSearch'), chip(L.t("Settings"), () => open(settings))), search.el, results, box, below));
  p.resume = () => {
    render();
    find(search.input.value);
  };
  // A property, not a listener: the home a language change mounts replaces the old one's.
  WIDE.onchange = listDex;
  render();

  function find(q) {
    box.style.display = below.style.display = q.trim() ? 'none' : '';
    if (!q.trim()) return results.replaceChildren();
    const g = game();
    const items = [];
    for (const [kind, list] of d.search(g, q, 20)) {
      items.push(header(L.t(kind)));
      for (const o of list) items.push(found(g, kind, o));
    }
    if (!items.length) return results.replaceChildren(h('p', 'empty', L.t("Nothing found")));
    const order = nums(items);
    fill(results, items, it => row(it, () => (isMon(it.ref) ? openMon(it.ref.num, order) : openRef(it.ref))), p.el);
  }

  function render() {
    const g = game();
    const c = color('grass');
    box.replaceChildren();
    if (waiting) {
      box.append(tonal(h('button', { class: 'update', onclick: update }, ink(h('span', null, L.t("Update available")), c), ink(icon(NEXT, 16), c)), c));
    }
    if (installable()) {
      const w = color('water');
      box.append(tonal(h('button', { class: 'update', onclick: () => install(render) },
        ink(h('span', null, L.t("Install DexSearch on this iPhone")), w), ink(icon(NEXT, 16), w)), w));
    }
    const species = g.dex.filter(e => !isForm(e.mon));
    const have = caughtSet();
    const n = species.filter(e => have.has(e.mon.num)).length;
    const progress = h('div', 'progress', h('div', { class: 'track' }, h('i', { style: `width:${(n * 100) / Math.max(species.length, 1)}%` })),
      h('div', null, L.f("%d of %d caught", n, species.length)));
    const pill = h('button', { class: 'pill glass tag more', 'aria-label': L.f("Workspace: %s", spaceName()), onclick: e => {
      e.stopPropagation();
      workspaces();
    } }, h('span', null, spaceName()), icon(NEXT, 12));
    const banner = gameCard(g, L.t("Change game"), () => open(games), progress, pill);
    banner.classList.add('grow');
    banner.onclick = () => open(pokedex);
    const tms = d.tmGame(g);
    const tile = (name, type, value, act) => tonal(h('button', { class: 'tile', onclick: act }, h('b', null, name), value(color(type))), color(type));
    const count = n => c2 => number(String(n), c2);
    const words = s => () => h('span', null, s);
    box.append(h('div', 'halves', banner, h('div', 'side', teamCard(),
      h('div', 'tiles grow',
        tile(L.t("Moves"), 'fire', count(d.movesIn(g).length), () => open(q => moves(q, false))),
        tms.virtual ? tile(L.t("TMs"), 'water', words(L.t("Pick a game")), () => open(games))
          : tile(L.t("TMs"), 'water', tms.tms.size ? count(tms.tms.size) : words(L.t("None here")), () => open(q => moves(q, true))),
        tile(L.t("Abilities"), 'psychic', count(d.abilities.length), () => open(abilities))),
      h('div', 'tiles grow',
        tile(L.t("Items"), 'electric', count(d.items.length), () => open(items)),
        tile(L.t("Type chart"), 'dragon', count(d.typesIn(g).length), () => open(typeChart)),
        tile(L.t("Natures"), 'ground', count(d.natures.length), () => open(natures))))));
    listDex();
  }

  /** On a computer the game's Pokédex fills the height below the cards; a phone's home has no room for it. */
  function listDex() {
    if (!WIDE.matches) {
      dex._more?.disconnect();
      return dex.replaceChildren();
    }
    const g = game();
    const nationalDex = g.virtual || store.get('national', false);
    const have = caughtSet();
    const shown = dexSource(g, nationalDex).map(e => dexEntry(g, e, nationalDex, have));
    const order = nums(shown);
    const cards = store.get('layout', 0) === 0;
    dex.className = cards ? 'cards' : 'rows';
    fill(dex, shown, it => (cards ? card : row)(it, () => openMon(it.ref.num, order)), p.el, true);
  }

  /** Tap one to switch to it. New starts one in the current game and opens the game picker; Delete asks first. */
  function workspaces() {
    const all = spaces();
    const k = all.findIndex(s => s[0] === spaceId());
    const s = sheet(L.t("Workspaces"));
    all.forEach((w, i) => s.body.append(h('button', { class: i === k ? 'opt on' : 'opt', onclick: () => {
      if (!s.close()) return;
      store.set('space', w[0]);
      p.resume();
    } }, w[1])));
    if (all.length > 1) s.button(L.t("Delete"), false, () => confirm(L.f("Delete %s?", all[k][1]),
      L.t("Its caught marks, teams and selected game are removed from this device. This cannot be undone."), L.t("Delete"), () => {
        const id = all[k][0];
        const rest = all.filter(w => w[0] !== id);
        for (const base of ['caught', 'teams', 'game']) store.remove(spaceKey(base, id));
        store.set('spaces', rest);
        store.set('space', rest[0][0]);
        p.resume();
      }));
    s.button(L.t("Rename"), false, () => askName(L.t("Workspace name"), all[k][1], name => {
      store.set('spaces', all.map(w => (w[0] === all[k][0] ? [w[0], name] : w)));
      p.resume();
    }));
    s.button(L.t("New"), true, () => askName(L.t("New workspace"), '', name => {
      const id = Math.max(...all.map(w => w[0])) + 1;
      store.set('spaces', [...all, [id, name]]);
      store.set(spaceKey('game', id), game().id);
      store.set('space', id);
      open(games);
    }));
    s.show();
  }
}

/**
 * Safari on an iPhone or iPad, not opened from the home screen icon. iPadOS reports a Mac, told apart by its touch
 * screen.
 */
const installable = () => !navigator.standalone && !store.get('hideInstall', false)
  && (/iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1));

/** Safari's steps to put the web app on the home screen; "Do not show again" hides the card and runs `hidden`. */
function install(hidden) {
  const s = sheet(L.t("Install DexSearch"));
  s.body.append(h('ol', 'steps',
    h('li', null, L.t("Tap Share: the square with an arrow, in Safari’s bottom bar or under ⋯.")),
    h('li', null, L.t("Tap “Add to Home Screen”. Not in the list? Scroll down or tap More.")),
    h('li', null, L.t("Tap Add, then open DexSearch from its new icon. It works offline from then on."))));
  s.button(L.t("Do not show again"), false, () => {
    store.set('hideInstall', true);
    hidden();
  });
  s.button(L.t("Done"), true).show();
}

function found(g, kind, o) {
  if (kind === 'Pokémon') {
    const e = g.byNum.get(o.num);
    return monEntry(o, d.typesOf(g, o), national(o.species), o.genus + (e && !g.virtual ? '  ·  ' + e.dex + ' #' + e.number : ''));
  }
  if (kind === 'Moves') return moveEntry(o, d.tmGame(g).tms.get(o.id) ?? '');
  if (kind === 'Abilities') return entry(0, '', o.name, o.shortEffect, [], o);
  if (kind === 'Items') return entry(0, '', o.name, o.effect, [], o);
  return entry(0, '', d.label(o), L.t("Pokémon of this type"), [o], o);
}

/** `s` with its first number large, in `c`'s ink. */
function number(s, c) {
  const m = /\d+/.exec(s);
  if (!m) return h('span', null, s);
  return h('span', null, s.slice(0, m.index), ink(h('span', 'big', m[0]), c), s.slice(m.index + m[0].length));
}

/**
 * Version-colored card: name, regional dexes, species count, `below`, box-art Pokémon and `tag`, a button of its own
 * with `onTag`, with `beside` next to it. A generation's Pokédex takes its first game's colors and its three starters.
 */
function gameCard(g, tag, onTag, below, beside) {
  const base = g.id > WHOLE ? d.games.find(x => x.gen === g.gen) ?? g : g;
  const colors = GAME_COLORS[base.id] ?? ['555555'];
  const starter = g.id > WHOLE ? g.dex.find(e => !isForm(e.mon)).mon.num : 0;
  const mascots = starter ? [starter, starter + 3, starter + 6] : MASCOTS[g.id] ?? [];
  const dexes = [...new Set(g.dex.map(e => e.dex))].join(', ');
  const count = g.dex.filter(e => !isForm(e.mon)).length;
  const label = tag ? h(onTag ? 'button' : 'span', { class: 'pill glass tag', onclick: onTag && (e => { e.stopPropagation(); onTag(); }) }, tag) : null;
  const c = h('div', { class: 'gamecard ball', role: 'button', tabindex: '0' },
    h('div', { class: 'texts', style: `--n:${mascots.length}` }, h('h2', null, g.name), h('p', null, dexes + '\n' + L.f("%d Pokémon", count)),
      below, beside ? h('div', 'tags', label, beside) : label),
    h('div', { class: 'art', 'aria-hidden': 'true' }, mascots.map(n => sprite(n))));
  c.style.setProperty('--g', `linear-gradient(to bottom right, ${css(deep(rgb(colors[0])))}, ${css(deep(rgb(colors.at(-1))))})`);
  return c;
}

/** Team count and the newest team's members; below, Compare and Damage calc. */
function teamCard() {
  const teams = loadTeams();
  const c = color('fighting');
  const team = teams.findLast(t => t.members.length);
  const line = teams.length ? number(L.f("Teams: %d", teams.length), c) : h('span', null, L.t("Plan a team"));
  const stop = act => e => {
    e.stopPropagation();
    act();
  };
  return tonal(h('div', { class: 'teamcard', role: 'button', tabindex: '0', onclick: () => open(teamsScreen) },
    h('div', 'top',
      h('div', null, h('div', { class: 'bold', style: 'font-size:15px' }, L.t("Team builder")), h('div', { style: 'font-size:14px;margin-top:2px' }, line)),
      h('div', { class: 'art', 'aria-hidden': 'true' }, team ? team.members.map(x => sprite(x.num)) : null)),
    h('div', 'wrap mt10',
      chip(L.t("Compare"), stop(() => open(q => compare(q, 0, game().id)))),
      chip(L.t("Damage calc"), stop(() => open(q => calc(q, -1, -1)))))), c);
}

function games(p) {
  const current = game();
  const col = h('div', 'col', head(L.t("Choose a game")));
  const add = g => {
    const c = gameCard(g, g === current ? L.t("Selected") : null);
    c.classList.add('wide', 'mb10');
    c.onclick = () => {
      store.set(spaceKey('game'), g.id);
      close();
    };
    col.append(c);
  };
  add(d.whole);
  let gen = 0;
  for (const g of [...d.games].reverse()) {
    if (g.id !== GO && g.gen !== gen) {
      gen = g.gen;
      col.append(h('div', { class: 'caption', style: 'padding:14px 20px 8px' }, L.f("Generation %s", ROMAN[gen - 1])));
    }
    add(g);
  }
  p.el.append(col);
}

// --- lists -----------------------------------------------------------------------------------

/** Title, search, filter chips, count and list, as ListScreen.kt; `s.items(query)` runs on every keystroke. */
function listScreen(p, s) {
  const search = searchBox(s.hint, () => refresh());
  search.el.classList.add('wide', 'mb10');
  const bar = h('div', 'chips');
  const count = h('div', { class: 'caption', style: 'padding:14px 16px 6px' });
  const list = h('div', s.cards ? 'cards' : 'rows');
  const none = h('p', 'empty');
  s.setup?.(bar, () => refresh());
  p.el.append(h('div', 'col broad', head(s.heading), search.el, bar.childElementCount ? bar : null, count, list, none));
  p.refresh = refresh;
  refresh();

  function refresh(keep = false) {
    const shown = s.items(search.input.value);
    fill(list, shown, it => (s.cards ? card : row)(it, () => (s.open ? s.open(it, shown) : openRef(it.ref))), p.el, keep);
    count.textContent = s.count(shown);
    none.textContent = shown.length ? '' : L.t("Nothing matches. Change the search or the filters.");
    s.labels?.();
  }
}

function pokedex(p, type) {
  const g = game();
  const gen = g.id > WHOLE ? g.gen : 0;
  let nationalDex = g.virtual || store.get('national', false);
  const types = new Set(type ? [type] : []);
  const gens = new Set();
  let minBst = 0;
  let sort = SORTS[0];
  let source = [];
  /** 0 all, 1 caught, 2 not caught, as CAUGHT in PokedexActivity. */
  let caughtFilter = 0;
  let have = caughtSet();
  const CAUGHT = [L.t("All"), L.t("Caught"), L.t("Not caught")];
  const chips = {};
  // Marks change on the Pokémon page above this one.
  p.resume = () => {
    have = caughtSet();
    p.refresh(true);
  };
  listScreen(p, {
    heading: gen ? L.f("Gen %d Pokédex", gen) : L.t("Pokédex"),
    hint: L.t("Search name or number"),
    cards: store.get('layout', 0) === 0,
    setup(bar, refresh) {
      const add = (k, act) => bar.append(chips[k] = chip('', act));
      if (!g.virtual) add('dex', () => {
        nationalDex = !nationalDex;
        store.set('national', nationalDex);
        refresh();
      });
      add('type', () => pickMany(L.t("Type (must have all)"), d.typesIn(g), types, refresh));
      if (!gen) add('gen', () => pickGens(gens, refresh));
      add('bst', () => pickOne(L.t("Lowest base stat total"), BST.map(b => (b ? b + '+' : L.t("All"))), BST.indexOf(minBst), i => {
        minBst = BST[i];
        refresh();
      }));
      add('sort', () => pickOne(L.t("Sort"), SORTS.map(o => L.t(o.label)), SORTS.indexOf(sort), i => {
        sort = SORTS[i];
        refresh();
      }));
      add('caught', () => pickOne(L.t("Caught"), CAUGHT, caughtFilter, i => {
        caughtFilter = i;
        refresh();
      }));
    },
    items(q) {
      source = dexSource(g, nationalDex);
      const keep = e => !caughtFilter || have.has(e.mon.num) === (caughtFilter === 1);
      return d.pokedex(g, source, q, types, gens, minBst, sort).filter(keep)
        .map(e => dexEntry(g, e, nationalDex, have, sort.stat ? L.t(sort.label) + ' ' + sort.stat(e.mon) : ''));
    },
    count: shown => L.f("%s  ·  %d of %d  ·  caught %d", gen ? g.dex[0].dex : nationalDex ? L.t("National") : g.name, shown.length, source.length,
      source.filter(e => have.has(e.mon.num)).length),
    labels() {
      setChip(chips.caught, caughtFilter ? CAUGHT[caughtFilter] : L.t("Caught"), caughtFilter > 0);
      if (chips.dex) setChip(chips.dex, nationalDex ? L.t("National") : L.t("Regional"), nationalDex);
      setChip(chips.type, types.size ? [...types].map(t => d.label(t)).join(' / ') : L.t("Type"), types.size > 0);
      if (chips.gen) setChip(chips.gen, gens.size ? L.f("Gen %s", [...gens].sort((a, b) => a - b).join(',')) : L.t("Gen"), gens.size > 0);
      setChip(chips.bst, minBst ? L.f("BST %d+", minBst) : L.t("BST"), minBst > 0);
      setChip(chips.sort, L.f("Sort: %s", L.t(sort.label)), sort !== SORTS[0]);
    },
    open: (it, shown) => openMon(it.ref.num, nums(shown)),
  });
}

/** The Pokédex screen's list before its filters: `g`'s own dex or the national one, forms as Settings says. */
function dexSource(g, nationalDex) {
  const all = (g.id > WHOLE && g.gen) || !nationalDex ? g.dex : d.national;
  return store.get('forms', 0) === 1 ? all : all.filter(e => !isForm(e.mon));
}

/** `e` as a Pokédex card: its dex number, its types in `g`, and the caught mark after `stat`. */
function dexEntry(g, e, nationalDex, have, stat = '') {
  const m = e.mon;
  const shown = nationalDex ? national(e.number) : '#' + String(e.number).padStart(3, '0');
  return monEntry(m, d.typesOf(g, m), shown, have.has(m.num) ? (stat ? stat + '  ·  ' : '') + L.t("Caught") : stat);
}

/** A tile per generation with its three starters and region; the list behind updates on every tap. */
function pickGens(gens, changed) {
  const s = sheet(L.t("Generation"));
  const all = [...new Set(d.mons.map(m => m.gen))].sort((a, b) => a - b);
  const tiles = all.map(g => {
    const b = h('button', {
      class: gens.has(g) ? 'on' : null, 'aria-label': L.f("Gen %d", g) + ', ' + d.regions[g - 1],
      onclick: () => {
        if (!gens.delete(g)) gens.add(g);
        b.classList.toggle('on', gens.has(g));
        changed();
      },
    }, h('div', null, [0, 1, 2].map(k => sprite(STARTERS[g - 1] + 3 * k))), h('div', { class: 'bold', style: 'font-size:14px' }, L.f("Gen %d", g)), h('div', 'note', d.regions[g - 1]));
    return b;
  });
  s.body.append(h('div', 'gens', tiles));
  s.button(L.t("Clear"), false, () => {
    gens.clear();
    tiles.forEach(t => t.classList.remove('on'));
    changed();
  }, true);
  s.button(L.t("Done"), true).show();
}

function moves(p, tm) {
  const g = game();
  const tms = d.tmGame(g);
  const source = tm ? [...tms.tms.keys()].map(id => d.move(g, id)) : d.movesIn(g);
  const types = new Set();
  const cats = new Set();
  let sort = MOVE_SORTS[0];
  const label = mv => (tm ? tms.tms.get(mv.id) : '');
  const chips = {};
  listScreen(p, {
    heading: tm ? L.t("TMs") : L.t("Moves"),
    hint: tm ? L.t("Search move or TM number") : L.t("Search moves"),
    setup(bar, refresh) {
      bar.append(
        chips.type = chip('', () => pickMany(L.t("Type"), d.typesIn(g), types, refresh)),
        chips.cat = chip('', () => pickMany(L.t("Category"), ['physical', 'special', 'status'], cats, refresh)),
        chips.sort = chip('', () => pickOne(L.t("Sort"), MOVE_SORTS.map(o => L.t(o)), MOVE_SORTS.indexOf(sort), i => {
          sort = MOVE_SORTS[i];
          refresh();
        })));
    },
    items: q => Dex.moves(source, q, types, cats, sort, label).map(mv => moveEntry(mv, label(mv))),
    count: shown => (tm ? L.f("TMs in %s: %d of %d", tms.name, shown.length, source.length) : L.f("Moves: %d of %d", shown.length, source.length)),
    labels() {
      setChip(chips.type, types.size ? [...types].map(t => d.label(t)).join(' / ') : L.t("Type"), types.size > 0);
      setChip(chips.cat, cats.size ? [...cats].map(t => d.label(t)).join(' / ') : L.t("Category"), cats.size > 0);
      setChip(chips.sort, L.f("Sort: %s", L.t(sort)), sort !== MOVE_SORTS[0]);
    },
  });
}

function abilities(p) {
  const byName = [...d.abilities].sort(byKey);
  listScreen(p, {
    heading: L.t("Abilities"),
    hint: L.t("Search abilities"),
    items: q => best(byName, fold(q), a => a.key, byName.length).map(a => entry(0, L.f("Gen %d", a.gen), a.name, a.shortEffect, [], a)),
    count: shown => L.f("Abilities: %d of %d", shown.length, byName.length),
  });
}

function items(p) {
  const byName = [...d.items].sort(byKey);
  const make = i => entry(0, L.f("Gen %d", i.gen), i.name, i.effect, [], i);
  listScreen(p, {
    heading: L.t("Items"),
    hint: L.t("Search items"),
    items: q => (q.trim() ? best(byName, fold(q), i => i.key, byName.length).map(make) : itemSections(byName, make)),
    count: shown => L.f("Items: %d of %d", shown.filter(it => it.ref).length, byName.length),
  });
}

// --- details ---------------------------------------------------------------------------------

async function moveScreen(p, id) {
  const g = game();
  const mv = d.move(g, id);
  const tms = d.tmGame(g);
  const tm = tms.tms.get(mv.id);
  const col = (v, k) => h('div', { style: 'flex:1' }, h('b', null, v), h('span', null, k));
  const top = hero(deep(color(mv.type)));
  top.append(h('h1', null, mv.name),
    h('div', { style: 'margin:8px 0 16px' }, glass(mv.type), h('span', 'pill glass big', d.label(mv.category))),
    h('div', 'cols', col(mv.power || '-', L.t("Power")), col(mv.accuracy ? mv.accuracy + '%' : '-', L.t("Accuracy")), col(mv.pp, L.t("PP")),
      mv.priority ? col((mv.priority > 0 ? '+' : '') + mv.priority, L.t("Priority")) : null),
    tm ? h('div', { class: 'bold', style: 'font-size:13px;padding-top:14px' }, L.f("%s in %s", tm, tms.name)) : null);
  const about = panel(L.t("Effect"), 'wide mt16 mb4');
  about.append(h('p', 'body', mv.effect || mv.shortEffect),
    h('p', { class: 'note', style: 'padding-top:10px' }, L.f("Introduced in Gen %d", mv.gen) + (mv.chance ? '  ·  ' + L.f("Effect chance %d%%", mv.chance) : '')));
  const learned = h('h2', { class: 'pad', style: 'font-size:17px;padding-top:16px' });
  const note = h('p', 'note pad');
  const list = h('div');
  p.el.append(h('div', 'col', top, about, learned, note, list));

  const lg = await d.moveGame(g, mv);
  const items = [];
  let method = -1;
  for (const [num, k, level] of await d.learners(lg, mv.id)) {
    if (k !== method) {
      method = k;
      items.push(header(L.t(METHODS[k])));
    }
    const x = d.mon(num);
    const how = k === 0 ? (level === 0 ? L.t("On evolution") : L.f("Level %d", level)) : k === 1 ? lg.tms.get(mv.id) ?? '' : '';
    items.push(monEntry(x, d.typesOf(g, x), national(x.species), how));
  }
  const order = nums(items);
  learned.textContent = L.f("Learned by %d in %s", order.length, lg.name);
  if (lg !== g && !g.virtual) note.textContent = L.f("No %s move data.", g.name);
  fill(list, items, it => row(it, () => openMon(it.ref.num, order)), p.el);
}

function abilityScreen(p, id) {
  const g = game();
  const a = d.ability(id);
  const regular = [];
  const hidden = [];
  for (const m of d.mons) {
    const it = monEntry(m, d.typesOf(g, m), national(m.species), m.genus);
    if (m.abilities.includes(a.id)) regular.push(it);
    if (m.hidden === a.id) hidden.push(it);
  }
  const items = [
    ...(regular.length ? [header(L.f("Ability (%d)", regular.length))] : []), ...regular,
    ...(hidden.length ? [header(L.f("Hidden ability (%d)", hidden.length))] : []), ...hidden,
  ];
  const top = hero(rgb('34495E'));
  top.append(h('div', { class: 'bold', style: 'font-size:12px;margin-top:4px' }, L.f("ABILITY  ·  GEN %d", a.gen)), h('h1', null, a.name));
  const about = panel(L.t("Effect"), 'wide mt16 mb4');
  about.append(h('p', 'body', a.effect || a.shortEffect));
  const list = h('div');
  p.el.append(h('div', 'col', top, about, items.length ? list : h('p', { class: 'muted', style: 'font-size:15px;padding:16px 16px 0' }, L.t("No Pokémon has this ability."))));
  const order = nums(items);
  fill(list, items, it => row(it, () => openMon(it.ref.num, order)), p.el);
}

let nationalNums = null;
const nationalOrder = () => (nationalNums ??= d.national.map(e => e.mon.num));

/** One Pokémon: hero with previous/next, then Info, Evolution and Moves tabs. `list` is the order browsed. */
function monScreen(p, start, list) {
  const g = game();
  const own = list && list.some(n => d.mon(n).species !== d.mon(start).species) ? list : null;
  let tab = 0;
  let method = 0;
  /** Bumped on every redraw, so a late async tab does not draw over a newer one. */
  let token = 0;
  let body = null;
  /** Shiny coloring for the picture and form thumbnails; kept while paging through Pokémon. */
  let shiny = false;
  /** Form and look thumbnails on the Info tab with a maker per shiny state, one family at most; redrawn when `shiny` flips. */
  let thumbs = [];
  /** The looks.tsv file shown in the header instead of the Pokémon's own picture, or null. */
  let worn = null;
  /** The header picture and the Pokémon it belongs to. */
  let pic = null;
  let picNum = 0;
  /** Format chip picked on the Builds tab, kept while paging through Pokémon. */
  let format = '';
  const wearing = file => {
    worn = file;
    const x = file ? look(file, 'main', shiny) : sprite(picNum, 'main', shiny);
    pic?.replaceWith(x);
    pic = x;
  };
  const step = (num, k) => {
    const n = own ? d.neighbor(own, num, k) : -1;
    return n < 0 ? d.neighbor(nationalOrder(), num, k) : n;
  };
  show(start);

  function show(num, wear = null) {
    const m = d.mon(num);
    const types = d.typesOf(g, m);
    const prev = step(num, -1);
    const next = step(num, 1);
    const e = g.byNum.get(num);
    const ink = deep(color(types[0]));
    const top = hero(ink);
    picNum = num;
    pic = null;
    wearing(wear);
    const toggle = h('button', {
      class: 'shiny', style: `--ink:${css(ink)}`, 'aria-label': L.t("Shiny"), title: L.t("Shiny"), 'aria-pressed': String(shiny),
      onclick: () => {
        shiny = !shiny;
        toggle.setAttribute('aria-pressed', String(shiny));
        wearing(worn);
        thumbs = thumbs.map(([img, make]) => {
          const x = make(shiny);
          img.replaceWith(x);
          return [x, make];
        });
      },
    }, sparkle());
    const caught = h('button', {
      class: 'add mark', style: `--ink:${css(ink)}`,
      onclick: () => {
        const on = !caughtSet().has(num);
        setCaught(num, on);
        paint(on);
      },
    });
    const paint = on => {
      caught.textContent = on ? L.t("Caught") : L.t("Mark caught");
      caught.setAttribute('aria-pressed', String(on));
    };
    paint(caughtSet().has(num));
    top.append(
      h('div', 'line', h('h1', null, d.mon(m.species).name),
        h('button', { class: 'nav', hidden: prev <= 0, 'aria-label': prev > 0 ? L.f("Previous: %s", d.mon(prev).name) : null, onclick: () => show(prev) }, icon(BACK, 20)),
        h('span', 'no', national(m.species)),
        h('button', { class: 'nav', hidden: next <= 0, 'aria-label': next > 0 ? L.f("Next: %s", d.mon(next).name) : null, onclick: () => show(next) }, icon(NEXT, 20))),
      h('div', { style: 'font-size:15px' }, isForm(m) ? m.name + '  ·  ' + m.genus : m.genus),
      h('div', 'mt8 typerow', types.map(glass), toggle),
      pic,
      g === d.whole ? null : h('div', 'where', e ? L.f("%s #%d  ·  %s", e.dex, e.number, g.name) : L.f("Not in %s", g.name)),
      h('div', 'acts', caught, h('button', { class: 'add', onclick: () => addToTeam(m) }, L.t("Add to team"))));
    const tabs = seg([L.t("Info"), L.t("Evolution"), L.t("Moves"), L.t("Builds")], tab, i => {
      tab = i;
      fillTab(m);
    });
    tabs.classList.add('wide', 'mt16', 'mb12');
    body = h('div');
    p.el.replaceChildren(h('div', 'col split', top, tabs, body));
    p.el.scrollTop = 0;
    fillTab(m);
  }

  function fillTab(m) {
    const t = ++token;
    body.replaceChildren();
    thumbs = [];
    if (tab === 0) info(m, t);
    else if (tab === 1) evolution(m);
    else if (tab === 2) learnset(m, t);
    else builds(m, t);
  }

  async function info(m, t) {
    const looks = forms(m);
    if (looks) body.append(looks);
    const imperial = store.get('units', 0) === 1;
    const text = h('p', 'body', m.flavor);
    const fact = (v, k) => h('div', null, h('b', null, v), h('span', null, k));
    const about = panel(null);
    about.append(text, h('div', 'facts', fact(height(m.height, imperial), L.t("Height")), fact(weight(m.weight, imperial), L.t("Weight")),
      fact(L.f("Gen %d", m.gen), L.t("Introduced"))));
    const where = h('div');
    const types = d.typesOf(g, m);

    const abil = panel(L.t("Abilities"));
    const ab = (id, hid) => {
      const a = d.ability(id);
      return h('button', { class: 'ab', onclick: () => openRef(a) },
        h('div', { class: 'bold', style: 'font-size:15px' }, a.name, hid ? h('span', 'tag caption', L.t("Hidden")) : null), h('div', 'note', a.shortEffect));
    };
    abil.append(...m.abilities.map(id => ab(id, false)), ...(m.hidden ? [ab(m.hidden, true)] : []));

    const stats = panel(L.t("Base stats"));
    m.stats.forEach((v, i) => stats.append(h('div', 'stat', h('span', 'k', L.t(STATS[i])), h('span', 'n', v),
      h('div', 'bar', h('i', { style: `width:${Math.min(100, (v * 100) / 255)}%;background:${tier(v)}` })))));
    stats.append(h('div', 'stat', h('span', 'k', L.t("Total")), h('span', 'n', m.bst)), chip(L.t("Compare"), () => open(q => compare(q, m.num, g.id)), 'mt10'));

    const defense = panel(L.t("Type defenses"));
    const labels = [L.t("Weak x4"), L.t("Weak x2"), L.t("Resists x1/2"), L.t("Resists x1/4"), L.t("Immune")];
    [400, 200, 50, 25, 0].forEach((f, i) => {
      const hit = d.typesIn(g).filter(a => d.factor(g, a, types) === f);
      if (hit.length) defense.append(h('div', { class: 'caption', style: 'padding:6px 2px' }, labels[i]), typePills(hit, 4));
    });

    const r = m.genderRate;
    const breeding = panel(L.t("Breeding and training"));
    breeding.append(...[
      [L.t("Catch rate"), m.captureRate], [L.t("Base friendship"), m.happiness], [L.t("Growth rate"), L.t(m.growth)],
      [L.t("Egg groups"), m.eggGroups], [L.t("Egg cycles"), m.hatchCounter],
      [L.t("Gender"), r < 0 ? L.t("Genderless") : L.f("%.1f%% male, %.1f%% female", 100 - r * 12.5, r * 12.5)],
    ].map(([k, v]) => h('div', 'fact', h('span', 'k', k), h('span', null, v))));
    body.append(about, where, abil, stats, defense, breeding);

    const [place, said] = await d.info(g, m);
    if (t !== token) return;
    if (said) text.textContent = said;
    if (!place) return;
    const w = panel(L.f("Where to find in %s", g.name));
    w.append(h('p', 'body', place));
    where.replaceWith(w);
  }

  /**
   * The family's forms, then the looks of the one that has them (MonActivity.forms): a look of this Pokémon only
   * changes the header picture, one of another form opens that form wearing it. Past 12 tiles (Alcremie, Vivillon)
   * shows 8 until asked, unless the shown one is further down.
   */
  function forms(m) {
    const fam = d.family(m);
    const owner = d.looks(m.num).length ? m : fam[0];
    const looks = d.looks(owner.num);
    if (fam.length + Math.max(looks.length - 1, 0) < 2) return null;
    const box = panel(L.t("Forms and looks"));
    const grid = h('div', 'forms');
    const tiles = [];
    const tile = (make, name, act) => {
      const img = make(shiny);
      thumbs.push([img, make]);
      const b = h('button', {
        onclick: () => {
          act();
          tiles.forEach(t => t.classList.toggle('on', t === b));
        },
      }, img, name);
      tiles.push(b);
      return b;
    };
    fam.forEach(f => tile(s => sprite(f.num, null, s), f === owner && looks.length ? looks[0][1] : f.name, () => (f === m ? wearing(null) : show(f.num))));
    looks.slice(1).forEach(([file, name]) => tile(s => look(file, null, s), name, () => (owner === m ? wearing(file) : show(owner.num, file))));
    const here = worn ? fam.length + looks.slice(1).findIndex(l => l[0] === worn) : fam.indexOf(m);
    tiles[here]?.classList.add('on');
    const many = tiles.length > 12 && here < 8;
    grid.append(...(many ? tiles.slice(0, 8) : tiles));
    box.append(grid);
    if (many) {
      const more = chip(L.f("Show all (%d)", tiles.length), () => {
        grid.replaceChildren(...tiles);
        more.remove();
      }, 'mt10');
      box.append(more);
    }
    return box;
  }

  async function builds(m, t) {
    const sets = await d.builds(g, new Set(d.family(m).map(f => f.num)));
    if (t !== token) return;
    body.append(h('p', 'note pad mb8', sets.length ? L.f("Smogon sets for %s", g.name) : L.f("No Smogon sets for %s in %s.", m.name, g.name)));
    if (!sets.length) return;
    body.append(buildList(g, sets, m.num, format, 'wide', b => h('div', 'two',
      chip(L.t("Add to team"), () => addToTeam(d.mon(b.num), buildMember(b)), 'on'),
      chip(L.t("Copy for Showdown"), () => {
        const one = new Team(b.name, g.id);
        one.members.push(buildMember(b));
        copy(one);
      })), f => { format = f; }));
  }

  function evolution(m) {
    const rows = d.chain(m);
    if (rows.length === 1) body.append(h('p', { class: 'muted pad mb12' }, L.f("%s does not evolve", m.name)));
    const here = rows.some(r => r[0] === m.num) ? m.num : m.species;
    const box = panel(null, 'flush wide mb12');
    for (const [num, depth] of rows) {
      const x = d.mon(num);
      const alone = depth === 0 && !d.evolutions(num).length && rows.length > 1;
      const r = row(monEntry(x, d.typesOf(g, x), '', alone ? L.t("Does not evolve") : how(d.evo(num))), () => show(num));
      r.style.paddingLeft = 16 + depth * 40 + 'px';
      if (num === here) r.classList.add('on');
      box.append(r);
    }
    body.append(box);
  }

  async function learnset(m, t) {
    const lg = d.learnGame(g, m);
    const cols = lg ? await d.learnset(lg, m) : null;
    if (t !== token) return;
    const counts = cols ? cols.map((c, k) => (k === 0 ? c.length / 2 : c.length)) : [];
    if (!counts.some(n => n)) return body.append(h('p', 'note pad', L.f("No move data for %s.", m.name)));
    if (!counts[method]) method = counts.findIndex(n => n > 0);
    body.append(h('p', 'note pad mb8', lg === g || g.virtual ? L.f("Moves in %s", lg.name) : L.f("No %s moves for %s. Showing %s.", g.name, m.name, lg.name)));
    const bar = h('div', 'chips');
    const list = h('div', 'panel flush wide');
    body.append(bar, list);
    const paint = () => {
      bar.replaceChildren(...counts.map((n, k) => (n ? chip(L.t(METHODS[k]) + '  ' + n, () => {
        method = k;
        paint();
      }, k === method ? 'on' : null) : '')));
      let rows;
      if (method === 0) {
        rows = [];
        const c = cols[0];
        for (let i = 0; i < c.length; i += 2) rows.push(moveEntry(d.move(lg, c[i + 1]), c[i] === 0 ? L.t("Evo") : L.f("Lv %d", c[i])));
      } else {
        const label = id => lg.tms.get(id) ?? '';
        const ids = [...cols[method]].sort(method === 1 ? (a, b) => label(a).localeCompare(label(b), undefined, { numeric: true }) : (a, b) => byKey(d.move(a), d.move(b)));
        rows = ids.map(id => moveEntry(d.move(lg, id), method === 1 ? lg.tms.get(id) ?? 'TM' : ''));
      }
      fill(list, rows, it => row(it, () => openRef(it.ref)), p.el);
    };
    paint();
  }
}

function how(e) {
  if (!e) return '';
  if (!e.gender) return e.how;
  const female = e.gender === 1;
  return h('span', null, ink(h('b', null, female ? L.t("Female only") : L.t("Male only")), color(female ? 'fairy' : 'water')), '  ·  ' + e.how);
}

/** `member` is `m` with a Smogon set applied; without it `m` joins with its first ability at level 50. */
function addToTeam(m, member = newMember(m)) {
  const teams = loadTeams();
  const labels = teams.map(t => `${t.name}  (${t.members.length}/${Team.SIZE}, ${d.game(t.game).name})`);
  pickOne(L.f("Add %s to", m.name), [...labels, L.t("New team")], -1, i => {
    if (i === teams.length) teams.push(new Team(L.f("Team %d", i + 1), game().id));
    const t = teams[i];
    if (t.members.length >= Team.SIZE) return toast(L.f("%s already has %d Pokémon", t.name, Team.SIZE));
    t.members.push(member);
    saveTeams(teams);
    open(q => memberScreen(q, i, t.members.length - 1));
  });
}

function typeChart(p) {
  const g = game();
  const types = d.typesIn(g);
  // 16 px page margin and 12 px panel padding on each side.
  const cell = Math.max(16, Math.floor((Math.min(innerWidth, 600) - 56) / (types.length + 1)));
  const short = t => (L.lang === 'ja' ? d.label(t).slice(0, 2) : d.label(t).slice(0, 3).toUpperCase());
  // One size for every header, so the widest label fits and the rest match it.
  const ctx = document.createElement('canvas').getContext('2d');
  ctx.font = '700 100px system-ui, -apple-system, sans-serif';
  const font = Math.min(10, (100 * (cell - 5)) / Math.max(...types.map(t => ctx.measureText(short(t)).width)));
  const size = `width:${cell - 1}px;height:${cell - 1}px`;
  const marks = { 200: ['2', '#1E7B3A'], 50: ['½', '#C7413A'], 0: ['0', '#303030'] };
  const th = t => {
    const c = color(t);
    return h('th', null, h('button', { style: `${size};font-size:${font}px;background:${css(c)};color:${on(c)};border-radius:4px;text-align:center`, 'aria-label': d.label(t), onclick: () => openRef(t) }, short(t)));
  };
  const table = h('table', null, h('tr', null, h('th'), types.map(th)), types.map(a => h('tr', null, th(a), types.map(t => {
    const mark = marks[d.factor(g, a, [t])];
    return h('td', { style: `${size};color:#fff;background:${mark ? mark[1] : 'var(--fill)'}` }, mark ? mark[0] : '');
  }))));
  const box = panel(null, 'wide mb16');
  box.style.padding = '12px';
  box.append(h('div', 'grid', table));
  p.el.append(h('div', 'col', head(L.t("Type chart")),
    h('p', { class: 'muted pad mb12' }, L.f("As in %s. Rows attack, columns defend. 2 is super effective, ½ not very effective, 0 no effect. Tap a type to see its Pokémon.", g.name)), box));
}

/** The 25 natures as the games' 5 x 5 chart; with `picked`, cells are buttons and `current` is highlighted. */
function natureChart(current, picked) {
  const stats = STATS.slice(1);
  const grid = h('div', 'natures', h('div', 'h'), stats.map(s => h('div', 'h down', '-' + L.t(s))));
  d.natureChart().forEach((line, r) => {
    grid.append(h('div', 'c up', '+' + L.t(stats[r])));
    line.forEach((i, k) => {
      const n = d.natures[i];
      const style = i === current ? 'background:var(--accent);color:#fff'
        : r === k ? 'background:var(--surface);color:var(--muted);box-shadow:inset 0 0 0 1px var(--fill)' : 'background:var(--fill)';
      const label = r === k ? L.f("%s, neutral", n.name) : L.f("%s, raises %s, lowers %s", n.name, L.t(n.up), L.t(n.down));
      grid.append(h(picked ? 'button' : 'div', { class: 'c', style, 'aria-label': label, onclick: picked && (() => picked(i)) }, n.name));
    });
  });
  return grid;
}

function natures(p) {
  const box = panel(null, 'wide mb16');
  box.style.padding = '10px 8px';
  box.append(natureChart(-1, null));
  p.el.append(h('div', 'col', head(L.t("Natures")),
    h('p', { class: 'muted pad mb12' }, L.t("A nature raises one stat by 10% (its row) and lowers another by 10% (its column). The five on the diagonal change nothing.")), box));
}

// --- settings --------------------------------------------------------------------------------

function settings(p) {
  const col = h('div', 'col', head(L.t("Settings")));
  const section = title => {
    const box = panel(null, 'wide mb10');
    col.append(h('div', { class: 'caption', style: 'padding:10px 20px 6px' }, title), box);
    return box;
  };
  const label = (box, s) => box.append(h('div', { class: 'bold', style: `margin:${box.childElementCount ? 18 : 0}px 0 8px` }, s));

  const look = section(L.t("Appearance"));
  label(look, L.t("Language"));
  look.append(seg([L.t("System"), 'English', 'Polski', 'Español', '日本語'], L.LANGS.indexOf(store.get('lang', '')) + 1, i => {
    store.set('lang', i ? L.LANGS[i - 1] : '');
    if (wanted() !== L.lang) restart().catch(() => toast(L.t("Could not load the data. Check the connection and reload.")));
  }));
  label(look, L.t("Theme"));
  look.append(seg([L.t("System"), L.t("Light"), L.t("Dark")], store.get('theme', 0), i => {
    store.set('theme', i);
    theme();
  }));
  label(look, L.t("Pokémon artwork"));
  const arts = [L.t("3D (HOME)"), L.t("Official art"), L.t("Pixel art")];
  look.append(seg(arts.map((s, i) => [sprite(25, 'pv', false, i), s]), store.get('art', 0), i => {
    store.set('art', i);
    keepArt();
  }));
  label(look, L.t("Type labels"));
  const styles = [L.t("Text"), L.t("Icon and name"), L.t("Icon only"), L.t("Pixel art")];
  const tiles = styles.map((s, k) => h('button', {
    class: LABEL_STYLES[k] === store.get('labels', 0) ? 'on' : null,
    onclick: () => {
      store.set('labels', LABEL_STYLES[k]);
      tiles.forEach((t, i) => t.classList.toggle('on', i === k));
    },
  }, h('div', null, ['fire', 'water'].map(t => pill(t, 'span', LABEL_STYLES[k]))), s));
  look.append(h('div', 'opts', tiles));

  const list = section(L.t("Pokédex"));
  label(list, L.t("Layout"));
  list.append(seg([L.t("Cards"), L.t("List")], store.get('layout', 0), i => store.set('layout', i)));
  label(list, L.t("Megas and other forms"));
  list.append(seg([L.t("On their Pokémon"), L.t("In the list too")], store.get('forms', 0), i => store.set('forms', i)));
  label(list, L.t("Height and weight"));
  const chomp = d.mon(445);
  const example = h('p', { class: 'note', style: 'margin:8px 0 0 4px' });
  const units = i => { example.textContent = `${chomp.name}: ${height(chomp.height, i === 1)}, ${weight(chomp.weight, i === 1)}`; };
  list.append(seg([L.t("Metric"), L.t("Imperial")], store.get('units', 0), i => {
    store.set('units', i);
    units(i);
  }), example);
  units(store.get('units', 0));

  const count = h('span', 'muted');
  const clear = chip(L.t("Clear"), () => confirm(L.t("Clear caught marks?"),
    L.f("Removes the caught mark from every game in %s. Pokémon: %d. This cannot be undone.", spaceName(), caughtSet().size), L.t("Clear"), () => {
      store.remove(spaceKey('caught'));
      shown();
    }));
  const shown = () => {
    const n = caughtSet().size;
    count.textContent = n ? L.f("Marked: %d", n) : L.t("None");
    clear.disabled = !n;
  };
  shown();
  section(L.t("Data")).append(h('div', 'setrow', h('span', 'bold', L.t("Caught Pokémon")), count, clear));

  section(L.t("About")).append(h('p', 'note', L.t("Data: PokeAPI and pokemondb.net. Artwork and type icons: PokeAPI sprites. Sets: Smogon University, via pkmn. Pokémon names and images © Nintendo / Game Freak.")));
  p.el.append(col);
}

function theme() {
  const t = store.get('theme', 0);
  if (t) document.documentElement.dataset.theme = t === 1 ? 'light' : 'dark';
  else delete document.documentElement.dataset.theme;
}

// --- teams -----------------------------------------------------------------------------------

function teamsScreen(p) {
  p.resume = render;
  render();

  function render() {
    const teams = loadTeams();
    const g = game();
    p.el.replaceChildren(h('div', 'col', head(L.t("Teams")),
      h('p', { class: 'muted pad mb12' }, L.t("Up to six Pokémon per team, with moves, items and stats for one game. Saved on this device.")),
      teams.map((t, i) => h('div', { class: 'panel wide mb10 tap', role: 'button', tabindex: '0', onclick: () => open(q => teamScreen(q, i)) },
        h('div', { class: 'bold', style: 'font-size:17px' }, t.name),
        h('div', 'note', d.game(t.game).name + '  ·  ' + L.f("%d of %d", t.members.length, Team.SIZE)),
        h('div', { class: 'slots', 'aria-hidden': 'true' }, Array.from({ length: Team.SIZE }, (_, k) => h('div', 'slot', k < t.members.length ? sprite(t.members[k].num) : null))))),
      h('button', {
        class: 'button',
        onclick: () => {
          teams.push(new Team(L.f("Team %d", teams.length + 1), g.id));
          saveTeams(teams);
          open(q => teamScreen(q, teams.length - 1));
        },
      }, L.f("New team for %s", g.name))));
  }
}

function teamScreen(p, ti) {
  p.resume = render;
  render();

  function render() {
    const teams = loadTeams();
    const team = teams[ti];
    if (!team) return close();
    const g = d.game(team.game);
    const save = () => saveTeams(teams);
    const pick = (skip, heading, picked) => pickMon(g, heading, [L.t("Suggested"), team.suggest(d, skip).map(m => teamMonEntry(g, m))], picked);
    const add = () => pick(-1, L.t("Add Pokémon"), x => {
      team.members.push(x);
      save();
      open(q => memberScreen(q, ti, team.members.length - 1));
    });
    const replace = k => pick(k, L.f("Swap %s", d.mon(team.members[k].num).name), x => {
      x.level = team.members[k].level;
      team.members[k] = x;
      save();
      render();
    });
    const rename = () => askName(L.t("Team name"), team.name, s => {
      team.name = s;
      save();
      render();
    });

    const slots = h('div', { class: 'panel wide', style: 'padding:6px 0' });
    team.members.forEach((x, k) => {
      const m = d.mon(x.num);
      const moves = x.moves.filter(id => id).map(id => d.move(id).name);
      const held = x.item ? d.item(x.item).name + '  ·  ' : '';
      const r = row(entry(m.num, L.f("Lv %d", x.level), m.name, held + (moves.length ? moves.join(L.t(", ")) : L.t("No moves yet")), d.typesOf(g, m), m),
        () => open(q => memberScreen(q, ti, k)));
      onHold(r, () => replace(k));
      slots.append(r);
    });
    if (team.members.length < Team.SIZE) slots.append(row(entry(0, '+', L.t("Add Pokémon"), L.f("Free slots: %d", Team.SIZE - team.members.length), [], ''), add));

    const top = head(team.name, chip(L.t("Rename"), rename));
    top.querySelector('.title').onclick = rename;
    const del = () => confirm(L.f("Delete %s?", team.name), L.f("Its %d Pokémon and their sets are removed from this device.", team.members.length), L.t("Delete"), () => {
      teams.splice(ti, 1);
      save();
      close();
    });
    const y = p.el.scrollTop;
    p.el.replaceChildren(h('div', 'col', top, h('p', 'sub', g.name), slots,
      h('p', { class: 'note', style: 'padding:6px 20px 12px' }, L.t("Hold a Pokémon to swap it for another.")),
      team.members.length ? [tips(team, g, add, replace), defense(team, g)] : null,
      h('div', 'chips',
        chip(L.t("Compare"), () => open(q => compare(q, 0, g.id))),
        chip(L.t("Damage calc"), () => open(q => calc(q, ti, -1))),
        chip(L.t("Copy as text"), () => copy(team)),
        chip(L.t("Delete team"), del, 'danger'))));
    p.el.scrollTop = y;
  }
}

/** Clipboard when the browser allows it; otherwise the text in a box to copy by hand. */
function copy(team) {
  const text = team.export(d);
  const show = () => {
    const s = sheet(team.name);
    const box = h('textarea', { class: 'field', rows: '10', readonly: true }, text);
    s.body.append(box);
    s.button(L.t("Done"), true).show();
    box.select();
  };
  navigator.clipboard?.writeText(text).then(() => toast(L.t("Copied")), show) ?? show();
}

/** Team.gaps in words: what hurts, what nothing answers, what no move hits, then the type that fixes most. */
function tips(team, g, add, replace) {
  const box = panel(L.t("Tips"));
  const gp = team.gaps(d);
  const types = d.typesIn(g);
  let count = 0;
  const names = list => {
    const n = list.map(i => d.label(types[i]));
    return n.length === 1 ? n[0] : L.f("%s and %s", n.slice(0, -1).join(L.t(", ")), n.at(-1));
  };
  const tip = (title, detail, list, action, act) => {
    count++;
    const pills = list.length ? typePills(list.map(i => types[i]), 4) : null;
    if (pills) pills.style.marginTop = '6px';
    box.append(h('div', 'tip', h('div', { class: 'bold', style: 'font-size:15px' }, title), h('p', { class: 'note', style: 'margin-top:2px' }, detail),
      pills, action ? chip(action, act, 'on') : null));
  };
  if (gp.stacked.length) tip(L.t("Weak spots"), L.t("Super effective against half the team or more, and resisted by fewer."), gp.stacked);
  if (gp.counter.length) tip(L.t("Nothing counters these"), L.t("No member resists them and no move hits them super effectively."), gp.counter);
  if (gp.uncovered.length) tip(L.t("Missing coverage"), L.t("No move hits these super effectively."), gp.uncovered);
  if (gp.wall >= 0) {
    const detail = L.f("It resists %s.", names([...gp.counter, ...gp.stacked].filter(i => d.factor(g, types[i], [types[gp.wall]]) < 100)));
    const title = L.f("Try a %s Pokémon", d.label(types[gp.wall]));
    if (team.members.length < Team.SIZE) tip(title, detail, [], L.t("Suggestions"), add);
    else tip(title, detail, []);
  }
  if (gp.move >= 0) {
    tip(L.f("Try a %s move", d.label(types[gp.move])), L.f("It hits %s super effectively.",
      names([...gp.counter, ...gp.uncovered].filter(i => d.factor(g, types[gp.move], [types[i]]) > 100))), []);
  }
  if (!team.attacks(d)) tip(L.t("No attacks yet"), L.t("Give your Pokémon damaging moves to see what nothing counters."), []);
  else if (!count) tip(L.t("No gaps"), L.t("Every type is resisted or hit super effectively, and none hits half the team."), []);
  // The lower base stat total of a pair is the one to swap: Noibat beside Garchomp.
  for (const o of team.overlaps(d)) {
    const a = d.mon(team.members[o.a].num);
    const b = d.mon(team.members[o.b].num);
    const k = b.bst <= a.bst ? o.b : o.a;
    tip(L.f("%s and %s overlap", a.name, b.name), L.t("They share these weaknesses, so one adds little. Swap it for a Pokémon that covers them."),
      o.weak, L.f("Swap %s", d.mon(team.members[k].num).name), () => replace(k));
  }
  return box;
}

/** Per attacking type: members it hits super effectively and members that resist it, in two columns. */
function defense(team, g) {
  const box = panel(L.t("Type defenses"));
  box.append(h('p', 'note mb8', L.t("Members weak to and resisting each attacking type. Red: half the team or more is weak.")));
  const def = team.defense(d);
  const types = d.typesIn(g);
  const half = Math.ceil(types.length / 2);
  const n = team.members.length;
  const column = c => h('div', null, h('div', 'defrow top', h('span'), h('b', null, L.t("Weak")), h('b', null, L.t("Resist"))),
    types.slice(c * half, (c + 1) * half).map((t, j) => {
      const [weak, resist] = def[c * half + j];
      return h('div', 'defrow', pill(t), h('b', { style: weak * 2 >= n && weak > resist ? 'color:var(--bad)' : null }, weak),
        h('b', { style: `color:var(--${resist ? 'good' : 'muted'})` }, resist));
    }));
  box.append(h('div', 'defs', column(0), column(1)));
  return box;
}

/**
 * A chip per format in `sets` ("OU  2", first seen first) over cards of the picked format's sets, as
 * MemberActivity.buildList, starting on `start` if the sets have it. Each card gets class `cls` and `actions(set)` at its
 * foot; `picked` gets a new format. Only one format's cards exist at a time: Arceus has 76 sets in Gen 9.
 */
function buildList(g, sets, num, start, cls, actions, picked = () => {}) {
  const counts = new Map();
  for (const b of sets) counts.set(b.format, (counts.get(b.format) ?? 0) + 1);
  const cards = h('div');
  const chips = [...counts].map(([f, n]) => chip(f + '  ' + n, () => {
    show(f);
    picked(f);
  }));
  const formats = [...counts.keys()];
  const show = f => {
    chips.forEach((c, i) => c.classList.toggle('on', formats[i] === f));
    cards.replaceChildren(...sets.filter(b => b.format === f).map(b => buildCard(g, b, num, cls, actions(b))));
  };
  show(counts.has(start) ? start : formats[0]);
  return h('div', 'builds', h('div', 'chips', chips), cards);
}

/**
 * One set: name, its form when not `num`, level when not 100 and Tera type; then item, ability (opens it), nature with
 * the stats it raises and lowers, EVs and any IV under 31; then the moves two to a row in their type's tint.
 */
function buildCard(g, b, num, cls, actions) {
  const card = panel(null, 'build ' + cls);
  const sub = [b.num === num ? '' : d.mon(b.num).name, b.level === 100 ? '' : L.f("Lv %d", b.level)].filter(s => s).join('  ·  ');
  const tera = b.tera >= 0 && (g.id === 25 || (g.virtual && g.gen >= 9));
  card.append(h('div', 'btop', h('div', null, h('div', 'bold', b.name), sub ? h('div', 'note', sub) : null),
    tera ? h('span', 'note', L.t("Tera")) : null, tera ? pill(d.types[b.tera]) : null));
  const facts = h('div', 'bfacts');
  const fact = (k, v, ref) => facts.append(h(ref ? 'button' : 'div', { onclick: ref ? () => openRef(ref) : null }, h('span', 'k', k), h(ref ? 'b' : 'span', null, v)));
  if (b.item) fact(L.t("Item"), d.item(b.item).name);
  if (b.ability) fact(L.t("Ability"), d.ability(b.ability).name, d.ability(b.ability));
  if (b.nature >= 0) {
    const n = d.natures[b.nature];
    fact(L.t("Nature"), n.up === n.down ? n.name : [n.name, h('b', 'up', '+' + L.t(n.up)), h('b', 'down', '-' + L.t(n.down))]);
  }
  const spread = (v, keep) => v.map((n, i) => (keep(n) ? n + ' ' + L.t(STATS[i]) : '')).filter(s => s).join(' / ');
  const evs = spread(b.evs, n => n > 0);
  const ivs = spread(b.ivs, n => n < Team.IV_MAX);
  if (evs) fact(L.t("EVs"), evs);
  if (ivs) fact(L.t("IVs"), ivs);
  card.append(facts, h('div', 'bmoves', b.moves.filter(id => id).map(id => {
    const mv = d.move(g, id);
    return tonal(h('button', { onclick: () => openRef(mv) }, h('b', null, mv.name), h('span', null, d.label(mv.type))), color(mv.type));
  })), actions);
  return card;
}

/** A new member of `b`'s form with `b` applied. */
const buildMember = b => loadBuild(b, newMember(d.mon(b.num)));

/** Replaces `x`'s whole set, as MemberActivity.load; an ability the data leaves out (Gen 1-2) stays if the form has it. */
function loadBuild(b, x) {
  const f = d.mon(b.num);
  if (b.ability) x.ability = b.ability;
  else if (x.ability !== f.hidden && !f.abilities.includes(x.ability)) x.ability = newMember(f).ability;
  return Object.assign(x, { num: b.num, item: b.item, nature: b.nature, tera: b.tera, level: b.level, evs: [...b.evs], ivs: [...b.ivs], moves: [...b.moves] });
}

/** One team member: ability, item, nature, Tera type, level, moves and stats; every change saves at once. */
function memberScreen(p, ti, k) {
  let teams;
  let team;
  let x;
  let g;
  const save = () => saveTeams(teams);
  /** Set by stats(): redraws totals, nature marks and the EV count after a level, IV or EV change. */
  let refreshStats = () => {};
  /** Smogon sets of the shown species' family in the team's game, one file scan per species. */
  let setsFor = 0;
  let sets = null;
  const builds = m => {
    if (setsFor !== m.species) {
      setsFor = m.species;
      sets = d.builds(g, new Set(d.family(m).filter(f => g.byNum.has(f.num)).map(f => f.num)));
    }
    return sets;
  };
  p.resume = render;
  render();

  function render() {
    teams = loadTeams();
    team = teams[ti];
    x = team?.members[k];
    if (!x) return close();
    g = d.game(team.game);
    build();
  }

  function build() {
    const m = d.mon(x.num);
    const types = d.typesOf(g, m);
    const art = sprite(m.num);
    art.onclick = () => openMon(m.num, null);
    const top = hero(deep(color(types[0])));
    top.append(h('div', 'member',
      h('div', null, h('h1', null, m.name), h('div', { style: 'font-size:14px;color:rgba(255,255,255,.8)' }, team.name + '  ·  ' + g.name), h('div', 'mt10', types.map(glass))),
      art));
    const swap = () => pickMon(g, L.f("Swap %s", m.name), [L.t("Suggested"), team.suggest(d, k).map(o => teamMonEntry(g, o))], nx => {
      nx.level = x.level;
      team.members[k] = x = nx;
      save();
      build();
    });
    const remove = () => confirm(L.f("Remove %s?", m.name), L.f("Its moves, item and stats are removed from %s.", team.name), L.t("Remove"), () => {
      team.members.splice(k, 1);
      save();
      close();
    });
    const y = p.el.scrollTop;
    p.el.replaceChildren(h('div', 'col', top,
      h('div', 'chips mt12', chip(L.t("Swap"), swap), chip(L.t("Compare"), () => open(q => compare(q, m.num, g.id))), chip(L.t("Damage calc"), () => open(q => calc(q, ti, k)))),
      forms(m), set(m), movesPanel(m), stats(m),
      h('div', 'chips', chip(L.t("Remove from team"), remove, 'danger'))));
    p.el.scrollTop = y;
  }

  /** Forms of the species in this game, the stage before and the stages after. */
  function forms(m) {
    const fam = d.family(m).filter(f => g.byNum.has(f.num) || f === m);
    const evo = d.evo(m.num);
    const from = evo && g.byNum.has(evo.from) ? d.mon(evo.from) : null;
    const into = [...new Set(d.evolutions(m.num).map(e => e.to))].filter(n => g.byNum.has(n)).map(n => d.mon(n));
    if (fam.length < 2 && !from && !into.length) return null;
    const to = f => () => {
      if (!f.abilities.includes(x.ability) && f.hidden !== x.ability) x.ability = newMember(f).ability;
      x.num = f.num;
      save();
      build();
    };
    return h('div', 'chips mt8',
      from ? chip(L.f("Devolve to %s", from.name), to(from)) : null,
      fam.length > 1 ? fam.map(f => chip(f.name, to(f), f === m ? 'on' : null)) : null,
      into.map(f => chip(L.f("Evolve into %s", f.name), to(f))));
  }

  function set(m) {
    const box = panel(L.t("Set"));
    const redo = () => {
      save();
      build();
    };
    const setting = (label, value, act) => h('button', { class: 'set', onclick: act }, h('span', null, label), h('span', 'v', value));
    const smogon = h('div');
    box.append(smogon);
    builds(m).then(all => {
      if (all.length) smogon.replaceWith(setting(L.t("Smogon set"), L.f("Sets: %d", all.length), () => pickBuild(all)));
    });
    if (g.gen >= 3) {
      const ids = [...new Set([...m.abilities, m.hidden].filter(id => id))];
      box.append(h('div', 'caption', L.t("Ability")), h('div', { class: 'wrap', style: 'margin:6px 0 8px' }, ids.map(id => {
        const name = d.ability(id).name;
        return chip(id === m.hidden ? L.f("%s (hidden)", name) : name, () => {
          x.ability = id;
          redo();
        }, id === x.ability ? 'on' : null);
      })));
      const a = d.ability(x.ability);
      if (a) box.append(h('p', 'note mb8', a.shortEffect));
    }
    if (g.gen >= 2) {
      box.append(setting(L.t("Item"), x.item ? d.item(x.item).name : L.t("None"), () => pickItem(m)));
    }
    if (g.gen >= 3) {
      const n = x.nature < 0 ? null : d.natures[x.nature];
      const label = !n ? L.t("Not set") : n.up === n.down ? L.f("%s (neutral)", n.name) : n.name + ' (+' + L.t(n.up) + ', -' + L.t(n.down) + ')';
      box.append(setting(L.t("Nature"), label, () => {
        const s = sheet(L.t("Nature"));
        s.body.append(natureChart(x.nature, i => {
          if (!s.close()) return;
          x.nature = i;
          redo();
        }));
        s.button(L.t("Cancel")).show();
      }));
    }
    if (g.id === 25 || (g.virtual && g.gen >= 9)) {
      box.append(setting(L.t("Tera type"), x.tera < 0 ? L.t("Not set") : d.label(d.types[x.tera]), () => pickType(L.t("Tera type"), d.types, x.tera, i => {
        x.tera = i;
        redo();
      })));
    }
    const LEVELS = [5, 50, 100];
    const quick = LEVELS.map(v => chip(String(v), () => {
      level.value = String(v);
      changed(v);
    }));
    const paint = () => quick.forEach((c, i) => c.classList.toggle('on', LEVELS[i] === x.level));
    const changed = v => {
      x.level = v;
      save();
      paint();
      refreshStats();
    };
    const level = numberBox(x.level, 1, () => 100, changed, L.t("Level"));
    paint();
    box.append(h('div', 'set', h('span', null, L.t("Level")), quick, level));
    return box;
  }

  function pickBuild(all) {
    const s = sheet(L.t("Smogon sets"));
    s.body.append(buildList(g, all, x.num, '', 'fill', b => chip(L.t("Use this set"), () => {
      if (!s.close()) return;
      loadBuild(b, x);
      save();
      build();
    }, 'on')));
    s.button(L.t("Cancel")).show();
  }

  /** Held items released by this game's generation: those in this Pokémon's Smogon sets first, most used on top. */
  async function pickItem(m) {
    const usable = d.items.filter(i => i.gen <= g.gen);
    const used = new Map();
    for (const b of await builds(m)) if (b.item) used.set(b.item, (used.get(b.item) ?? 0) + 1);
    const top = usable.filter(i => used.has(i.id)).sort((a, b) => used.get(b.id) - used.get(a.id));
    const make = i => entry(0, '', i.name, i.effect, [], i);
    pickSearch(L.t("Held item"), [], [[
      entry(0, '', L.t("No item"), '', [], 0),
      ...(top.length ? [header(L.t("Smogon sets")), ...top.map(make)] : []),
      ...itemSections(usable, make),
    ]], ref => {
      x.item = ref ? ref.id : 0;
      save();
      build();
    });
  }

  function movesPanel(m) {
    const box = panel(L.t("Moves"), 'flush wide mb12');
    x.moves.forEach((id, slot) => box.append(row(id ? moveEntry(d.move(g, id), '') : entry(0, '', L.t("Empty slot"), L.t("Tap to choose a move"), [], ''),
      () => pickMove(m, slot))));
    return box;
  }

  async function pickMove(m, slot) {
    const lg = d.learnGame(g, m);
    const cols = lg ? await d.learnset(lg, m) : null;
    const none = entry(0, '', L.t("No move"), L.t("Leave this slot empty"), [], 0);
    const learn = [none];
    const seen = new Set();
    if (cols) {
      cols.forEach((c, k) => {
        const rows = [];
        const add = (id, badge) => {
          if (seen.has(id)) return;
          seen.add(id);
          rows.push(moveEntry(d.move(g, id), badge));
        };
        if (k === 0) for (let i = 0; i < c.length; i += 2) add(c[i + 1], c[i] === 0 ? L.t("Evo") : L.f("Lv %d", c[i]));
        else for (const id of c) add(id, '');
        if (rows.length) learn.push(header(L.t(METHODS[k]) + (lg === g ? '' : ' (' + lg.name + ')')), ...rows);
      });
    }
    const rest = d.movesIn(g).filter(mv => !seen.has(mv.id)).map(mv => moveEntry(mv, ''));
    const other = header(cols ? L.f("Not learnable by %s in %s. For ROM hacks such as Unbound.", m.name, lg.name) : L.f("No learnset for %s: all moves", m.name));
    const done = ref => {
      const id = ref ? ref.id : 0;
      const j = id ? x.moves.indexOf(id) : -1;
      if (j >= 0 && j !== slot) x.moves[j] = x.moves[slot];
      x.moves[slot] = id;
      save();
      build();
    };
    const heading = L.f("Move %d", slot + 1);
    if (cols) pickSearch(heading, [L.t("Learnable"), L.t("Not learnable")], [learn, [other, ...rest]], done);
    else pickSearch(heading, [], [[none, other, ...rest]], done);
  }

  function stats(m) {
    const box = panel(L.t("Stats"));
    if (g.gen < 3) box.append(h('p', 'note mb8', L.f("Calculated with the Generation 3 and later formula. %s used DVs and stat experience instead.", g.name)));
    const mid = s => h('span', { style: 'width:60px;text-align:center' }, s);
    box.append(h('div', 'statrow top caption', h('span'), h('span', 'b', L.t("Base")), mid(L.t("IV")), mid(L.t("EV")), h('span', 'o', L.t("Stat"))));
    const labels = [];
    const totals = [];
    STATS.forEach((s, i) => {
      labels.push(h('span'));
      totals.push(h('span', 'o tnum'));
      const change = (list, v) => {
        list[i] = v;
        save();
        refreshStats();
      };
      box.append(h('div', 'statrow', labels[i], h('span', 'b tnum', m.stats[i]),
        numberBox(x.ivs[i], 0, () => Team.IV_MAX, v => change(x.ivs, v), L.t(s) + ' IV'),
        numberBox(x.evs[i], 0, () => Team.evRoom(x, i), v => change(x.evs, v), L.t(s) + ' EV'),
        totals[i]));
    });
    const left = h('p', 'note mt8');
    box.append(left);
    refreshStats = () => {
      const st = Team.stats(d, x);
      const n = x.nature < 0 ? null : d.natures[x.nature];
      STATS.forEach((s, i) => {
        const up = n && n.up !== n.down && n.up === s;
        const down = n && n.up !== n.down && n.down === s;
        labels[i].textContent = L.t(s) + (up ? ' +' : down ? ' -' : '');
        labels[i].className = up ? 'up' : down ? 'down' : '';
        totals[i].textContent = st[i];
      });
      left.textContent = L.f("%d of %d EVs left. At most %d per stat.", Team.EV_TOTAL - x.evs.reduce((a, b) => a + b, 0), Team.EV_TOTAL, Team.EV_STAT);
    };
    refreshStats();
    return box;
  }
}

// --- Compare and Damage calc -----------------------------------------------------------------

/** Values at both ends, the higher one marked; bars out of 255 between them when `bars`. */
function sides(mid, a, b, bars, la = String(a), lb = String(b)) {
  const v = (x, y, label) => h('span', x > y ? 'v best tnum' : 'v tnum', label);
  const bar = (x, left) => h('span', left ? 'side l' : 'side', bars ? h('i', { style: `width:${Math.min(100, (x * 100) / 255)}%;background:${tier(x)}` }) : null);
  return h('div', bars ? 'sides bars' : 'sides', v(a, b, la), bar(a, true), h('span', 'mid', mid), bar(b, false), v(b, a, lb));
}

/** Two Pokémon side by side: base stats, type defenses, abilities. Nothing is saved. */
function compare(p, first, gameId) {
  const g = d.game(gameId);
  const picked = [first, 0];
  const col = h('div', 'col');
  p.el.append(col);
  const pick = i => pickMon(g, L.t("Compare"), teamsTab(g), x => {
    picked[i] = x.num;
    render();
    if (i === 0 && !picked[1]) pick(1);
  });
  render();
  pick(picked[0] ? 1 : 0);

  function render() {
    const [a, b] = picked.map(n => (n ? d.mon(n) : null));
    const headCard = (m, i) => h('button', { class: 'headcard', onclick: () => pick(i) },
      m ? sprite(m.num) : h('div', { style: 'width:96px;height:96px' }),
      h('div', 'nm', m ? m.name : L.t("Choose a Pokémon")),
      m ? typePills(d.typesOf(g, m), 2) : null);
    col.replaceChildren(head(L.t("Compare")), h('p', 'sub', g.name), h('div', 'pair wide mb12', headCard(a, 0), headCard(b, 1)));
    if (!a || !b) return;
    const base = panel(L.t("Base stats"));
    base.append(...STATS.map((s, i) => sides(L.t(s), a.stats[i], b.stats[i], true)), sides(L.t("Total"), a.bst, b.bst, false));
    const ta = d.typesOf(g, a);
    const tb = d.typesOf(g, b);
    const def = panel(L.t("Type defenses"));
    for (const t of d.typesIn(g)) {
      const fa = d.factor(g, t, ta);
      const fb = d.factor(g, t, tb);
      if (fa !== 100 || fb !== 100) def.append(sides(pill(t), -fa, -fb, false, effect(fa), effect(fb)));
    }
    const names = m => h('div', null, m.abilities.map(id => h('div', { style: 'font-size:15px' }, d.ability(id).name)),
      m.hidden && !m.abilities.includes(m.hidden) ? h('div', { style: 'font-size:15px' }, L.f("%s (hidden)", d.ability(m.hidden).name)) : null);
    const abil = panel(L.t("Abilities"));
    abil.append(h('div', 'pair', names(a), names(b)));
    col.append(base, def, abil);
  }
}

/** Damage of one move between two Pokémon. Starts from a team member when given one; nothing is saved back. */
function calc(p, ti, k) {
  const team = loadTeams()[ti];
  const g = team ? d.game(team.game) : game();
  const picked = [team && k >= 0 ? team.members[k] : null, null];
  const firstMove = x => x.moves.find(id => id && d.move(g, id).power > 0) ?? 0;
  let move = picked[0] ? firstMove(picked[0]) : 0;
  const col = h('div', 'col');
  const out = panel(L.t("Damage"));
  p.el.append(col);
  render();
  pick(picked[0] ? 1 : 0);

  function pick(i) {
    pickMon(g, i ? L.t("Defender") : L.t("Attacker"), teamsTab(g), x => {
      picked[i] = x;
      if (i === 0) move = firstMove(x);
      render();
      if (!picked[1]) pick(1);
      else if (!move) pickMove();
    });
  }

  function pickMove() {
    const all = d.movesIn(g).filter(mv => mv.category !== 'status').map(mv => moveEntry(mv, ''));
    const own = picked[0] ? picked[0].moves.filter(id => id).map(id => d.move(g, id)).filter(mv => mv.category !== 'status').map(mv => moveEntry(mv, '')) : [];
    pickSearch(L.t("Move"), own.length ? [L.t("Its moves"), L.t("All")] : [], own.length ? [own, all] : [all], ref => {
      move = ref.id;
      render();
    });
  }

  function render() {
    const heads = picked.map((x, i) => {
      const m = x && d.mon(x.num);
      const level = x ? numberBox(x.level, 1, () => 100, v => {
        x.level = v;
        result();
      }, L.t("Level")) : null;
      level?.addEventListener('click', e => e.stopPropagation());
      return h('div', { class: 'headcard', role: 'button', tabindex: '0', onclick: () => pick(i) },
        h('div', 'caption', i ? L.t("Defender") : L.t("Attacker")),
        m ? sprite(m.num) : null, h('div', { style: m ? null : 'height:80px' }),
        h('div', 'nm', m ? m.name : L.t("Choose a Pokémon")),
        x ? h('div', 'lvl', h('span', 'muted', L.t("Level")), level) : null);
    });
    for (const img of heads.map(c => c.querySelector('img')).filter(i => i)) img.style.cssText = 'width:80px;height:80px';
    const mv = panel(L.t("Move"), 'flush wide mb12');
    mv.append(row(move ? moveEntry(d.move(g, move), '') : entry(0, '', L.t("Choose a move"), '', [], ''), pickMove));
    col.replaceChildren(head(L.t("Damage calc")), h('p', 'sub', g.name), h('div', 'pair wide mb12', heads),
      h('div', 'chips', chip(L.t("Swap sides"), () => {
        picked.reverse();
        render();
      })),
      mv, out,
      h('p', 'note pad', L.t("Gen 5 and later formula. Not counted: abilities, items, weather, critical hits, stat changes and burns.")));
    result();
  }

  function result() {
    out.replaceChildren(h('h2', null, L.t("Damage")));
    const [a, b] = picked;
    if (!a || !b || !move) return out.append(h('p', 'muted', L.t("Choose both Pokémon and a move.")));
    const mv = d.move(g, move);
    if (mv.category === 'status' || !mv.power) {
      return out.append(h('p', 'muted', L.f("%s has no base power: status moves and fixed damage such as Seismic Toss are not calculated.", mv.name)));
    }
    const ma = d.mon(a.num);
    const mb = d.mon(b.num);
    const special = mv.category === 'special';
    const sa = Team.stats(d, a);
    const sb = Team.stats(d, b);
    const atk = special ? 3 : 1;
    const guard = special ? 4 : 2;
    const factor = d.factor(g, mv.type, d.typesOf(g, mb));
    const stab = d.typesOf(g, ma).includes(mv.type);
    const hit = Team.damage(a.level, mv.power, sa[atk], sb[guard], stab, factor);
    const hp = sb[0];
    const fast = hit[1] ? Math.ceil(hp / hit[1]) : 0;
    const slow = hit[0] ? Math.ceil(hp / hit[0]) : 0;
    const ko = hit[1] === 0 ? L.t("No effect") : slow === 1 ? L.t("Faints in one hit")
      : fast === slow ? L.f("Faints in %d hits", fast) : L.f("Faints in %d to %d hits", fast, slow);
    const top = Math.min(hit[1], hp);
    const fact = (k, v) => h('div', 'fact wide-v', h('span', 'k', k), h('span', 'v', v));
    out.append(
      h('div', { class: 'bold tnum', style: 'font-size:30px' }, L.f("%d%% to %d%%", Math.trunc((hit[0] * 100) / hp), Math.trunc((hit[1] * 100) / hp))),
      h('div', 'hp', h('i', { style: `width:${(top * 100) / hp}%` }, h('i', { style: `width:${top ? (Math.min(hit[0], hp) * 100) / top : 0}%` }))),
      h('div', { class: 'tnum', style: 'font-size:15px' }, L.f("%d to %d of %d HP", hit[0], hit[1], hp)),
      h('div', { class: 'bold', style: 'font-size:17px;margin:6px 0 8px' }, ko),
      fact(L.t(STATS[atk]) + '  ·  ' + ma.name, sa[atk]),
      fact(L.t(STATS[guard]) + '  ·  ' + mb.name, sb[guard]),
      fact(L.t("Power"), mv.power),
      fact(L.t("Effectiveness"), effect(factor)),
      fact(L.t("Same type"), stab ? 'x1.5' : 'x1'));
  }
}

// --- start -----------------------------------------------------------------------------------

async function fetchText(path) {
  try {
    const r = await fetch('assets/' + path);
    return r.ok ? await r.text() : null;
  } catch (e) {
    return null;
  }
}

/** The stored language, else the device's when the app has it, else English. */
function wanted() {
  const want = store.get('lang', '');
  if (L.LANGS.includes(want)) return want;
  return L.LANGS.find(l => (navigator.language || '').toLowerCase().startsWith(l)) ?? 'en';
}

/** Loads the UI table and the data; keeps the old ones when either fails. */
async function start() {
  const lang = wanted();
  let table = new Map();
  if (lang !== 'en') {
    const text = await fetchText(`lang/${lang}.tsv`);
    if (text === null) throw new Error('missing lang/' + lang + '.tsv');
    table = L.read(text);
  }
  const old = [L.lang, L.table];
  // Dex.load already translates (egg group separator, generation names).
  L.lang = lang;
  L.table = table;
  try {
    d = await Dex.load(fetchText, lang);
  } catch (e) {
    [L.lang, L.table] = old;
    throw e;
  }
  nationalNums = null;
  document.documentElement.lang = lang;
}

/** After a language change: home and Settings again, at the history depth Settings already has. */
async function restart() {
  await start();
  for (const p of pages.splice(0)) p.el.remove();
  mount(home);
  mount(settings);
}

function update() {
  updating = true;
  waiting.postMessage('skip');
}

(async () => {
  try {
    await start();
  } catch (e) {
    document.getElementById('boot').textContent = L.t("Could not load the data. Check the connection and reload.");
    throw e;
  }
  document.getElementById('boot').remove();
  history.replaceState({ depth: 0 }, '');
  mount(home);

  if (!('serviceWorker' in navigator)) return;
  navigator.serviceWorker.addEventListener('controllerchange', () => { if (updating) location.reload(); });
  const reg = await navigator.serviceWorker.register('sw.js');
  keepArt();
  const offer = w => {
    waiting = w;
    pages[0]?.resume?.();
  };
  if (reg.waiting && navigator.serviceWorker.controller) offer(reg.waiting);
  reg.addEventListener('updatefound', () => {
    const w = reg.installing;
    w.addEventListener('statechange', () => { if (w.state === 'installed' && navigator.serviceWorker.controller) offer(w); });
  });
  // A home screen app stays open for days; look for a new build whenever it comes back to the front.
  document.addEventListener('visibilitychange', () => { if (!document.hidden) reg.update(); });
})();
