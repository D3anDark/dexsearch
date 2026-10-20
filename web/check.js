'use strict';
// Self-check of dex.js against the same assets and expectations as Dex.main: node web/check.js assets
const fs = require('fs');
const path = require('path');
const { L, Dex, Team, height, weight, SORTS, WHOLE, GO } = require('./dex.js');

const assets = process.argv[2];
const fetchText = async p => {
  const f = path.join(assets, p);
  return fs.existsSync(f) ? fs.readFileSync(f, 'utf8') : null;
};
const check = (ok, what) => {
  if (!ok) throw new Error('check failed: ' + what);
};
const sort = label => SORTS.find(s => s.label === label);
const first = list => (list.length ? list[0].mon.name : '<none>');
const tree = rows => rows.map(r => r.join('/')).join(', ');
const lang = async code => {
  L.lang = code;
  L.table = code === 'en' ? new Map() : L.read(await fetchText(`lang/${code}.tsv`));
  return Dex.load(fetchText, code);
};

(async () => {
  const d = await lang('en');
  const none = new Set();
  const dex = (q, types = none, gens = none, by = 'No.') => d.pokedex(d.whole, d.national, q, types, gens, 0, sort(by));
  check(dex('').length === d.mons.length, 'empty query returns all');
  check(first(dex('mr mime')) === 'Mr. Mime', 'punctuation ignored');
  check(first(dex('FLABEBE')) === 'Flabébé', 'accents and case ignored');
  check(first(dex('#006')) === 'Charizard', 'number with # and zeros');
  check(dex('char').filter(e => e.mon.num === e.mon.species).length === 7, 'substring match');
  const fireFlying = dex('', new Set(['fire', 'flying']));
  check(first(fireFlying) === 'Charizard' && fireFlying.every(e => e.mon.types.length === 2), 'types are AND');
  check(dex('', none, new Set([1])).length === 151, 'gen 1 has 151');
  check(first(dex('', none, none, 'BST')) === 'Eternamax Eternatus', 'BST sort descending');
  check(first(dex('', none, none, 'Name')) === '10% Zygarde', 'name sort');
  check(first(dex('', none, none, 'Spe')) === 'Regieleki', 'speed sort');
  check(d.pokedex(d.whole, d.national, '', none, none, 600, sort('No.')).every(e => e.mon.bst >= 600), 'BST floor');

  const charizard = d.mon(6);
  check(d.evo(5)?.how === 'Lv 16' && d.evo(5)?.from === 4 && d.evo(4) === null, 'evolution level');
  check(tree(d.chain(d.mon(53))) === '52/0, 53/1, 10107/0, 10108/1, 10161/0, 863/1', 'Meowth tree');
  check(tree(d.chain(d.mon(83))) === '83/0, 10166/0, 865/1', "Farfetch'd beside Sirfetch'd's line");
  check(tree(d.chain(d.mon(840))) === '840/0, 841/1, 842/1, 1011/1, 1019/2', 'Applin tree');
  const national = d.national.map(e => e.mon.num);
  const megaX = d.family(charizard)[1].num;
  check(d.neighbor(national, 6, 1) === 7 && d.neighbor(national, megaX, 1) === 7 && d.neighbor(national, megaX, -1) === 5
    && d.neighbor(national, 1, -1) === 0 && d.neighbor([6, megaX], 6, 1) === 0 && d.neighbor([1], 6, 1) === -1, 'previous/next skip forms');
  check(d.factor(d.whole, 'rock', charizard.types) === 400 && d.factor(d.whole, 'ground', charizard.types) === 0
    && d.factor(d.whole, 'water', d.mon(9).types) === 50, 'type factors');

  const sv = d.game(25);
  const za = d.game(30);
  const frlg = d.game(7);
  check(sv.name === 'Scarlet / Violet' && sv.multiDex && sv.dex[0].mon.name === 'Sprigatito', 'Paldea starts with Sprigatito');
  check(first(d.pokedex(sv, sv.dex, '1', none, none, 0, sort('No.'))) === 'Sprigatito', 'regional number search');
  check(za.byNum.get(152).number === 1, 'Lumiose #1 is Chikorita');
  check(frlg.byNum.get(151).dex === 'Kanto' && frlg.byNum.get(152).dex === 'National' && frlg.byNum.has(252), 'FireRed dexes');
  check(sv.tms.get(53) === 'TM125', 'Flamethrower is TM125 in SV');
  const charmander = await d.learnset(sv, d.mon(4));
  check(charmander[0][4] === 4 && charmander[0][5] === 52, 'Charmander learns Ember at Lv 4 in SV');
  check(d.learnGame(za, d.mon(906)) === sv && (await d.moveGame(za, d.move(53))) === za, 'species missing from Z-A fall back to SV');
  const pursuit = await d.moveGame(d.whole, d.move(228));
  check(d.whole.dex.length === d.mons.length && d.whole.byNum.get(10100).number === 26 && d.whole.gen === 9
    && d.learnGame(d.whole, d.mon(19)).id === 23 && d.learnGame(d.whole, d.mon(445)) === sv && d.learnGame(d.whole, d.mon(16)) === za
    && (await d.moveGame(d.whole, d.move(53))) === sv && pursuit !== sv && (await d.learners(pursuit, 228)).length > 0
    && d.tmGame(d.whole).tms.get(53) === 'TM125', 'Whole Pokédex learnsets and TMs');
  const gen1 = d.game(WHOLE + 1);
  const gen5 = d.generations[4];
  const gen6 = d.generations[5];
  check(gen1.name === 'Gen 1 Pokédex' && gen1.dex.length === 151 && d.generations[6].byNum.has(10100)
    && d.generations.reduce((n, g) => n + g.dex.length, 0) === d.national.length, 'generation dexes');
  check(d.typesIn(gen1).length === 15 && d.typesIn(gen5).length === 17 && d.typesIn(gen6).length === 18
    && d.typesOf(gen1, d.mon(35)).join() === 'normal' && d.typesOf(gen6, d.mon(35)).join() === 'fairy'
    && d.typesOf(gen5, d.mon(81)).join() === 'electric,steel', 'era types');
  check(d.factor(gen1, 'ghost', ['psychic']) === 0 && d.factor(gen6, 'ghost', ['psychic']) === 200
    && d.factor(gen5, 'ghost', ['steel']) === 50 && d.factor(gen6, 'ghost', ['steel']) === 100, 'era type chart');
  check(d.move(gen1, 44).type === 'normal' && d.move(d.game(3), 44).type === 'dark'
    && d.move(d.game(7), 7).category === 'special' && d.move(d.game(8), 7).category === 'physical'
    && d.movesIn(gen1).length === 165, 'era moves');
  const go = d.game(GO);
  const goChomp = await d.learnset(go, d.mon(445));
  check(go.name === 'Pokémon GO' && goChomp[0].length === 0 && goChomp[5].includes(341) && goChomp[7].includes(414)
    && d.learnGame(go, d.mon(445)) === go && d.learnGame(sv, d.mon(16)) !== go, 'Pokémon GO');
  const fire = await d.info(frlg, charizard);
  check(fire[0].startsWith('Evolve Charmander') && fire[1].includes('\n\nLeafGreen: '), 'FireRed info');
  check((await d.learners(sv, 53)).some(r => r[0] === 6 && r[1] === 1), 'Charizard learns Flamethrower by TM');
  check((await d.learnset(za, d.mon(10034))) === (await d.learnset(za, charizard)), 'Mega Charizard X: species learnset');

  const chomp = Team.member(445, 24, 100);
  chomp.nature = d.natures.findIndex(n => n.name === 'Adamant');
  chomp.evs[1] = 252;
  const st = Team.stats(d, chomp);
  check(st[0] === 357 && st[1] === 394 && st[3] === 176, 'Garchomp Lv100 Adamant 252 Atk');
  chomp.evs[5] = 252;
  check(Team.evRoom(chomp, 2) === 6 && Team.evRoom(chomp, 1) === 252, 'EV room');
  chomp.moves[0] = 89;
  const team = new Team('Test\tteam', 25);
  team.members.push(chomp, Team.member(10100, d.mon(10100).abilities[0], 50));
  const saved = Team.formatAll([team, team]);
  check(Team.parseAll(saved + '\n    ').length === 2, 'blank lines skipped');
  const back = Team.parseAll(saved)[0];
  check(back.name === 'Test team' && back.members[0].evs[5] === 252 && back.format() === team.format(), 'team round trip');
  check(team.export(d).startsWith('Garchomp\nAbility: ') && team.export(d).includes('EVs: 252 Atk / 252 Spe\nAdamant Nature\n- Earthquake'), 'Showdown text');
  const crystal = new Team('', 4);
  crystal.members.push(Team.member(143, d.mon(143).abilities[0], 50));
  check(crystal.export(d) === 'Snorlax\nLevel: 50', 'no Ability line before Generation 3');

  const lone = new Team('', 25);
  lone.members.push(Team.member(445, 0, 50));
  const svTypes = d.typesIn(sv);
  const bare = lone.gaps(d);
  check(bare.stacked.map(i => svTypes[i]).sort().join() === 'dragon,fairy,ice' && !bare.counter.length && svTypes[bare.wall] === 'steel' && bare.move === -1, 'Garchomp alone');
  lone.members[0].moves[0] = 89;
  const eq = lone.gaps(d);
  check(['flying', 'ice'].every(t => eq.counter.map(i => svTypes[i]).includes(t)) && eq.move >= 0, 'Garchomp with Earthquake');
  lone.members[0].moves[0] = 0;
  lone.members.push(Team.member(714, 0, 50));
  const twin = lone.overlaps(d);
  check(twin.length === 1 && twin[0].weak.map(i => svTypes[i]).sort().join() === 'dragon,fairy,ice', 'Garchomp and Noibat overlap');
  const swap = lone.suggest(d, 1);
  check(swap.length === 30 && swap.every(m => !d.evolutions(m.num).length && !Team.legend(m.species))
    && ['ice', 'dragon', 'fairy'].every(t => d.factor(sv, t, d.typesOf(sv, swap[0])) < 100), 'Noibat swap suggestions');
  const fox = new Team('', WHOLE);
  fox.members.push(Team.member(10103, 0, 37), Team.member(448, 0, 49));
  const eevee = Team.member(133, 0, 20);
  const kept = Team.member(10103, 0, 37);
  kept.item = Team.EVIOLITE;
  check(fox.evolved(d, d.whole, fox.members[0]).num === 10104 && !fox.overlaps(d).length && fox.evolved(d, d.whole, eevee).num === 133
    && fox.evolved(d, d.whole, kept).num === 10103, 'Alolan Vulpix counts as Alolan Ninetales, so no overlap with Lucario; Eevee and an Eviolite stay');
  const umbreon = { ...Team.member(133, 0, 20), evo: 197 };
  const gallade = { ...Team.member(280, 0, 20), evo: 475 };
  const picked = new Team('', WHOLE);
  picked.members.push(umbreon);
  check(fox.evolved(d, d.whole, umbreon).num === 197 && fox.evolved(d, d.whole, gallade).num === 475
    && Team.parse(picked.format()).members[0].evo === 197 && Team.parse(fox.format()).members[0].evo === 0
    && fox.branches(d, d.whole, umbreon).length === 8 && !fox.branches(d, d.whole, Team.member(236, 0, 20)).length,
    'Eevee picked as Umbreon and Ralts as Gallade count as them and keep the pick; Tyrogue asks nothing');
  const lesson = new Team('', sv.id);
  lesson.members.push(Team.member(445, 0, 50));
  lesson.members[0].moves[0] = 337;
  const open = await lesson.teach(d, 'fire');
  lesson.members[0].moves = [337, 89, 14, 523];
  const full = await lesson.teach(d, 'fire');
  check(open && open.slot === 1 && d.move(sv, open.move).type === 'fire' && d.move(sv, open.move).category === 'physical'
    && full && full.slot === 3 && full.move === open.move, 'Garchomp learns a physical Fire move, else in place of Bulldoze: ' + JSON.stringify([open, full]));
  check(Team.damage(75, 65, 123, 163, true, 400).join() === '168,196' && Team.damage(50, 40, 10, 400, false, 50)[0] === 1
    && Team.damage(50, 90, 100, 100, true, 0)[1] === 0, 'damage');

  check(d.search(d.whole, 'char', 20).find(r => r[0] === 'Pokémon')[1][0].name === 'Charmander', 'global search prefix first');
  check(d.search(d.whole, '25', 5).find(r => r[0] === 'Pokémon')[1][0].name === 'Pikachu', 'global number search');
  check(d.search(d.whole, 'sash', 5).find(r => r[0] === 'Items')[1][0].name === 'Focus Sash', 'global item search');
  const fireSpecial = Dex.moves(d.movesIn(sv), '', new Set(['fire']), new Set(['special']), 'Power', m => sv.tms.get(m.id) ?? '');
  check(fireSpecial.every(m => m.type === 'fire' && m.category === 'special') && fireSpecial[0].power >= fireSpecial[1].power
    && Dex.moves(d.movesIn(sv), 'tm125', none, none, 'No.', m => sv.tms.get(m.id) ?? '')[0].id === 53, 'move filter, sort and TM label');
  const chart = d.natureChart();
  check(new Set(chart.flat()).size === 25 && d.natures[chart[0][2]].name === 'Adamant' && d.natures[chart[4][4]].name === 'Serious', 'nature chart');
  check(height(19, true) === `6'03"` && weight(950, true) === '209.4 lbs' && height(4, false) === '0.4 m' && weight(60, false) === '6.0 kg', 'height and weight');

  const ja = await lang('ja');
  check(ja.mon(6).name === 'リザードン' && ja.label('fire') === 'ほのお'
    && ja.search(ja.whole, 'りざーどん', 5).find(r => r[0] === 'Pokémon')[1][0].num === 6
    && ja.search(ja.whole, 'charizard', 5).find(r => r[0] === 'Pokémon')[1][0].num === 6 && team.export(ja) === team.export(d), 'Japanese');
  const es = await lang('es');
  check(es.move(53).name === 'Lanzallamas' && es.move(gen1, 44).name === es.move(44).name && es.game(25).name === 'Escarlata / Púrpura'
    && height(4, false) === '0,4 m' && es.regions[4] === 'Teselia' && L.f('%d%% to %d%%', 5, 7) === '5% a 7%'
    && L.f('%.1f%% male, %.1f%% female', 87.5, 12.5) === '87,5% macho, 12,5% hembra', 'Spanish');
  console.log(`dex.js self-check OK (${d.mons.length} Pokémon and forms, ${d.moves.length} moves)`);
})().catch(e => {
  console.error(e.message);
  process.exit(1);
});
