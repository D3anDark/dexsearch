package demo.dex

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

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
     * the title names it: the second list looked the same as the first, as if the first pick had not counted.
     */
    private fun pick(i: Int) {
        val teams = TeamsActivity.members(this, dex, game)
        val other = dex.monOrNull(nums[1 - i])
        val heading = if (other == null) L.t("Compare") else L.f("Compare with %s", other.name)
        TeamsActivity.pick(this, dex, game, heading, if (teams.isEmpty()) null else L.t("Teams") to teams) { x ->
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
            for (t in dex.types(game)) {
                val fa = dex.factor(game, t, ta)
                val fb = dex.factor(game, t, tb)
                if (fa == 100 && fb == 100) continue
                // Less damage taken is better, so the values go in negated.
                defenses.addView(sides(Ui.pill(this, t), -fa, -fb, CalcActivity.effect(fa), CalcActivity.effect(fb), false))
            }
            root.addView(defenses, Ui.wide(this, 0, 12))

            val abilities = Ui.panel(this, L.t("Abilities"))
            abilities.addView(pair(this, abilities(a), abilities(b), 12))
            root.addView(abilities, Ui.wide(this, 0, 12))
        }
        scroll.removeAllViews()
        scroll.addView(root)
    }

    /** Sprite, name and types; tap to pick another. An empty side is a slot to fill: dashed outline, a plus for the sprite. */
    private fun head(i: Int, m: Dex.Mon?): View {
        val h = Ui.vertical(this)
        h.gravity = Gravity.CENTER_HORIZONTAL
        val face = Ui.rounded(this, Ui.SURFACE, 18)
        if (m == null) face.setStroke(Ui.dp(this, 2), Ui.MUTED, Ui.dp(this, 6).toFloat(), Ui.dp(this, 4).toFloat())
        h.background = Ui.pressable(this, face, 18)
        h.setPadding(Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12))
        val img = if (m != null) ImageView(this).apply { setImageDrawable(Ui.sprite(this@CompareActivity, m.num, 2)) }
            else Ui.text(this, "+", 40, false).apply {
                setTextColor(Ui.MUTED)
                gravity = Gravity.CENTER
                background = Ui.rounded(this@CompareActivity, Ui.FILL, 48)
            }
        h.addView(img, LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 96)))
        val name = Ui.text(this, m?.name ?: L.t("Choose a Pokémon"), 16, true)
        if (m == null) name.setTextColor(Ui.MUTED)
        name.gravity = Gravity.CENTER
        h.addView(name)
        if (m != null) h.addView(Ui.typePills(this, dex.types(game, m), 2),
            Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 6, 0, 0))
        h.setOnClickListener { pick(i) }
        return h
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
