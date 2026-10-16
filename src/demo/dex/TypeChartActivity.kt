package demo.dex

import android.app.Activity
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
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

        val grid = Ui.vertical(this)
        val top = LinearLayout(this)
        top.addView(cell(""), size(cell))
        for (t in types) top.addView(header(t), size(cell))
        grid.addView(top)
        for (attack in types) {
            val row = LinearLayout(this)
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
                row.addView(c, size(cell))
            }
            grid.addView(row)
        }

        val root = Ui.vertical(this)
        root.addView(Ui.title(this, L.t("Type chart")))
        val help = Ui.muted(this, L.f("As in %s. Rows attack, columns defend. 2 is super effective, ½ not very effective, 0 no effect. Tap a type to see its Pokémon.", game.name), 14)
        help.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 12))
        root.addView(help)
        val panel = Ui.panel(this, null)
        panel.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12))
        val h = HorizontalScrollView(this)
        h.addView(grid)
        panel.addView(h)
        root.addView(panel, Ui.wide(this, 0, 16))
        val v = ScrollView(this)
        v.addView(root)
        Ui.show(this, v)
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
