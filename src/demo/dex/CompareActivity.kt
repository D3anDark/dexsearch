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

/** Two Pokémon side by side: types, base stats with the higher one marked, type defenses and abilities. */
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

    /** Picks side `i`; picking the first while the second is empty goes straight on to it. */
    private fun pick(i: Int) {
        val teams = TeamsActivity.members(this, dex, game)
        TeamsActivity.pick(this, dex, game, L.t("Compare"), if (teams.isEmpty()) null else L.t("Teams") to teams) { x ->
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
            for (s in 0..Dex.STATS.size) {
                val label = if (s < Dex.STATS.size) L.t(Dex.STATS[s]) else L.t("Total")
                stats.addView(statRow(label, if (s < Dex.STATS.size) a.stats[s] else a.bst, if (s < Dex.STATS.size) b.stats[s] else b.bst))
            }
            root.addView(stats, Ui.wide(this, 0, 12))

            val defenses = Ui.panel(this, L.t("Type defenses"))
            defenses.addView(pair(this, MonActivity.defenses(this, dex, game, a, 2), MonActivity.defenses(this, dex, game, b, 2), 12))
            root.addView(defenses, Ui.wide(this, 0, 12))

            val abilities = Ui.panel(this, L.t("Abilities"))
            abilities.addView(pair(this, abilities(a), abilities(b), 12))
            root.addView(abilities, Ui.wide(this, 0, 12))
        }
        scroll.removeAllViews()
        scroll.addView(root)
    }

    /** Sprite, name and types; tap to pick another. */
    private fun head(i: Int, m: Dex.Mon?): View {
        val h = Ui.vertical(this)
        h.gravity = Gravity.CENTER_HORIZONTAL
        h.background = Ui.pressable(this, Ui.rounded(this, Ui.SURFACE, 18), 18)
        h.setPadding(Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12))
        val img = ImageView(this)
        if (m != null) img.setImageDrawable(Ui.sprite(this, m.num, 2))
        h.addView(img, LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 96)))
        val name = Ui.text(this, m?.name ?: L.t("Choose a Pokémon"), 16, true)
        name.gravity = Gravity.CENTER
        h.addView(name)
        if (m != null) h.addView(Ui.typePills(this, dex.types(game, m), 2),
            Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 6, 0, 0))
        h.setOnClickListener { pick(i) }
        return h
    }

    /** Both values around the stat's name, the higher one bold in the natures' "raised" color. */
    private fun statRow(label: String, a: Int, b: Int): View {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3))
        for ((k, v) in listOf(a, b).withIndex()) {
            val higher = v > (if (k == 0) b else a)
            val t = Ui.text(this, v.toString(), 15, higher)
            if (higher) t.setTextColor(Ui.UP)
            t.gravity = if (k == 0) Gravity.END else Gravity.START
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (k == 0) {
                val name = Ui.muted(this, label, 14)
                name.gravity = Gravity.CENTER
                row.addView(name, LinearLayout.LayoutParams(Ui.dp(this, 72), ViewGroup.LayoutParams.WRAP_CONTENT))
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
