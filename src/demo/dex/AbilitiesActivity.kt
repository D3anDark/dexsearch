package demo.dex

/** All abilities by name with their short effect. */
class AbilitiesActivity : ListScreen() {
    private lateinit var byName: List<Dex.Ability>

    override fun heading() = L.t("Abilities")

    override fun hint() = L.t("Search abilities")

    override fun setup() {
        byName = dex.abilities.sortedBy { it.key }
    }

    override fun items(query: String): List<Ui.Item> =
        Dex.best(byName, query, { it.key }, byName.size).map { Ui.Item(0, L.f("Gen %d", it.gen), it.name, it.shortEffect, emptyList(), it) }

    override fun count(shown: List<Ui.Item>) = L.f("Abilities: %d of %d", shown.size, byName.size)
}
