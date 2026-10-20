package demo.dex

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** One team: its six slots, a type weakness table and move coverage, rename, copy as text, delete. */
class TeamActivity : Activity() {
    private lateinit var dex: Dex
    private var index = 0
    private lateinit var teams: MutableList<Team>
    private lateinit var team: Team
    private lateinit var game: Dex.Game
    private lateinit var scroll: ScrollView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        dex = Ui.dex(this)
        index = intent.getIntExtra("team", 0)
        scroll = ScrollView(this)
        Ui.show(this, scroll)
    }

    /** Members change on the member screen, so the whole page is rebuilt on return. */
    override fun onResume() {
        super.onResume()
        teams = TeamsActivity.load(this)
        if (index >= teams.size) {
            finish()
            return
        }
        team = teams[index]
        game = dex.game(team.game)
        val y = scroll.scrollY
        scroll.removeAllViews()
        scroll.addView(page())
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun page(): View {
        val root = Ui.vertical(this)
        root.setPadding(0, 0, 0, Ui.dp(this, 16))
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        val name = Ui.title(this, team.name)
        name.setOnClickListener { rename() }
        head.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.chip(this, L.t("Rename")) { rename() }, Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 4, 16, 0))
        root.addView(head)
        val sub = Ui.muted(this, game.name, 14)
        sub.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 12))
        root.addView(sub)

        val slots = Ui.vertical(this)
        slots.background = Ui.rounded(this, Ui.SURFACE, 18)
        slots.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6))
        for ((k, x) in team.members.withIndex()) {
            val m = dex.mon(x.num)
            val row = Ui.row(this)
            Ui.bind(row, Ui.Item(m.num, L.f("Lv %d", x.level), m.name, TeamsActivity.setLine(dex, x), dex.types(game, m), m))
            row.setOnClickListener { MemberActivity.open(this, index, k) }
            row.setOnLongClickListener {
                replace(k)
                true
            }
            slots.addView(row)
        }
        if (team.members.size < Team.SIZE) {
            val add = Ui.row(this)
            Ui.bind(add, Ui.Item(0, "+", L.t("Add Pokémon"), L.f("Free slots: %d", Team.SIZE - team.members.size), emptyList(), ""))
            add.setOnClickListener { addMember() }
            slots.addView(add)
        }
        root.addView(slots, Ui.wide(this, 0, 0))
        val hold = Ui.muted(this, L.t("Hold a Pokémon to swap it for another."), 13)
        hold.setPadding(Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 20), Ui.dp(this, 12))
        root.addView(hold)

        if (team.members.isNotEmpty()) {
            root.addView(tips(), Ui.wide(this, 0, 12))
            root.addView(defense(), Ui.wide(this, 0, 12))
            root.addView(coverage(), Ui.wide(this, 0, 12))
        }

        val actions = LinearLayout(this)
        Ui.addChip(actions, Ui.chip(this, L.t("Compare")) { CompareActivity.open(this, 0, game.id) })
        Ui.addChip(actions, Ui.chip(this, L.t("Damage calc")) { CalcActivity.open(this, index, -1) })
        Ui.addChip(actions, Ui.chip(this, L.t("Copy as text")) { copy() })
        root.addView(Ui.chipBar(this, actions))
        // Its own row, as "Remove from team" on a member: out of reach of a tap meant for the actions above.
        val deleteChip = Ui.chip(this, L.t("Delete team")) { delete() }
        deleteChip.setTextColor(Ui.ACCENT_TEXT)
        val last = LinearLayout(this)
        Ui.addChip(last, deleteChip)
        root.addView(Ui.chipBar(this, last))
        return root
    }

    /**
     * Pokémon of this team's game (species and their forms), by regional number, suggestions first. A pick from the
     * Box tab joins as the Box's set and leaves the Box; any other joins new.
     */
    private fun addMember() {
        pick(this, dex, team, -1, L.t("Add Pokémon")) { x, boxed ->
            if (boxed) Ui.fromBox(this, x.num)
            team.members.add(x)
            TeamsActivity.save(this, teams)
            MemberActivity.open(this, index, team.members.size - 1)
        }
    }

    /** Swaps slot `k` for another Pokémon after [confirmSwap]; the old one goes to the Box. */
    private fun replace(k: Int) {
        val old = team.members[k]
        pick(this, dex, team, k, L.f("Swap %s", dex.mon(old.num).name)) { x, boxed ->
            confirmSwap(this, dex, game, old, x, boxed) { nx ->
                team.members[k] = nx
                TeamsActivity.save(this, teams)
                onResume()
            }
        }
    }

    /** Every attacking type with how many members it hits super effectively and how many resist it. */
    private fun defense(): View {
        val d = team.defense(dex)
        return typeTable(L.t("Type defenses"), L.t("Members weak to and resisting each attacking type. Red: half the team or more is weak."),
            L.t("Weak"), L.t("Resist")) { i ->
            val bad = d[i][0] * 2 >= team.members.size && d[i][0] > d[i][1]
            listOf(d[i][0].toString() to (if (bad) red else Ui.TEXT), d[i][1].toString() to (if (d[i][1] > 0) green else Ui.MUTED))
        }
    }

    /** The other side: every defending type with how many members hit it super effectively and how many it walls. */
    private fun coverage(): View {
        val c = team.coverage(dex)
        // Without a single attack every count is 0, which is not yet a gap.
        val attacks = team.attacks(dex)
        return typeTable(L.t("Type coverage"), L.t("Members with an attack that hits each type super effectively, and members whose every attack it resists. Red: nothing hits it."),
            L.t("Hits"), L.t("Walled")) { i ->
            listOf(c[i][0].toString() to (if (c[i][0] > 0) green else if (attacks) red else Ui.MUTED), c[i][1].toString() to (if (c[i][1] > 0) red else Ui.MUTED))
        }
    }

    private val red get() = (if (Ui.night) 0xFFFF8A80 else 0xFFC62828).toInt()
    private val green get() = (if (Ui.night) 0xFF7BD88F else 0xFF1E7B3A).toInt()

    /** The game's types in two columns with two numbers each, under `first` and `second`; `cell(i)` gives type `i`'s numbers with their colors. */
    private fun typeTable(heading: String, help: String, first: String, second: String, cell: (Int) -> List<Pair<String, Int>>): View {
        val p = Ui.panel(this, heading)
        val h = Ui.muted(this, help, 13)
        h.setPadding(0, 0, 0, Ui.dp(this, 8))
        p.addView(h)
        val types = dex.types(game)
        val half = (types.size + 1) / 2
        val cols = LinearLayout(this)
        for (c in 0 until 2) {
            val col = Ui.vertical(this)
            col.addView(typeRow(null, listOf(first to Ui.MUTED, second to Ui.MUTED)))
            for (i in c * half until minOf(types.size, (c + 1) * half)) col.addView(typeRow(types[i], cell(i)))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (c == 0) lp.setMargins(0, 0, Ui.dp(this, 12), 0)
            cols.addView(col, lp)
        }
        p.addView(cols)
        return p
    }

    private fun typeRow(type: String?, cells: List<Pair<String, Int>>): View {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2))
        row.addView(if (type == null) View(this) else Ui.pill(this, type),
            LinearLayout.LayoutParams(Ui.dp(this, 76), ViewGroup.LayoutParams.WRAP_CONTENT))
        for ((label, color) in cells) {
            val t = Ui.text(this, label, if (type == null) 11 else 14, true)
            t.setTextColor(color)
            t.gravity = Gravity.CENTER
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return row
    }

    /** [Team.gaps] in words: what hurts, what nothing answers, what no move hits, each with the type that fixes most. */
    private fun tips(): View {
        val p = Ui.panel(this, L.t("Tips"))
        val g = team.gaps(dex)
        val types = dex.types(game)
        val names = { list: List<Int> ->
            val n = list.map { dex.label(types[it]) }
            if (n.size == 1) n[0] else L.f("%s and %s", n.dropLast(1).joinToString(L.t(", ")), n.last())
        }
        val stacked = if (g.stacked.isEmpty()) null
            else tip(p, L.t("Weak spots"), L.t("Super effective against half the team or more, and resisted by fewer."), g.stacked)
        val counter = if (g.counter.isEmpty()) null
            else tip(p, L.t("Nothing counters these"), L.t("No member resists them and no move hits them super effectively."), g.counter)
        val uncovered = if (g.uncovered.isEmpty()) null
            else tip(p, L.t("Missing coverage"), L.t("No move hits these super effectively."), g.uncovered)
        // gaps() leaves wall at -1 when counter and stacked are empty, move when counter and uncovered are.
        if (g.wall >= 0) fix((counter ?: stacked)!!, L.f("Try a %s Pokémon", dex.label(types[g.wall])),
            L.f("It resists %s.", names((g.counter + g.stacked).filter { dex.factor(game, types[it], listOf(types[g.wall])) < 100 })),
            if (team.members.size < Team.SIZE) L.t("Suggestions") else null) { addMember() }
        if (g.move >= 0) {
            val hits = L.f("It hits %s super effectively.", names((g.counter + g.uncovered).filter { dex.factor(game, types[g.move], listOf(types[it])) > 100 }))
            val t = team.teach(dex, types[g.move])
            if (t == null) fix((uncovered ?: counter)!!, L.f("Try a %s move", dex.label(types[g.move])), hits)
            else {
                val x = team.members[t.member]
                val mon = team.evolved(dex, game, x).name
                val move = dex.move(game, t.move).name
                val how = if (x.moves[t.slot] == 0) L.f("%s can learn %s.", mon, move)
                    else L.f("%s can learn %s in place of %s.", mon, move, dex.move(game, x.moves[t.slot]).name)
                fix((uncovered ?: counter)!!, L.f("Try a %s move", dex.label(types[g.move])), "$hits $how", L.f("Teach %s", move)) {
                    confirmTeach(this, dex, game, mon, x, t.slot, t.move) {
                        x.moves[t.slot] = t.move
                        TeamsActivity.save(this, teams)
                        onResume()
                    }
                }
            }
        }
        if (!team.attacks(dex)) tip(p, L.t("No attacks yet"), L.t("Give your Pokémon damaging moves to see what nothing counters."), emptyList())
        else if (p.childCount == 1) tip(p, L.t("No gaps"), L.t("Every type is resisted or hit super effectively, and none hits half the team."), emptyList())
        val ends = team.members.map { team.evolved(dex, game, it) }
        // Until picked, a branching Pokémon counts as it is: Eevee stays Normal. The pick keeps its tip, to change it there.
        val branching = BooleanArray(team.members.size)
        for ((i, x) in team.members.withIndex()) {
            val ways = team.branches(dex, game, x)
            if (ways.isEmpty()) continue
            branching[i] = true
            val name = dex.mon(x.num).name
            val pick = {
                pickEvolution(this, dex, team, x) {
                    TeamsActivity.save(this, teams)
                    onResume()
                }
            }
            val into = ways.firstOrNull { it.num == ends[i].num }
            if (into == null) tip(p, L.f("What will %s evolve into?", name), L.t("Tips count it as it is until you pick."), emptyList(), L.t("Pick evolution"), pick)
            else tip(p, L.f("%s will evolve into %s", name, into.name), L.t("Tips count its types once evolved."), emptyList(), L.t("Change evolution"), pick)
        }
        // The lower base stat total once evolved is the one to swap: Noibat beside Garchomp, but Gible beside Noivern.
        for (o in team.overlaps(dex)) {
            val a = dex.mon(team.members[o.a].num)
            val b = dex.mon(team.members[o.b].num)
            val k = if (ends[o.b].bst <= ends[o.a].bst) o.b else o.a
            tip(p, L.f("%s and %s overlap", a.name, b.name), L.t("They share these weaknesses, so one adds little. Swap it for a Pokémon that covers them."),
                o.weak, L.f("Swap %s", dex.mon(team.members[k].num).name)) { replace(k) }
        }
        // Only evolutions that change types change the tips; Gabite into Garchomp goes unsaid.
        val changed = team.members.withIndex().filter { (i, x) -> !branching[i] && dex.types(game, ends[i]) != dex.types(game, dex.mon(x.num)) }
            .map { (i, x) -> L.f("%s as %s", dex.mon(x.num).name, ends[i].name) }
        if (changed.isNotEmpty()) p.addView(Ui.muted(this, L.f("Counted as evolved: %s.", changed.joinToString(L.t(", "))), 13),
            Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 12, 0, 0))
        return p
    }

    /** Bold title, muted detail, then the types as pills and an optional `action` chip; a hairline above all but the first. */
    private fun tip(p: LinearLayout, title: String, detail: String, list: List<Int>, action: String? = null, act: () -> Unit = {}): LinearLayout {
        if (p.childCount > 1) {
            val line = View(this)
            line.setBackgroundColor(Ui.FILL)
            p.addView(line, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1), this, 0, 12, 0, 12))
        }
        val box = Ui.vertical(this)
        box.addView(Ui.text(this, title, 15, true))
        box.addView(Ui.muted(this, detail, 13), Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 2, 0, 0))
        if (list.isNotEmpty()) box.addView(Ui.typePills(this, list.map { dex.types(game)[it] }, 4),
            Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 6, 0, 0))
        if (action != null) box.addView(Ui.chip(this, action) { act() },
            Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 8, 0, 0))
        p.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    /** The answer to a [tip], inside it on a green tint, so it does not read as one more problem. */
    private fun fix(box: LinearLayout, title: String, detail: String, action: String? = null, act: () -> Unit = {}) {
        val f = Ui.vertical(this)
        f.background = Ui.rounded(this, Ui.tonal(Ui.GOOD), 12)
        f.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10))
        val t = Ui.text(this, title, 14, true)
        t.setTextColor(Ui.GOOD)
        f.addView(t)
        f.addView(Ui.muted(this, detail, 13), Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 2, 0, 0))
        if (action != null) f.addView(Ui.chip(this, action) { act() },
            Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 8, 0, 0))
        box.addView(f, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 10, 0, 0))
    }

    private fun rename() {
        Ui.askName(this, L.t("Team name"), team.name) { s ->
            team.name = s
            TeamsActivity.save(this, teams)
            onResume()
        }
    }

    private fun copy() {
        getSystemService(ClipboardManager::class.java)!!.setPrimaryClip(ClipData.newPlainText(team.name, team.export(dex)))
        // Android 13 and later show their own confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(this, L.t("Copied"), Toast.LENGTH_SHORT).show()
    }

    private fun delete() {
        Ui.confirm(this, L.f("Delete %s?", team.name), L.f("Its %d Pokémon and their sets are removed from this device.",
            team.members.size), L.t("Delete")) {
            teams.removeAt(index)
            TeamsActivity.save(this, teams)
            finish()
        }
    }

    companion object {
        fun open(a: Activity, team: Int) {
            a.startActivity(Intent(a, TeamActivity::class.java).putExtra("team", team))
        }

        /**
         * [Team.branches] of `x` as Pokémon rows, the picked one ringed; picking one sets [Team.Member.evo]. Eevee's
         * eight rows hold a sprite each while it is open, 64 KB apiece.
         */
        fun pickEvolution(a: Activity, dex: Dex, team: Team, x: Team.Member, done: () -> Unit) {
            val game = dex.game(team.game)
            val s = Ui.Sheet(a, L.f("What will %s evolve into?", dex.mon(x.num).name))
            val rows = Ui.vertical(a)
            for (m in team.branches(dex, game, x)) {
                val r = Ui.row(a)
                Ui.bind(r, TeamsActivity.monItem(dex, game, m))
                r.background = Ui.choice(a, m.num == x.evo, 14)
                r.isSelected = m.num == x.evo
                r.setOnClickListener {
                    if (s.close()) {
                        x.evo = m.num
                        done()
                    }
                }
                rows.addView(r, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, a, 0, 0, 0, 6))
            }
            val scroll = ScrollView(a)
            scroll.addView(rows)
            s.body.addView(scroll)
            s.button(L.t("Cancel"))
            s.show()
        }

        /** Asks before a tip's move goes into `slot` of `x` (shown as `mon`): the move it forgets and the one it learns side by side. */
        fun confirmTeach(a: Activity, dex: Dex, game: Dex.Game, mon: String, x: Team.Member, slot: Int, id: Int, yes: () -> Unit) {
            val new = dex.move(game, id)
            val old = if (x.moves[slot] == 0) null else dex.move(game, x.moves[slot])
            val box = Ui.vertical(a)
            box.addView(CompareActivity.pair(a,
                side(a, L.t("Forgets"), 0, old?.name ?: L.t("Empty slot"), old?.let { listOf(it.type, it.category) } ?: emptyList(), old?.shortEffect ?: ""),
                side(a, L.t("Learns"), 0, new.name, listOf(new.type, new.category), new.shortEffect), 12),
                Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, a, 0, 0, 0, 10))
            val row = { name: String, x: Int, y: Int, shownX: String, shownY: String ->
                box.addView(CompareActivity.sides(a, CompareActivity.label(a, name), x, y, shownX, shownY, false))
            }
            val shown = { v: Int -> if (v == 0) "-" else v.toString() }
            // Accuracy 0 never misses, so it counts above 100. An empty slot takes the new values, so neither side is marked.
            val sure = { m: Dex.Move -> if (m.accuracy == 0) 101 else m.accuracy }
            row(L.t("Power"), old?.power ?: new.power, new.power, old?.let { shown(it.power) } ?: "-", shown(new.power))
            row(L.t("Accuracy"), old?.let(sure) ?: sure(new), sure(new), old?.let { shown(it.accuracy) } ?: "-", shown(new.accuracy))
            row(L.t("PP"), old?.pp ?: new.pp, new.pp, old?.pp?.toString() ?: "-", new.pp.toString())
            val scroll = ScrollView(a)
            scroll.addView(box)
            val s = Ui.Sheet(a, L.f("Teach %s to %s?", new.name, mon))
            s.body.addView(scroll)
            s.button(L.t("Cancel"))
            s.button(L.f("Teach %s", new.name), main = true, act = yes)
            s.show()
        }

        /**
         * Asks before `new` takes `old`'s slot: both with their base stats side by side, and where each set goes. `old`
         * goes to the Box, as in the games, so no question about it; `boxed` says `new` is the Box's set, which leaves
         * the Box, else `new` joins fresh at `old`'s level. `yes` gets the member that joins.
         */
        fun confirmSwap(a: Activity, dex: Dex, game: Dex.Game, old: Team.Member, new: Team.Member, boxed: Boolean, yes: (Team.Member) -> Unit) {
            val om = dex.mon(old.num)
            val nm = dex.mon(new.num)
            val box = Ui.vertical(a)
            box.addView(CompareActivity.pair(a, side(a, null, om.num, om.name, dex.types(game, om), ""),
                side(a, null, nm.num, nm.name, dex.types(game, nm), ""), 12),
                Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, a, 0, 0, 0, 10))
            for (i in Dex.STATS.indices) box.addView(CompareActivity.sides(a, CompareActivity.label(a, L.t(Dex.STATS[i])), om.stats[i], nm.stats[i],
                om.stats[i].toString(), nm.stats[i].toString(), true))
            box.addView(CompareActivity.sides(a, CompareActivity.label(a, L.t("Total")), om.bst, nm.bst, om.bst.toString(), nm.bst.toString(), false))
            box.addView(Ui.muted(a, L.f("%s goes to the Box with its moves and item.", om.name), 13),
                Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, a, 0, 12, 0, 0))
            val from = if (boxed) L.f("%s comes from the Box: %s", nm.name, L.f("Lv %d", new.level) + "  ·  " + TeamsActivity.setLine(dex, new))
                else L.f("%s joins new at level %d.", nm.name, old.level)
            box.addView(Ui.muted(a, from, 13), Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, a, 0, 6, 0, 0))
            val scroll = ScrollView(a)
            scroll.addView(box)
            val s = Ui.Sheet(a, L.f("Swap %s for %s?", om.name, nm.name))
            s.body.addView(scroll)
            s.button(L.t("Cancel"))
            s.button(L.t("Swap"), main = true) {
                // Out of the Box before the old set goes in, which would replace a set of the same species.
                if (boxed) Ui.fromBox(a, new.num) else new.level = old.level
                Ui.toBox(a, old)
                yes(new)
            }
            s.show()
        }

        /** One side of a confirm: `caption` or the sprite of `num`, the name, pills that do not navigate away, and `note`. */
        private fun side(a: Activity, caption: String?, num: Int, name: String, pills: List<String>, note: String): View {
            val col = Ui.vertical(a)
            col.gravity = Gravity.CENTER_HORIZONTAL
            if (caption != null) col.addView(Ui.caption(a, caption))
            else col.addView(ImageView(a).apply { setImageDrawable(Ui.sprite(a, num, 2)) }, LinearLayout.LayoutParams(Ui.dp(a, 72), Ui.dp(a, 72)))
            val t = Ui.text(a, name, 16, true)
            t.gravity = Gravity.CENTER
            col.addView(t)
            if (pills.isNotEmpty()) {
                // Half the width each, as in Compare: one type sits at the start.
                val row = LinearLayout(a)
                row.weightSum = 2f
                for (key in pills) {
                    val p = Ui.pill(a, key)
                    p.setPadding(0, Ui.dp(a, 5), 0, Ui.dp(a, 5))
                    row.addView(p, Ui.margins(0, ViewGroup.LayoutParams.WRAP_CONTENT, a, 3, 0, 3, 0).apply { weight = 1f })
                }
                col.addView(row, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, a, 0, 6, 0, 0))
            }
            if (note.isNotEmpty()) col.addView(Ui.muted(a, note, 13).apply { gravity = Gravity.CENTER },
                Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, a, 0, 6, 0, 0))
            return col
        }

        /** A Pokémon for slot `skip` of `team` (-1: a new slot), with [Team.suggest]ions in the first tab; see [TeamsActivity.pick]. */
        fun pick(a: Activity, dex: Dex, team: Team, skip: Int, heading: String, picked: (Team.Member, Boolean) -> Unit) {
            val game = dex.game(team.game)
            TeamsActivity.pick(a, dex, game, heading, L.t("Suggested") to team.suggest(dex, skip).map { TeamsActivity.monItem(dex, game, it) },
                picked = picked)
        }
    }
}
