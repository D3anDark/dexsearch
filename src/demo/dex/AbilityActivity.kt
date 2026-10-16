package demo.dex

import android.app.Activity
import android.os.Bundle
import android.widget.ListView

/** One ability: effect, then the Pokémon that have it, regular and hidden. */
class AbilityActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val dex = Ui.dex(this)
        val game = Ui.game(this)
        val a = dex.ability(intent.getIntExtra("id", 1))

        val regular = ArrayList<Ui.Item>()
        val hidden = ArrayList<Ui.Item>()
        for (m in dex.mons) {
            val item = Ui.Item.mon(m, dex.types(game, m), Ui.national(m.species), m.genus)
            for (id in m.abilities) if (id == a.id) regular.add(item)
            if (m.hidden == a.id) hidden.add(item)
        }
        val items = ArrayList<Ui.Item>()
        if (regular.isNotEmpty()) items.add(Ui.Item.header(L.f("Ability (%d)", regular.size)))
        items.addAll(regular)
        if (hidden.isNotEmpty()) items.add(Ui.Item.header(L.f("Hidden ability (%d)", hidden.size)))
        items.addAll(hidden)

        val hero = Ui.hero(this, SLATE)
        hero.setPadding(hero.paddingLeft, Ui.dp(this, 20), hero.paddingRight, hero.paddingBottom)
        hero.addView(Ui.white(this, L.f("ABILITY  ·  GEN %d", a.gen), 12, true))
        hero.addView(Ui.white(this, a.name, 30, true))
        val head = Ui.vertical(this)
        head.addView(hero)
        val effect = Ui.panel(this, L.t("Effect"))
        effect.addView(Ui.text(this, a.effect.ifEmpty { a.shortEffect }, 15, false))
        head.addView(effect, Ui.wide(this, 16, 4))
        if (items.isEmpty()) {
            val none = Ui.muted(this, L.t("No Pokémon has this ability."), 15)
            none.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), 0)
            head.addView(none)
        }

        val adapter = Ui.Adapter()
        adapter.set(items)
        val lv = Ui.list(this, false) as ListView
        lv.addHeaderView(head, null, false)
        lv.adapter = adapter
        val nums = Ui.nums(items)
        lv.setOnItemClickListener { parent, _, i, _ ->
            val item = parent.getItemAtPosition(i) as? Ui.Item ?: return@setOnItemClickListener
            Ui.openMon(this, (item.ref as Dex.Mon).num, nums)
        }
        Ui.show(this, lv, SLATE)
    }

    companion object {
        private const val SLATE = 0xFF34495E.toInt()
    }
}
