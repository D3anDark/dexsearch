package demo.dex

import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import java.util.TreeMap
import kotlin.math.roundToInt

/** Offline Pokédex data and queries, read from the TSVs gen_data.py writes. No Android deps. */
class Dex private constructor(private val assets: Assets) {

    fun interface Assets {
        fun open(path: String): InputStream
    }

    /**
     * num, name, genus, types, gen, stats, abilities, hidden ability, height dm, weight hg, catch rate,
     * gender rate (female eighths, -1 genderless), egg groups, egg cycles, base friendship, growth rate,
     * evolution chain, evolves from and method (unread: evo.tsv has them with forms), games, flavor text, species.
     * A form copies its species' Pokédex columns (genus, breeding, evolution, flavor) and has its own types, stats, abilities.
     * `n` holds names in the app's language (gen_names.py) by "kind\tkey"; anything missing stays English.
     */
    class Mon(f: List<String>, n: Map<String, String> = emptyMap()) {
        /** The species number, or for an alternate form (Alolan, Mega...) its own id from 10001 up. */
        val num = f[0].toInt()
        /** English name, for Showdown text; [name] is the one shown. */
        val en = f[1]
        val name = n["mon\t${f[0]}"] ?: en
        val genus = n["genus\t${f[21]}"] ?: f[2]
        val types: List<String> = f[3].split(',')
        val gen = f[4].toInt()
        val stats = ints(f[5], ',')
        val bst = stats.sum()
        val abilities = ints(f[6], ',')
        val hidden = optInt(f[7])
        val height = optInt(f[8])
        val weight = optInt(f[9])
        val captureRate = optInt(f[10])
        val genderRate = optInt(f[11])
        val eggGroups = f[12].split(", ").joinToString(L.t(", ")) { n["egg\t$it"] ?: it }
        val hatchCounter = optInt(f[13])
        val happiness = optInt(f[14])
        val growth = f[15]
        val chain = optInt(f[16])
        /** Games with learnset data for this species, ascending; for a form, the games it appears in. */
        val games = ints(f[19], ' ')
        val flavor = f[20]
        val species = f[21].toInt()
        val key = key(name, en)

        fun form() = num != species
    }

    /** `name` is the one shown, [en] the English one for Showdown text; the same for Ability and Item. */
    class Move(f: List<String>, val name: String = f[1]) {
        val id = f[0].toInt()
        val en = f[1]
        val type = f[2]
        val category = f[3]
        val power = optInt(f[4])
        val accuracy = optInt(f[5])
        val pp = optInt(f[6])
        val priority = optInt(f[7])
        val gen = optInt(f[8])
        val chance = optInt(f[9])
        val shortEffect = f[10]
        val effect = f[11].replace("\\n", "\n")
        val key = key(name, en)

        /** This move with an older generation's values (past.tsv) or category. */
        fun with(type: String, category: String, power: Int, accuracy: Int, pp: Int, priority: Int) =
            Move(listOf(id.toString(), en, type, category, power.toString(), accuracy.toString(), pp.toString(),
                priority.toString(), gen.toString(), chance.toString(), shortEffect, effect), name)
    }

    class Ability(f: List<String>, val name: String = f[1]) {
        val id = f[0].toInt()
        val en = f[1]
        val gen = optInt(f[2])
        val shortEffect = f[3]
        val effect = f[4].replace("\\n", "\n")
        val key = key(name, en)
    }

    /** A held item: Leftovers, Charizardite X, Sitrus Berry. */
    class Item(f: List<String>, val name: String = f[1]) {
        val id = f[0].toInt()
        val en = f[1]
        val gen = optInt(f[2])
        /** Without PokeAPI's "Held: ": every item here is one to hold. */
        val effect = f[3].removePrefix("Held: ")
        /** One of [ITEM_GROUPS]. */
        val group = f[4]
        val key = key(name, en)
    }

    /** [up] and [down] are English [STATS] entries, "" for a neutral nature. */
    class Nature(f: List<String>, val name: String = f[0]) {
        val en = f[0]
        val up = f[1]
        val down = f[2]
    }

    /** A Smogon set (gen_sets.py). Ability 0 keeps the Pokémon's own, item 0 is none, nature and tera -1 when unset. */
    class Build(f: List<String>) {
        val num = f[0].toInt()
        val format = f[1]
        val name = f[2]
        val level = f[3].toInt()
        val ability = f[4].toInt()
        val item = f[5].toInt()
        val nature = f[6].toInt()
        val tera = f[7].toInt()
        val evs = ints(f[8], ',')
        val ivs = ints(f[9], ',')
        val moves = ints(f[10], ',')
    }

    /** One evolution (gen_evo.py): species or form numbers, gender 1 if only females evolve this way, 2 males, 0 any. */
    class Evo(f: List<String>) {
        val from = f[0].toInt()
        val to = f[1].toInt()
        val gender = f[2].toInt()
        /** "Lv 16", "Dawn Stone", "Thunder Stone in Alola". */
        val how = f[3]
    }

    /** A Pokédex row: the species and its number in the dex being shown. */
    class Entry(
        val mon: Mon,
        val number: Int,
        /** Name of the regional dex the number belongs to, in the app's language; "" in the national dex. */
        val dex: String,
    )

    class Game private constructor(
        val id: Int,
        val name: String,
        /** Generation whose types and type chart apply. */
        val gen: Int,
        val multiDex: Boolean,
        /** Version group whose learnsets and move values apply: the game's own, or a generation's last core game. */
        val versionGroup: Int = id,
    ) {
        /** The Whole Pokédex or a generation's, not a game: national numbers, no TMs of its own (see [tmGame]). */
        val virtual get() = id >= WHOLE
        /**
         * Regional dexes concatenated; a species listed in several (Paldea, Kitakami) appears once, first dex wins.
         * Each species is followed by its forms found in this game, under the same number.
         */
        val dex = ArrayList<Entry>()
        val byNum = HashMap<Int, Entry>()
        /** Move id to machine label, e.g. 53 to "TM125". */
        val tms = LinkedHashMap<Int, String>()

        constructor(f: List<String>, d: Dex) : this(f[0].toInt(), d.names["game\t${f[0]}"] ?: f[1], f[4].toInt(), ';' in f[2]) {
            for (dx in f[2].split(';')) {
                val eq = dx.indexOf('=')
                for (pair in dx.substring(eq + 1).split(' ')) {
                    val p = ints(pair, ':')
                    if (p[1] in byNum) continue
                    val name = d.dexName(dx.substring(0, eq))
                    for (m in d.family(d.mon(p[1]))) {
                        if (m.form() && id !in m.games) continue
                        val e = Entry(m, p[0], name)
                        dex.add(e)
                        byNum[m.num] = e
                    }
                }
            }
            if (f[3].isNotEmpty()) {
                for (pair in f[3].split(' ')) {
                    val colon = pair.indexOf(':')
                    tms[pair.substring(0, colon).toInt()] = pair.substring(colon + 1)
                }
            }
        }

        /** Every species and form in national order, like pokemondb's National Pokédex; TMs from [tmGame]. */
        constructor(d: Dex) : this(WHOLE, L.t("Whole Pokédex"), d.mons.maxOfOrNull { it.gen } ?: 1, false) {
            val name = d.dexName("National")
            for (e in d.national) {
                val x = Entry(e.mon, e.number, name)
                dex.add(x)
                byNum[x.mon.num] = x
            }
        }

        /** Species and forms introduced in generation `n`, in national order, with that generation's mechanics. */
        constructor(d: Dex, n: Int) : this(WHOLE + n, L.f("Gen %d Pokédex", n), n, false, ERA_GAMES[n - 1]) {
            val name = d.dexName(REGIONS[n - 1])
            for (e in d.national) {
                if (e.mon.gen != n) continue
                val x = Entry(e.mon, e.number, name)
                dex.add(x)
                byNum[x.mon.num] = x
            }
        }
    }

