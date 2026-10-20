package demo.dex

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

/**
 * Two Pokémon side by side: types, base stats on bars growing out from the middle, every attacking type that is not
 * neutral on both with each side's multiplier, and abilities. The better value of each pair is marked.
 */
class CompareActivity : Activity() {
    private lateinit var dex: Dex
    private lateinit var game: Dex.Game
    private lateinit var scroll: ScrollView
    /** Species or form numbers, 0 until picked. */
    private val nums = IntArray(2)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        dex = Ui.dex(this)
        game = dex.game(intent.getIntExtra("game", Ui.game(this).id))
        nums[0] = intent.getIntExtra("num", 0)
        scroll = ScrollView(this)
        Ui.show(this, scroll)
        render()
        pick(if (nums[0] == 0) 0 else 1)
    }

    /**
     * Picks side `i`; picking the first while the second is empty goes straight on to it. With the other side picked
     * the title names it and [slots] show it: the second list looked the same as the first, as if the first pick had
     * not counted, and the title alone was still easy to miss.
     */
    private fun pick(i: Int) {
        val teams = TeamsActivity.members(this, dex, game)
        val other = dex.monOrNull(nums[1 - i])
        val heading = if (other == null) L.t("Compare") else L.f("Compare with %s", other.name)
        TeamsActivity.pick(this, dex, game, heading, if (teams.isEmpty()) null else L.t("Teams") to teams, other?.let { slots(i, it) }) { x ->
            nums[i] = x.num
            render()
            if (i == 0 && nums[1] == 0) pick(1)
        }
    }

    private fun render() {
        val root = Ui.vertical(this)
        root.setPadding(0, 0, 0, Ui.dp(this, 16))
        root.addView(Ui.title(this, L.t("Compare")))
        val sub = Ui.muted(this, game.name, 14)
        sub.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 12))
        root.addView(sub)
        val mons = nums.map { dex.monOrNull(it) }
        root.addView(pair(this, head(0, mons[0]), head(1, mons[1]), 12), Ui.wide(this, 0, 12))
        val a = mons[0]
        val b = mons[1]
        if (a != null && b != null) {
            val stats = Ui.panel(this, L.t("Base stats"))
            for (s in Dex.STATS.indices) stats.addView(sides(label(L.t(Dex.STATS[s])), a.stats[s], b.stats[s], a.stats[s].toString(),
                b.stats[s].toString(), true))
            stats.addView(sides(label(L.t("Total")), a.bst, b.bst, a.bst.toString(), b.bst.toString(), false))
            root.addView(stats, Ui.wide(this, 0, 12))

            val defenses = Ui.panel(this, L.t("Type defenses"))
            val ta = dex.types(game, a)
            val tb = dex.types(game, b)
            // Attacking types each side is weak to and resists (immunities included), for the verdict.
            val weak = IntArray(2)
            val resist = IntArray(2)
            for (t in dex.types(game)) {
                val fa = dex.factor(game, t, ta)
                val fb = dex.factor(game, t, tb)
                for ((k, f) in listOf(fa, fb).withIndex()) {
                    if (f > 100) weak[k]++
                    if (f < 100) resist[k]++
                }
                if (fa == 100 && fb == 100) continue
                // Less damage taken is better, so the values go in negated.
                defenses.addView(sides(Ui.pill(this, t), -fa, -fb, CalcActivity.effect(fa), CalcActivity.effect(fb), false))
            }
            root.addView(defenses, Ui.wide(this, 0, 12))

            val abilities = Ui.panel(this, L.t("Abilities"))
            abilities.addView(pair(this, abilities(a), abilities(b), 12))
            root.addView(abilities, Ui.wide(this, 0, 12))
            root.addView(verdict(a, b, weak, resist), Ui.wide(this, 0, 12))
        }
        scroll.removeAllViews()
        scroll.addView(root)
    }

    /**
     * The short answer, last: who is better in more of four plain measures, each shown so it can be checked. Fewer
     * weaknesses is better, so those go in negated like the defenses.
     */
    private fun verdict(a: Dex.Mon, b: Dex.Mon, weak: IntArray, resist: IntArray): View {
        val rows = listOf(
            Triple(L.t("Total"), a.bst, b.bst),
            Triple(L.t(Dex.STATS[5]), a.stats[5], b.stats[5]),
            Triple(L.t("Weaknesses"), -weak[0], -weak[1]),
            Triple(L.t("Resistances"), resist[0], resist[1]))
        val wins = intArrayOf(rows.count { it.second > it.third }, rows.count { it.third > it.second })
        val best = if (wins[0] == wins[1]) null else if (wins[0] > wins[1]) a else b
        val p = Ui.panel(this, null)
        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(0, 0, 0, Ui.dp(this, 8))
        if (best != null) top.addView(ImageView(this).apply { setImageDrawable(Ui.sprite(this@CompareActivity, best.num, 2)) },
            Ui.margins(Ui.dp(this, 56), Ui.dp(this, 56), this, 0, 0, 10, 0))
        val texts = Ui.vertical(this)
        texts.addView(Ui.text(this, if (best == null) L.t("Too close to call") else L.f("%s comes out ahead", best.name), 17, true))
        texts.addView(Ui.muted(this, if (best == null) L.f("Each is better in %d of these %d.", wins[0], rows.size)
            else L.f("Better in %d of these %d.", maxOf(wins[0], wins[1]), rows.size), 13))
        top.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        p.addView(top)
        for ((name, x, y) in rows) p.addView(sides(label(name), x, y, abs(x).toString(), abs(y).toString(), false))
        return p
    }

    /** Sprite, name and types; tap to pick another. */
    private fun head(i: Int, m: Dex.Mon?): View {
        val h = Ui.vertical(this)
        h.gravity = Gravity.CENTER_HORIZONTAL
        h.background = Ui.pressable(this, face(m, 18), 18)
        h.setPadding(Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12))
        h.addView(image(m, 40), LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 96)))
        val name = Ui.text(this, m?.name ?: L.t("Choose a Pokémon"), 16, true)
        if (m == null) name.setTextColor(Ui.MUTED)
        name.gravity = Gravity.CENTER
        h.addView(name)
        if (m != null) h.addView(Ui.typePills(this, dex.types(game, m), 2),
            Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 6, 0, 0))
        h.setOnClickListener { pick(i) }
        return h
    }

    /** An empty side is a slot to fill: dashed outline on `fill`, and [image] draws a plus for the sprite. */
    private fun face(m: Dex.Mon?, radius: Int, fill: Int = Ui.SURFACE): GradientDrawable {
        val face = Ui.rounded(this, if (m == null) Ui.SURFACE else fill, radius)
        if (m == null) face.setStroke(Ui.dp(this, 2), Ui.MUTED, Ui.dp(this, 6).toFloat(), Ui.dp(this, 4).toFloat())
        return face
    }

    private fun image(m: Dex.Mon?, plusSp: Int): View =
        if (m != null) ImageView(this).apply { setImageDrawable(Ui.sprite(this@CompareActivity, m.num, 2)) }
        else Ui.text(this, "+", plusSp, false).apply {
            setTextColor(Ui.MUTED)
            gravity = Gravity.CENTER
            background = Ui.rounded(this@CompareActivity, Ui.FILL, 48)
        }

    /** Above the second list: the side already picked and the one being picked, in their places, "vs" between. */
    private fun slots(i: Int, other: Dex.Mon): View {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        val both = if (i == 1) listOf(other, null) else listOf(null, other)
        for ((k, m) in both.withIndex()) {
            if (k == 1) row.addView(Ui.muted(this, L.t("vs"), 13), Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, this, 8, 0, 8, 0))
            val s = LinearLayout(this)
            s.gravity = Gravity.CENTER_VERTICAL
            s.background = face(m, 14, Ui.FILL)
            s.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6))
            s.addView(image(m, 22), LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)))
            val name = Ui.text(this, m?.name ?: L.t("Choose a Pokémon"), 14, true)
            name.maxLines = 2
            if (m == null) name.setTextColor(Ui.MUTED)
            s.addView(name, Ui.margins(0, ViewGroup.LayoutParams.WRAP_CONTENT, this, 8, 0, 0, 0).apply { weight = 1f })
            row.addView(s, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return row
    }

    private fun label(s: String): View {
        val t = Ui.muted(this, s, 14)
        t.gravity = Gravity.CENTER
        return t
    }

    /**
     * `shownA` at the start edge and `shownB` at the end edge with `middle` between them; base-stat `bars` grow out from
     * the middle. The higher of `a` and `b` is bold in [Ui.GOOD].
     */
    private fun sides(middle: View, a: Int, b: Int, shownA: String, shownB: String, bars: Boolean): View {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, Ui.dp(this, if (bars) 4 else 5), 0, Ui.dp(this, if (bars) 4 else 5))
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        for ((k, v) in listOf(a, b).withIndex()) {
            val better = v > (if (k == 0) b else a)
            val t = Ui.text(this, if (k == 0) shownA else shownB, 15, better)
            if (better) t.setTextColor(Ui.GOOD)
            t.gravity = if (k == 0) Gravity.START else Gravity.END
            val space = if (bars) MonActivity.bar(this, v, if (k == 0) Gravity.END else Gravity.START) else View(this)
            val spaceLp = Ui.margins(0, if (bars) Ui.dp(this, 8) else 0, this, 8, 0, 8, 0).apply { weight = 1f }
            if (k == 0) {
                row.addView(t, LinearLayout.LayoutParams(Ui.dp(this, 40), wrap))
                row.addView(space, spaceLp)
                row.addView(middle, LinearLayout.LayoutParams(Ui.dp(this, 80), wrap))
            } else {
                row.addView(space, spaceLp)
                row.addView(t, LinearLayout.LayoutParams(Ui.dp(this, 40), wrap))
            }
        }
        return row
    }

    private fun abilities(m: Dex.Mon): View {
        val col = Ui.vertical(this)
        for (id in m.abilities) col.addView(Ui.text(this, dex.ability(id).name, 14, false))
        if (m.hidden != 0 && m.hidden !in m.abilities) col.addView(Ui.text(this, L.f("%s (hidden)", dex.ability(m.hidden).name), 14, false))
        return col
    }

    companion object {
        /** `left` and `right` sharing the width, `gap` dp apart. */
        fun pair(c: Context, left: View, right: View, gap: Int): LinearLayout {
            val row = LinearLayout(c)
            row.addView(left, Ui.margins(0, ViewGroup.LayoutParams.MATCH_PARENT, c, 0, 0, gap / 2, 0).apply { weight = 1f })
            row.addView(right, Ui.margins(0, ViewGroup.LayoutParams.MATCH_PARENT, c, gap / 2, 0, 0, 0).apply { weight = 1f })
            return row
        }

        /** `num` 0 picks both sides; `game` sets types and the list to pick from. */
        fun open(a: Activity, num: Int, game: Int) {
            a.startActivity(Intent(a, CompareActivity::class.java).putExtra("num", num).putExtra("game", game))
        }
    }
}
