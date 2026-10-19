package demo.dex

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
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
    /** Lowest base stat total shown, 0 for any. */
    private var minBst = 0
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
    private lateinit var bstChip: TextView
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
        typeChip = addChip { Ui.pickMany(this, L.t("Type (must have all)"), dex.types(game), types, this::refresh) }
        genChip = addChip { pickGens(allGens) }
        if (gen > 0) genChip.visibility = View.GONE
        caughtChip = addChip {
            Ui.pickOne(this, L.t("Caught"), CAUGHT.map { L.t(it) }, caughtFilter) { i ->
                caughtFilter = i
                refresh()
            }
        }
        bstChip = addChip {
            Ui.pickOne(this, L.t("Lowest base stat total"), BST.map { if (it == 0) L.t("All") else "$it+" }, BST.indexOf(minBst)) { i ->
                minBst = BST[i]
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
        val keep = { m: Dex.Mon -> m.bst >= minBst && (caughtFilter == 0 || (m.num.toString() in caught) == (caughtFilter == 1)) }
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
        bstChip.text = if (minBst == 0) L.t("BST") else L.f("BST %d+", minBst)
        Ui.chipActive(bstChip, minBst != 0)
        sortChip.text = L.f("Sort: %s", L.t(sort.label))
        Ui.chipActive(sortChip, sort != Dex.Sort.NUMBER)
    }

    /**
     * The generation filter: a tile per generation with its Grass, Fire and Water starters and region. Like
     * [Ui.pickMany], the list behind updates on every tap.
     */
    private fun pickGens(all: List<Int>) {
        val d = Ui.Sheet(this, L.t("Generation"))
        val box = d.body
        val tiles = ArrayList<LinearLayout>()
        val paint = { tile: LinearLayout, on: Boolean ->
            tile.background = Ui.choice(this, on, 16)
            tile.isSelected = on
        }
        lateinit var row: LinearLayout
        for ((i, g) in all.withIndex()) {
            if (i % COLS == 0) {
                row = LinearLayout(this)
                box.addView(row)
            }
            val tile = Ui.vertical(this)
            tile.gravity = Gravity.CENTER_HORIZONTAL
            tile.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10))
            val art = LinearLayout(this)
            art.gravity = Gravity.CENTER_HORIZONTAL
            for (k in 0..2) {
                val img = ImageView(this)
                img.setImageDrawable(Ui.sprite(this, Dex.STARTERS[g - 1] + 3 * k, 2))
                art.addView(img, LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)))
            }
            tile.addView(art)
            val number = Ui.text(this, L.f("Gen %d", g), 14, true)
            number.setPadding(0, Ui.dp(this, 4), 0, 0)
            number.gravity = Gravity.CENTER_HORIZONTAL
            tile.addView(number)
            val region = dex.regions[g - 1]
            val place = Ui.muted(this, region, 12)
            place.gravity = Gravity.CENTER_HORIZONTAL
            tile.addView(place)
            tile.contentDescription = L.f("Gen %d", g) + ", " + region
            paint(tile, g in gens)
            tile.setOnClickListener {
                if (!gens.add(g)) gens.remove(g)
                paint(tile, g in gens)
                refresh()
            }
            tiles.add(tile)
            row.addView(tile, Ui.margins(0, ViewGroup.LayoutParams.WRAP_CONTENT, this, 4, 4, 4, 4).apply { weight = 1f })
        }
        repeat((COLS - all.size % COLS) % COLS) {
            row.addView(View(this), Ui.margins(0, 1, this, 4, 4, 4, 4).apply { weight = 1f })
        }

        d.button(L.t("Clear"), close = false) {
            gens.clear()
            for (tile in tiles) paint(tile, false)
            refresh()
        }
        d.button(L.t("Done"), main = true)
        d.show()
    }

    override fun open(item: Ui.Item, shown: List<Ui.Item>) {
        Ui.openMon(this, (item.ref as Dex.Mon).num, Ui.nums(shown))
    }

    companion object {
        private val CAUGHT = listOf(L.k("All"), L.k("Caught"), L.k("Not caught"))
        /** Floors for the BST chip; 0 is any, 600 the pseudo-legendaries' total (Garchomp, Dragonite). */
        private val BST = listOf(0, 300, 400, 450, 500, 525, 550, 600)
        /** Generation tiles to a row: three keeps three starters at 28 dp in a tile on a 360 dp screen. */
        private const val COLS = 3
    }
}
