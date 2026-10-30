package demo.dex

import java.util.Base64

/** Up to six Pokémon for one game, and the team's type coverage. Stored as one line of text; no Android deps. */
class Team(var name: String, val game: Int) {
    val members = ArrayList<Member>()

    class Member(
        /** Species or form number; the form can be switched later. */
        var num: Int,
        var ability: Int,
        var level: Int,
    ) {
        /** Ability and item ids (item 0 = none), nature index into [Dex.natures] and Tera type index into
         *  [Dex.types], both -1 when not chosen. */
        var item = 0
        var nature = -1
        var tera = -1
        val evs = IntArray(6)
        val ivs = IntArray(6) { IV_MAX }
        /** Move ids, 0 for an empty slot. */
        val moves = IntArray(MOVES)
        /** Last stage picked where the evolution branches (Eevee into Umbreon), 0 until picked; see [evolved]. */
        var evo = 0

        /** Most EVs this stat can take: 252, less whatever the other five already use of the 510 total. */
        fun evRoom(stat: Int) = minOf(EV_STAT, EV_TOTAL - evs.sum() + evs[stat])
    }

    /** What the damage calc sets beyond the two sets: [weather] 0 for none or [SUN] to [SNOW], stat stages -6 to 6. */
    class Field {
        var weather = 0
        var crit = false
        var atkStage = 0
        var defStage = 0
        var burned = false
    }

    /**
     * A [hit]: lowest and highest roll, the attacking and defending stat and base power after modifiers, the type
     * factor (x100, 0 when an ability makes it immune) and each modifier that applied as (label, 4096ths) in order.
     */
    class Hit(val roll: IntArray, val atk: Int, val def: Int, val power: Int, val factor: Int, val mods: List<Pair<String, Int>>)

    /**
     * name, game, members separated by tabs; a member is num,ability,item,nature,tera,level, 6 EVs, 6 IVs, 4 moves, and
     * then [Member.evo] when picked; teams saved before it was added have 22 fields.
     */
    fun format(): String {
        val b = StringBuilder(name.replace('\t', ' ').replace('\n', ' ')).append('\t').append(game).append('\t')
        for ((i, m) in members.withIndex()) {
            if (i > 0) b.append(';')
            b.append(m.num).append(',').append(m.ability).append(',').append(m.item).append(',').append(m.nature)
                .append(',').append(m.tera).append(',').append(m.level)
            for (part in arrayOf(m.evs, m.ivs, m.moves)) for (v in part) b.append(',').append(v)
            if (m.evo != 0) b.append(',').append(m.evo)
        }
        return b.toString()
    }

    /**
     * Showdown-style text, in English whatever the app's language: Showdown reads no other. Form names are this
     * app's ("Alolan Raichu"), which Showdown's importer may not know. No Ability line before Generation 3, which had none.
     */
    fun export(d: Dex): String {
        val b = StringBuilder()
        val abilities = d.game(game).gen >= 3
        for (x in members) {
            b.append(d.mon(x.num).en)
            if (x.item != 0) b.append(" @ ").append(d.item(x.item).en)
            if (abilities && x.ability != 0) b.append("\nAbility: ").append(d.ability(x.ability).en)
            b.append("\nLevel: ").append(x.level)
            if (x.tera >= 0) b.append("\nTera Type: ").append(d.types[x.tera].replaceFirstChar { it.uppercaseChar() })
            b.append(spread("EVs", x.evs, 0))
            if (x.nature >= 0) b.append('\n').append(d.natures[x.nature].en).append(" Nature")
            b.append(spread("IVs", x.ivs, IV_MAX))
            for (id in x.moves) if (id != 0) b.append("\n- ").append(d.move(id).en)
            b.append("\n\n")
        }
        return b.toString().trim()
    }

    /** This team as a link: the web app offers to add it when opened, and Import in either app reads it back. */
    fun link(): String = LINK + Base64.getUrlEncoder().withoutPadding().encodeToString(format().toByteArray())

    /**
     * What member `x` counts as in the type checks below: the Pokémon it ends up as while each stage has one evolution
     * in `g`, as a team built during a playthrough will (Alolan Vulpix: Alolan Ninetales, part Fairy). At a branch (Eevee,
     * Kirlia) it takes the way to [Member.evo] and stays as it is until one is picked; it also stays when holding an
     * Eviolite, which only helps a Pokémon that has not evolved.
     */
    fun evolved(d: Dex, g: Dex.Game, x: Member): Dex.Mon {
        var m = d.mon(x.num)
        if (x.item == EVIOLITE) return m
        while (true) {
            val next = d.evolutions(m.num).map { it.to }.distinct().filter { g.byNum.containsKey(it) }
            m = d.mon(next.singleOrNull() ?: next.firstOrNull { it == x.evo || reaches(d, it, x.evo) } ?: return m)
        }
    }

