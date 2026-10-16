package demo.dex

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The 25 natures as the games' 5 x 5 chart. */
class NaturesActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val dex = Ui.dex(this)
        val root = Ui.vertical(this)
        root.addView(Ui.title(this, L.t("Natures")))
        val about = Ui.muted(this, L.t("A nature raises one stat by 10% (its row) and lowers another by 10% (its column). The five on the diagonal change nothing."), 14)
        about.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 12))
        root.addView(about)
        val p = Ui.panel(this, null)
        p.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10))
        p.addView(chart(this, dex, -1, null))
        root.addView(p, Ui.wide(this, 0, 16))
        val v = ScrollView(this)
        v.addView(root)
        Ui.show(this, v)
    }

    companion object {
        /**
         * "+Atk" row and "-Atk" column headers around [Dex.natureChart]. With `picked`, cells are buttons that pass the
         * index in [Dex.natures], and `current` (-1 for none) is highlighted.
         */
        fun chart(c: Context, d: Dex, current: Int, picked: ((Int) -> Unit)?): LinearLayout {
            val stats = Dex.STATS.drop(1)
            val grid = Ui.vertical(c)
            val head = LinearLayout(c)
            head.addView(header(c, "", 0), LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 28)))
            for (s in stats) head.addView(header(c, "-" + L.t(s), Ui.DOWN), LinearLayout.LayoutParams(0, Ui.dp(c, 28), 1f))
            grid.addView(head)
            for ((r, row) in d.natureChart().withIndex()) {
                val line = LinearLayout(c)
                line.addView(header(c, "+" + L.t(stats[r]), Ui.UP), LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 48)))
                for ((k, i) in row.withIndex()) {
                    val n = d.natures[i]
                    val on = i == current
                    val t = Ui.text(c, n.name, 12, true)
                    t.gravity = Gravity.CENTER
                    // Fixed-size cells: "Adamant" shrinks in the picker dialog instead of being cut. Not setSingleLine():
                    // it scrolls horizontally, so autosize measures against unlimited width and never shrinks.
                    t.maxLines = 1
                    t.setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, TypedValue.COMPLEX_UNIT_SP)
                    t.contentDescription = if (r == k) L.f("%s, neutral", n.name) else L.f("%s, raises %s, lowers %s", n.name, L.t(n.up), L.t(n.down))
                    t.setTextColor(if (on) Ui.WHITE else if (r == k) Ui.MUTED else Ui.TEXT)
                    val fill = Ui.rounded(c, if (on) Ui.ACCENT else if (r == k) Ui.SURFACE else Ui.FILL, 10)
                    if (r == k && !on) fill.setStroke(Ui.dp(c, 1), Ui.FILL)
                    t.background = if (picked == null) fill else Ui.pressable(c, fill, 10)
                    if (picked != null) t.setOnClickListener { picked(i) }
                    line.addView(t, Ui.margins(0, ViewGroup.LayoutParams.MATCH_PARENT, c, 2, 2, 2, 2).apply { weight = 1f })
                }
                grid.addView(line)
            }
            return grid
        }

        private fun header(c: Context, s: String, color: Int): TextView {
            val t = Ui.text(c, s, 12, true)
            t.gravity = Gravity.CENTER
            t.setTextColor(color)
            return t
        }
    }
}
