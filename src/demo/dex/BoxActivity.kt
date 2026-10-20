package demo.dex

/**
 * The workspace's Box: every Pokémon marked caught and every set taken off a team, in national order. A kept set
 * shows its level, item and moves, as in the team picker's Box tab; a tap opens the Pokémon's page, whose Add to team
 * takes that set along.
 */
class BoxActivity : ListScreen() {
    private var total = 0

    override fun heading() = L.t("Box")

    override fun hint() = L.t("Search the Box")

    override fun setup() {}

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
        Ui.openMon(this, (item.ref as Dex.Mon).num, Ui.nums(shown))
    }
}