    /**
     * Last stages member `x` can reach in `g` (Eevee: eight), when they differ in type, else empty: Tyrogue's three are
     * all Fighting, so the pick would change no tip. Empty with an Eviolite too, as [evolved] then stays.
     */
    fun branches(d: Dex, g: Dex.Game, x: Member): List<Dex.Mon> {
        if (x.item == EVIOLITE) return emptyList()
        val out = LinkedHashSet<Int>()
        fun walk(n: Int) {
            val next = d.evolutions(n).map { it.to }.distinct().filter { g.byNum.containsKey(it) }
            if (next.isEmpty()) out.add(n) else next.forEach { walk(it) }
        }
        walk(x.num)
        val ends = out.map { d.mon(it) }
        return if (ends.map { d.types(g, it) }.distinct().size > 1) ends else emptyList()
    }

    /**
     * Per attacking type of the team's game, in [Dex.types] order: {members it hits super effectively, members that
     * resist or are immune}, each member as [evolved]. ponytail: types only; Levitate, Flash Fire, other abilities and
     * Tera are not counted.
     */
    fun defense(d: Dex): Array<IntArray> {
        val g = d.game(game)
        val types = d.types(g)
        return Array(types.size) { i ->
            val row = IntArray(2)
            for (m in members) {
                val f = d.factor(g, types[i], d.types(g, evolved(d, g, m)))
                if (f > 100) row[0]++ else if (f < 100) row[1]++
            }
            row
        }
    }

    /**
     * Per defending type of the team's game, in [Dex.types] order: {members with a damaging move that hits it super
     * effectively, members with damaging moves that it resists or is immune to, every one}. Freeze-Dry style exceptions
     * and abilities are not counted.
     */
    fun coverage(d: Dex): Array<IntArray> {
        val g = d.game(game)
        val types = d.types(g)
        return Array(types.size) { i ->
            val target = listOf(types[i])
            val row = IntArray(2)
            for (m in members) {
                // -1: no damaging move at all, so the member is in neither column.
                var best = -1
                for (id in m.moves) {
                    if (id == 0) continue
                    val mv = d.move(g, id)
                    if (mv.category != "status") best = maxOf(best, d.factor(g, mv.type, target))
                }
                if (best > 100) row[0]++ else if (best in 0..99) row[1]++
            }
            row
        }
    }

    /** The first column of [coverage]: members that hit each type super effectively. */
    fun offense(d: Dex): IntArray = coverage(d).let { c -> IntArray(c.size) { c[it][0] } }

    /** True once any member has a damaging move, so an all-zero [offense] means gaps, not an empty moveset. */
    fun attacks(d: Dex): Boolean = members.any { m -> m.moves.any { it != 0 && d.move(it).category != "status" } }

    /**
     * What the team lacks, by index into the game's types. `counter`: types no member resists and no move hits super
     * effectively. `stacked`: other attacking types that hit half the team or more super effectively and more members
     * than resist them. `uncovered`: other types no move hits super effectively. Without damaging moves only `stacked`
     * is filled. `wall`: the type whose Pokémon resist most of `counter` and `stacked`; `move`: the move type that hits
     * most of `counter` and `uncovered` super effectively; -1 when there is nothing for it to fix.
     */
    class Gaps(val counter: List<Int>, val stacked: List<Int>, val uncovered: List<Int>, val wall: Int, val move: Int)

    fun gaps(d: Dex): Gaps {
        val g = d.game(game)
        val types = d.types(g)
        val def = defense(d)
        val off = if (attacks(d)) offense(d) else null
        val counter = def.indices.filter { off != null && off[it] == 0 && def[it][1] == 0 }
        val stacked = def.indices.filter { it !in counter && def[it][0] * 2 >= members.size && def[it][0] > def[it][1] }
        val uncovered = def.indices.filter { it !in counter && off != null && off[it] == 0 }
        val best = { targets: List<Int>, good: (Int, Int) -> Boolean ->
            types.indices.maxByOrNull { x -> targets.count { good(x, it) } }?.takeIf { x -> targets.any { good(x, it) } } ?: -1
        }
        return Gaps(counter, stacked, uncovered,
            best(counter + stacked) { w, t -> d.factor(g, types[t], listOf(types[w])) < 100 },
            best(counter + uncovered) { a, t -> d.factor(g, types[a], listOf(types[t])) > 100 })
    }

    /** Move `move` for member `member`, in `slot` (empty, or holding the attack it replaces). */
    class Lesson(val member: Int, val slot: Int, val move: Int)

