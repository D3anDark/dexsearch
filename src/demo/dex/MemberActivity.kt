package demo.dex

import android.app.Activity
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

        val forms = dex.family(m).filter { it === m || game.byNum.containsKey(it.num) }
        if (forms.size > 1) {
            val chips = LinearLayout(this)
            for (f in forms) {
                val chip = Ui.chip(this, f.name) { setForm(f) }
                Ui.chipActive(chip, f === m)
                Ui.addChip(chips, chip)
            }
            root.addView(Ui.chipBar(this, chips), Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 12, 0, 0))
        }

        root.addView(details(m), Ui.wide(this, 12, 0))
        root.addView(moves(m), Ui.wide(this, 12, 0))
        root.addView(stats(m), Ui.wide(this, 12, 12))

        val remove = Ui.chip(this, L.t("Remove from team")) { remove(m) }
        remove.setTextColor(if (Ui.night) 0xFFFF8A65.toInt() else Ui.ACCENT)
        val actions = LinearLayout(this)
        Ui.addChip(actions, remove)
        actions.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0)
        root.addView(actions)

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
        return h
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
        val sets = builds(m)
        if (sets.isNotEmpty()) p.addView(Ui.setting(this, L.t("Smogon set"), L.f("Sets: %d", sets.size)) { pickBuild(sets) })
        if (game.gen >= 3) {
            val ids = m.abilities.toMutableList()
            if (m.hidden != 0 && m.hidden !in ids) ids.add(m.hidden)
            val chips = LinearLayout(this)
            for (id in ids) {
                val label = if (id == m.hidden) L.f("%s (hidden)", dex.ability(id).name) else dex.ability(id).name
                val chip = Ui.chip(this, label) {
                    x.ability = id
                    render()
                }
                Ui.chipActive(chip, id == x.ability)
                Ui.addChip(chips, chip)
            }
            p.addView(Ui.caption(this, L.t("Ability")))
            val bar = Ui.chipBar(this, chips)
            bar.setPadding(0, 0, 0, 0)
            p.addView(bar, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 6, 0, 8))
            if (x.ability != 0) p.addView(Ui.muted(this, dex.ability(x.ability).shortEffect, 13))
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
        box = number(x.level, 1, 100) { v ->
            x.level = v
            for ((i, q) in quick.withIndex()) Ui.chipActive(q, LEVELS[i] == v)
            refreshStats()
            v
        }
        for ((i, q) in quick.withIndex()) {
            Ui.chipActive(q, LEVELS[i] == x.level)
            Ui.addChip(level, q)
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
            Ui.bind(row, if (id == 0) Ui.Item(0, "${i + 1}", L.t("Empty slot"), L.t("Tap to choose a move"), emptyList(), "")
                         else Ui.Item.move(dex.move(game, id), "${i + 1}"))
            row.setOnClickListener { pickMove(m, i) }
            p.addView(row)
        }
        return p
    }

    /**
     * Moves this form learns in the team's game (or the nearest game with data), grouped by method; every other move
     * sits in a second tab, since ROM hacks such as Unbound teach moves the official learnset lacks. Picking a move
     * already in another slot swaps the two.
     */
    private fun pickMove(m: Dex.Mon, slot: Int) {
        val items = ArrayList<Ui.Item>()
        items.add(Ui.Item(0, "", L.t("No move"), L.t("Leave this slot empty"), emptyList(), 0))
        val g = dex.learnGame(game, m)
        val cols = g?.let { dex.learnset(it, m) }
        val seen = LinkedHashSet<Int>()
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
        Ui.pickSearch(this, L.f("Move %d", slot + 1), tabs, if (cols == null) listOf(items) else listOf(items, other)) { ref ->
            val id = (ref as? Dex.Move)?.id ?: 0
            for (j in 0 until Team.MOVES) if (j != slot && id != 0 && x.moves[j] == id) x.moves[j] = x.moves[slot]
            x.moves[slot] = id
            render()
        }
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
            val iv = number(x.ivs[s], 0, Team.IV_MAX) { v ->
                x.ivs[s] = v
                refreshStats()
                v
            }
            val ev = number(x.evs[s], 0, Team.EV_STAT) { v ->
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

    /**
     * Numeric box; `set` gets each value as typed, clamped to [min, max] (an empty box counts as min), and returns the
     * value it kept, which the box then shows. A box left empty shows min again.
     */
    private fun number(value: Int, min: Int, max: Int, set: (Int) -> Int): EditText {
        val e = EditText(this)
        e.setText(value.toString())
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.imeOptions = EditorInfo.IME_ACTION_DONE
        e.setSelectAllOnFocus(true)
        e.setSingleLine()
        e.textSize = 15f
        e.setTextColor(Ui.TEXT)
        e.gravity = Gravity.CENTER
        e.background = Ui.rounded(this, Ui.FILL, 10)
        e.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6))
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
         * A chip per format in `sets` ("OU  2", first seen first) over cards of the picked format's sets, starting on
         * `start` if the sets have it. Each card is `color` with `actions(set)` at its foot; `picked` gets a new format.
         * Only one format's cards exist at a time: Arceus has 76 sets in Gen 9, about 36 views a card.
         */
        fun buildList(a: Activity, dex: Dex, game: Dex.Game, sets: List<Dex.Build>, num: Int, start: String, color: Int,
                      actions: (Dex.Build) -> View, picked: (String) -> Unit): LinearLayout {
            val box = Ui.vertical(a)
            val counts = sets.groupingBy { it.format }.eachCount()
            val chips = LinearLayout(a)
            val cards = Ui.vertical(a)
            val show = { f: String ->
                for (i in 0 until chips.childCount) Ui.chipActive(chips.getChildAt(i) as TextView, (chips.getChildAt(i).tag) == f)
                cards.removeAllViews()
                for (b in sets) if (b.format == f) cards.addView(buildCard(a, dex, game, b, num, color, actions(b)), Ui.wide(a, 0, 12))
            }
            for ((f, n) in counts) {
                val chip = Ui.chip(a, "$f  $n") {
                    show(f)
                    picked(f)
                }
                chip.tag = f
                Ui.addChip(chips, chip)
            }
            box.addView(Ui.chipBar(a, chips))
            box.addView(cards, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(a, 12)
            })
            show(if (start in counts) start else counts.keys.first())
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
