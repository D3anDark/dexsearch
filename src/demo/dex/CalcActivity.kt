package demo.dex

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Damage of one move from an attacker to a defender, each a team member or a new one at level 50 with neutral stats.
 * Levels can be changed here; nothing is saved back to the team.
 */
class CalcActivity : Activity() {
    private lateinit var dex: Dex
    private lateinit var game: Dex.Game
    private lateinit var heads: LinearLayout
    private lateinit var moveChip: TextView
    private lateinit var out: LinearLayout
    /** Attacker and defender, null until picked. */
    private val sides = arrayOfNulls<Team.Member>(2)
    private var move = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        dex = Ui.dex(this)
        val team = TeamsActivity.load(this).getOrNull(intent.getIntExtra("team", -1))
        game = if (team != null) dex.game(team.game) else Ui.game(this)
        sides[0] = team?.members?.getOrNull(intent.getIntExtra("member", -1))
        move = sides[0]?.moves?.firstOrNull { it != 0 && dex.move(game, it).power > 0 } ?: 0

        val root = Ui.vertical(this)
        root.setPadding(0, 0, 0, Ui.dp(this, 16))
        root.addView(Ui.title(this, L.t("Damage calc")))
        val sub = Ui.muted(this, game.name + "\n" + L.t("Gen 5 and later formula. Not counted: abilities, items, weather, critical hits, stat changes and burns."), 14)
        sub.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 12))
        root.addView(sub)
        heads = Ui.vertical(this)
        root.addView(heads, Ui.wide(this, 0, 12))
        val chips = LinearLayout(this)
        moveChip = Ui.chip(this, "") { pickMove() }
        Ui.chipActive(moveChip, true)
        Ui.addChip(chips, moveChip)
        Ui.addChip(chips, Ui.chip(this, L.t("Swap sides")) {
            sides.reverse()
            render()
        })
        root.addView(Ui.chipBar(this, chips), Ui.wide(this, 0, 12))
        out = Ui.panel(this, L.t("Damage"))
        root.addView(out, Ui.wide(this, 0, 12))
        val scroll = ScrollView(this)
        scroll.addView(root)
        Ui.show(this, scroll)
        render()
        if (sides[0] == null) pick(0)
        else pick(1)
    }

    /** Picks side `i`; picking the attacker while the defender is empty goes straight on to it. */
    private fun pick(i: Int) {
        val teams = TeamsActivity.members(this, dex, game)
        TeamsActivity.pick(this, dex, game, L.t(SIDES[i]), if (teams.isEmpty()) null else L.t("Teams") to teams) { x ->
            sides[i] = x
            if (i == 0) move = x.moves.firstOrNull { it != 0 && dex.move(game, it).power > 0 } ?: move
            render()
            if (i == 0 && sides[1] == null) pick(1)
            else if (move == 0) pickMove()
        }
    }

    /** The attacker's own moves first when it has any, then every move that deals damage in this game. */
    private fun pickMove() {
        val all = dex.moves(game).filter { it.category != "status" }.map { Ui.Item.move(it, "") }
        val own = sides[0]?.moves?.filter { it != 0 }?.map { Ui.Item.move(dex.move(game, it), "") }.orEmpty()
        val tabs = if (own.isEmpty()) emptyArray() else arrayOf(L.t("Its moves"), L.t("All"))
        Ui.pickSearch(this, L.t("Move"), tabs, if (own.isEmpty()) listOf(all) else listOf(own, all)) { ref ->
            move = (ref as Dex.Move).id
            render()
        }
    }

    private fun render() {
        heads.removeAllViews()
        heads.addView(CompareActivity.pair(this, head(0), head(1), 12))
        moveChip.text = if (move == 0) L.t("Choose a move") else L.f("Move: %s", dex.move(game, move).name)
        result()
    }

    /** Sprite, role, name and a level box; tap to pick another. */
    private fun head(i: Int): View {
        val x = sides[i]
        val h = Ui.vertical(this)
        h.gravity = Gravity.CENTER_HORIZONTAL
        h.background = Ui.pressable(this, Ui.rounded(this, Ui.SURFACE, 18), 18)
        h.setPadding(Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12))
        h.addView(Ui.caption(this, L.t(SIDES[i])))
        val img = ImageView(this)
        if (x != null) img.setImageDrawable(Ui.sprite(this, x.num, 2))
        h.addView(img, LinearLayout.LayoutParams(Ui.dp(this, 80), Ui.dp(this, 80)))
        val name = Ui.text(this, if (x == null) L.t("Choose a Pokémon") else dex.mon(x.num).name, 16, true)
        name.gravity = Gravity.CENTER
        h.addView(name)
        if (x != null) {
            val level = LinearLayout(this)
            level.gravity = Gravity.CENTER_VERTICAL
            level.addView(Ui.muted(this, L.t("Level"), 14))
            level.addView(MemberActivity.number(this, x.level, 1, 100) { v ->
                x.level = v
                result()
                v
            }, Ui.margins(Ui.dp(this, 56), ViewGroup.LayoutParams.WRAP_CONTENT, this, 8, 0, 0, 0))
            h.addView(level, Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 8, 0, 0))
        }
        h.setOnClickListener { pick(i) }
        return h
    }

    /** Min to max damage, as HP and as a share of the defender's HP, hits to faint it and what went into it. */
    private fun result() {
        while (out.childCount > 1) out.removeViewAt(1)
        val a = sides[0]
        val d = sides[1]
        if (a == null || d == null || move == 0) {
            out.addView(Ui.muted(this, L.t("Choose both Pokémon and a move."), 14))
            return
        }
        val mv = dex.move(game, move)
        if (mv.category == "status" || mv.power == 0) {
            out.addView(Ui.muted(this, L.f("%s has no base power: status moves and fixed damage such as Seismic Toss are not calculated.", mv.name), 14))
            return
        }
        val special = mv.category == "special"
        val atk = Team.stats(dex, a)[if (special) 3 else 1]
        val def = Team.stats(dex, d)
        val guard = def[if (special) 4 else 2]
        val factor = dex.factor(game, mv.type, dex.types(game, dex.mon(d.num)))
        val stab = mv.type in dex.types(game, dex.mon(a.num))
        val hit = Team.damage(a.level, mv.power, atk, guard, stab, factor)
        val hp = def[0]
        out.addView(Ui.text(this, L.f("%d to %d HP", hit[0], hit[1]), 22, true))
        out.addView(Ui.text(this, L.f("%d%% to %d%% of %d HP", hit[0] * 100 / hp, hit[1] * 100 / hp, hp), 15, false))
        // At least 1 per hit unless immune, so only an immune defender has a 0 here.
        val fast = if (hit[1] == 0) 0 else (hp + hit[1] - 1) / hit[1]
        val slow = if (hit[0] == 0) 0 else (hp + hit[0] - 1) / hit[0]
        val ko = when {
            hit[1] == 0 -> L.t("No effect")
            slow == 1 -> L.t("Faints in one hit")
            fast == slow -> L.f("Faints in %d hits", fast)
            else -> L.f("Faints in %d to %d hits", fast, slow)
        }
        out.addView(Ui.text(this, ko, 15, true), Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 6, 0, 0))
        val parts = listOf(
            L.f("%s %d against %s %d", L.t(Dex.STATS[if (special) 3 else 1]), atk, L.t(Dex.STATS[if (special) 4 else 2]), guard),
            L.f("Power %d", mv.power),
            L.f("Effectiveness %s", EFFECT[factor] ?: "x${factor / 100.0}"),
            if (stab) L.t("Same type x1.5") else L.t("Not its type"),
        )
        out.addView(Ui.muted(this, parts.joinToString("\n"), 14), Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 10, 0, 0))
    }

    companion object {
        private val SIDES = arrayOf(L.k("Attacker"), L.k("Defender"))
        private val EFFECT = mapOf(0 to "x0", 25 to "x1/4", 50 to "x1/2", 100 to "x1", 200 to "x2", 400 to "x4")

        /** `team` -1 for none; `member` of that team (-1 for none) attacks. */
        fun open(a: Activity, team: Int, member: Int) {
            a.startActivity(Intent(a, CalcActivity::class.java).putExtra("team", team).putExtra("member", member))
        }
    }
}
