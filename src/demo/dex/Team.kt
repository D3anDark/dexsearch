package demo.dex

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

    /** Per defending type: members with a damaging move that hits it super effectively (Freeze-Dry style exceptions not counted). */
    fun offense(d: Dex): IntArray {
        val g = d.game(game)
        val types = d.types(g)
        return IntArray(types.size) { i ->
            val target = listOf(types[i])
            members.count { m -> m.moves.any { id -> id != 0 && d.move(g, id).let { it.category != "status" && d.factor(g, it.type, target) > 100 } } }
        }
    }

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
            val stab = d.types(g, m)
            val score = { mv: Dex.Move ->
                mv.power * (if (mv.accuracy == 0) 100 else mv.accuracy) * (if (mv.type in stab) 3 else 2) *
                    m.stats[if (mv.category == "physical") 1 else 3]
            }
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
        /** Effect text of attacks a team does not run for coverage: recharge or charge turns, fainting, conditions. */
        private val AWKWARD = Regex("recharge|faints|charge before|charges for|next turn|later|[Oo]nly (works|inflicts|be used)|" +
            "[Ff]ails if|canceled|half of its max HP|damages the user\\.|no longer|Removes the user|readying|shares a type|Misty Terrain is active")
        /** Legendary, mythical, Ultra Beast and Paradox species, Articuno to Pecharunt; the data has no flag for them. */
        private val LEGENDS = listOf(144..146, 150..151, 243..245, 249..251, 377..386, 480..494, 638..649, 716..721,
            772..773, 785..809, 888..898, 905..905, 984..995, 1001..1010, 1014..1017, 1020..1025)

        fun legend(species: Int) = LEGENDS.any { species in it }

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
