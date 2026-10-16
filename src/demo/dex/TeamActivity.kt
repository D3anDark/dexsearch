package demo.dex

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.util.Locale

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
            val moves = x.moves.filter { it != 0 }.map { dex.move(it).name }
            val held = if (x.item == 0) "" else dex.item(x.item).name + "  ·  "
            val row = Ui.row(this)
            Ui.bind(row, Ui.Item(m.num, L.f("Lv %d", x.level), m.name,
                held + if (moves.isEmpty()) L.t("No moves yet") else moves.joinToString(L.t(", ")), dex.types(game, m), m))
            row.setOnClickListener { MemberActivity.open(this, index, k) }
            slots.addView(row)
        }
        if (team.members.size < Team.SIZE) {
            val add = Ui.row(this)
            Ui.bind(add, Ui.Item(0, "+", L.t("Add Pokémon"), L.f("Free slots: %d", Team.SIZE - team.members.size), emptyList(), ""))
            add.setOnClickListener { addMember() }
            slots.addView(add)
        }
        root.addView(slots, Ui.wide(this, 0, 12))

        if (team.members.isNotEmpty()) {
            root.addView(tips(), Ui.wide(this, 0, 12))
            root.addView(defense(), Ui.wide(this, 0, 12))
        }

        val actions = LinearLayout(this)
        val copyChip = Ui.chip(this, L.t("Copy as text")) { copy() }
        val deleteChip = Ui.chip(this, L.t("Delete team")) { delete() }
        deleteChip.setTextColor(if (Ui.night) 0xFFFF8A65.toInt() else Ui.ACCENT)
        Ui.addChip(actions, copyChip)
        Ui.addChip(actions, deleteChip)
        actions.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0)
        root.addView(actions)
        return root
    }

    /** Pokémon of this team's game (species and their forms), by regional number. */
    private fun addMember() {
        val items = game.dex.map { Ui.Item.mon(it.mon, dex.types(game, it.mon), "#%03d".format(Locale.ROOT, it.number), "") }
        Ui.pickSearch(this, L.t("Add Pokémon"), items) { ref ->
            team.members.add(TeamsActivity.member(ref as Dex.Mon))
            TeamsActivity.save(this, teams)
            MemberActivity.open(this, index, team.members.size - 1)
        }
    }

    /** Every attacking type with how many members it hits super effectively and how many resist it. */
    private fun defense(): View {
        val p = Ui.panel(this, L.t("Type defenses"))
        val help = Ui.muted(this, L.t("Members weak to and resisting each attacking type. Red: half the team or more is weak."), 13)
        help.setPadding(0, 0, 0, Ui.dp(this, 8))
        p.addView(help)
        val d = team.defense(dex)
        val types = dex.types(game)
        val half = (types.size + 1) / 2
        val cols = LinearLayout(this)
        for (c in 0 until 2) {
            val col = Ui.vertical(this)
            col.addView(defenseRow(null, L.t("Weak"), L.t("Resist"), Ui.MUTED, Ui.MUTED))
            for (i in c * half until minOf(types.size, (c + 1) * half)) {
                val bad = d[i][0] * 2 >= team.members.size && d[i][0] > d[i][1]
                col.addView(defenseRow(types[i], d[i][0].toString(), d[i][1].toString(),
                    if (bad) (if (Ui.night) 0xFFFF8A80 else 0xFFC62828).toInt() else Ui.TEXT,
                    if (d[i][1] > 0) (if (Ui.night) 0xFF7BD88F else 0xFF1E7B3A).toInt() else Ui.MUTED))
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (c == 0) lp.setMargins(0, 0, Ui.dp(this, 12), 0)
            cols.addView(col, lp)
        }
        p.addView(cols)
        return p
    }

    private fun defenseRow(type: String?, weak: String, resist: String, weakColor: Int, resistColor: Int): View {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2))
        row.addView(if (type == null) View(this) else Ui.pill(this, type),
            LinearLayout.LayoutParams(Ui.dp(this, 76), ViewGroup.LayoutParams.WRAP_CONTENT))
        for ((label, color) in listOf(weak to weakColor, resist to resistColor)) {
            val t = Ui.text(this, label, if (type == null) 11 else 14, true)
            t.setTextColor(color)
            t.gravity = Gravity.CENTER
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return row
    }

    /** [Team.gaps] in words: what hurts, what nothing answers, what no move hits, then the type that fixes most. */
    private fun tips(): View {
        val p = Ui.panel(this, L.t("Tips"))
        val g = team.gaps(dex)
        val types = dex.types(game)
        val names = { list: List<Int> ->
            val n = list.map { dex.label(types[it]) }
            if (n.size == 1) n[0] else L.f("%s and %s", n.dropLast(1).joinToString(L.t(", ")), n.last())
        }
        if (g.stacked.isNotEmpty()) tip(p, L.t("Weak spots"), L.t("Super effective against half the team or more, and resisted by fewer."), g.stacked)
        if (g.counter.isNotEmpty()) tip(p, L.t("Nothing counters these"), L.t("No member resists them and no move hits them super effectively."), g.counter)
        if (g.uncovered.isNotEmpty()) tip(p, L.t("Missing coverage"), L.t("No move hits these super effectively."), g.uncovered)
        if (g.wall >= 0) tip(p, L.f("Try a %s Pokémon", dex.label(types[g.wall])), L.f("It resists %s.",
            names((g.counter + g.stacked).filter { dex.factor(game, types[it], listOf(types[g.wall])) < 100 })), emptyList())
        if (g.move >= 0) tip(p, L.f("Try a %s move", dex.label(types[g.move])), L.f("It hits %s super effectively.",
            names((g.counter + g.uncovered).filter { dex.factor(game, types[g.move], listOf(types[it])) > 100 })), emptyList())
        if (!team.attacks(dex)) tip(p, L.t("No attacks yet"), L.t("Give your Pokémon damaging moves to see what nothing counters."), emptyList())
        else if (p.childCount == 1) tip(p, L.t("No gaps"), L.t("Every type is resisted or hit super effectively, and none hits half the team."), emptyList())
        return p
    }

    /** Bold title, muted detail, then the types as pills. */
    private fun tip(p: LinearLayout, title: String, detail: String, list: List<Int>) {
        val box = Ui.vertical(this)
        box.addView(Ui.text(this, title, 15, true))
        box.addView(Ui.muted(this, detail, 13), Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 2, 0, 0))
        if (list.isNotEmpty()) box.addView(Ui.typePills(this, list.map { dex.types(game)[it] }, 4),
            Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 6, 0, 0))
        p.addView(box, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0,
            if (p.childCount == 1) 0 else 14, 0, 0))
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
        AlertDialog.Builder(this).setTitle(L.f("Delete %s?", team.name))
            .setMessage(L.f("Its %d Pokémon and their sets are removed from this device.", team.members.size))
            .setPositiveButton(L.t("Delete")) { _, _ ->
                teams.removeAt(index)
                TeamsActivity.save(this, teams)
                finish()
            }
            .setNegativeButton(L.t("Cancel"), null)
            .show()
    }

    companion object {
        fun open(a: Activity, team: Int) {
            a.startActivity(Intent(a, TeamActivity::class.java).putExtra("team", team))
        }
    }
}
