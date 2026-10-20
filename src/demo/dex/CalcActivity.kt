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

/**
 * Damage of one move from an attacker to a defender, each a team member or a new one at level 50 with neutral stats.
 * Levels can be changed here; nothing is saved back to the team.
 */
class CalcActivity : Activity() {
    private lateinit var dex: Dex
    private lateinit var game: Dex.Game
    private lateinit var heads: LinearLayout
    private lateinit var moveRow: View
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
        val sub = Ui.muted(this, game.name, 14)
        sub.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 12))
        root.addView(sub)
        heads = Ui.vertical(this)
        root.addView(heads, Ui.wide(this, 0, 12))
        val chips = LinearLayout(this)
        Ui.addChip(chips, Ui.chip(this, L.t("Swap sides")) {
            sides.reverse()
            render()
        })
        root.addView(Ui.chipBar(this, chips), Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 0, 0, 12))
        val moves = Ui.panel(this, L.t("Move"))
        moves.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 8))
        moves.getChildAt(0).setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 4))
        moveRow = Ui.row(this)
        moveRow.setOnClickListener { pickMove() }
        moves.addView(moveRow)
        root.addView(moves, Ui.wide(this, 0, 12))
        out = Ui.panel(this, L.t("Damage"))
        root.addView(out, Ui.wide(this, 0, 8))
        val note = Ui.muted(this, L.t("Gen 5 and later formula. Not counted: abilities, items, weather, critical hits, stat changes and burns."), 13)
        note.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0)
        root.addView(note)
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
        Ui.bind(moveRow, if (move == 0) Ui.Item(0, "", L.t("Choose a move"), "", emptyList(), "") else Ui.Item.move(dex.move(game, move), ""))
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
        val name = Ui.text(this, if (x == null) L.t("Choose") else dex.mon(x.num).name, 16, true)
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

    /** Min to max damage as a share of the defender's HP, on a bar and in HP, hits to faint it and what went into it. */
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
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        out.addView(Ui.text(this, L.f("%d%% to %d%%", hit[0] * 100 / hp, hit[1] * 100 / hp), 30, true))
        out.addView(hpBar(minOf(hit[0], hp), minOf(hit[1], hp), hp),
            Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 10), this, 0, 8, 0, 8))
        out.addView(Ui.text(this, L.f("%d to %d of %d HP", hit[0], hit[1], hp), 15, false))
        // At least 1 per hit unless immune, so only an immune defender has a 0 here.
        val fast = if (hit[1] == 0) 0 else (hp + hit[1] - 1) / hit[1]
        val slow = if (hit[0] == 0) 0 else (hp + hit[0] - 1) / hit[0]
        val ko = when {
            hit[1] == 0 -> L.t("No effect")
            slow == 1 -> L.t("Faints in one hit")
            fast == slow -> L.f("Faints in %d hits", fast)
            else -> L.f("Faints in %d to %d hits", fast, slow)
        }
        out.addView(Ui.text(this, ko, 17, true), Ui.margins(wrap, wrap, this, 0, 4, 0, 10))
        fact(L.t(Dex.STATS[if (special) 3 else 1]) + "  ·  " + dex.mon(a.num).name, atk.toString())
        fact(L.t(Dex.STATS[if (special) 4 else 2]) + "  ·  " + dex.mon(d.num).name, guard.toString())
        fact(L.t("Power"), mv.power.toString())
        fact(L.t("Effectiveness"), effect(factor))
        fact(L.t("Same type"), if (stab) "×1.5" else "×1")
    }

    private fun fact(label: String, value: String) {
        val row = LinearLayout(this)
        row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4))
        row.addView(Ui.muted(this, label, 14), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.text(this, value, 15, true))
        out.addView(row)
    }

    /** The defender's HP as a track: the least damage solid, the most damage faint behind it, both from the start. */
    private fun hpBar(least: Int, most: Int, hp: Int): View {
        val track = LinearLayout(this)
        track.background = Ui.rounded(this, Ui.FILL, 999)
        track.weightSum = hp.toFloat()
        val range = LinearLayout(this)
        range.background = Ui.rounded(this, Ui.alpha(Ui.ACCENT, 0x66), 999)
        range.weightSum = most.toFloat()
        val sure = View(this)
        sure.background = Ui.rounded(this, Ui.ACCENT, 999)
        range.addView(sure, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, least.toFloat()))
        track.addView(range, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, most.toFloat()))
        return track
    }

    companion object {
        private val SIDES = arrayOf(L.k("Attacker"), L.k("Defender"))
        private val EFFECT = mapOf(0 to "×0", 25 to "×¼", 50 to "×½", 100 to "×1", 200 to "×2", 400 to "×4")

        /** A type multiplier in hundredths as shown: "×½", "×2". */
        fun effect(factor: Int): String = EFFECT[factor] ?: "×${factor / 100.0}"

        /** `team` -1 for none; `member` of that team (-1 for none) attacks. */
        fun open(a: Activity, team: Int, member: Int) {
            a.startActivity(Intent(a, CalcActivity::class.java).putExtra("team", team).putExtra("member", member))
        }
    }
}
