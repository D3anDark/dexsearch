package demo.dex

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Title, search field, a scrollable row of filter chips, a result count and the list. Filtering runs per keystroke. */
abstract class ListScreen : Activity() {
    lateinit var adapter: Ui.Adapter
    lateinit var dex: Dex
    lateinit var game: Dex.Game
    private lateinit var search: EditText
    private lateinit var bar: LinearLayout
    private lateinit var countView: TextView
    private lateinit var emptyView: TextView

    abstract fun heading(): String

    abstract fun hint(): String

    /** Reads intent extras and adds filter chips with [addChip]; runs before [hint]. */
    abstract fun setup()

    abstract fun items(query: String): List<Ui.Item>

    abstract fun count(shown: List<Ui.Item>): String

    /** Updates chip labels and highlights after a change. */
    open fun labels() {}

    /** Shown when the list is empty. */
    open fun empty() = L.t("Nothing matches. Change the search or the filters.")

    /** Two-column Pokédex cards instead of rows. */
    open fun cards() = false

    open fun open(item: Ui.Item, shown: List<Ui.Item>) {
        Ui.open(this, item.ref)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        dex = Ui.dex(this)
        game = Ui.game(this)
        adapter = Ui.Adapter(cards())
        bar = LinearLayout(this)
        setup()
        search = Ui.search(this, hint(), this::refresh)
        countView = Ui.caption(this, "")
        countView.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 6))
        val list = Ui.list(this, cards())
        list.adapter = adapter
        list.setOnItemClickListener { _, _, i, _ ->
            val shown = adapter.items()
            open(shown[i], shown)
        }
        emptyView = Ui.muted(this, "", 15)
        emptyView.gravity = Gravity.CENTER
        emptyView.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), 0)
        val body = FrameLayout(this)
        body.addView(list)
        body.addView(emptyView)
        list.emptyView = emptyView

        val root = Ui.vertical(this)
        root.addView(Ui.title(this, heading()))
        root.addView(search, Ui.wide(this, 0, 10))
        if (bar.childCount > 0) root.addView(Ui.chipBar(this, bar))
        root.addView(countView)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        Ui.show(this, root)
    }

    /** Also the first fill after onCreate; refreshing on resume picks up "caught" changes made on detail screens. */
    override fun onResume() {
        super.onResume()
        refresh()
    }

    fun refresh() {
        val shown = items(search.text.toString())
        adapter.set(shown)
        countView.text = count(shown)
        emptyView.text = empty()
        labels()
    }

    fun addChip(l: View.OnClickListener): TextView {
        val chip = Ui.chip(this, "", l)
        Ui.addChip(bar, chip)
        return chip
    }
}
