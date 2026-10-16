package demo.dex

import android.widget.TextView
import java.util.TreeSet

/** All moves, or with extra "tm" the selected game's TMs in machine order. Type, category filters and sorts. */
class MovesActivity : ListScreen() {
    private val types = TreeSet<String>()
    private val categories = TreeSet<String>()
    private var sort = Dex.MoveSort.ORDER
    private var tm = false
    private lateinit var source: List<Dex.Move>
    private lateinit var typeChip: TextView
    private lateinit var categoryChip: TextView
    private lateinit var sortChip: TextView

    override fun heading() = if (tm) L.t("TMs") else L.t("Moves")

    override fun hint() = if (tm) L.t("Search move or TM number") else L.t("Search moves")

    override fun setup() {
        tm = intent.getBooleanExtra("tm", false)
        source = if (tm) game.tms.keys.map { dex.move(game, it) } else dex.moves(game)
        val sorts = Dex.MoveSort.entries.map { L.t(it.label) }
        typeChip = addChip { Ui.pickMany(this, L.t("Type"), dex.types(game), types, { dex.label(it) }, this::refresh) }
        categoryChip = addChip { Ui.pickMany(this, L.t("Category"), CATEGORIES, categories, { dex.label(it) }, this::refresh) }
        sortChip = addChip {
            Ui.pickOne(this, L.t("Sort"), sorts, sort.ordinal) { i ->
                sort = Dex.MoveSort.entries[i]
                refresh()
            }
        }
    }

    override fun items(query: String): List<Ui.Item> =
        Dex.moves(source, query, types, categories, sort, this::label).map { Ui.Item.move(it, label(it)) }

    private fun label(m: Dex.Move) = if (tm) game.tms.getValue(m.id) else ""

    override fun count(shown: List<Ui.Item>) =
        if (tm) L.f("TMs in %s: %d of %d", game.name, shown.size, source.size) else L.f("Moves: %d of %d", shown.size, source.size)

    override fun labels() {
        typeChip.text = if (types.isEmpty()) L.t("Type") else types.joinToString(" / ") { dex.label(it) }
        Ui.chipActive(typeChip, types.isNotEmpty())
        categoryChip.text = if (categories.isEmpty()) L.t("Category") else categories.joinToString(" / ") { dex.label(it) }
        Ui.chipActive(categoryChip, categories.isNotEmpty())
        sortChip.text = L.f("Sort: %s", L.t(sort.label))
        Ui.chipActive(sortChip, sort != Dex.MoveSort.ORDER)
    }

    companion object {
        private val CATEGORIES = listOf("physical", "special", "status")
    }
}