    /**
     * The best `type` attack a member can learn, as [evolved], and where it goes: an empty slot, else the weakest attack
     * whose loss leaves every type in [offense] still hit; status moves stay. Ranked by power, accuracy, same-type bonus
     * and the attacking base stat for its category. ponytail: learn level, TM availability and the member's level are
     * not checked; awkward moves are left out by their effect text ([AWKWARD]).
     */
    fun teach(d: Dex, type: String): Lesson? {
        val g = d.game(game)
        val before = offense(d)
        var best: Lesson? = null
        var top = 0
        for ((k, x) in members.withIndex()) {
            val m = evolved(d, g, x)
            val cols = d.learnGame(g, m)?.let { d.learnset(it, m) } ?: continue
            val score = { mv: Dex.Move -> score(d, g, m, mv) }
            val learnable = cols.withIndex().flatMap { (c, col) -> if (c == 0) col.filterIndexed { i, _ -> i % 2 == 1 } else col.asList() }
            val mv = learnable.distinct().filter { it !in x.moves }.map { d.move(g, it) }
                .filter { it.type == type && it.category != "status" && it.power > 0 && !AWKWARD.containsMatchIn(it.shortEffect) }
                .maxByOrNull(score) ?: continue
            if (score(mv) <= top) continue
            val slots = x.moves.indexOf(0).takeIf { it >= 0 }?.let { listOf(it) } ?: (0 until MOVES)
                .filter { d.move(g, x.moves[it]).let { o -> o.category != "status" && o.power > 0 } }
                .sortedBy { score(d.move(g, x.moves[it])) }
            val slot = slots.firstOrNull { s ->
                val old = x.moves[s]
                x.moves[s] = mv.id
                val after = offense(d)
                x.moves[s] = old
                before.indices.all { before[it] == 0 || after[it] > 0 }
            } ?: continue
            best = Lesson(k, slot, mv.id)
            top = score(mv)
        }
        return best
    }

    /**
     * One attack in a slot of a member offered `move`: whether the offered move is the stronger one by [score], and the
     * types (index into the game's types) no member would hit super effectively any more if this attack went.
     */
    class Trade(val slot: Int, val stronger: Boolean, val lost: List<Int>)

    /**
     * The answer: `slot` to take the move in, -1 to skip it; `trades` for the slots holding an attack (status and fixed-
     * damage moves are not rated); `adds`: types nothing on the team hits super effectively yet, that the move does.
     */
    class Advice(val slot: Int, val trades: List<Trade>, val adds: List<Int>)

    /**
     * Whether member `k` should learn `move` when offered it, as the games ask on a level-up with four moves known: into
     * an empty slot, else in place of the weakest attack it beats without any type losing its super-effective answer.
     * A status move offered is not rated (-1): its worth is in the effect. The member counts as [evolved], like [teach].
     */
    fun advise(d: Dex, k: Int, move: Int): Advice {
        val g = d.game(game)
        val x = members[k]
        val m = evolved(d, g, x)
        val mv = d.move(g, move)
        val attack = mv.category != "status" && mv.power > 0
        val before = offense(d)
        val trades = ArrayList<Trade>()
        for (s in 0 until MOVES) {
            val old = x.moves[s]
            if (old == 0) continue
            val o = d.move(g, old)
            if (o.category == "status" || o.power == 0) continue
            x.moves[s] = move
            val after = offense(d)
            x.moves[s] = old
            trades.add(Trade(s, attack && score(d, g, m, mv) > score(d, g, m, o), before.indices.filter { before[it] > 0 && after[it] == 0 }))
        }
        val adds = if (!attack) emptyList() else before.indices.filter { before[it] == 0 && d.factor(g, mv.type, listOf(d.types(g)[it])) > 100 }
        val empty = x.moves.indexOf(0)
        val slot = if (empty >= 0) empty else if (!attack) -1
            else trades.filter { it.stronger && it.lost.isEmpty() }.minByOrNull { score(d, g, m, d.move(g, x.moves[it.slot])) }?.slot ?: -1
        return Advice(slot, trades, adds)
    }

    /** Members `a` < `b` that double up, and the attacking types (index into the game's types) both are weak to. */
    class Overlap(val a: Int, val b: Int, val weak: List<Int>)

    /** Pairs, as [evolved], sharing a type and a weakness (Garchomp and Noibat), or two or more weaknesses. */
    fun overlaps(d: Dex): List<Overlap> {
        val g = d.game(game)
        val types = members.map { d.types(g, evolved(d, g, it)) }
        val out = ArrayList<Overlap>()
        for (a in members.indices) for (b in a + 1 until members.size) overlap(d, g, types[a], types[b])?.let { out.add(Overlap(a, b, it)) }
        return out
    }

