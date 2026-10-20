package demo.dex

import android.view.ViewGroup
import android.widget.Toast

/**
 * The workspace's Box, the games' PC: every Pokémon marked caught and every set taken off a team, in national order.
 * A kept set shows its level, item and moves, as in the team picker's Box tab. A tap offers what can be done with it:
 * onto a team (with its set), its page, or Release, the only way out of the Box.
 */
class BoxActivity : ListScreen() {
    private var total = 0

    override fun heading() = L.t("Box")

    override fun hint() = L.t("Search the Box")

    override fun setup() {
        addChip { add() }.text = L.t("Add Pokémon")
    }

    override fun items(query: String): List<Ui.Item> {
        val caught = Ui.caught(this)
        val kept = Ui.box(this).associateBy { it.num }
        val all = dex.national.filter { it.mon.num.toString() in caught || it.mon.num in kept }
        total = all.size
        return dex.pokedex(game, all, query, emptySet(), emptySet(), { true }, Dex.Sort.NUMBER).map { e ->
            val m = e.mon
            val x = kept[m.num]
            if (x == null) Ui.Item.mon(m, dex.types(game, m), Ui.national(e.number), m.genus)
            else Ui.Item.mon(m, dex.types(game, m), L.f("Lv %d", x.level), TeamsActivity.setLine(dex, x))
        }
    }

    override fun count(shown: List<Ui.Item>) = L.f("Box: %d of %d", shown.size, total)

    override fun empty() = if (total == 0) L.t("Nothing here yet. Mark a Pokémon caught on its page, or take one off a team, and it lands in the Box.")
        else super.empty()

    override fun open(item: Ui.Item, shown: List<Ui.Item>) {
        val m = item.ref as Dex.Mon
        val kept = Ui.boxed(this, m.num)
        val s = Ui.pickOne(this, m.name, listOf(L.t("Add to team"), L.t("Pokémon page"), L.t("Release")), -1) { i ->
            when (i) {
                0 -> TeamsActivity.addTo(this, m)
                1 -> Ui.openMon(this, m.num, Ui.nums(shown))
                else -> release(m, kept != null)
            }
        }
        if (kept != null) s.body.addView(Ui.muted(this, L.f("Lv %d", kept.level) + "  ·  " + TeamsActivity.setLine(dex, kept), 13), 0,
            Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 4, 0, 4, 10))
    }

    /** The selected game's Pokémon not in the Box yet; one picked counts as caught, as Mark caught on its page does. */
    private fun add() {
        val caught = Ui.caught(this)
        val items = game.dex.map { it.mon }.filter { it.num.toString() !in caught && !dex.alike(game, it) }
            .map { TeamsActivity.monItem(dex, game, it) }
        Ui.pickSearch(this, L.t("Add Pokémon"), items) { ref ->
            val m = ref as Dex.Mon
            Ui.setCaught(this, m.num, true)
            Toast.makeText(this, L.f("%s is in the Box", m.name), Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    /** Out of the Box for good: the caught mark goes, and so does a kept set. Teams keep their members. */
    private fun release(m: Dex.Mon, kept: Boolean) {
        Ui.confirm(this, L.f("Release %s?", m.name),
            if (kept) L.t("Its moves, item and stats are forgotten and it no longer counts as caught. This cannot be undone.")
            else L.t("It no longer counts as caught."), L.t("Release")) {
            Ui.fromBox(this, m.num)
            Ui.setCaught(this, m.num, false)
            refresh()
        }
    }
}
