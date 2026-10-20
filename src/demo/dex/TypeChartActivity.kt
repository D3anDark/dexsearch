package demo.dex

import android.app.Activity
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Effectiveness grid of the selected game's generation (15 x 15 in Gen 1, 18 x 18 from Gen 6), attacking type per row,
 * defending type per column. Type headers open that type's Pokédex.
 */
class TypeChartActivity : Activity() {
    private var gap = 0
    private var headPx = 0f

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val dex = Ui.dex(this)
        val game = Ui.game(this)
        val types = dex.types(game)
        val n = types.size
        gap = Ui.dp(this, 1)
        // 16 dp page margin and 12 dp panel padding on each side.
        val cell = maxOf(Ui.dp(this, 16), (resources.displayMetrics.widthPixels - Ui.dp(this, 56)) / (n + 1))
        // One size for every header, so the widest label fits and the rest match it.
        val p = Paint()
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 100f
        val widest = types.maxOf { p.measureText(label(it)) }
        headPx = minOf(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics),
            100f * (cell - gap - Ui.dp(this, 4)) / widest)

        // The cells are too small to read a row at a glance; a tapped row is ringed and spelled out under the chart.
        val legend = Ui.muted(this, "", 13)
        legend.visibility = View.GONE
        legend.setPadding(0, Ui.dp(this, 12), 0, 0)
        var lit: LinearLayout? = null
        val marks = listOf(200 to "2", 50 to "½", 0 to "0")
        val spell = { attack: String, row: LinearLayout ->
            lit?.let { ring(it, false) }
            lit = row
            ring(row, true)
            val by = { f: Int -> types.filter { dex.factor(game, attack, listOf(it)) == f }.joinToString(L.t(", ")) { Ui.label(it) } }
            val head = L.f("%s attacks", Ui.label(attack)) + ": "
            val s = SpannableString(head + marks.map { (f, mark) -> mark to by(f) }.filter { it.second.isNotEmpty() }
                .joinToString("  ·  ") { it.first + " " + it.second })
            s.setSpan(StyleSpan(Typeface.BOLD), 0, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            legend.text = s
            legend.visibility = View.VISIBLE
        }

        val grid = Ui.vertical(this)
        // Rows not baseline-aligned: the headers' smaller text dropped them below their cells and cut their corners.
        val top = LinearLayout(this)
        top.isBaselineAligned = false
        top.addView(cell(""), size(cell))
        for (t in types) top.addView(header(t), size(cell))
        grid.addView(top)
        for (attack in types) {
            val row = LinearLayout(this)
            row.isBaselineAligned = false
            row.addView(header(attack), size(cell))
            for (defend in types) {
                val f = dex.factor(game, attack, listOf(defend))
                val c = cell(when (f) { 200 -> "2"; 50 -> "½"; 0 -> "0"; else -> "" })
                c.background = Ui.rounded(this, when (f) {
                    200 -> 0xFF1E7B3A.toInt()
                    50 -> 0xFFC7413A.toInt()
                    0 -> 0xFF303030.toInt()
                    else -> Ui.FILL
                }, 4)
                c.setOnClickListener { spell(attack, row) }
                row.addView(c, size(cell))
            }
            grid.addView(row)
        }

        val root = Ui.vertical(this)
        root.addView(Ui.title(this, L.t("Type chart")))
        val help = Ui.muted(this, L.f("As in %s. Rows attack, columns defend. 2 is super effective, ½ not very effective, 0 no effect. Tap a row to read it in words, a type to see its Pokémon.", game.name), 14)
        help.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 12))
        root.addView(help)
        val panel = Ui.panel(this, null)
        panel.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12))
        val h = HorizontalScrollView(this)
        h.addView(grid)
        panel.addView(h)
        panel.addView(legend)
        root.addView(panel, Ui.wide(this, 0, 16))
        val v = ScrollView(this)
        v.addView(root)
        Ui.show(this, v)
    }

    /** A 2 dp ring in the text color on every cell of `row` but its header. */
    private fun ring(row: LinearLayout, on: Boolean) {
        for (i in 1 until row.childCount) (row.getChildAt(i).background as GradientDrawable).setStroke(if (on) Ui.dp(this, 2) else 0, Ui.TEXT)
    }

    private fun size(cell: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(cell - gap, cell - gap)
        lp.setMargins(0, 0, gap, gap)
        return lp
    }

    private fun cell(s: String): TextView {
        val t = Ui.white(this, s, 12, true)
        t.gravity = Gravity.CENTER
        return t
    }

    private fun header(type: String): TextView {
        val t = cell(label(type))
        t.maxLines = 1
        t.contentDescription = Ui.label(type)
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, headPx)
        t.background = Ui.rounded(this, Ui.color(type), 4)
        t.setTextColor(Ui.on(Ui.color(type)))
        t.setOnClickListener { Ui.open(this, type) }
        return t
    }

    /** First 3 letters of the shown name in capitals; 2 kana in Japanese, where 3 would be most of the word. */
    private fun label(type: String) = if (L.lang == "ja") Ui.label(type).take(2) else Ui.label(type).take(3).uppercase(Locale.ROOT)
}