    /**
     * Final-stage Pokémon of the game for slot `skip` (-1: a new one), best first. Against each of the other members'
     * [gaps], members as [evolved]: +1 if it resists, -1 if weak, +1 if one of its types hits it super effectively; ties by base stat total.
     * Anything that would overlap a member is left out, and so are [LEGENDS]: on a team built while playing, the top
     * of a BST tie is otherwise Zamazenta and Victini. ponytail: types only, top 30; forms outside evolution (Megas,
     * Rotom's appliances) are left out.
     */
    fun suggest(d: Dex, skip: Int): List<Dex.Mon> {
        val g = d.game(game)
        val rest = Team(name, game)
        for ((i, m) in members.withIndex()) if (i != skip) rest.members.add(m)
        val gp = rest.gaps(d)
        val need = (gp.counter + gp.stacked + gp.uncovered).map { d.types(g)[it] }
        val ends = rest.members.map { evolved(d, g, it) }
        val have = ends.map { d.types(g, it) }
        // Gabite in the team rules out Garchomp; suggestions are final stages, so Gabite itself never comes up.
        val species = ends.mapTo(HashSet()) { it.species }
        return g.dex.asSequence().map { it.mon }
            .filter { m -> d.evolutions(m.num).isEmpty() && (!m.form() || d.evo(m.num) != null) && m.species !in species && !legend(m.species) }
            .map { m -> m to d.types(g, m) }
            .filter { (_, t) -> have.none { overlap(d, g, it, t) != null } }
            .map { (m, t) ->
                m to need.sumOf { x ->
                    val f = d.factor(g, x, t)
                    (if (f < 100) 1 else if (f > 100) -1 else 0) + (if (t.any { d.factor(g, it, listOf(x)) > 100 }) 1 else 0)
                }
            }
            .sortedWith(compareByDescending<Pair<Dex.Mon, Int>> { it.second }.thenByDescending { it.first.bst })
            .take(30).map { it.first }.toList()
    }

