package demo.dex

import android.view.View
import android.widget.TextView
import java.util.Locale
import java.util.TreeSet

/**
 * The selected game's regional or the national Pokédex, or a generation's when that is the selected game, as cards or
 * rows (Settings), with filters and sorts. Megas, regional and other forms only if Settings shows them; otherwise they
 * are on their species' page.
 */
class PokedexActivity : ListScreen() {
    private val types = TreeSet<String>()
    private val gens = TreeSet<Int>()
    private var caughtFilter = 0
    private var sort = Dex.Sort.NUMBER
    private var national = false
    /** 0, or the generation whose Pokédex is the selected game. */
    private var gen = 0
    private lateinit var source: List<Dex.Entry>
    private lateinit var caught: Set<String>
    private lateinit var dexChip: TextView
    private lateinit var typeChip: TextView
    private lateinit var genChip: TextView
    private lateinit var caughtChip: TextView
    private lateinit var sortChip: TextView

    override fun heading() = if (gen > 0) L.f("Gen %d Pokédex", gen) else L.t("Pokédex")

    override fun hint() = L.t("Search name or number")

    override fun cards() = Ui.prefs(this).getInt("layout", 0) == 0

    override fun setup() {
        gen = if (game.id > Dex.WHOLE) game.gen else 0
        national = game.virtual || Ui.prefs(this).getBoolean("national", false)
        intent.getStringExtra("type")?.let { types.add(it) }
        val allGens = dex.mons.map { it.gen }.distinct().sorted()
        val sorts = Dex.Sort.entries.map { L.t(it.label) }
        dexChip = addChip {
            national = !national
            Ui.prefs(this).edit().putBoolean("national", national).apply()
            refresh()
        }
        if (game.virtual) dexChip.visibility = View.GONE
        typeChip = addChip { Ui.pickMany(this, L.t("Type (must have all)"), dex.types(game), types, { dex.label(it) }, this::refresh) }
        genChip = addChip { Ui.pickMany(this, L.t("Generation"), allGens, gens, { L.f("Gen %d", it) }, this::refresh) }
        if (gen > 0) genChip.visibility = View.GONE
        caughtChip = addChip {
            Ui.pickOne(this, L.t("Caught"), CAUGHT.map { L.t(it) }, caughtFilter) { i ->
                caughtFilter = i
                refresh()
            }
        }
        sortChip = addChip {
            Ui.pickOne(this, L.t("Sort"), sorts, sort.ordinal) { i ->
                sort = Dex.Sort.entries[i]
                refresh()
            }
        }
    }

    override fun items(query: String): List<Ui.Item> {
        val all = if (gen > 0) game.dex else if (national) dex.national else game.dex
        source = if (Ui.prefs(this).getInt("forms", 0) == 1) all else all.filter { !it.mon.form() }
        caught = Ui.caught(this)
        val keep: (Dex.Mon) -> Boolean = if (caughtFilter == 0) { _ -> true }
                                         else { m -> (m.num.toString() in caught) == (caughtFilter == 1) }
        return dex.pokedex(game, source, query, types, gens, keep, sort).map { e ->
            val m = e.mon
            val number = if (national) Ui.national(e.number) else "#%03d".format(Locale.ROOT, e.number)
            val stat = sort.stat?.let { "${L.t(sort.label)} ${it(e)}" } ?: ""
            val have = m.num.toString() in caught
            Ui.Item.mon(m, dex.types(game, m), number, stat + if (have) (if (stat.isEmpty()) "" else "  ·  ") + L.t("Caught") else "")
        }
    }

    override fun count(shown: List<Ui.Item>): String {
        val have = source.count { it.mon.num.toString() in caught }
        // A generation's entries carry its region's name in the app's language.
        val name = if (gen > 0) game.dex[0].dex else if (national) L.t("National") else game.name
        return L.f("%s  ·  %d of %d  ·  caught %d", name, shown.size, source.size, have)
    }

    override fun labels() {
        dexChip.text = if (national) L.t("National") else L.t("Regional")
        Ui.chipActive(dexChip, national)
        typeChip.text = if (types.isEmpty()) L.t("Type") else types.joinToString(" / ") { dex.label(it) }
        Ui.chipActive(typeChip, types.isNotEmpty())
        genChip.text = if (gens.isEmpty()) L.t("Gen") else L.f("Gen %s", gens.joinToString(","))
        Ui.chipActive(genChip, gens.isNotEmpty())
        caughtChip.text = if (caughtFilter == 0) L.t("Caught") else L.t(CAUGHT[caughtFilter])
        Ui.chipActive(caughtChip, caughtFilter != 0)
        sortChip.text = L.f("Sort: %s", L.t(sort.label))
        Ui.chipActive(sortChip, sort != Dex.Sort.NUMBER)
    }

    override fun open(item: Ui.Item, shown: List<Ui.Item>) {
        Ui.openMon(this, (item.ref as Dex.Mon).num, Ui.nums(shown))
    }

    companion object {
        private val CAUGHT = listOf(L.k("All"), L.k("Caught"), L.k("Not caught"))
    }
}
