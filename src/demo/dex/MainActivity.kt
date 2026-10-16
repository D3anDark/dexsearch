package demo.dex

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Home: title with Settings, one search box across Pokémon, moves, abilities, items and types, then the selected
 * game's card opening its Pokédex, a team builder card and two-column reference tiles. Sized to fit 360 x 780 dp
 * without scrolling; larger fonts scroll.
 */
class MainActivity : Activity() {
    private val results = Ui.Adapter()
    private lateinit var dex: Dex
    private lateinit var game: Dex.Game
    private lateinit var search: EditText
    private lateinit var resultList: AbsListView
    private lateinit var tileScroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var spaceChip: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        dex = Ui.dex(this)

        search = Ui.search(this, L.t("Pokémon, moves, abilities, types"), this::searchChanged)
        resultList = Ui.list(this, false)
        resultList.adapter = results
        resultList.setOnItemClickListener { _, _, i, _ ->
            val ref = results.getItem(i).ref
            if (ref is Dex.Mon) Ui.openMon(this, ref.num, Ui.nums(results.items())) else Ui.open(this, ref)
        }
        content = Ui.vertical(this)
        content.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 16))
        tileScroll = ScrollView(this)
        tileScroll.addView(content)

        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(Ui.title(this, "DexSearch"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val settings = Ui.chip(this, L.t("Settings")) { startActivity(Intent(this, SettingsActivity::class.java)) }
        head.addView(settings, Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 4, 16, 0))

        // Under the title: beside it, a long workspace name squeezed "DexSearch" onto two lines.
        spaceChip = Ui.chip(this, "") { workspaces() }
        spaceChip.maxWidth = Ui.dp(this, 240)
        spaceChip.ellipsize = TextUtils.TruncateAt.END
        val more = Ui.Chevron(Ui.TEXT, false)
        more.setBounds(0, 0, Ui.dp(this, 12), Ui.dp(this, 12))
        spaceChip.setCompoundDrawablesRelative(null, null, more, null)
        spaceChip.compoundDrawablePadding = Ui.dp(this, 6)

        val root = Ui.vertical(this)
        root.addView(head)
        root.addView(spaceChip, Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 16, 0, 16, 0))
        root.addView(search, Ui.wide(this, 0, 10))
        val fill = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        root.addView(resultList, fill)
        root.addView(tileScroll, fill)
        Ui.show(this, root)
    }

    /** Counts and sprites depend on the game, "caught", teams and the artwork style, all changed on other screens. */
    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        game = Ui.game(this)
        spaceChip.text = Ui.spaceName(this)
        spaceChip.contentDescription = L.f("Workspace: %s", spaceChip.text)
        content.removeAllViews()
        val banner = GamesActivity.card(this, game, L.t("Change game"), caught()) {
            startActivity(Intent(this, GamesActivity::class.java))
        }
        banner.setOnClickListener { startActivity(Intent(this, PokedexActivity::class.java)) }
        content.addView(banner, Ui.wide(this, 0, 12))
        content.addView(teams(), Ui.wide(this, 0, 12))

        val tms = dex.tmGame(game)
        tiles(tile(L.t("Moves"), count(L.f("%d moves", dex.moves(game).size)), "fire", Intent(this, MovesActivity::class.java)),
            if (tms.virtual) tile(L.t("TMs"), words(L.t("Pick a game")), "water", Intent(this, GamesActivity::class.java))
            else tile(L.t("TMs"), if (tms.tms.isEmpty()) words(L.t("None here")) else count(L.f("%d machines", tms.tms.size)), "water",
                Intent(this, MovesActivity::class.java).putExtra("tm", true)))
        tiles(tile(L.t("Abilities"), count(L.f("%d abilities", dex.abilities.size)), "psychic", Intent(this, AbilitiesActivity::class.java)),
            tile(L.t("Items"), count(L.f("%d items", dex.items.size)), "electric", Intent(this, ItemsActivity::class.java)))
        tiles(tile(L.t("Type chart"), count(L.f("%d types", dex.types(game).size)), "dragon", Intent(this, TypeChartActivity::class.java)),
            tile(L.t("Natures"), count(L.f("%d natures", dex.natures.size)), "ground", Intent(this, NaturesActivity::class.java)))
        searchChanged()
    }

    /** Tap one to switch to it. New starts one in the current game and opens the game picker; Delete asks first. */
    private fun workspaces() {
        val spaces = Ui.spaces(this)
        val k = spaces.indexOfFirst { it.first == Ui.space(this) }
        val switch = { id: Int -> Ui.prefs(this).edit().putInt("space", id).apply() }
        val b = AlertDialog.Builder(this).setTitle(L.t("Workspaces"))
            .setSingleChoiceItems(spaces.map { it.second }.toTypedArray(), k) { d, i ->
                switch(spaces[i].first)
                d.dismiss()
                refresh()
            }
            .setPositiveButton(L.t("New")) { _, _ ->
                Ui.askName(this, L.t("New workspace"), "") { name ->
                    val id = spaces.maxOf { it.first } + 1
                    Ui.saveSpaces(this, spaces + (id to name))
                    Ui.prefs(this).edit().putInt(Ui.key(this, "game", id), game.id).apply()
                    switch(id)
                    startActivity(Intent(this, GamesActivity::class.java))
                }
            }
            .setNeutralButton(L.t("Rename")) { _, _ ->
                Ui.askName(this, L.t("Workspace name"), spaces[k].second) { name ->
                    Ui.saveSpaces(this, spaces.map { if (it.first == spaces[k].first) it.first to name else it })
                    refresh()
                }
            }
        if (spaces.size > 1) b.setNegativeButton(L.t("Delete")) { _, _ -> delete(spaces, k) }
        b.show()
    }

    private fun delete(spaces: List<Pair<Int, String>>, k: Int) {
        val id = spaces[k].first
        AlertDialog.Builder(this).setTitle(L.f("Delete %s?", spaces[k].second))
            .setMessage(L.t("Its caught marks, teams and selected game are removed from this device. This cannot be undone."))
            .setPositiveButton(L.t("Delete")) { _, _ ->
                val rest = spaces.filter { it.first != id }
                val e = Ui.prefs(this).edit()
                for (base in arrayOf("caught", "teams", "game")) e.remove(Ui.key(this, base, id))
                e.putInt("space", rest[0].first).apply()
                Ui.saveSpaces(this, rest)
                refresh()
            }
            .setNegativeButton(L.t("Cancel"), null)
            .show()
    }

    /** Caught progress of the game's species, white for its card. */
    private fun caught(): View {
        val species = game.dex.filter { !it.mon.form() }
        val have = Ui.caught(this)
        val caught = species.count { it.mon.num.toString() in have }
        val v = Ui.vertical(this)
        v.addView(progress(caught, species.size), Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 8, 0, 0))
        v.addView(Ui.white(this, L.f("%d of %d caught", caught, species.size), 12, false),
            Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 4, 0, 0))
        return v
    }

    /** Wide tile: team count and the newest team's members, the one most likely being built. */
    private fun teams(): View {
        val teams = TeamsActivity.load(this)
        val color = Ui.color("fighting")
        val texts = Ui.vertical(this)
        texts.addView(Ui.text(this, L.t("Team builder"), 15, true))
        val line = if (teams.isEmpty()) words(L.t("Plan a team")) else count(L.f("Teams: %d", teams.size))
        texts.addView(Ui.text(this, line(color), 14, false),
            Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 2, 0, 0))
        val members = teams.lastOrNull { it.members.isNotEmpty() }?.members ?: emptyList()
        return wide(color, Intent(this, TeamsActivity::class.java), texts, members.map { it.num }, 44)
    }

    /**
     * Tile across the page washed in `color`: `texts` on the left, the `nums` sprites overlapping on the right at
     * `size` dp. Sprites decode at 128 px, 64 KB each, on every return to this screen (the style may have changed).
     */
    private fun wide(color: Int, open: Intent, texts: LinearLayout, nums: List<Int>, size: Int): View {
        val f = FrameLayout(this)
        f.background = Ui.pressable(this, Ui.rounded(this, Ui.tonal(color), 18), 18)
        f.setOnClickListener { startActivity(open) }
        val art = LinearLayout(this)
        art.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        for ((k, num) in nums.withIndex()) {
            val img = ImageView(this)
            img.setImageDrawable(Ui.sprite(this, num, 2))
            art.addView(img, Ui.margins(Ui.dp(this, size), Ui.dp(this, size), this, if (k == 0) 0 else -14, 0, 0, 0))
        }
        f.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = Ui.dp(this@MainActivity, 10) })
        texts.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12))
        val artWidth = if (nums.isEmpty()) 0 else 10 + size + (size - 14) * (nums.size - 1)
        f.addView(texts, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = Ui.dp(this@MainActivity, artWidth) })
        return f
    }

    /** 6 dp white bar, `done` of `total` filled. */
    private fun progress(done: Int, total: Int): View {
        val track = LinearLayout(this)
        track.background = Ui.rounded(this, Ui.alpha(Ui.WHITE, 0x40), 999)
        track.weightSum = maxOf(total, 1).toFloat()
        val bar = View(this)
        bar.background = Ui.rounded(this, Ui.WHITE, 999)
        track.addView(bar, LinearLayout.LayoutParams(0, Ui.dp(this, 6), done.toFloat()))
        return track
    }

    private fun tiles(vararg views: View) {
        val row = LinearLayout(this)
        for ((k, v) in views.withIndex()) {
            row.addView(v, Ui.margins(0, ViewGroup.LayoutParams.MATCH_PARENT, this, if (k == 0) 0 else 6, 0,
                if (k == views.size - 1) 0 else 6, 0).apply { weight = 1f })
        }
        content.addView(row, Ui.wide(this, 0, 12))
    }

    /** Neutral tile washed in `type`'s color, title over a [count] or [words] line. */
    private fun tile(title: String, line: (Int) -> CharSequence, type: String, open: Intent): View {
        val color = Ui.color(type)
        val t = Ui.vertical(this)
        t.background = Ui.pressable(this, Ui.rounded(this, Ui.tonal(color), 18), 18)
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12))
        t.addView(Ui.text(this, title, 15, true))
        t.addView(Ui.text(this, line(color), 14, false),
            Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 2, 0, 0))
        t.setOnClickListener { startActivity(open) }
        return t
    }

    /** "919 moves": the number at 1.6 times the size (22 sp, large text), bold, in the tile's ink, wherever the language puts it. */
    private fun count(text: String): (Int) -> CharSequence = { color -> count(text, color) }

    private fun count(text: String, color: Int): CharSequence {
        val n = DIGITS.find(text)?.range ?: return text
        return big(text, n.first, n.last + 1, color, 1.6f)
    }

    /** A short phrase in place of a count, bold in the text color: ink at a size that meets 3:1 wraps in a tile. */
    private fun words(text: String): (Int) -> CharSequence = { _ ->
        SpannableString(text).apply { setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    /** Characters `start` until `end` of `text` at `scale` times the size, bold, in `color`'s ink. */
    private fun big(text: String, start: Int, end: Int, color: Int, scale: Float): CharSequence {
        val s = SpannableString(text)
        for (span in listOf<Any>(ForegroundColorSpan(Ui.ink(color)), RelativeSizeSpan(scale), StyleSpan(Typeface.BOLD))) {
            s.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return s
    }

    private fun searchChanged() {
        val q = search.text.toString().trim()
        val searching = q.isNotEmpty()
        resultList.visibility = if (searching) View.VISIBLE else View.GONE
        tileScroll.visibility = if (searching) View.GONE else View.VISIBLE
        if (!searching) return
        val items = ArrayList<Ui.Item>()
        for ((kind, hits) in dex.search(game, q, 20)) {
            items.add(Ui.Item.header(L.t(kind)))
            for (o in hits) items.add(item(o))
        }
        if (items.isEmpty()) items.add(Ui.Item.header(L.t("Nothing found")))
        results.set(items)
    }

    private fun item(o: Any): Ui.Item = when (o) {
        is Dex.Mon -> {
            val e = if (game.virtual) null else game.byNum[o.num]
            Ui.Item.mon(o, dex.types(game, o), Ui.national(o.species), o.genus + if (e == null) "" else "  ·  ${e.dex} #${e.number}")
        }
        is Dex.Move -> Ui.Item.move(o, dex.tmGame(game).tms[o.id] ?: "")
        is Dex.Ability -> Ui.Item(0, "", o.name, o.shortEffect, emptyList(), o)
        is Dex.Item -> Ui.Item(0, "", o.name, o.effect, emptyList(), o)
        else -> {
            val type = o as String
            Ui.Item(0, "", dex.label(type), L.t("Pokémon of this type"), listOf(type), type)
        }
    }

    companion object {
        private val DIGITS = Regex("[0-9]+")
    }
}