    enum class Sort(
        /** English; shown through [L.t]. */
        val label: String,
        /** Sorted descending; null for NUMBER (dex order) and NAME. */
        val stat: ((Entry) -> Int)?,
    ) {
        NUMBER(L.k("No."), null),
        NAME(L.k("Name"), null),
        BST(L.k("BST"), { it.mon.bst }),
        HP(STATS[0], { it.mon.stats[0] }),
        ATK(STATS[1], { it.mon.stats[1] }),
        DEF(STATS[2], { it.mon.stats[2] }),
        SPA(STATS[3], { it.mon.stats[3] }),
        SPD(STATS[4], { it.mon.stats[4] }),
        SPE(STATS[5], { it.mon.stats[5] }),
    }

    /** Labels in English; shown through [L.t]. */
    enum class MoveSort(val label: String) {
        ORDER(L.k("No.")), NAME(L.k("Name")), POWER(L.k("Power")), ACCURACY(L.k("Accuracy")), PP(L.k("PP")),
    }

    /** Species in national order, then forms by id. */
    val mons = ArrayList<Mon>()
    val items = ArrayList<Item>()
    val moves = ArrayList<Move>()
    val abilities = ArrayList<Ability>()
    val natures = ArrayList<Nature>()
    val games = ArrayList<Game>()
    val types = ArrayList<String>()
    /** Every species, each followed by its forms. */
    val national = ArrayList<Entry>()
    private val monByNum = HashMap<Int, Mon>()
    private val forms = HashMap<Int, MutableList<Mon>>()
    /** Forms left out for sharing their species' types and stats (Pikachu in a cap, Gigantamax): id to species. */
    private val merged = HashMap<Int, Int>()
    /** Evolutions by the species or form they lead to (each has one parent), and by the one they start from. */
    private val evoInto = HashMap<Int, Evo>()
    private val evoFrom = HashMap<Int, MutableList<Evo>>()
    private val itemById = HashMap<Int, Item>()
    private val moveById = HashMap<Int, Move>()
    private val abilityById = HashMap<Int, Ability>()
    /** Every name file row while loading (about 4,400 for Japanese), then emptied: only [labels] stay. */
    private var names: Map<String, String> = emptyMap()
    /** Type and move category names in the app's language, "type\tfire" to "ほのお". */
    private val labels = HashMap<String, String>()
    /** All species and forms, not tied to a game; not in [games]. */
    lateinit var whole: Game
    /** A Pokédex per generation, Gen 1 first; not in [games]. */
    val generations = ArrayList<Game>()
    /** Each generation's region ([REGIONS]) in the app's language, Gen 1 first: "Teselia". */
    lateinit var regions: List<String>
    private lateinit var factors: Array<IntArray>
    /** Generation each type arrived in: Steel and Dark in 2, Fairy in 6. */
    private val typeGen = HashMap<String, Int>()
    /** Older matchups as {attack, defend ([types] indices), factor, last generation}, oldest first. */
    private val pastChart = ArrayList<IntArray>()
    /** Older types by Pokémon id as {last generation, types}, oldest first: Clefairy is Normal up to Gen 5. */
    private val pastTypes = HashMap<Int, MutableList<Pair<Int, List<String>>>>()
    /** Older moves by id as {version group that changed it, the move before}, oldest first. */
    private val pastMoves = HashMap<Int, MutableList<Pair<Int, Move>>>()
    /** Per game id, its moves that differ from today's; at most two games (selected one plus a team's). */
    private val eraCache = object : LinkedHashMap<Int, Map<Int, Move>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Map<Int, Move>>?) = size > 2
    }
    /** Games with any learnset data, ascending. */
    private lateinit var learnGames: IntArray
    /** The newest generation's game with the most learnsets: where the Whole Pokédex looks up moves first. */
    private lateinit var widest: Game
    /** Parsed learn/<game>.tsv, at most two games (selected one plus a fallback); each is a few hundred KB. */
    private val learnCache = object : LinkedHashMap<Int, Map<Int, Array<IntArray>>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Map<Int, Array<IntArray>>>?) = size > 2
    }

    fun mon(num: Int): Mon = monByNum.getValue(num)

    fun monOrNull(num: Int): Mon? = monByNum[num]

    /** A team saved with a form since left out (Gigantamax Charizard) gets its species instead. */
    fun known(num: Int) = merged[num] ?: num

    /** The species, then its forms (Raichu, Alolan Raichu, Mega Raichu X, Mega Raichu Y). */
    fun family(m: Mon): List<Mon> {
        val base = mon(m.species)
        return listOf(base) + forms[base.num].orEmpty()
    }

    /**
     * The Pokémon `step` places from `num`'s species in `order` (Pokédex or search order), passing over that species'
     * forms, which the Forms and looks card reaches, and landing on a species rather than its last form; 0 past either
     * end, -1 when the species is not in `order`.
     */
    fun neighbor(order: IntArray, num: Int, step: Int): Int {
        val species = mon(num).species
        var j = order.indexOfFirst { mon(it).species == species }
        if (j < 0) return -1
        while (j in order.indices && mon(order[j]).species == species) j += step
        if (j !in order.indices) return 0
        val other = mon(order[j]).species
        return if (other in order) other else order[j]
    }

    fun item(id: Int): Item = itemById.getValue(id)

    fun move(id: Int): Move = moveById.getValue(id)

    /** Move `id` as it was in `g`: Bite is Normal in Gen 1, Charm Normal before Gen 6, Fire Punch special before Gen 4. */
    fun move(g: Game, id: Int): Move = era(g)[id] ?: move(id)

    /** Moves that exist in `g`'s generation, in [moves] order, as they were there. */
    fun moves(g: Game): List<Move> {
        val era = era(g)
        return moves.filter { it.gen <= g.gen }.map { era[it.id] ?: it }
    }

    /**
     * `g`'s moves that differ from today's: older type, power, accuracy, PP or priority (past.tsv), and before Gen 4
     * the category, which followed the type. Built once per game: under 400 moves, a few hundred copies in Gens 1 to 3.
     */
    private fun era(g: Game): Map<Int, Move> = eraCache.getOrPut(g.id) {
        val out = HashMap<Int, Move>()
        for (m in moves) {
            if (m.gen > g.gen) continue
            val old = pastMoves[m.id]?.firstOrNull { g.versionGroup < it.first }?.second ?: m
            val category = when {
                g.gen >= 4 || old.category == "status" -> old.category
                old.type in SPECIAL -> "special"
                else -> "physical"
            }
            val x = if (category == old.category) old
                    else old.with(old.type, category, old.power, old.accuracy, old.pp, old.priority)
            if (x !== m) out[m.id] = x
        }
        out
    }

    fun ability(id: Int): Ability = abilityById.getValue(id)

    /** A type or move category ("fire", "physical") as shown: "Fire", "Fuego", "ほのお". */
    fun label(key: String): String = labels["type\t$key"] ?: labels["category\t$key"] ?: key.replaceFirstChar { it.uppercaseChar() }

    /** A regional dex ("Original Johto") in the app's language: "Johto Original", "ジョウト"; UI text if no data has it. */
    private fun dexName(s: String): String = names["dex\t$s"] ?: L.t(s)

    /** Falls back to the first game if a stored id no longer exists. */
    fun game(id: Int): Game {
        if (id == WHOLE && ::whole.isInitialized) return whole
        return games.firstOrNull { it.id == id } ?: generations.firstOrNull { it.id == id } ?: games[0]
    }

    /** Types that exist in `g`'s generation, in [types] order: 15 in Gen 1, no Fairy before Gen 6. */
    fun types(g: Game): List<String> = types.filter { typeGen.getValue(it) <= g.gen }

    /** `m`'s types in `g`'s generation: Clefairy is Normal before Gen 6, Magnemite only Electric in Gen 1. */
    fun types(g: Game, m: Mon): List<String> = pastTypes[m.num]?.firstOrNull { g.gen <= it.first }?.second ?: m.types

    /** Damage multiplier x100 in `g`'s generation of an attacking type against a defender's types: rock vs fire/flying is 400. */
    fun factor(g: Game, attack: String, defender: List<String>): Int {
        val a = types.indexOf(attack)
        var f = 100
        for (d in defender) {
            val t = types.indexOf(d)
            val past = pastChart.firstOrNull { it[0] == a && it[1] == t && g.gen <= it[3] }
            f = f * (past?.get(2) ?: factors[a][t]) / 100
        }
        return f
    }

    /** How `num` (species or form) evolves from its parent; null for the first stage and forms outside evolution (Megas). */
    fun evo(num: Int): Evo? = evoInto[num]

    /** What `num` evolves into; empty for a final stage. */
    fun evolutions(num: Int): List<Evo> = evoFrom[num].orEmpty()

    /**
     * Evolution family in tree order, rows of {species or form, depth}: each first stage, species before forms
     * (Meowth, Alolan Meowth, Galarian Meowth), then what it evolves into. A species that does not evolve stays in
     * as a lone root (Farfetch'd beside Galarian Farfetch'd), a form only if it evolves. Just `m` when nothing evolves.
     */
    fun chain(m: Mon): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (x in mons) {
            if (x.chain == m.chain && x.num !in evoInto && (x.num in evoFrom || !x.form())) walk(x.num, 0, out)
        }
        return if (out.size > 1) out else listOf(intArrayOf(m.num, 0))
    }

    private fun walk(num: Int, depth: Int, out: MutableList<IntArray>) {
        out.add(intArrayOf(num, depth))
        for (e in evoFrom[num].orEmpty()) walk(e.to, depth + 1, out)
    }

    /**
     * Game whose learnset to show for a species: the selected one if it has data, else the newest earlier game
     * with data (Legends: Z-A has none in PokeAPI), else the oldest later one. Null if the species has none.
     * The Whole Pokédex prefers [widest]: Champions, the newest, lists no level-up moves.
     */
    /** Game whose TMs `g` shows: its own; for the Whole Pokédex [widest] (Scarlet/Violet), as pokemondb lists them. */
    fun tmGame(g: Game): Game = if (g === whole) widest else g

    fun learnGame(g: Game, m: Mon): Game? {
        if (g === whole && widest.id in m.games) return widest
        return pick(g, m.games)
    }

    /**
     * Game whose learners to list for a move: same fallback as the species overload, over all species.
     * The Whole Pokédex uses [widest], or if nothing learns the move there, the newest game where something does.
     */
    fun learnGame(g: Game, mv: Move): Game {
        if (g !== whole) return pick(g, learnGames)!!
        if (learners(widest, mv.id).isNotEmpty()) return widest
        // ponytail: loads one learn file per game back to the move's generation (Z-moves: ~9 files, ~0.5 s once); precompute per move if it shows
        for (i in learnGames.indices.reversed()) {
            val x = game(learnGames[i])
            if (x.id == GO) continue
            if (x.gen < mv.gen) break
            if (learners(x, mv.id).isNotEmpty()) return x
        }
        return widest
    }

    private fun pick(g: Game, ids: IntArray): Game? {
        var pick = 0
        for (id in ids) if (id <= g.versionGroup && (id != GO || g.id == GO)) pick = id
        if (pick == 0 && ids.isNotEmpty()) pick = ids[0]
        return if (pick == 0) null else game(pick)
    }

    /**
     * Learnset columns (see [METHODS]), level-up as flat {level, move} pairs; null if not in that game.
     * A form that learns the same as its species (most Megas) has no row of its own and gets the species'.
     */
    fun learnset(g: Game, m: Mon): Array<IntArray>? {
        val all = learnsets(g.id)
        return all[m.num] ?: all[m.species]
    }

    /** Species that learn a move in a game, rows of {species, method, level}, by method, level, number. */
    fun learners(g: Game, move: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        for ((num, c) in learnsets(g.id)) {
            for (i in c[0].indices step 2) {
                if (c[0][i + 1] == move) {
                    out.add(intArrayOf(num, 0, c[0][i]))
                    break
                }
            }
            for (k in 1 until c.size) {
                for (x in c[k]) if (x == move) out.add(intArrayOf(num, k, 0))
            }
        }
        out.sortWith(compareBy({ it[1] }, { it[2] }, { it[0] }))
        return out
    }

    /**
     * pokemondb's {where to find, Pokédex entry} for a species in a game (a generation's Pokédex: its last core game),
     * both "" if it has none. One file scan, ~150 KB.
     */
    fun info(g: Game, m: Mon): Array<String> {
        val key = "${m.species}\t"
        try {
            assets.open("info/${g.versionGroup}.tsv").bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith(key)) {
                        val f = line.split('\t')
                        return arrayOf(f[1], f[2].replace("\\n", "\n"))
                    }
                }
            }
        } catch (e: FileNotFoundException) {
            // Champions, Pokémon GO and the Whole Pokédex have no pokemondb page data
        }
        return arrayOf("", "")
    }

    /**
     * Smogon sets for these Pokémon in the game's generation, grouped by format, OU first. One streamed scan of
     * sets/<file>.tsv (gen 9 is 330 KB); only matching lines are split.
     */
    fun builds(g: Game, nums: Set<Int>): List<Build> {
        val file = when (g.id) {
            19 -> "letsgo"  // Let's Go Pikachu / Eevee
            23 -> "bdsp"  // Brilliant Diamond / Shining Pearl
            24, 30, 32, GO -> return emptyList()  // Legends: Arceus, Legends: Z-A, Champions: no Smogon format
            else -> "gen${g.gen}"
        }
        val out = ArrayList<Build>()
        assets.open("sets/$file.tsv").bufferedReader().useLines { lines ->
            for (line in lines) if (line.substring(0, line.indexOf('\t')).toInt() in nums) out.add(Build(line.split('\t')))
        }
        return out
    }

    /**
     * Looks of this entry that differ only in appearance, in game order, as (file, name): Tatsugiri's three shapes,
     * female Pikachu. File "" is the entry's own sprite, others are looks/<file>.webp. Empty for one look. Reads
     * looks.tsv (9 KB) per call.
     */
    fun looks(num: Int): List<Pair<String, String>> =
        read(assets, "looks.tsv").filter { it[0] == num.toString() }.map { it[1] to it[2] }

    private fun learnsets(game: Int): Map<Int, Array<IntArray>> {
        learnCache[game]?.let { return it }
        val out = HashMap<Int, Array<IntArray>>()
        try {
            for (f in read(assets, "learn/$game.tsv")) {
                out[f[0].toInt()] = Array(METHODS.size) { k ->
                    val s = if (k + 1 < f.size) f[k + 1] else ""
                    if (k == 0) ints(s, ' ', ':') else ints(s, ' ')
                }
            }
        } catch (e: FileNotFoundException) {
            // no learnset data for this game; callers fall back through learnGame()
        }
        learnCache[game] = out
        return out
    }

    /**
     * Name contains the query, or the query is a number ("6", "#006") that prefixes the shown dex number.
     * Types (as in `g`) are AND (fire+flying = both), generations are OR. Empty sets mean no filter.
     */
    fun pokedex(g: Game, source: List<Entry>, text: String, types: Set<String>, gens: Set<Int>,
                keep: (Mon) -> Boolean, sort: Sort): List<Entry> {
        val q = fold(text)
        val num = if (q.matches(DIGITS)) q.replaceFirst(ZEROS, "") else null
        val out = source.filter { e ->
            val m = e.mon
            (q.isEmpty() || q in m.key || num != null && e.number.toString().startsWith(num))
                    && (gens.isEmpty() || m.gen in gens) && types(g, m).containsAll(types) && keep(m)
        }
        if (sort == Sort.NAME) return out.sortedBy { it.mon.key }
        val stat = sort.stat ?: return out
        return out.sortedByDescending(stat)  // stable: ties keep dex order
    }

    /** Global search: kind (English "Types", "Pokémon", "Moves", "Abilities", "Items") to matches, at most `limit` each; types and moves as in `g`. */
    fun search(g: Game, text: String, limit: Int): Map<String, List<Any>> {
        val q = fold(text)
        if (q.isEmpty()) return emptyMap()
        val number = q.matches(DIGITS)
        val n = if (number) q.replaceFirst(ZEROS, "") else q
        val out = LinkedHashMap<String, List<Any>>()
        out[L.k("Types")] = if (number) emptyList() else best(types(g), q, { key(label(it), it) }, limit)
        out[L.k("Pokémon")] = best(mons, n, { if (number) it.species.toString() else it.key }, limit)
        out[L.k("Moves")] = if (number) emptyList() else best(moves(g), q, { it.key }, limit)
        out[L.k("Abilities")] = if (number) emptyList() else best(abilities, q, { it.key }, limit)
        out[L.k("Items")] = if (number) emptyList() else best(items, q, { it.key }, limit)
        return out.filterValues { it.isNotEmpty() }
    }

    /** [natures] indices as the games' 5 x 5 chart: row r raises STATS[r + 1], column lowers one; neutral on the diagonal. */
    fun natureChart(): List<IntArray> = STATS.indices.drop(1).map { up ->
        IntArray(STATS.size - 1) { k ->
            val down = k + 1
            natures.indexOfFirst { if (up == down) it.en == NEUTRAL[k] else it.up == STATS[up] && it.down == STATS[down] }
        }
    }

    companion object {
        /** English short names: Showdown text and natures.tsv use them; shown through [L.t]. */
        val STATS = arrayOf(L.k("HP"), L.k("Atk"), L.k("Def"), L.k("SpA"), L.k("SpD"), L.k("Spe"))
        /** Sections of the item lists, in list order; items.tsv names one per item. Shown through [L.t]. */
        val ITEM_GROUPS = arrayOf(L.k("Held items"), L.k("Berries"), L.k("Type boosters"), L.k("Gems"),
            L.k("Species items"), L.k("Mega Stones"), L.k("Z-Crystals"), L.k("Training"))
        /** Each generation's first starter, the Grass one; its Fire and Water ones are 3 and 6 after. */
        val STARTERS = intArrayOf(1, 152, 252, 387, 495, 650, 722, 810, 906)
        /** Growth rates in pokemon.tsv; shown through [L.t]. */
        private val GROWTH = arrayOf(L.k("Erratic"), L.k("Fast"), L.k("Medium"), L.k("Medium Slow"), L.k("Slow"), L.k("Fluctuating"))
        /** Neutral nature the games place on each stat's diagonal cell, Atk to Spe. */
        private val NEUTRAL = arrayOf("Hardy", "Docile", "Bashful", "Quirky", "Serious")

        /** "1.9 m", or feet and inches rounded to the inch as the games show them ("6'03\""). */
        fun height(dm: Int, imperial: Boolean): String {
            if (!imperial) return "%.1f m".format(L.locale, dm / 10.0)
            val inches = (dm * 3.937).roundToInt()
            return "%d'%02d\"".format(Locale.ROOT, inches / 12, inches % 12)
        }

        fun weight(hg: Int, imperial: Boolean): String =
            if (imperial) "%.1f lbs".format(L.locale, hg * 0.220462) else "%.1f kg".format(L.locale, hg / 10.0)
        /** Id of the "Whole Pokédex" pseudo game, above every PokeAPI version group so learnsets resolve to the newest. */
        const val WHOLE = 999
        /** Pokémon GO's id (gen_go.py): its moves only show for GO itself, never as another game's fallback. */
        const val GO = 100
        /** Region each generation introduced, Gen 1 first. */
        val REGIONS = arrayOf("Kanto", "Johto", "Hoenn", "Sinnoh", "Unova", "Kalos", "Alola", "Galar", "Paldea")
        /** Each generation's last core game (Yellow ... Scarlet / Violet): its learnsets and moves serve that generation's Pokédex. */
        private val ERA_GAMES = intArrayOf(2, 4, 7, 10, 14, 16, 18, 20, 25)
        /** Before Gen 4 a damaging move's category followed its type: these were special, the rest physical. */
        private val SPECIAL = setOf("fire", "water", "grass", "electric", "psychic", "ice", "dragon", "dark")
        /**
         * Columns of learn/<game>.tsv after the species: level-up pairs, then plain move lists. Pokémon GO fills only
         * the last three; the other games' files stop after Other.
         */
        val METHODS = arrayOf(L.k("Level"), L.k("TM"), L.k("Egg"), L.k("Tutor"), L.k("Other"), L.k("Fast Attack"), L.k("Charged Attack"), L.k("Elite TM"))
        private val DIGITS = Regex("\\d+")
        /** An L.t, L.f or L.k call on a literal: function, literal, then "," or ")" (missing when the argument goes on). */
        private val CALL = Regex("""L\.([tfk])\(\s*"((?:[^"\\]|\\.)*)"\s*([,)])?""")
        /** A java.util.Formatter conversion: %d, %.1f, %1\$s, %%. */
        private val FORMAT = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]""")
        private val POSITION = Regex("""\d+\$""")
        private val ZEROS = Regex("^0+(?=\\d)")

        /** Names in `lang` from names/<lang>.tsv where it has them (Spanish, Japanese); load [L] first for the UI text in them. */
        fun load(assets: Assets, lang: String = L.lang): Dex {
            val d = Dex(assets)
            d.names = try {
                read(assets, "names/$lang.tsv").associate { "${it[0]}\t${it[1]}" to it[2] }
            } catch (e: FileNotFoundException) {
                emptyMap()  // English, and Polish, which uses the English names
            }
            for ((k, v) in d.names) if (k.startsWith("type\t") || k.startsWith("category\t")) d.labels[k] = v
            val n = d.names
            for (f in read(assets, "pokemon.tsv")) {
                val m = Mon(f, n)
                d.mons.add(m)
                d.monByNum[m.num] = m
                if (m.form()) d.forms.getOrPut(m.species) { ArrayList() }.add(m)
            }
            for (f in read(assets, "merged.tsv")) d.merged[f[0].toInt()] = f[1].toInt()
            for (f in read(assets, "evo.tsv")) {  // sorted by from, then to
                val e = Evo(f)
                d.evoInto[e.to] = e
                d.evoFrom.getOrPut(e.from) { ArrayList() }.add(e)
            }
            read(assets, "moves.tsv").mapTo(d.moves) { Move(it, n["move\t${it[0]}"] ?: it[1]) }
            read(assets, "abilities.tsv").mapTo(d.abilities) { Ability(it, n["ability\t${it[0]}"] ?: it[1]) }
            read(assets, "items.tsv").mapTo(d.items) { Item(it, n["item\t${it[0]}"] ?: it[1]) }
            read(assets, "natures.tsv").mapTo(d.natures) { Nature(it, n["nature\t${it[0]}"] ?: it[0]) }
            read(assets, "games.tsv").mapTo(d.games) { Game(it, d) }
            val types = read(assets, "types.tsv")
            types.mapTo(d.types) { it[0] }
            d.factors = Array(types.size) { i -> types[i].drop(1).map { it.toInt() }.toIntArray() }
            val learn = TreeMap<Int, Int>()
            for (m in d.mons) {
                for (g in m.games) learn[g] = (learn[g] ?: 0) + 1
                if (!m.form()) for (x in d.family(m)) d.national.add(Entry(x, m.num, ""))
            }
            d.learnGames = learn.keys.toIntArray()
            d.widest = d.games.filter { it.id in learn && it.id != GO }.maxWith(compareBy<Game> { it.gen }.thenBy { learn[it.id] })
            d.whole = Game(d)
            for (n in 1..REGIONS.size) d.generations.add(Game(d, n))
            d.regions = REGIONS.map { d.dexName(it) }
            d.moves.associateByTo(d.moveById) { it.id }
            d.abilities.associateByTo(d.abilityById) { it.id }
            d.items.associateByTo(d.itemById) { it.id }
            for (f in read(assets, "past.tsv")) {
                when (f[0]) {
                    "type" -> d.typeGen[f[1]] = f[2].toInt()
                    "chart" -> d.pastChart.add(intArrayOf(d.types.indexOf(f[1]), d.types.indexOf(f[2]), f[3].toInt(), f[4].toInt()))
                    "mon" -> d.pastTypes.getOrPut(f[1].toInt()) { ArrayList() }.add(f[2].toInt() to f[3].split(','))
                    "move" -> d.moveById[f[1].toInt()]?.let { m ->
                        d.pastMoves.getOrPut(m.id) { ArrayList() }.add(f[2].toInt() to
                                m.with(f[3], m.category, optInt(f[4]), optInt(f[6]), optInt(f[5]), optInt(f[7])))
                    }
                }
            }
            d.pastChart.sortBy { it[3] }
            d.names = emptyMap()
            return d
        }

        /** Name (or machine label, "tm12") contains the query; type and category sets are OR, empty means any. */
        fun moves(source: List<Move>, text: String, types: Set<String>, categories: Set<String>,
                  sort: MoveSort, label: (Move) -> String): List<Move> {
            val q = fold(text)
            val out = source.filter { m ->
                (q.isEmpty() || q in m.key || q in fold(label(m))) && (types.isEmpty() || m.type in types)
                        && (categories.isEmpty() || m.category in categories)
            }
            return when (sort) {
                MoveSort.NAME -> out.sortedBy { it.key }
                MoveSort.POWER -> out.sortedByDescending { it.power }
                MoveSort.ACCURACY -> out.sortedByDescending { it.accuracy }
                MoveSort.PP -> out.sortedByDescending { it.pp }
                MoveSort.ORDER -> out
            }
        }

        /** Matches whose folded key starts with the query come first, then the rest that contain it; source order within. */
        fun <T> best(source: List<T>, text: String, key: (T) -> String, limit: Int): List<T> {
            val q = fold(text)
            val starts = ArrayList<T>()
            val contains = ArrayList<T>()
            for (t in source) {
                val k = key(t)
                if (k.startsWith(q)) starts.add(t) else if (q in k) contains.add(t)
            }
            return (starts + contains).take(limit)
        }

        /**
         * "Mr. Mime" and "mr mime" both become "mrmime", "Flabébé" "flabebe", "ガブリアス" and "がぶりあす" "カフリアス":
         * accents and dakuten dropped, hiragana as katakana, punctuation and spaces removed.
         */
        fun fold(s: String): String {
            val b = StringBuilder()
            for (c in Normalizer.normalize(s, Normalizer.Form.NFKD).lowercase(Locale.ROOT)) {
                if (c in 'a'..'z' || c in '0'..'9') b.append(c)
                else if (c in '\u3041'..'\u3096') b.append(c + 0x60)
                else if (c > '\u007f' && c.isLetterOrDigit()) b.append(c)
            }
            return b.toString()
        }

        /** Folded shown name, then the English one if it differs, so either finds it; '|' never survives [fold]. */
        private fun key(name: String, en: String) = if (name == en) fold(en) else fold(name) + "|" + fold(en)

        /** 0 for an empty column. */
        private fun optInt(s: String) = if (s.isEmpty()) 0 else s.toInt()

        private fun ints(s: String, vararg sep: Char): IntArray =
            if (s.isEmpty()) IntArray(0) else s.split(*sep).map { it.toInt() }.toIntArray()

        private fun read(assets: Assets, path: String): List<List<String>> =
            assets.open(path).bufferedReader().useLines { lines -> lines.map { it.split('\t') }.toList() }

        /** Self-check: java -cp build/classes:kotlin-stdlib.jar demo.dex.Dex assets */
        @JvmStatic
        fun main(args: Array<String>) {
            val d = load(Assets { FileInputStream(args[0] + "/" + it) })
            val noTypes = emptySet<String>()
            val noGens = emptySet<Int>()
            val all: (Mon) -> Boolean = { true }
            check(d.pokedex(d.whole, d.national, "", noTypes, noGens, all, Sort.NUMBER).size == d.mons.size, "empty query returns all")
            check(first(d.pokedex(d.whole, d.national, "mr mime", noTypes, noGens, all, Sort.NUMBER)) == "Mr. Mime", "punctuation ignored")
            check(first(d.pokedex(d.whole, d.national, "FLABEBE", noTypes, noGens, all, Sort.NUMBER)) == "Flabébé", "accents and case ignored")
            check(first(d.pokedex(d.whole, d.national, "#006", noTypes, noGens, all, Sort.NUMBER)) == "Charizard", "number with # and zeros")
            check(d.pokedex(d.whole, d.national, "char", noTypes, noGens, all, Sort.NUMBER).count { !it.mon.form() } == 7, "substring match")
            val fireFlying = d.pokedex(d.whole, d.national, "", setOf("fire", "flying"), noGens, all, Sort.NUMBER)
            check(first(fireFlying) == "Charizard" && fireFlying.all { it.mon.types.size == 2 }, "types are AND")
            check(d.pokedex(d.whole, d.national, "", noTypes, setOf(1), all, Sort.NUMBER).size == 151, "gen 1 has 151")
            check(first(d.pokedex(d.whole, d.national, "", noTypes, noGens, all, Sort.BST)) == "Eternamax Eternatus", "BST sort descending")
            check(first(d.pokedex(d.whole, d.national, "", noTypes, noGens, all, Sort.NAME)) == "10% Zygarde", "name sort")
            check(first(d.pokedex(d.whole, d.national, "", noTypes, noGens, all, Sort.SPE)) == "Regieleki", "speed sort")

            val charizard = d.mon(6)
            val eevee = d.mon(133)
            check(d.evo(5)?.how == "Lv 16" && d.evo(5)?.from == 4 && d.evo(4) == null, "evolution level")
            check(d.evoInto.values.all { it.from in d.monByNum && it.to in d.monByNum }, "every evolution resolves")
            check(d.evo(475)?.let { it.from == 281 && it.gender == 2 && it.how == "Dawn Stone" } == true
                    && d.evo(478)?.gender == 1 && d.evo(282)?.gender == 0, "Gallade: male Kirlia only; Froslass: female Snorunt")
            check(d.chain(d.mon(53)).joinToString { "${it[0]}/${it[1]}" } == "52/0, 53/1, 10107/0, 10108/1, 10161/0, 863/1",
                "Meowth tree: Alolan Meowth into Alolan Persian, Galarian Meowth into Perrserker")
            check(d.chain(d.mon(83)).joinToString { "${it[0]}/${it[1]}" } == "83/0, 10166/0, 865/1", "Farfetch'd stays beside Sirfetch'd's line")
            check(d.evo(10100)?.how == "Thunder Stone in Alola" && d.evo(678)?.gender == 0
                    && d.chain(d.mon(10250)).let { it.size == 1 && it[0][0] == 10250 }, "regional and gender splits")
            val family = d.chain(eevee)
            check(family.size == 9 && family[0][0] == 133 && family[1][1] == 1, "Eevee has 8 branches")
            check(d.chain(d.mon(840)).joinToString { "${it[0]}/${it[1]}" } == "840/0, 841/1, 842/1, 1011/1, 1019/2",
                "Applin tree: Hydrapple right under Dipplin")
            val national = d.national.map { it.mon.num }.toIntArray()
            val megaX = d.family(charizard)[1].num
            check(d.neighbor(national, 6, 1) == 7 && d.neighbor(national, megaX, 1) == 7 && d.neighbor(national, megaX, -1) == 5
                    && d.neighbor(national, 7, -1) == 6 && d.neighbor(national, 1, -1) == 0 && d.neighbor(intArrayOf(6, megaX), 6, 1) == 0
                    && d.neighbor(intArrayOf(1), 6, 1) == -1, "previous/next skip forms")
            check(d.factor(d.whole, "rock", charizard.types) == 400 && d.factor(d.whole, "ground", charizard.types) == 0
                    && d.factor(d.whole, "water", d.mon(9).types) == 50, "type factors")

            val sv = d.game(25)
            val za = d.game(30)
            check(sv.name == "Scarlet / Violet" && sv.multiDex && sv.dex[0].mon.name == "Sprigatito", "Paldea starts with Sprigatito")
            check(first(d.pokedex(sv, sv.dex, "1", noTypes, noGens, all, Sort.NUMBER)) == "Sprigatito", "regional number search")
            check(za.byNum.getValue(152).number == 1, "Lumiose #1 is Chikorita")
            val frlg = d.game(7)
            check(frlg.byNum.getValue(151).dex == "Kanto" && frlg.byNum.getValue(152).dex == "National"
                    && 252 in frlg.byNum, "FireRed lists Gen 2 and 3 after Kanto")
            check(sv.tms[53] == "TM125", "Flamethrower is TM125 in SV")
            val charmander = d.learnset(sv, d.mon(4))!!
            check(charmander[0][4] == 4 && charmander[0][5] == 52, "Charmander learns Ember at Lv 4 in SV")
            val zaCharizard = d.learnset(za, charizard)!!
            val level = zaCharizard[0]
            check((0 until level.size / 2).any { level[2 * it] == 1 && level[2 * it + 1] == 337 }
                    && 337 in zaCharizard[1] && za.tms[337] == "TM02",
                    "Z-A Charizard (pokemondb): Dragon Claw at Lv 1 and by TM02")
            check(d.learnGame(za, d.mon(906)) === sv && d.learnGame(za, d.move(53)) === za, "species missing from Z-A fall back to SV")
            val wholeDex = d.game(WHOLE)
            val pursuit = d.learnGame(wholeDex, d.move(228))
            check(wholeDex.dex.size == d.mons.size && wholeDex.byNum.getValue(10100).number == 26 && wholeDex.gen == 9
                    && d.learnGame(wholeDex, d.mon(19))?.id == 23 && d.learnGame(wholeDex, d.mon(445)) === sv
                    && d.learnGame(wholeDex, d.mon(16)) === za
                    && d.learnGame(wholeDex, d.move(53)) === sv && pursuit !== sv && d.learners(pursuit, 228).isNotEmpty()
                    && d.tmGame(wholeDex).tms[53] == "TM125",
                    "Whole Pokédex: every entry, SV learnsets and TMs, else the newest game with data")
            val gen1 = d.game(WHOLE + 1)
            val gen5 = d.generations[4]
            val gen6 = d.generations[5]
            check(gen1.name == "Gen 1 Pokédex" && gen1.dex.size == 151 && 10100 in d.generations[6].byNum
                    && d.generations.sumOf { it.dex.size } == d.national.size && gen1.byNum.getValue(151).number == 151,
                    "Gen 1 is #1 to #151; forms in their own generation; every entry in exactly one")
            val clefairy = d.mon(35)
            check(d.types(gen1).size == 15 && d.types(gen5).size == 17 && d.types(gen6).size == 18
                    && d.types(gen1, clefairy) == listOf("normal") && d.types(gen6, clefairy) == listOf("fairy")
                    && d.types(gen1, d.mon(81)) == listOf("electric") && d.types(gen5, d.mon(81)) == listOf("electric", "steel")
                    && d.types(d.game(16), d.mon(10008)) == d.mon(10008).types, "era types")
            check(d.factor(gen1, "ghost", listOf("psychic")) == 0 && d.factor(gen6, "ghost", listOf("psychic")) == 200
                    && d.factor(gen5, "ghost", listOf("steel")) == 50 && d.factor(gen6, "ghost", listOf("steel")) == 100
                    && d.factor(gen1, "bug", listOf("poison")) == 200, "era type chart")
            check(d.move(gen1, 44).type == "normal" && d.move(d.game(3), 44).type == "dark"
                    && d.move(d.game(7), 7).category == "special" && d.move(d.game(8), 7).category == "physical"
                    && d.move(gen5, 204).type == "normal" && d.move(gen6, 204).type == "fairy"
                    && d.moves(gen1).size == 165 && d.moves(gen1).none { it.type == "dark" || it.type == "steel" },
                    "era moves: Bite Normal in Gen 1, Fire Punch special before Gen 4, Charm Normal before Gen 6")
            val go = d.game(GO)
            val goChomp = d.learnset(go, d.mon(445))!!
            check(go.name == "Pokémon GO" && 10100 in go.byNum && 10195 !in go.byNum
                    && goChomp[0].isEmpty() && 341 in goChomp[5]
                    && 414 in goChomp[7] && d.learnset(sv, d.mon(445))!![5].isEmpty()
                    && d.learnGame(go, d.mon(445)) === go && d.learnGame(go, d.move(341)) === go && d.learnGame(sv, d.mon(16)) !== go,
                    "Pokémon GO: forms, Fast and Elite TM moves, never another game's fallback")
            val fire = d.info(frlg, charizard)
            check(fire[0].startsWith("Evolve Charmander") && fire[1].startsWith("FireRed: ") && "\n\nLeafGreen: " in fire[1],
                    "FireRed where to find and both version entries")
            check(d.learners(sv, 53).any { it[0] == 6 && it[1] == 1 }, "Charizard learns Flamethrower by TM")

            val alolan = d.mon(10100)
            val raichu = d.national.indexOfFirst { it.mon.num == 26 }
            check(alolan.name == "Alolan Raichu" && alolan.species == 26 && alolan.gen == 7
                    && alolan.types == listOf("electric", "psychic") && d.national[raichu + 1].mon === alolan
                    && d.national[raichu + 1].number == 26, "Alolan Raichu follows Raichu under #026")
            check(sv.byNum.getValue(10100).number == sv.byNum.getValue(26).number && 10100 !in frlg.byNum, "forms only in their games")
            check(d.learnset(za, d.mon(10034)) === d.learnset(za, charizard) && "dragon" in d.mon(10034).types,
                    "Mega Charizard X: own types, species learnset")
            check(d.family(alolan).size > 1 && d.family(alolan)[0].num == 26
                    && d.chain(alolan).joinToString { "${it[0]}/${it[1]}" } == "172/0, 25/1, 26/2, 10100/2", "form family and chain")
            check(d.items.any { it.name == "Leftovers" }, "held items loaded")

            val chomp = Team.Member(445, 24, 100)
            chomp.nature = d.natures.indexOfFirst { it.name == "Adamant" }
            chomp.evs[1] = 252
            val st = Team.stats(d, chomp)
            check(st[0] == 357 && st[1] == 394 && st[3] == 176, "Garchomp Lv100 Adamant 252 Atk: HP 357, Atk 394, SpA 176")
            chomp.evs[5] = 252
            check(chomp.evRoom(2) == 6 && chomp.evRoom(1) == 252, "EV room: 510 total, 252 per stat")
            chomp.moves[0] = 89
            val team = Team("Test\tteam", 25)
            team.members.add(chomp)
            team.members.add(Team.Member(10100, alolan.abilities[0], 50))
            val saved = Team.formatAll(listOf(team, team))
            check(!saved.endsWith("\n") && Team.parseAll(saved + "\n    ").size == 2, "teams text survives SharedPreferences XML")
            val back = Team.parseAll(saved)[0]
            check(back.name == "Test team" && back.members.size == 2 && back.members[0].evs[5] == 252
                    && back.members[0].moves[0] == 89 && back.members[1].num == 10100 && back.format() == team.format(),
                    "team round trip")
            check(d.monOrNull(10094) == null && d.monOrNull(10199) == null && d.known(10094) == 25 && d.known(10100) == 10100
                    && d.family(d.mon(25)).none { "Cap" in it.name }, "Pikachu in a cap and Gigantamax are left out")
            check(team.export(d).startsWith("Garchomp\nAbility: ")
                    && "EVs: 252 Atk / 252 Spe\nAdamant Nature\n- Earthquake" in team.export(d), "Showdown text")
            val crystal = Team("", 4)
            crystal.members.add(Team.Member(143, d.mon(143).abilities[0], 50))
            check(d.game(4).gen == 2 && crystal.export(d) == "Snorlax\nLevel: 50", "no Ability line before Generation 3")

            val sets = d.builds(d.whole, d.mons.mapTo(HashSet()) { it.num })
            check(sets.size > 3000 && sets.all { b ->
                b.num in d.monByNum && (b.ability == 0 || b.ability in d.abilityById) && (b.item == 0 || b.item in d.itemById)
                        && b.nature < d.natures.size && b.tera < d.types.size && b.evs.size == 6 && b.ivs.size == 6
                        && b.moves.size == Team.MOVES && b.moves.all { it == 0 || it in d.moveById }
            }, "every gen 9 set resolves")
            val chompSets = d.builds(sv, setOf(445))
            check(chompSets[0].format == "OU" && chompSets.all { it.num == 445 } && d.builds(d.game(GO), setOf(445)).isEmpty(),
                "Garchomp's sets start with OU; GO has none")

            check(d.looks(978) == listOf("" to "Curly Form", "10427" to "Droopy Form", "10428" to "Stretchy Form")
                    && d.looks(1).isEmpty(), "Tatsugiri's three looks, Bulbasaur one")
            check(read(d.assets, "looks.tsv").all { it[0].toInt() in d.monByNum
                    && (it[1].isEmpty() || java.io.File("${args[0]}/looks/${it[1]}.webp").isFile) }, "every look has its entry and file")
            check(java.io.File("${args[0]}/shiny").list()?.toSet() == java.io.File("${args[0]}/sprites").list()?.toSet(),
                "every Pokémon has a shiny render")

            val lone = Team("", sv.id)
            lone.members.add(Team.Member(445, 0, 50))
            val svTypes = d.types(sv)
            val bare = lone.gaps(d)
            check(bare.stacked.map { svTypes[it] }.toSet() == setOf("ice", "dragon", "fairy") && bare.counter.isEmpty()
                    && bare.uncovered.isEmpty() && svTypes[bare.wall] == "steel" && bare.move == -1,
                "Garchomp alone: Ice, Dragon and Fairy stack, Steel resists all three, no moves to judge")
            lone.members[0].moves[0] = 89
            val eq = lone.gaps(d)
            check(eq.counter.map { svTypes[it] }.containsAll(listOf("flying", "ice")) && "ice" !in eq.stacked.map { svTypes[it] }
                    && (eq.counter + eq.uncovered).none { svTypes[it] == "steel" || svTypes[it] == "fire" } && eq.move >= 0,
                "Garchomp with Earthquake: nothing counters Flying or Ice; Steel and Fire are covered")
            lone.members[0].moves[0] = 0
            lone.members.add(Team.Member(714, 0, 50))
            val twin = lone.overlaps(d)
            check(twin.size == 1 && twin[0].a == 0 && twin[0].b == 1 && twin[0].weak.map { svTypes[it] }.toSet() == setOf("ice", "dragon", "fairy"),
                "Garchomp and Noibat overlap on Ice, Dragon and Fairy")
            val swap = lone.suggest(d, 1)
            val pairs = swap.map { Team("", sv.id).apply { members.add(Team.Member(445, 0, 50)); members.add(Team.Member(it.num, 0, 50)) } }
            check(swap.size == 30 && swap.none { d.evolutions(it.num).isNotEmpty() || Team.legend(it.species) } && pairs.all { it.overlaps(d).isEmpty() }
                    && listOf("ice", "dragon", "fairy").all { d.factor(sv, it, d.types(sv, swap[0])) < 100 },
                "Noibat's swap: final stages, no legendaries, none overlapping Garchomp, the first resisting all its weaknesses: ${swap.take(5).map { it.name }}")
            check(Team.damage(75, 65, 123, 163, true, 400).contentEquals(intArrayOf(168, 196))
                    && Team.damage(50, 40, 10, 400, false, 50)[0] == 1 && Team.damage(50, 90, 100, 100, true, 0)[1] == 0,
                "damage: Glaceon's Ice Fang on Garchomp 168-196 (Bulbapedia), at least 1, 0 if immune")

            val hits = d.search(d.whole, "char", 20)
            check((hits.getValue("Pokémon")[0] as Mon).name == "Charmander", "global search ranks prefix first")
            check((d.search(d.whole, "25", 5).getValue("Pokémon")[0] as Mon).name == "Pikachu", "global number search")
            check((d.search(d.whole, "sash", 5).getValue("Items")[0] as Item).name == "Focus Sash", "global item search")
            check(d.items.all { it.group in ITEM_GROUPS } && d.items.first { it.en == "Leftovers" }.group == "Held items", "item sections")
            check(d.mon(4).abilities.any { d.ability(it).name == "Blaze" }, "Charmander has Blaze")
            val chart = d.natureChart()
            check(chart.flatMap { it.toList() }.toSet().let { it.size == 25 && -1 !in it }
                    && d.natures[chart[0][2]].name == "Adamant" && d.natures[chart[4][4]].name == "Serious", "nature chart")
            check(height(19, true) == "6'03\"" && weight(950, true) == "209.4 lbs" && height(4, false) == "0.4 m"
                    && weight(60, false) == "6.0 kg", "Garchomp 6'03\", 209.4 lbs; Pikachu 0.4 m, 6.0 kg")
            check(d.mons.all { it.growth in GROWTH }, "every growth rate has UI text")
            check(STARTERS.size == d.mons.maxOf { it.gen } && REGIONS.size == STARTERS.size
                    && STARTERS.withIndex().all { (i, n) ->
                        val (grass, fire, water) = listOf(n, n + 3, n + 6).map { d.mon(it) }
                        listOf(grass, fire, water).all { it.gen == i + 1 }
                            && grass.types[0] == "grass" && fire.types[0] == "fire" && water.types[0] == "water"
                    }, "a Grass, Fire and Water starter for every generation")

            val keys = HashSet<String>()
            val formats = HashSet<String>()
            for (file in java.io.File(args[0]).absoluteFile.parentFile.resolve("src/demo/dex").listFiles()!!) {
                if (!file.name.endsWith(".kt")) continue
                for (m in CALL.findAll(file.readText())) {
                    val lit = m.groupValues[2]
                    check(m.groups[3] != null && '$' !in lit && '\\' !in lit.replace("\\n", ""),
                        "${file.name}: L.${m.groupValues[1]} takes one literal without templates or escapes but \\n: ${m.value}")
                    keys.add(lit.replace("\\n", "\n"))
                    if (m.groupValues[1] == "f") formats.add(lit.replace("\\n", "\n"))
                }
            }
            val conversions = { s: String -> FORMAT.findAll(s).map { it.value.replace(POSITION, "") }.sorted().toList() }
            for (lang in L.LANGS.drop(1)) {
                val table = L.read(d.assets, lang)
                check(table.keys == keys, "lang/$lang.tsv: missing ${(keys - table.keys).take(5)}, unused ${(table.keys - keys).take(5)}")
                for (k in formats) {
                    val v = table.getValue(k)
                    check(conversions(k) == conversions(v), "lang/$lang.tsv: $k has other conversions than $v")
                    val sample = FORMAT.findAll(k).mapNotNull<MatchResult, Any> {
                        when (it.value.last()) { 'd' -> 1; 'f' -> 1.0; 's' -> "x"; else -> null }
                    }
                    v.format(Locale.forLanguageTag(lang), *sample.toList().toTypedArray())
                }
                check(table.values.none { it.isBlank() }, "lang/$lang.tsv: empty translation")
            }

            L.load(d.assets, "ja")
            val ja = load(d.assets, "ja")
            check(ja.mon(6).name == "リザードン" && ja.mon(6).en == "Charizard" && ja.label("fire") == "ほのお"
                    && (ja.search(ja.whole, "りざーどん", 5).getValue("Pokémon")[0] as Mon).num == 6
                    && (ja.search(ja.whole, "charizard", 5).getValue("Pokémon")[0] as Mon).num == 6
                    && ja.natures[chomp.nature].name != "Adamant" && team.export(ja) == team.export(d),
                    "Japanese names, found in kana or English; Showdown text stays English")
            L.load(d.assets, "es")
            val es = load(d.assets, "es")
            check(es.move(53).name == "Lanzallamas" && es.move(gen1, 44).let { it.name == es.move(44).name && it.name != "Bite" }
                    && es.game(25).name == "Escarlata / Púrpura" && height(4, false) == "0,4 m" && es.regions[4] == "Teselia",
                    "Spanish names, also for older moves and regions")
            L.load(d.assets, "en")
            println("Dex self-check OK (${d.mons.size} Pokémon and forms, ${d.moves.size} moves, " +
                    "${d.abilities.size} abilities, ${d.items.size} items, ${d.games.size} games)")
        }

        private fun first(list: List<Entry>) = if (list.isEmpty()) "<none>" else list[0].mon.name

        private fun check(ok: Boolean, what: String) {
            if (!ok) throw AssertionError(what)
        }
    }
}
