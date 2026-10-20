package demo.dex

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView

/** One team member's set: form, ability, item, nature, Tera type, level, moves, IVs and EVs with live stats. */
class MemberActivity : Activity() {
    private lateinit var dex: Dex
    private lateinit var teams: MutableList<Team>
    private lateinit var team: Team
    private lateinit var x: Team.Member
    private lateinit var game: Dex.Game
    private var removed = false
    private var scroll: ScrollView? = null
    private val totals = ArrayList<TextView>(6)
    private var evLeft: TextView? = null
    private var builds = emptyList<Dex.Build>()
    private var buildsFor = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        dex = Ui.dex(this)
        val t = intent.getIntExtra("team", 0)
        val k = intent.getIntExtra("member", 0)
        teams = TeamsActivity.load(this)
        if (t >= teams.size || k >= teams[t].members.size) {
            finish()
            return
        }
        team = teams[t]
        x = team.members[k]
        game = dex.game(team.game)
        render()
    }

    /** Every edit changes `x` in memory; leaving the screen (or the app) writes the whole team list once. */
    override fun onPause() {
        super.onPause()
        if (::team.isInitialized && !removed) TeamsActivity.save(this, teams)
    }

    /** Rebuilds the page after a choice that changes several parts (form, nature, moves); keeps the scroll. */
    private fun render() {
        val m = dex.mon(x.num)
        val color = Ui.deep(Ui.color(dex.types(game, m)[0]))
        val y = scroll?.scrollY ?: 0
        val root = Ui.vertical(this)
        root.setPadding(0, 0, 0, Ui.dp(this, 16))
        root.addView(hero(m, color))

        val actions = LinearLayout(this)
        val k = team.members.indexOf(x)
        Ui.addChip(actions, Ui.chip(this, L.t("Swap")) {
            TeamActivity.pick(this, dex, team, k, L.f("Swap %s", m.name)) { new ->
                TeamActivity.confirmSwap(this, dex, game, x, new) { nx ->
                    x = nx
                    team.members[k] = x
                    render()
                }
            }
        })
        Ui.addChip(actions, Ui.chip(this, L.t("Compare")) { CompareActivity.open(this, m.num, game.id) })
        Ui.addChip(actions, Ui.chip(this, L.t("Damage calc")) { CalcActivity.open(this, teams.indexOf(team), k) })
        root.addView(Ui.chipBar(this, actions), Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 12, 0, 0))

        root.addView(details(m), Ui.wide(this, 12, 0))
        root.addView(moves(m), Ui.wide(this, 12, 0))
        root.addView(stats(m), Ui.wide(this, 12, 12))

        val remove = Ui.chip(this, L.t("Remove from team")) { remove(m) }
        remove.setTextColor(Ui.ACCENT_TEXT)
        val last = LinearLayout(this)
        Ui.addChip(last, remove)
        root.addView(Ui.chipBar(this, last))

        val s = ScrollView(this)
        s.addView(root)
        scroll = s
        Ui.show(this, s, color)
        s.post { s.scrollTo(0, y) }
    }

    private fun hero(m: Dex.Mon, color: Int): View {
        val h = Ui.hero(this, color)
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        val texts = Ui.vertical(this)
        texts.addView(Ui.white(this, m.name, 26, true))
        texts.addView(Ui.white(this, "${team.name}  ·  ${game.name}", 14, false))
        val pills = LinearLayout(this)
        for (t in dex.types(game, m)) {
            pills.addView(Ui.typeGlass(this, t), Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 0, 6, 0))
        }
        texts.addView(pills, Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 10, 0, 0))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val img = ImageView(this)
        img.setImageDrawable(Ui.sprite(this, m.num, 1))
        img.setOnClickListener { Ui.openMon(this, m.num, null) }
        row.addView(img, LinearLayout.LayoutParams(Ui.dp(this, 128), Ui.dp(this, 128)))
        h.addView(row, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 12, 0, 0))

        // Back a stage on the left, forward on the right; moves, item and stats carry over. Space, not a bare View,
        // holds an empty half: a View takes all the height it is offered.
        val from = dex.evo(m.num)?.from?.takeIf { game.byNum.containsKey(it) }?.let { dex.mon(it) }
        val into = dex.evolutions(m.num).map { it.to }.distinct().filter { game.byNum.containsKey(it) }.map { dex.mon(it) }
        if (from != null || into.isNotEmpty()) {
            val where = Ui.white(this, L.t("Evolution stage"), 12, false)
            where.alpha = 0.85f
            h.addView(where, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 14, 0, 0))
            val evo = LinearLayout(this)
            val half = { v: View, start: Boolean ->
                evo.addView(v, Ui.margins(0, ViewGroup.LayoutParams.WRAP_CONTENT, this, if (start) 0 else 5, 0, if (start) 5 else 0, 0)
                    .apply { weight = 1f })
            }
            half(if (from == null) Space(this) else stage(from.name, L.f("Devolve to %s", from.name), true) { setForm(from) }, true)
            half(when (into.size) {
                0 -> Space(this)
                1 -> stage(into[0].name, L.f("Evolve into %s", into[0].name), false) { setForm(into[0]) }
                // Eevee's eight would not fit a line; they go in a list.
                else -> stage(L.t("Evolve"), L.t("Evolve"), false) {
                    Ui.pickOne(this, L.t("Evolve"), into.map { it.name }, -1) { i -> setForm(into[i]) }
                }
            }, false)
            h.addView(evo, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 6, 0, 0))
        }
        return h
    }

    /** A glass button in the hero naming a stage, its arrow on the side it leads to. */
    private fun stage(label: String, description: String, back: Boolean, act: () -> Unit): TextView {
        val t = Ui.white(this, label, 14, true)
        t.gravity = Gravity.CENTER
        t.setSingleLine()
        t.ellipsize = TextUtils.TruncateAt.END
        t.minHeight = Ui.dp(this, 44)
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10))
        t.background = Ui.pressable(this, Ui.rounded(this, 0x2E000000, 999), 999)
        t.contentDescription = description
        val arrow = Ui.Chevron(Ui.WHITE, back)
        arrow.setBounds(0, 0, Ui.dp(this, 10), Ui.dp(this, 10))
        if (back) t.setCompoundDrawablesRelative(arrow, null, null, null) else t.setCompoundDrawablesRelative(null, null, arrow, null)
        t.compoundDrawablePadding = Ui.dp(this, 6)
        t.setOnClickListener { act() }
        return t
    }

    /** Keeps the ability if the new form has it, else takes the form's first one. */
    private fun setForm(f: Dex.Mon) {
        x.num = f.num
        if (f.hidden != x.ability && x.ability !in f.abilities) x.ability = TeamsActivity.member(f).ability
        render()
    }

    /** Smogon sets, ability (Gen 3 on), held item (Gen 2 on), nature (Gen 3 on), Tera type (Scarlet and Violet), level. */
    private fun details(m: Dex.Mon): View {
        val p = Ui.panel(this, L.t("Set"))
        // Every form in this game, also the [Dex.alike] ones pickers leave out.
        val forms = dex.family(m).filter { it === m || game.byNum.containsKey(it.num) }
        if (forms.size > 1) p.addView(Ui.setting(this, L.t("Form"), m.name) {
            Ui.pickOne(this, L.t("Form"), forms.map { it.name }, forms.indexOf(m)) { i -> setForm(forms[i]) }
        })
        // What Team.evolved counts it as in the tips, where the line branches.
        val ways = team.branches(dex, game, x)
        if (ways.isNotEmpty()) p.addView(Ui.setting(this, L.t("Evolves into"), ways.firstOrNull { it.num == x.evo }?.name ?: L.t("Not set")) {
            TeamActivity.pickEvolution(this, dex, team, x) { render() }
        })
        val sets = builds(m)
        if (sets.isNotEmpty()) p.addView(Ui.setting(this, L.t("Smogon sets"), sets.size.toString()) { pickBuild(sets) })
        if (game.gen >= 3) {
            val ids = m.abilities.toMutableList()
            if (m.hidden != 0 && m.hidden !in ids) ids.add(m.hidden)
            val labels = ids.map { if (it == m.hidden) L.f("%s (hidden)", dex.ability(it).name) else dex.ability(it).name }
            // A list, not chips: three names with "(hidden)" do not fit one line at 360 dp.
            p.addView(Ui.setting(this, L.t("Ability"), if (x.ability == 0) L.t("Not set") else dex.ability(x.ability).name) {
                Ui.pickOne(this, L.t("Ability"), labels, ids.indexOf(x.ability)) { i ->
                    x.ability = ids[i]
                    render()
                }
            })
            if (x.ability != 0) p.addView(Ui.muted(this, dex.ability(x.ability).shortEffect, 13),
                Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 0, 0, 8))
        }
        if (game.gen >= 2) p.addView(Ui.setting(this, L.t("Item"), if (x.item == 0) L.t("None") else Ui.itemName(this, dex.item(x.item))) { pickItem() })
        if (game.gen >= 3) {
            p.addView(Ui.setting(this, L.t("Nature"), if (x.nature < 0) L.t("Not set") else natureLabel(dex.natures[x.nature])) {
                val d = Ui.Sheet(this, L.t("Nature"))
                d.body.addView(NaturesActivity.chart(this, dex, x.nature) { i ->
                    if (d.close()) {
                        x.nature = i
                        render()
                    }
                })
                d.button(L.t("Cancel"))
                d.show()
            })
        }
        if (game.id == TERA_GAME || game.virtual && game.gen >= 9) {
            p.addView(Ui.setting(this, L.t("Tera type"), if (x.tera < 0) L.t("Not set") else dex.label(dex.types[x.tera])) {
                Ui.pickType(this, L.t("Tera type"), dex.types, x.tera) { i ->
                    x.tera = i
                    render()
                }
            })
        }
        val level = Ui.settingRow(this, L.t("Level"))
        lateinit var box: EditText
        val quick = LEVELS.map { v -> Ui.chip(this, v.toString()) { box.setText(v.toString()) } }
        box = number(this, x.level, 1, 100) { v ->
            x.level = v
            for ((i, q) in quick.withIndex()) Ui.chipActive(q, LEVELS[i] == v)
            refreshStats()
            v
        }
        for ((i, q) in quick.withIndex()) {
            Ui.chipActive(q, LEVELS[i] == x.level)
            level.addView(q, Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 0, 8, 0))
        }
        level.addView(box, LinearLayout.LayoutParams(Ui.dp(this, 56), ViewGroup.LayoutParams.WRAP_CONTENT))
        p.addView(level)
        return p
    }

    /** Smogon sets for this Pokémon and its forms in the game, read once per species (one file scan). */
    private fun builds(m: Dex.Mon): List<Dex.Build> {
        if (buildsFor != m.species) {
            builds = dex.builds(game, dex.family(m).filter { game.byNum.containsKey(it.num) }.mapTo(HashSet()) { it.num })
            buildsFor = m.species
        }
        return builds
    }

    private fun pickBuild(sets: List<Dex.Build>) {
        val d = Ui.Sheet(this, L.t("Smogon sets"))
        val list = buildList(this, dex, game, sets, x.num, "", Ui.FILL, { b ->
            val use = Ui.chip(this, L.t("Use this set")) {
                if (d.close()) {
                    load(dex, b, x)
                    render()
                }
            }
            Ui.chipActive(use, true)
            use
        }) {}
        val s = ScrollView(this)
        s.addView(list)
        d.body.setPadding(0, 0, 0, 0)
        d.body.addView(s)
        d.button(L.t("Cancel"))
        d.show()
    }

    /** Held items released by this game's generation: those in this Pokémon's Smogon sets first, most used on top, then by section. */
    private fun pickItem() {
        val items = ArrayList<Ui.Item>()
        items.add(Ui.Item(0, "", L.t("No item"), "", emptyList(), 0))
        val row = { it: Dex.Item -> Ui.Item(0, "", it.name, it.effect, emptyList(), it) }
        val usable = dex.items.filter { it.gen <= game.gen }
        val used = builds(dex.mon(x.num)).filter { it.item != 0 }.groupingBy { it.item }.eachCount()
        val top = usable.filter { it.id in used }.sortedByDescending { used[it.id] }
        if (top.isNotEmpty()) {
            items.add(Ui.Item.header(L.t("Smogon sets")))
            top.mapTo(items, row)
        }
        items.addAll(Ui.itemSections(usable, row))
        Ui.pickSearch(this, L.t("Held item"), items) { ref ->
            x.item = (ref as? Dex.Item)?.id ?: 0
            render()
        }
    }

    private fun moves(m: Dex.Mon): View {
        val p = Ui.panel(this, L.t("Moves"))
        p.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 8))
        p.getChildAt(0).setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 4))
        for (i in 0 until Team.MOVES) {
            val row = Ui.row(this)
            val id = x.moves[i]
            Ui.bind(row, if (id == 0) Ui.Item(0, "", L.t("Empty slot"), L.t("Tap to choose a move"), emptyList(), "")
                         else Ui.Item.move(dex.move(game, id), ""))
            row.setOnClickListener { pickMove(m, i) }
            p.addView(row)
        }
        p.addView(Ui.chip(this, L.t("Offered a new move?")) { offered(m) },
            Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 16, 6, 16, 4))
        return p
    }

    /** Picking a move already in another slot swaps the two. */
    private fun pickMove(m: Dex.Mon, slot: Int) {
        val (tabs, lists) = moveLists(m, Ui.Item(0, "", L.t("No move"), L.t("Leave this slot empty"), emptyList(), 0), IntArray(0))
        Ui.pickSearch(this, L.f("Move %d", slot + 1), tabs, lists) { ref ->
            val id = (ref as? Dex.Move)?.id ?: 0
            for (j in 0 until Team.MOVES) if (j != slot && id != 0 && x.moves[j] == id) x.moves[j] = x.moves[slot]
            x.moves[slot] = id
            render()
        }
    }

    /** The game offers `m` a move it does not know yet: which one, then [advise] on taking it. */
    private fun offered(m: Dex.Mon) {
        val (tabs, lists) = moveLists(m, null, x.moves)
        Ui.pickSearch(this, L.t("Offered move"), tabs, lists) { ref -> advise(m, (ref as? Dex.Move ?: return@pickSearch).id) }
    }

    /**
     * [Team.advise] in words: take it or skip it and why, then the four slots with what each trade costs, the one to use
     * ringed. A tap on a slot teaches into it after the usual side-by-side confirm, whatever the advice said.
     */
    private fun advise(m: Dex.Mon, id: Int) {
        val a = team.advise(dex, team.members.indexOf(x), id)
        val mv = dex.move(game, id)
        val types = dex.types(game)
        val names = { list: List<Int> -> list.joinToString(L.t(", ")) { dex.label(types[it]) } }
        val s = Ui.Sheet(this, L.f("Take %s?", mv.name))
        val box = Ui.vertical(this)
        val top = Ui.row(this)
        Ui.bind(top, Ui.Item.move(mv, ""))
        top.background = null
        box.addView(top)
        val verdict = when {
            a.slot >= 0 && x.moves[a.slot] == 0 -> L.t("Take it: a free slot.")
            a.slot >= 0 -> L.f("Take it in place of %s.", dex.move(game, x.moves[a.slot]).name)
            mv.category == "status" || mv.power == 0 -> L.t("Not rated: a status move is only as good as its effect.")
            a.trades.none { it.stronger } -> L.t("Skip it: weaker than every attack here.")
            else -> L.t("Skip it: each attack it beats is the only answer to some type.")
        }
        box.addView(Ui.text(this, verdict, 15, true), Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 10, 0, 0))
        val notes = ArrayList<String>()
        if (a.adds.isNotEmpty()) notes.add(L.f("It hits %s super effectively, which nothing on the team does.", names(a.adds)))
        val end = team.evolved(dex, game, x)
        if (end.num != m.num) notes.add(L.f("Counted as evolved: %s.", L.f("%s as %s", m.name, end.name)))
        if (notes.isNotEmpty()) box.addView(Ui.muted(this, notes.joinToString(" "), 13),
            Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 4, 0, 0))
        box.addView(Ui.caption(this, L.t("In place of")), Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 12, 0, 8))
        for (slot in 0 until Team.MOVES) {
            val old = if (x.moves[slot] == 0) null else dex.move(game, x.moves[slot])
            val t = a.trades.firstOrNull { it.slot == slot }
            val sub = when {
                old == null -> ""
                t == null -> L.t("Not rated: status or fixed damage")
                else -> listOfNotNull(if (t.stronger) L.t("The offer hits harder") else L.t("This hits harder"),
                    if (t.lost.isEmpty()) null else L.f("Loses %s", names(t.lost))).joinToString("  ·  ")
            }
            val r = Ui.row(this)
            Ui.bind(r, Ui.Item(0, "", old?.name ?: L.t("Empty slot"), sub, old?.let { listOf(it.type, it.category) } ?: emptyList(), old ?: ""))
            r.background = Ui.choice(this, slot == a.slot, 14)
            r.isSelected = slot == a.slot
            r.setOnClickListener {
                if (s.close()) TeamActivity.confirmTeach(this, dex, game, m.name, x, slot, id) {
                    x.moves[slot] = id
                    render()
                }
            }
            box.addView(r, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 0, 0, 6))
        }
        val scroll = ScrollView(this)
        scroll.addView(box)
        s.body.addView(scroll)
        s.button(L.t("Cancel"))
        s.show()
    }

    /**
     * Moves this form learns in the team's game (or the nearest game with data), grouped by method, after `first`;
     * every other move sits in a second tab, since ROM hacks such as Unbound teach moves the official learnset lacks.
     * Moves in `skip` are left out of both. Tabs and their lists, one list and no tabs without a learnset.
     */
    private fun moveLists(m: Dex.Mon, first: Ui.Item?, skip: IntArray): Pair<Array<String>, List<List<Ui.Item>>> {
        val items = ArrayList<Ui.Item>()
        if (first != null) items.add(first)
        val g = dex.learnGame(game, m)
        val cols = g?.let { dex.learnset(it, m) }
        val seen = LinkedHashSet<Int>()
        for (id in skip) seen.add(id)
        var otherHeader = L.f("No learnset for %s: all moves", m.name)
        if (g != null && cols != null) {
            for ((c, col) in cols.withIndex()) {
                val group = ArrayList<Ui.Item>()
                val step = if (c == 0) 2 else 1
                for (i in col.indices step step) {
                    val id = col[i + step - 1]
                    if (seen.add(id)) group.add(Ui.Item.move(dex.move(game, id), if (c > 0) "" else if (col[i] == 0) L.t("Evo") else L.f("Lv %d", col[i])))
                }
                if (group.isEmpty()) continue
                items.add(Ui.Item.header(L.t(Dex.METHODS[c]) + if (g === game) "" else " (${g.name})"))
                items.addAll(group)
            }
            otherHeader = L.f("Not learnable by %s in %s. For ROM hacks such as Unbound.", m.name, g.name)
        }
        val other = if (cols == null) items else ArrayList()
        other.add(Ui.Item.header(otherHeader))
        for (mv in dex.moves(game)) if (seen.add(mv.id)) other.add(Ui.Item.move(mv, ""))
        val tabs = if (cols == null) emptyArray() else arrayOf(L.t("Learnable"), L.t("Not learnable"))
        return tabs to (if (cols == null) listOf(items) else listOf(items, other))
    }

    /** Base, IV and EV per stat with the computed value; EVs are capped at 252 each and 510 in total. */
    private fun stats(m: Dex.Mon): View {
        val p = Ui.panel(this, L.t("Stats"))
        if (game.gen < 3) {
            val note = Ui.muted(this, L.f("Calculated with the Generation 3 and later formula. %s used DVs and stat experience instead.",
                game.name), 13)
            note.setPadding(0, 0, 0, Ui.dp(this, 8))
            p.addView(note)
        }
        p.addView(statRow(Ui.caption(this, ""), cell(Ui.caption(this, L.t("Base")), Gravity.END), cell(Ui.caption(this, L.t("IV")), Gravity.CENTER),
            cell(Ui.caption(this, L.t("EV")), Gravity.CENTER), cell(Ui.caption(this, L.t("Stat")), Gravity.END)))
        val n = if (x.nature < 0) null else dex.natures[x.nature]
        totals.clear()
        for ((s, stat) in Dex.STATS.withIndex()) {
            val up = n != null && n.up != n.down && n.up == stat
            val down = n != null && n.up != n.down && n.down == stat
            val label = Ui.text(this, L.t(stat) + if (up) " +" else if (down) " -" else "", 15, true)
            if (up) label.setTextColor(Ui.UP)
            if (down) label.setTextColor(Ui.DOWN)
            val iv = number(this, x.ivs[s], 0, Team.IV_MAX) { v ->
                x.ivs[s] = v
                refreshStats()
                v
            }
            val ev = number(this, x.evs[s], 0, Team.EV_STAT) { v ->
                x.evs[s] = minOf(v, x.evRoom(s))
                refreshStats()
                x.evs[s]
            }
            // The keyboard's next key walks IV, EV, then the next stat's row, so a spread is typed without closing it.
            iv.imeOptions = EditorInfo.IME_ACTION_NEXT
            if (s < Dex.STATS.size - 1) ev.imeOptions = EditorInfo.IME_ACTION_NEXT
            val total = cell(Ui.text(this, "", 15, true), Gravity.END)
            totals.add(total)
            p.addView(statRow(label, cell(Ui.muted(this, m.stats[s].toString(), 15), Gravity.END), iv, ev, total))
        }
        val left = Ui.muted(this, "", 13)
        left.setPadding(0, Ui.dp(this, 8), 0, 0)
        p.addView(left)
        evLeft = left
        refreshStats()
        return p
    }

    private fun statRow(label: View, base: View, iv: View, ev: View, total: View): View {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3))
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        row.addView(label, LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(base, LinearLayout.LayoutParams(Ui.dp(this, 40), wrap))
        row.addView(iv, Ui.margins(Ui.dp(this, 60), wrap, this, 10, 0, 0, 0))
        row.addView(ev, Ui.margins(Ui.dp(this, 60), wrap, this, 8, 0, 0, 0))
        row.addView(total, LinearLayout.LayoutParams(Ui.dp(this, 52), wrap))
        return row
    }

    private fun refreshStats() {
        val left = evLeft ?: return
        val s = Team.stats(dex, x)
        for ((i, t) in totals.withIndex()) t.text = s[i].toString()
        left.text = L.f("%d of %d EVs left. At most %d per stat.", Team.EV_TOTAL - x.evs.sum(), Team.EV_TOTAL, Team.EV_STAT)
    }

    private fun remove(m: Dex.Mon) {
        Ui.confirm(this, L.f("Remove %s?", m.name), L.f("Its moves, item and stats are removed from %s.", team.name), L.t("Remove")) {
            team.members.remove(x)
            TeamsActivity.save(this, teams)
            removed = true
            finish()
        }
    }

    companion object {
        /** Quick picks: Little Cup, VGC and Battle Stadium, Smogon singles. */
        private val LEVELS = intArrayOf(5, 50, 100)

        /** Scarlet and Violet, the only games with Terastallization. */
        private const val TERA_GAME = 25

        fun open(a: Activity, team: Int, member: Int) {
            a.startActivity(Intent(a, MemberActivity::class.java).putExtra("team", team).putExtra("member", member))
        }

        /**
         * A Format row ("OU  ·  2"; a list of them, first seen first, to pick from) over cards of that format's sets,
         * starting on `start` if the sets have it. A row, not a chip per format: a Pokémon can have a dozen. Each card
         * is `color` with `actions(set)` at its foot; `picked` gets a new format. Only one format's cards exist at a
         * time: Arceus has 76 sets in Gen 9, about 36 views a card.
         */
        fun buildList(a: Activity, dex: Dex, game: Dex.Game, sets: List<Dex.Build>, num: Int, start: String, color: Int,
                      actions: (Dex.Build) -> View, picked: (String) -> Unit): LinearLayout {
            val box = Ui.vertical(a)
            val counts = sets.groupingBy { it.format }.eachCount()
            val formats = counts.keys.toList()
            val labels = formats.map { "$it  ·  ${counts[it]}" }
            var current = if (start in counts) start else formats.first()
            val cards = Ui.vertical(a)
            lateinit var value: TextView
            val show = { f: String ->
                current = f
                value.text = labels[formats.indexOf(f)]
                cards.removeAllViews()
                for (b in sets) if (b.format == f) cards.addView(buildCard(a, dex, game, b, num, color, actions(b)), Ui.wide(a, 0, 12))
            }
            val row = Ui.setting(a, L.t("Format"), "") {
                Ui.pickOne(a, L.t("Format"), labels, formats.indexOf(current)) { i ->
                    show(formats[i])
                    picked(formats[i])
                }
            } as ViewGroup
            value = row.getChildAt(1) as TextView
            box.addView(row, Ui.wide(a, 0, 0))
            box.addView(cards, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(a, 12)
            })
            show(current)
            return box
        }

        /**
         * One set: name, its form when not `num`, level when not 100 and Tera type; then item, ability (opens it),
         * nature with the stats it raises and lowers, EVs and any IV under 31; then the moves two to a row in their
         * type's tint, each opening its move; `actions` last.
         */
        private fun buildCard(a: Activity, dex: Dex, game: Dex.Game, b: Dex.Build, num: Int, color: Int, actions: View): View {
            val card = Ui.panel(a, null)
            card.background = Ui.rounded(a, color, 18)
            val top = LinearLayout(a)
            top.gravity = Gravity.CENTER_VERTICAL
            val names = Ui.vertical(a)
            names.addView(Ui.text(a, b.name, 17, true))
            val sub = listOfNotNull(if (b.num == num) null else dex.mon(b.num).name, if (b.level == 100) null else L.f("Lv %d", b.level))
            if (sub.isNotEmpty()) names.addView(Ui.muted(a, sub.joinToString("  ·  "), 13))
            top.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (b.tera >= 0 && (game.id == TERA_GAME || game.virtual && game.gen >= 9)) {
                top.addView(Ui.muted(a, L.t("Tera"), 13), Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, a, 8, 0, 6, 0))
                top.addView(Ui.pill(a, dex.types[b.tera]))
            }
            card.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val facts = Ui.vertical(a)
            if (b.item != 0) fact(facts, L.t("Item"), Ui.itemName(a, dex.item(b.item)), null)
            if (b.ability != 0) dex.ability(b.ability).let { fact(facts, L.t("Ability"), it.name, it) }
            if (b.nature >= 0) {
                val n = dex.natures[b.nature]
                val s = SpannableStringBuilder(n.name)
                if (n.up != n.down) {
                    span(s, "   +" + L.t(n.up), Ui.UP)
                    span(s, "  -" + L.t(n.down), Ui.DOWN)
                }
                fact(facts, L.t("Nature"), s, null)
            }
            val evs = (0 until 6).filter { b.evs[it] > 0 }.joinToString(" / ") { b.evs[it].toString() + " " + L.t(Dex.STATS[it]) }
            if (evs.isNotEmpty()) fact(facts, L.t("EVs"), evs, null)
            val ivs = (0 until 6).filter { b.ivs[it] < Team.IV_MAX }.joinToString(" / ") { b.ivs[it].toString() + " " + L.t(Dex.STATS[it]) }
            if (ivs.isNotEmpty()) fact(facts, L.t("IVs"), ivs, null)
            card.addView(facts, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, a, 0, 10, 0, 4))

            val moves = b.moves.filter { it != 0 }.map { dex.move(game, it) }
            for (i in moves.indices step 2) {
                val row = LinearLayout(a)
                for (k in 0..1) {
                    val cell = moves.getOrNull(i + k)?.let { moveCell(a, it) } ?: View(a)
                    row.addView(cell, Ui.margins(0, ViewGroup.LayoutParams.MATCH_PARENT, a, 4 * k, 0, 4 * (1 - k), 0).apply { weight = 1f })
                }
                card.addView(row, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, a, 0, 8, 0, 0))
            }
            card.addView(actions, Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, a, 0, 14, 0, 0))
            return card
        }

        /** Muted label in a fixed column, then the value; a non-null `ref` makes the row open it. */
        private fun fact(into: LinearLayout, label: String, value: CharSequence, ref: Any?) {
            val a = into.context as Activity
            val row = LinearLayout(a)
            row.addView(Ui.muted(a, label, 14), LinearLayout.LayoutParams(Ui.dp(a, 72), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(Ui.text(a, value, 14, ref != null), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.setPadding(0, Ui.dp(a, 3), 0, Ui.dp(a, 3))
            if (ref != null) {
                row.background = Ui.pressable(a, null, 8)
                row.setOnClickListener { Ui.open(a, ref) }
            }
            into.addView(row)
        }

        private fun span(s: SpannableStringBuilder, part: String, color: Int) {
            s.append(part)
            s.setSpan(ForegroundColorSpan(color), s.length - part.length, s.length, 0)
        }

        /** Move name over its type, on the type's tint; opens the move. */
        private fun moveCell(a: Activity, m: Dex.Move): View {
            val cell = Ui.vertical(a)
            val name = Ui.text(a, m.name, 14, true)
            name.setSingleLine()
            name.ellipsize = TextUtils.TruncateAt.END
            cell.addView(name)
            cell.addView(Ui.text(a, Ui.label(m.type), 12, false))
            cell.background = Ui.pressable(a, Ui.rounded(a, Ui.tonal(Ui.color(m.type)), 12), 12)
            cell.setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8))
            cell.setOnClickListener { Ui.open(a, m) }
            return cell
        }

        /**
         * Numeric box; `set` gets each value as typed, clamped to [min, max] (an empty box counts as min), and returns the
         * value it kept, which the box then shows. A box left empty shows min again.
         */
        fun number(c: Context, value: Int, min: Int, max: Int, set: (Int) -> Int): EditText {
            val e = EditText(c)
            e.setText(value.toString())
            e.inputType = InputType.TYPE_CLASS_NUMBER
            e.imeOptions = EditorInfo.IME_ACTION_DONE
            e.setSelectAllOnFocus(true)
            e.setSingleLine()
            e.textSize = 15f
            e.setTextColor(Ui.TEXT)
            e.gravity = Gravity.CENTER
            e.background = Ui.rounded(c, Ui.FILL, 10)
            e.setPadding(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6))
            e.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable) {
                    val t = s.toString()
                    val kept = set(if (t.isEmpty()) min else if (t.length > 3) max else t.toInt().coerceIn(min, max))
                    if (t.isNotEmpty() && t != kept.toString()) {
                        e.setText(kept.toString())
                        e.setSelection(e.length())
                    }
                }
            })
            e.setOnFocusChangeListener { _, focused -> if (!focused && e.length() == 0) e.setText(min.toString()) }
            return e
        }

        /** A new member of `b`'s form with `b` applied. */
        fun member(dex: Dex, b: Dex.Build): Team.Member {
            val x = TeamsActivity.member(dex.mon(b.num))
            load(dex, b, x)
            return x
        }

        /** Replaces `x`'s whole set; an ability the data leaves out (Gen 1-2) keeps the current one if the form has it. */
        fun load(dex: Dex, b: Dex.Build, x: Team.Member) {
            val f = dex.mon(b.num)
            x.num = b.num
            x.ability = when {
                b.ability != 0 -> b.ability
                x.ability == f.hidden || x.ability in f.abilities -> x.ability
                else -> TeamsActivity.member(f).ability
            }
            x.item = b.item
            x.nature = b.nature
            x.tera = b.tera
            x.level = b.level
            b.evs.copyInto(x.evs)
            b.ivs.copyInto(x.ivs)
            b.moves.copyInto(x.moves)
        }

        /** "Adamant (+Atk, -SpA)", or "Hardy (neutral)". */
        private fun natureLabel(n: Dex.Nature) =
            if (n.up == n.down) L.f("%s (neutral)", n.name) else n.name + " (+" + L.t(n.up) + ", -" + L.t(n.down) + ")"
    }
}

private fun cell(t: TextView, gravity: Int): TextView {
    t.gravity = gravity
    return t
}
