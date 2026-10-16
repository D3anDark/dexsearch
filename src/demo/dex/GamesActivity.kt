package demo.dex

import android.app.Activity
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Game picker: a card per game in its version colors, newest generation first, each generation led by its Pokédex.
 * Tapping one selects it.
 */
class GamesActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val dex = Ui.dex(this)
        val current = Ui.game(this)
        val root = Ui.vertical(this)
        root.setPadding(0, 0, 0, Ui.dp(this, 16))
        root.addView(Ui.title(this, L.t("Choose a game")))
        addCard(root, dex.whole, current)
        var gen = 0
        for (g in dex.games.asReversed()) {
            if (g.id == Dex.GO) {
                addCard(root, g, current)
                continue
            }
            if (g.gen != gen) {
                gen = g.gen
                val h = Ui.caption(this, L.f("Generation %s", ROMAN[gen - 1]))
                h.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 20), Ui.dp(this, 8))
                root.addView(h)
                addCard(root, dex.generations[gen - 1], current)
            }
            addCard(root, g, current)
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        Ui.show(this, scroll)
    }

    private fun addCard(root: LinearLayout, g: Dex.Game, current: Dex.Game) {
        val card = card(this, g, if (g === current) L.t("Selected") else null)
        card.setOnClickListener {
            Ui.prefs(this).edit().putInt("game", g.id).apply()
            finish()
        }
        root.addView(card, Ui.wide(this, 0, 10))
    }

    companion object {
        private val ROMAN = arrayOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX")

        /** Version colors, two for paired releases, by game id. */
        private val COLORS = mapOf(
            1 to longArrayOf(0xFFDA3B2F, 0xFF2E64C8), 2 to longArrayOf(0xFFE0A800),
            3 to longArrayOf(0xFFC9A227, 0xFF8C96A3), 4 to longArrayOf(0xFF3FA7C9),
            5 to longArrayOf(0xFFC0182E, 0xFF1F4FB5), 6 to longArrayOf(0xFF1E9E5A),
            7 to longArrayOf(0xFFF05A28, 0xFF4CA53C), 8 to longArrayOf(0xFF5E7FD0, 0xFFC97D9C),
            9 to longArrayOf(0xFF7E7E8E), 10 to longArrayOf(0xFFC9971A, 0xFF8FA0B2),
            11 to longArrayOf(0xFF2B2B2B, 0xFF7D838C), 14 to longArrayOf(0xFF24476B, 0xFFC0504D),
            15 to longArrayOf(0xFF2A6FDB, 0xFFD3263E), 16 to longArrayOf(0xFFB3122B, 0xFF1C54B2),
            17 to longArrayOf(0xFFF29B1D, 0xFF5B4FC4), 18 to longArrayOf(0xFFE8641B, 0xFF6A2FA0),
            19 to longArrayOf(0xFFE6B400, 0xFFA0703D), 20 to longArrayOf(0xFF1E9BD7, 0xFFD62F6B),
            23 to longArrayOf(0xFF4C7BD9, 0xFFD07FA6), 24 to longArrayOf(0xFF3F7C84),
            25 to longArrayOf(0xFFD4302B, 0xFF7D3CB5), 30 to longArrayOf(0xFF2E9E62),
            32 to longArrayOf(0xFF33475B), Dex.GO to longArrayOf(0xFF2A75BB, 0xFF1FA59A), Dex.WHOLE to longArrayOf(0xFFD03A3A, 0xFF33475B))

        /** Box-art Pokémon per game id, one per version. */
        private val MASCOTS = mapOf(
            1 to intArrayOf(6, 9), 2 to intArrayOf(25), 3 to intArrayOf(250, 249),
            4 to intArrayOf(245), 5 to intArrayOf(383, 382), 6 to intArrayOf(384),
            7 to intArrayOf(6, 3), 8 to intArrayOf(483, 484), 9 to intArrayOf(487),
            10 to intArrayOf(250, 249), 11 to intArrayOf(643, 644), 14 to intArrayOf(646),
            15 to intArrayOf(716, 717), 16 to intArrayOf(383, 382), 17 to intArrayOf(791, 792),
            18 to intArrayOf(800), 19 to intArrayOf(25, 133), 20 to intArrayOf(888, 889),
            23 to intArrayOf(483, 484), 24 to intArrayOf(493), 25 to intArrayOf(1007, 1008),
            30 to intArrayOf(718), Dex.GO to intArrayOf(25, 808), Dex.WHOLE to intArrayOf(151, 493))

        /**
         * Version-colored card: name, regional dexes, species count, `below` (may be null) and box-art Pokémon; `tag`
         * (may be null) as a pill under the text, a button of its own when `onTag` is given. A generation's Pokédex
         * takes its first game's colors and its three starters. Sprites decode at 128 px, 64 KB each.
         */
        fun card(a: Activity, g: Dex.Game, tag: String?, below: View? = null, onTag: View.OnClickListener? = null): View {
            val generation = g.id > Dex.WHOLE
            val look = if (generation) Ui.dex(a).games.first { it.gen == g.gen }.id else g.id
            val colors = COLORS[look] ?: longArrayOf(0xFF555555)
            val f = FrameLayout(a)
            f.minimumHeight = Ui.dp(a, 96)
            f.background = Ui.cardBackground(a, Ui.deep(colors.first().toInt()), Ui.deep(colors.last().toInt()), 110)

            val starter = g.dex.first { !it.mon.form() }.mon.num  // Paldean Tauros and other new forms come earlier
            val mascots = if (generation) intArrayOf(starter, starter + 3, starter + 6) else MASCOTS[g.id] ?: IntArray(0)
            val art = LinearLayout(a)
            for (num in mascots) {
                val img = ImageView(a)
                img.setImageDrawable(Ui.sprite(a, num, 2))
                art.addView(img, LinearLayout.LayoutParams(Ui.dp(a, 64), Ui.dp(a, 64)))
            }
            val artLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
            artLp.setMargins(0, 0, Ui.dp(a, 8), Ui.dp(a, 6))
            f.addView(art, artLp)

            val regions = g.dex.mapTo(LinkedHashSet()) { it.dex }
            val species = g.dex.count { !it.mon.form() }
            val texts = Ui.vertical(a)
            texts.setPadding(Ui.dp(a, 16), Ui.dp(a, 14), Ui.dp(a, 16), Ui.dp(a, 14))
            val name = Ui.white(a, g.name, 19, true)
            name.maxLines = 2
            name.ellipsize = TextUtils.TruncateAt.END
            texts.addView(name)
            val sub = Ui.white(a, regions.joinToString(", ") + "\n" + L.f("%d Pokémon", species), 13, false)
            sub.setPadding(0, Ui.dp(a, 4), 0, 0)
            texts.addView(sub)
            if (below != null) texts.addView(below)
            if (tag != null) {
                val pill = Ui.glassPill(a, tag)
                if (onTag != null) {
                    pill.background = Ui.pressable(a, pill.background, 999)
                    pill.setPadding(Ui.dp(a, 14), Ui.dp(a, 7), Ui.dp(a, 14), Ui.dp(a, 7))
                    pill.setOnClickListener(onTag)
                }
                texts.addView(pill, Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, a, 0, 10, 0, 0))
            }
            val textLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL)
            textLp.setMargins(0, 0, Ui.dp(a, 12 + 56 * mascots.size), 0)
            f.addView(texts, textLp)
            return f
        }
    }
}