    companion object {
        const val SIZE = 6
        const val MOVES = 4
        const val EV_STAT = 252
        const val EV_TOTAL = 510
        const val IV_MAX = 31
        private const val FIELDS = 6 + 6 + 6 + MOVES
        private const val SHEDINJA = 292
        private const val EVIOLITE = 581
        private const val LINK = "https://deandark.me/dexsearch/#team="
        private val LINK_CODE = Regex("(?:https?://\\S*)?#team=([A-Za-z0-9_-]+)")
        /** [Field.weather] values; the names are [WEATHER]. */
        const val SUN = 1
        const val RAIN = 2
        const val SAND = 3
        const val SNOW = 4
        val WEATHER = listOf(L.k("None"), L.k("Sun"), L.k("Rain"), L.k("Sandstorm"), L.k("Snow"))
        /** Effect texts of type boosters (Charcoal, plates, incenses), gems and resist berries, for [hit]. */
        private val BOOST = Regex("(\\w+)-[Tt]ype (?:moves|attacks) (?:from holder do 20% more|have 1\\.2×)")
        private val GEM = Regex("damaging (\\w+)-type move, the move has")
        private val BERRY = Regex("struck by a (?:super-effective )?(\\w+)-type attack to halve")
        /** Abilities that turn Normal moves (Normalize: every move) into a type. */
        private val ATE = mapOf("Aerilate" to "flying", "Pixilate" to "fairy", "Refrigerate" to "ice", "Galvanize" to "electric", "Normalize" to "normal")
        /** Abilities that raise the attacking stat for one type. */
        private val TYPE_ATK = mapOf("Steelworker" to "steel", "Dragon’s Maw" to "dragon", "Transistor" to "electric", "Rocky Payload" to "rock")
        private val FORMAT = Regex("^\\[[^]]*]")
        private val GENDER = Regex("\\s*\\((M|F)\\)$")
        private val NICKNAME = Regex("\\(([^()]+)\\)$")
        /** Effect text of attacks a team does not run for coverage: recharge or charge turns, fainting, conditions. */
        private val AWKWARD = Regex("recharge|faints|charge before|charges for|next turn|later|[Oo]nly (works|inflicts|be used)|" +
            "[Ff]ails if|canceled|half of its max HP|damages the user\\.|no longer|Removes the user|readying|shares a type|Misty Terrain is active")
        /** Legendary, mythical, Ultra Beast and Paradox species, Articuno to Pecharunt; the data has no flag for them. */
        private val LEGENDS = listOf(144..146, 150..151, 243..245, 249..251, 377..386, 480..494, 638..649, 716..721,
            772..773, 785..809, 888..898, 905..905, 984..995, 1001..1010, 1014..1017, 1020..1025)

        fun legend(species: Int) = LEGENDS.any { species in it }

        /** How hard `mv` hits from `m`: power, accuracy (0 never misses), same-type bonus and the base stat for its category. */
        private fun score(d: Dex, g: Dex.Game, m: Dex.Mon, mv: Dex.Move): Int =
            mv.power * (if (mv.accuracy == 0) 100 else mv.accuracy) * (if (mv.type in d.types(g, m)) 3 else 2) *
                m.stats[if (mv.category == "physical") 1 else 3]

        private fun reaches(d: Dex, from: Int, to: Int): Boolean = d.evolutions(from).any { it.to == to || reaches(d, it.to, to) }

        fun parse(line: String): Team {
            val f = line.split('\t')
            val t = Team(f[0], f[1].toInt())
            if (f[2].isEmpty()) return t
            for (s in f[2].split(';')) {
                val v = s.split(',').map { it.toInt() }.toIntArray()
                require(v.size == FIELDS || v.size == FIELDS + 1) { "member has ${v.size} fields: $s" }
                val m = Member(v[0], v[1], v[5])
                m.item = v[2]
                m.nature = v[3]
                m.tera = v[4]
                v.copyInto(m.evs, 0, 6, 12)
                v.copyInto(m.ivs, 0, 12, 18)
                v.copyInto(m.moves, 0, 18, 18 + MOVES)
                if (v.size > FIELDS) m.evo = v[FIELDS]
                t.members.add(m)
            }
            return t
        }

        fun parseAll(text: String): MutableList<Team> = text.split('\n').filter { it.isNotBlank() }.mapTo(ArrayList()) { parse(it) }

        /**
         * No trailing newline: SharedPreferences' XML writer indents the closing tag after text ending in one, so the
         * value reads back with trailing spaces (blank lines are skipped on parse for data saved that way).
         */
        fun formatAll(teams: List<Team>): String = teams.joinToString("\n") { it.format() }

        /** What [read] found: teams (named "" where the text had no "=== Name ===" line) and the names it did not know. */
        class Import(val teams: List<Team>, val unknown: List<String>)

        /**
         * Teams in pasted text: [link]s, or else Showdown sets split into teams by "=== [format] Name ===" lines and every
         * six sets, in `game`. Showdown's form names ("Raichu-Alola", "Urshifu-Rapid-Strike") find this app's forms by
         * their words ("Alolan Raichu"), an unknown form its species. Nicknames, gender, Shiny and lines this app has no
         * field for are skipped. No Level line means 100, as Showdown leaves it out there.
         * ponytail: the game is not taken from the format; a format line could pick its generation's newest game.
         */
        fun read(d: Dex, text: String, game: Int): Import {
            val teams = ArrayList<Team>()
            val unknown = LinkedHashSet<String>()
            for (m in LINK_CODE.findAll(text)) {
                val t = try {
                    parse(String(Base64.getUrlDecoder().decode(m.groupValues[1])))
                } catch (e: RuntimeException) {
                    null
                }
                if (t != null && valid(d, t)) teams.add(t) else unknown.add(m.value)
            }
            // Text around a link is a message ("look: <link>"), not sets.
            if (teams.isNotEmpty() || unknown.isNotEmpty()) return Import(teams, unknown.toList())
            val names = HashMap<String, Int>()
            for (m in d.mons) names.putIfAbsent(Dex.fold(gendered(m.en)), m.num)
            val items = d.items.associate { Dex.fold(it.en) to it.id }
            val abilities = d.abilities.associate { Dex.fold(it.en) to it.id }
            val moves = d.moves.associate { Dex.fold(it.en) to it.id }
            var team: Team? = null
            var x: Member? = null
            // True from a set's first line to the blank line after it, also when the Pokémon was not found.
            var inSet = false
            for (raw in text.lines()) {
                val line = raw.trim()
                val value = line.substringAfter(':').trim()
                when {
                    line.startsWith("===") -> {
                        team = Team(FORMAT.replace(line.trim('=', ' '), "").trim(), game).also { teams.add(it) }
                        inSet = false
                    }
                    line.isEmpty() -> inSet = false
                    !inSet -> {
                        inSet = true
                        val at = line.lastIndexOf(" @ ")
                        val head = GENDER.replace(if (at < 0) line else line.substring(0, at), "").trim()
                        val num = findMon(d, names, head) ?: NICKNAME.find(head)?.let { findMon(d, names, it.groupValues[1]) }
                        if (num == null) {
                            unknown.add(head)
                            x = null
                            continue
                        }
                        val m = d.mon(num)
                        x = Member(num, if (m.abilities.isNotEmpty()) m.abilities[0] else m.hidden, 100)
                        if (team == null || team.members.size == SIZE) team = Team(team?.name ?: "", game).also { teams.add(it) }
                        team.members.add(x)
                        if (at >= 0) {
                            val item = line.substring(at + 3).trim()
                            items[Dex.fold(item)]?.let { x.item = it } ?: unknown.add(item)
                        }
                    }
                    x == null -> {}
                    line.startsWith("Ability:") -> abilities[Dex.fold(value)]?.let { x.ability = it } ?: unknown.add(value)
                    line.startsWith("Level:") -> value.toIntOrNull()?.let { x.level = it.coerceIn(1, 100) }
                    line.startsWith("Tera Type:") -> d.types.indexOf(value.lowercase()).let { if (it >= 0) x.tera = it else unknown.add(value) }
                    line.startsWith("EVs:") || line.startsWith("IVs:") -> for (part in value.split('/')) {
                        val f = part.trim().split(' ')
                        val n = f[0].toIntOrNull() ?: continue
                        val stat = Dex.STATS.indexOf(f.getOrNull(1))
                        if (stat < 0) continue
                        if (line[0] == 'E') x.evs[stat] = n.coerceIn(0, x.evRoom(stat)) else x.ivs[stat] = n.coerceIn(0, IV_MAX)
                    }
                    line.endsWith(" Nature") -> d.natures.indexOfFirst { it.en == line.removeSuffix(" Nature").trim() }.let { if (it >= 0) x.nature = it }
                    line.startsWith("-") -> {
                        // "- Hidden Power [Fire]"; "- Flamethrower / Fire Blast" offers a choice, the first is taken.
                        val name = line.drop(1).substringBefore(" / ").substringBefore('[').trim()
                        val id = moves[Dex.fold(name)]
                        val slot = x.moves.indexOf(0)
                        if (id == null) unknown.add(name) else if (slot >= 0) x.moves[slot] = id
                    }
                }
            }
            return Import(teams.filter { it.members.isNotEmpty() }, unknown.toList())
        }

        /**
         * Species or form number for a Showdown name: the whole of it ("Ho-Oh", this app's "Alolan Raichu"), else the
         * species before a hyphen and the form whose words begin with most of the parts after it, fewest words first,
         * else the species. A Mega needs "Mega" in the name: Meowstic-F is not Meowstic Female Mega.
         */
        private fun findMon(d: Dex, names: Map<String, Int>, s: String): Int? {
            val name = gendered(s)
            names[Dex.fold(name)]?.let { return it }
            for ((i, c) in name.withIndex()) {
                if (c != '-') continue
                val base = names[Dex.fold(name.substring(0, i))]?.let { d.mon(it) }?.takeIf { !it.form() } ?: continue
                val parts = name.substring(i + 1).split('-').map { Dex.fold(it) }.filter { it.isNotEmpty() }
                var best = base.num
                var top = 0
                for (f in d.mons) {
                    if (f.species != base.num || !f.form()) continue
                    val words = f.en.split(' ', '(', ')').map { Dex.fold(it) }.filter { it.isNotEmpty() }
                    if ("mega" in words && "mega" !in parts) continue
                    val hits = parts.count { p -> words.any { it.startsWith(p) } }
                    if (hits > 0 && hits * 10 - words.size > top) {
                        best = f.num
                        top = hits * 10 - words.size
                    }
                }
                return best
            }
            return null
        }

        /** Nidoran♀ and Nidoran♂ as Showdown writes them, so the two do not fold to the same name. */
        private fun gendered(s: String) = s.replace("♀", "-F").replace("♂", "-M")

        /** A team from a link only when every number in it is one this data has. */
        private fun valid(d: Dex, t: Team): Boolean = d.game(t.game).id == t.game && t.members.size <= SIZE && t.members.all { x ->
            d.monOrNull(x.num) != null && (x.evo == 0 || d.monOrNull(x.evo) != null) && x.level in 1..100
                && (x.ability == 0 || d.abilities.any { it.id == x.ability }) && (x.item == 0 || d.items.any { it.id == x.item })
                && x.moves.all { id -> id == 0 || d.moves.any { it.id == id } } && x.nature in -1 until d.natures.size
                && x.tera in -1 until d.types.size && x.evs.all { it in 0..EV_STAT } && x.evs.sum() <= EV_TOTAL && x.ivs.all { it in 0..IV_MAX }
        }

        /**
         * Gen 3+ formula: HP = (2B + IV + EV/4) * L / 100 + L + 10, others ((2B + IV + EV/4) * L / 100 + 5) * nature.
         * ponytail: Gen 1-2 DVs and stat experience, Let's Go AVs and Legends effort levels are not modeled.
         */
        fun stats(d: Dex, x: Member): IntArray {
            val m = d.mon(x.num)
            val n = if (x.nature < 0) null else d.natures[x.nature]
            return IntArray(6) { i ->
                val core = (2 * m.stats[i] + x.ivs[i] + x.evs[i] / 4) * x.level / 100
                if (i == 0) {
                    if (m.species == SHEDINJA) 1 else core + x.level + 10
                } else {
                    val boost = when {
                        n == null -> 100
                        n.up == Dex.STATS[i] -> 110
                        n.down == Dex.STATS[i] -> 90
                        else -> 100
                    }
                    (core + 5) * boost / 100
                }
            }
        }

        /**
         * Lowest and highest roll, Gen 5+ formula: base from level, power and attacking over defending stat, the
         * 85-100% roll, 1.5x same-type bonus, then `factor` (x100, [Dex.factor]); at least 1 unless immune.
         * ponytail: no abilities, items, weather, critical hits, stat stages, burn, Tera or Gen 1-4 differences.
         */
        fun damage(level: Int, power: Int, atk: Int, def: Int, stab: Boolean, factor: Int): IntArray {
            val base = (2 * level / 5 + 2) * power * atk / def / 50 + 2
            return IntArray(2) { i ->
                var x = base * (if (i == 0) 85 else 100) / 100
                if (stab) x = (x * 6144 + 2047) / 4096
                x = x * factor / 100
                if (factor > 0) maxOf(1, x) else 0
            }
        }

        /**
         * [damage] with both sets' items and abilities and the calc's [Field], in the order of the Gen 5+ games: base
         * power, attacking and defending stat, base damage, weather, critical hit, roll, same type, type, burn, then
         * the final modifiers. Modifiers are in 4096ths and chained and rounded as the games do; the defender is at
         * full HP (Multiscale).
         * ponytail: about 60 common items and abilities; no Tera, screens, terrain, contact or punch abilities,
         * moves whose power changes, or Gen 1-4 differences. Add one to [ATE], [TYPE_ATK] or the whens below.
         */
        fun hit(d: Dex, g: Dex.Game, a: Member, b: Member, mv: Dex.Move, f: Field): Hit {
            val ai = if (a.item == 0) null else d.item(a.item)
            val bi = if (b.item == 0) null else d.item(b.item)
            val aa = if (a.ability <= 0) null else d.ability(a.ability)
            val ba = if (b.ability <= 0) null else d.ability(b.ability)
            val ae = aa?.en ?: ""
            val be = ba?.en ?: ""
            val special = mv.category == "special"
            val shown = ArrayList<Pair<String, Int>>()

            val bp = ArrayList<Pair<String, Int>>()
            var type = mv.type
            ATE[ae]?.let { to ->
                if (mv.type == "normal" || ae == "Normalize") {
                    type = to
                    if (g.gen >= 7) bp.add(aa!!.name to 4915) else if (ae != "Normalize") bp.add(aa!!.name to 5325)
                }
            }
            val types = d.types(g, d.mon(b.num))
            var factor = d.factor(g, type, types)
            if (ai != null) {
                when {
                    GEM.find(ai.effect)?.groupValues?.get(1)?.lowercase() == type -> bp.add(ai.name to if (g.gen >= 6) 5325 else 6144)
                    BOOST.find(ai.effect)?.groupValues?.get(1)?.lowercase() == type -> bp.add(ai.name to 4915)
                    ai.en == "Muscle Band" && !special || ai.en == "Wise Glasses" && special -> bp.add(ai.name to 4505)
                }
            }
            if (ae == "Technician" && mv.power <= 60) bp.add(aa!!.name to 6144)
            if (ae == "Sheer Force" && mv.chance > 0) bp.add(aa!!.name to 5325)
            val power = maxOf(1, apply(mv.power, chain(bp)))
            shown += bp

            val astage = if (f.crit && f.atkStage < 0) 0 else f.atkStage
            if (astage != 0) shown.add(L.t("Attack stage") + " " + signed(astage) to stage(4096, astage))
            val am = ArrayList<Pair<String, Int>>()
            val species = d.mon(a.num).species
            when {
                ai == null -> {}
                ai.en == "Choice Band" && !special || ai.en == "Choice Specs" && special -> am.add(ai.name to 6144)
                ai.en == "Light Ball" && species == 25 || ai.en == "Thick Club" && !special && (species == 104 || species == 105) -> am.add(ai.name to 8192)
            }
            when {
                (ae == "Huge Power" || ae == "Pure Power") && !special -> am.add(aa!!.name to 8192)
                ae == "Guts" && f.burned && !special || ae == "Hustle" && !special || ae == "Solar Power" && special && f.weather == SUN ->
                    am.add(aa!!.name to 6144)
                ae == "Water Bubble" && type == "water" -> am.add(aa!!.name to 8192)
                TYPE_ATK[ae] == type -> am.add(aa!!.name to if (ae == "Transistor" && g.gen >= 9) 5325 else 6144)
            }
            if (be == "Thick Fat" && (type == "fire" || type == "ice") || (be == "Heatproof" || be == "Water Bubble") && type == "fire"
                    || be == "Purifying Salt" && type == "ghost") am.add(ba!!.name to 2048)
            val atk = apply(stage(stats(d, a)[if (special) 3 else 1], astage), chain(am))
            shown += am

            val dstage = if (f.crit && f.defStage > 0) 0 else f.defStage
            if (dstage != 0) shown.add(L.t("Defense stage") + " " + signed(dstage) to stage(4096, dstage))
            val dm = ArrayList<Pair<String, Int>>()
            if (bi?.en == "Assault Vest" && special || b.item == EVIOLITE && d.evolutions(b.num).isNotEmpty()) dm.add(bi!!.name to 6144)
            if (be == "Fur Coat" && !special) dm.add(ba!!.name to 8192)
            if (f.weather == SAND && special && "rock" in types && g.gen >= 4) dm.add(L.t("Sandstorm") to 6144)
            if (f.weather == SNOW && !special && "ice" in types && g.gen >= 9) dm.add(L.t("Snow") to 6144)
            val def = maxOf(1, apply(stage(stats(d, b)[if (special) 4 else 2], dstage), chain(dm)))
            shown += dm

            val immune = when (be) {
                "Levitate", "Earth Eater" -> type == "ground"
                "Flash Fire", "Well-Baked Body" -> type == "fire"
                "Water Absorb", "Storm Drain", "Dry Skin" -> type == "water"
                "Volt Absorb", "Lightning Rod", "Motor Drive" -> type == "electric"
                "Sap Sipper" -> type == "grass"
                "Wonder Guard" -> factor <= 100
                else -> false
            }
            if (immune && factor > 0) {
                factor = 0
                shown.add(ba!!.name to 0)
            }
            val weather = when {
                f.weather == SUN && type == "fire" || f.weather == RAIN && type == "water" -> 6144
                f.weather == SUN && type == "water" || f.weather == RAIN && type == "fire" -> 2048
                else -> 4096
            }
            if (weather != 4096) shown.add(L.t(WEATHER[f.weather]) to weather)
            val crit = if (g.gen >= 6) 6144 else 8192
            if (f.crit) shown.add(L.t("Critical hit") to crit)
            val stab = when {
                type !in d.types(g, d.mon(a.num)) -> 4096
                ae == "Adaptability" -> 8192
                else -> 6144
            }
            if (stab != 4096) shown.add((if (stab == 8192) aa!!.name else L.t("Same type")) to stab)
            val burn = f.burned && !special && ae != "Guts"
            if (burn) shown.add(L.t("Burned") to 2048)

            val fm = ArrayList<Pair<String, Int>>()
            when {
                ai == null -> {}
                ai.en == "Life Orb" -> fm.add(ai.name to 5324)
                ai.en == "Expert Belt" && factor > 100 -> fm.add(ai.name to 4915)
            }
            val berry = bi?.let { BERRY.find(it.effect) }?.groupValues?.get(1)?.lowercase()
            if (berry == type && (factor > 100 || type == "normal" && factor > 0)) fm.add(bi!!.name to 2048)
            when {
                (be == "Multiscale" || be == "Shadow Shield") && factor > 0 || be == "Ice Scales" && special -> fm.add(ba!!.name to 2048)
                (be == "Filter" || be == "Solid Rock" || be == "Prism Armor") && factor > 100 -> fm.add(ba!!.name to 3072)
            }
            if (ae == "Tinted Lens" && factor in 1..99) fm.add(aa!!.name to 8192)
            if (ae == "Sniper" && f.crit) fm.add(aa!!.name to 6144)
            shown += fm

            val base = ((2 * a.level / 5 + 2).toLong() * power * atk / def / 50 + 2).toInt()
            val roll = IntArray(2) { i ->
                var x = apply(base, weather)
                if (f.crit) x = x * crit / 4096
                x = x * (if (i == 0) 85 else 100) / 100
                x = apply(x, stab)
                x = x * factor / 100
                if (burn) x = apply(x, 2048)
                x = apply(x, chain(fm))
                if (factor > 0) maxOf(1, x) else 0
            }
            return Hit(roll, atk, def, power, factor, shown)
        }

        /** Modifiers in 4096ths combined as the games do, each step rounded half up. */
        private fun chain(mods: List<Pair<String, Int>>): Int = mods.fold(4096) { c, m -> (c * m.second + 2048) shr 12 }

        /** `x` times `mod` (4096ths), rounded half down as the games do. */
        private fun apply(x: Int, mod: Int): Int = ((x.toLong() * mod + 2047) / 4096).toInt()

        /** `x` at stat stage `s` (-6 to 6): +1 is 3/2, -1 is 2/3. */
        private fun stage(x: Int, s: Int): Int = if (s >= 0) x * (2 + s) / 2 else x * 2 / (2 - s)

        fun signed(s: Int): String = if (s > 0) "+$s" else s.toString()

        /** Attacking types both type lists are weak to, if they double up (see [overlaps]), else null. */
        private fun overlap(d: Dex, g: Dex.Game, a: List<String>, b: List<String>): List<Int>? {
            val types = d.types(g)
            val weak = types.indices.filter { d.factor(g, types[it], a) > 100 && d.factor(g, types[it], b) > 100 }
            return if (weak.size >= 2 || weak.isNotEmpty() && a.any { it in b }) weak else null
        }

        /** "\nEVs: 252 Atk / 4 SpD", leaving out stats at the default; "" when all are. */
        private fun spread(label: String, values: IntArray, skip: Int): String {
            val parts = (0 until 6).filter { values[it] != skip }.map { "${values[it]} ${Dex.STATS[it]}" }
            return if (parts.isEmpty()) "" else "\n$label: " + parts.joinToString(" / ")
        }
    }
}
