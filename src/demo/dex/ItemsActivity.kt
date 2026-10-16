package demo.dex

/** All held items in sections (Berries, Mega Stones) with their icon and short effect (English); a search ranks them all. */
class ItemsActivity : ListScreen() {
    private lateinit var byName: List<Dex.Item>

    override fun heading() = L.t("Items")

    override fun hint() = L.t("Search items")

    override fun setup() {
        byName = dex.items.sortedBy { it.key }
    }

    override fun items(query: String): List<Ui.Item> =
        if (query.isBlank()) Ui.itemSections(byName, this::row) else Dex.best(byName, query, { it.key }, byName.size).map(this::row)

    private fun row(it: Dex.Item) = Ui.Item(0, L.f("Gen %d", it.gen), it.name, it.effect, emptyList(), it)

    override fun count(shown: List<Ui.Item>) = L.f("Items: %d of %d", shown.count { it.ref != null }, byName.size)
}
