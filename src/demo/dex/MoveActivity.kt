package demo.dex

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView

/** One move: type-colored header with its numbers, effect, then every Pokémon that learns it in the selected game. */
class MoveActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val dex = Ui.dex(this)
        val game = Ui.game(this)
        val m = dex.move(game, intent.getIntExtra("id", 1))
        val color = Ui.deep(Ui.color(m.type))

        val hero = Ui.hero(this, color)
        hero.setPadding(hero.paddingLeft, Ui.dp(this, 20), hero.paddingRight, hero.paddingBottom)
        hero.addView(Ui.white(this, m.name, 30, true))
        val pills = LinearLayout(this)
        // Baseline-aligned, the category pill sat lower than the taller type pill and lost its bottom edge.
        pills.isBaselineAligned = false
        val type = Ui.typeGlass(this, m.type)
        type.setOnClickListener { Ui.open(this, m.type) }
        pills.addView(type, Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 0, 6, 0))
        pills.addView(Ui.glassPill(this, dex.label(m.category)))
        hero.addView(pills, Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 8, 0, 16))
        val numbers = LinearLayout(this)
        column(numbers, if (m.power == 0) "-" else m.power.toString(), L.t("Power"))
        column(numbers, if (m.accuracy == 0) "-" else "${m.accuracy}%", L.t("Accuracy"))
        column(numbers, m.pp.toString(), L.t("PP"))
        if (m.priority != 0) column(numbers, (if (m.priority > 0) "+" else "") + m.priority, L.t("Priority"))
        hero.addView(numbers)
        val tms = dex.tmGame(game)
        val tm = tms.tms[m.id]
        if (tm != null) {
            val t = Ui.white(this, L.f("%s in %s", tm, tms.name), 13, true)
            t.setPadding(0, Ui.dp(this, 14), 0, 0)
            hero.addView(t)
        }

        val head = Ui.vertical(this)
        head.addView(hero)
        val effect = Ui.panel(this, L.t("Effect"))
        effect.addView(Ui.text(this, m.effect.ifEmpty { m.shortEffect }, 15, false))
        val meta = Ui.muted(this, L.f("Introduced in Gen %d", m.gen) + if (m.chance == 0) "" else "  ·  " + L.f("Effect chance %d%%", m.chance), 13)
        meta.setPadding(0, Ui.dp(this, 10), 0, 0)
        effect.addView(meta)
        head.addView(effect, Ui.wide(this, 16, 4))

        val g = dex.learnGame(game, m)
        val items = ArrayList<Ui.Item>()
        var method = -1
        for (r in dex.learners(g, m.id)) {
            if (r[1] != method) {
                method = r[1]
                items.add(Ui.Item.header(L.t(Dex.METHODS[method])))
            }
            val x = dex.mon(r[0])
            val how = when (r[1]) {
                0 -> if (r[2] == 0) L.t("On evolution") else L.f("Level %d", r[2])
                1 -> g.tms[m.id] ?: ""
                else -> ""
            }
            items.add(Ui.Item.mon(x, dex.types(game, x), Ui.national(x.species), how))
        }
        val nums = Ui.nums(items)
        val learned = Ui.text(this, L.f("Learned by %d in %s", nums.size, g.name), 17, true)
        learned.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), 0)
        head.addView(learned)
        if (g !== game && !game.virtual) {
            val note = Ui.muted(this, L.f("No %s move data.", game.name), 13)
            note.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0)
            head.addView(note)
        }

        val adapter = Ui.Adapter()
        adapter.set(items)
        val lv = Ui.list(this, false) as ListView
        lv.addHeaderView(head, null, false)
        lv.adapter = adapter
        lv.setOnItemClickListener { parent, _, i, _ ->
            val item = parent.getItemAtPosition(i) as? Ui.Item ?: return@setOnItemClickListener
            Ui.openMon(this, (item.ref as Dex.Mon).num, nums)
        }
        Ui.show(this, lv, color)
    }

    private fun column(row: LinearLayout, value: String, label: String) {
        val col = Ui.vertical(this)
        col.addView(Ui.white(this, value, 22, true))
        col.addView(Ui.white(this, label, 12, false))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
}
