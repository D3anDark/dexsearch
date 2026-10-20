package demo.dex

import android.app.Activity
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
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
 * game's card opening its Pokédex, a team builder card with Compare and Damage calc, and three-column reference tiles.
 * The page fills the screen without scrolling at 360 x 640 dp and up, an update banner included: the game card and
 * the tiles share the height left over. Only what cannot fit (landscape, the largest fonts) scrolls.
 */
class MainActivity : Activity() {
    private val results = Ui.Adapter()
    private lateinit var dex: Dex
    private lateinit var game: Dex.Game
    private lateinit var search: EditText
    private lateinit var resultList: AbsListView
    private lateinit var tileScroll: ScrollView
    private lateinit var content: LinearLayout
    /** Published versionCode newer than this build, from [Update.check]; 0 when none. */
    private var newer = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        dex = Ui.dex(this)
        if (state == null && intent.getBooleanExtra(SettingsActivity.REOPEN, false)) startActivity(Intent(this, SettingsActivity::class.java))

        search = Ui.search(this, L.t("Pokémon, moves, abilities, types"), this::searchChanged)
        resultList = Ui.list(this, false)
        resultList.adapter = results
        resultList.setOnItemClickListener { _, _, i, _ ->
            val ref = results.getItem(i).ref
            if (ref is Dex.Mon) Ui.openMon(this, ref.num, Ui.nums(results.items())) else Ui.open(this, ref)
        }
        content = Ui.vertical(this)
        content.setPadding(0, 0, 0, Ui.dp(this, 4))
        tileScroll = ScrollView(this)
        tileScroll.isFillViewport = true
        tileScroll.addView(content)

        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(Ui.title(this, "DexSearch"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val settings = Ui.chip(this, L.t("Settings")) { startActivity(Intent(this, SettingsActivity::class.java)) }
        head.addView(settings, Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 4, 16, 0))

        val root = Ui.vertical(this)
        root.addView(head)
        root.addView(search, Ui.wide(this, 0, 10))
        val fill = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        root.addView(resultList, fill)
        root.addView(tileScroll, fill)
        Ui.show(this, root)
        // Also when restored: granting "install unknown apps" for an update restarts the process.
        Update.check(this) { code ->
            newer = code
            refresh()
        }
    }

    /** The workspace switch, in the game card: a workspace is what that card shows, its game and caught marks. */
    private fun spacePill(): TextView {
        val pill = Ui.glassPill(this, L.f("Workspace: %s", Ui.spaceName(this)))
        Ui.glassButton(pill) { workspaces() }
        pill.ellipsize = TextUtils.TruncateAt.END
        val more = Ui.Chevron(Ui.WHITE, false)
        more.setBounds(0, 0, Ui.dp(this, 10), Ui.dp(this, 10))
        pill.setCompoundDrawablesRelative(null, null, more, null)
        pill.compoundDrawablePadding = Ui.dp(this, 6)
        return pill
    }

    /** Counts and sprites depend on the game, "caught", teams and the artwork style, all changed on other screens. */
    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        game = Ui.game(this)
        content.removeAllViews()
        val banner = GamesActivity.card(this, game, L.t("Change game"), caught(), spacePill()) {
            startActivity(Intent(this, GamesActivity::class.java))
        }
        banner.setOnClickListener { startActivity(Intent(this, PokedexActivity::class.java)) }
        content.addView(banner, grow(12))
        content.addView(teams(), Ui.wide(this, 0, 12))

        val tms = dex.tmGame(game)
        val names = arrayOf(L.t("Moves"), L.t("TMs"), L.t("Abilities"), L.t("Items"), L.t("Type chart"), L.t("Natures"))
        val sp = titleSize(names)
        tiles(tile(names[0], sp, count(dex.moves(game).size), "fire", Intent(this, MovesActivity::class.java)),
            if (tms.virtual) tile(names[1], sp, words(L.t("Pick a game")), "water", Intent(this, GamesActivity::class.java))
            else tile(names[1], sp, if (tms.tms.isEmpty()) words(L.t("None here")) else count(tms.tms.size), "water",
                Intent(this, MovesActivity::class.java).putExtra("tm", true)),
            tile(names[2], sp, count(dex.abilities.size), "psychic", Intent(this, AbilitiesActivity::class.java)))
        tiles(tile(names[3], sp, count(dex.items.size), "electric", Intent(this, ItemsActivity::class.java)),
            tile(names[4], sp, words(L.f("%d types", dex.types(game).size)), "dragon", Intent(this, TypeChartActivity::class.java)),
            tile(names[5], sp, words(L.f("%d natures", dex.natures.size)), "ground", Intent(this, NaturesActivity::class.java)))
        // Last: the check answers after the home is drawn, and above the cards it pushed them under a finger on its way.
        if (newer > 0) content.addView(update(), Ui.wide(this, 0, 0))
        searchChanged()
    }

    /** Tap one to switch to it. New starts one in the current game and opens the game picker; Delete asks first. */
    private fun workspaces() {
        val spaces = Ui.spaces(this)
        val k = spaces.indexOfFirst { it.first == Ui.space(this) }
        val switch = { id: Int -> Ui.prefs(this).edit().putInt("space", id).apply() }
        val s = Ui.pickOne(this, L.t("Workspaces"), spaces.map { it.second }, k) { i ->
            switch(spaces[i].first)
            refresh()
        }
        val note = Ui.muted(this, L.t("Each workspace is a separate playthrough with its own caught marks, teams and game."), 14)
        note.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0)
        s.body.addView(note, 0, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 0, 0, 12))
        if (spaces.size > 1) s.button(L.t("Delete")) { delete(spaces, k) }
        s.button(L.t("Rename")) {
            Ui.askName(this, L.t("Workspace name"), spaces[k].second) { name ->
                Ui.saveSpaces(this, spaces.map { if (it.first == spaces[k].first) it.first to name else it })
                refresh()
            }
        }
        s.button(L.t("New"), main = true) {
            Ui.askName(this, L.t("New workspace"), "") { name ->
                val id = spaces.maxOf { it.first } + 1
                Ui.saveSpaces(this, spaces + (id to name))
                Ui.prefs(this).edit().putInt(Ui.key(this, "game", id), game.id).apply()
                switch(id)
                startActivity(Intent(this, GamesActivity::class.java))
            }
        }
    }

    private fun delete(spaces: List<Pair<Int, String>>, k: Int) {
        val id = spaces[k].first
        Ui.confirm(this, L.f("Delete %s?", spaces[k].second),
            L.t("Its caught marks, teams and selected game are removed from this device. This cannot be undone."), L.t("Delete")) {
            val rest = spaces.filter { it.first != id }
            val e = Ui.prefs(this).edit()
            // "box" holds sets kept by an older version.
            for (base in arrayOf("caught", "teams", "box", "game")) e.remove(Ui.key(this, base, id))
            e.putInt("space", rest[0].first).apply()
            Ui.saveSpaces(this, rest)
            refresh()
        }
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

    /**
     * Team builder across the page: team count and the newest team's members, the one most likely being built, on the
     * right; below, Compare and Damage calc, the tools used while building. Sprites decode at 128 px, 64 KB each, on
     * every return to this screen (the style may have changed).
     */
    private fun teams(): View {
        val teams = TeamsActivity.load(this)
        val color = Ui.color("fighting")
        val card = Ui.vertical(this)
        card.background = Ui.pressable(this, Ui.rounded(this, Ui.tonal(color), 18), 18)
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12))
        card.setOnClickListener { startActivity(Intent(this, TeamsActivity::class.java)) }

        val texts = Ui.vertical(this)
        texts.addView(Ui.text(this, L.t("Team builder"), 15, true))
        val line = if (teams.isEmpty()) words(L.t("Plan a team")) else count(L.f("Teams: %d", teams.size))
        texts.addView(Ui.text(this, line(color), 14, false),
            Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 2, 0, 0))
        val nums = teams.lastOrNull { it.members.isNotEmpty() }?.members?.map { it.num } ?: emptyList()
        val size = 44
        val art = LinearLayout(this)
        art.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        for ((k, num) in nums.withIndex()) {
            val img = ImageView(this)
            img.setImageDrawable(Ui.sprite(this, num, 2))
            art.addView(img, Ui.margins(Ui.dp(this, size), Ui.dp(this, size), this, if (k == 0) 0 else -14, 0, 0, 0))
        }
        val top = FrameLayout(this)
        top.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL))
        val artWidth = if (nums.isEmpty()) 0 else 8 + size + (size - 14) * (nums.size - 1)
        top.addView(texts, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL).apply { marginEnd = Ui.dp(this@MainActivity, artWidth) })
        card.addView(top)

        val tools = LinearLayout(this)
        tools.addView(Ui.chip(this, L.t("Compare")) { startActivity(Intent(this, CompareActivity::class.java)) })
        tools.addView(Ui.chip(this, L.t("Damage calc")) { startActivity(Intent(this, CalcActivity::class.java)) },
            Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 8, 0, 0, 0))
        card.addView(tools, Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 10, 0, 0))
        return card
    }

    /** One line washed in green: the published version; a tap downloads and installs it. */
    private fun update(): View {
        val color = Ui.color("grass")
        val head = L.t("Update available")
        val s = head + "  " + L.f("Version %d", newer)
        val t = Ui.text(this, big(s, head.length, s.length, color, 1f), 15, true)
        t.background = Ui.pressable(this, Ui.rounded(this, Ui.tonal(color), 18), 18)
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12))
        val more = Ui.Chevron(Ui.ink(color), false)
        more.setBounds(0, 0, Ui.dp(this, 10), Ui.dp(this, 10))
        t.setCompoundDrawablesRelative(null, null, more, null)
        // A tap in its first half second was aimed at a tile it just squeezed, not at installing.
        val shown = SystemClock.uptimeMillis()
        t.setOnClickListener { if (SystemClock.uptimeMillis() - shown > 500) Update.install(this, newer) }
        return t
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

    /** Full width minus the side margins, `bottom` dp below, plus an equal share of the height the page has left. */
    private fun grow(bottom: Int): LinearLayout.LayoutParams =
        Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 16, 0, 16, bottom).apply { weight = 1f }

    private fun tiles(vararg views: View) {
        val row = LinearLayout(this)
        for ((k, v) in views.withIndex()) {
            row.addView(v, Ui.margins(0, ViewGroup.LayoutParams.MATCH_PARENT, this, if (k == 0) 0 else 6, 0,
                if (k == views.size - 1) 0 else 6, 0).apply { weight = 1f })
        }
        content.addView(row, grow(12))
    }

    /** Neutral tile washed in `type`'s color: its title at `sp` up to two lines, a [count] or [words] at the bottom. */
    private fun tile(title: String, sp: Float, line: (Int) -> CharSequence, type: String, open: Intent): View {
        val color = Ui.color(type)
        val t = Ui.vertical(this)
        t.background = Ui.pressable(this, Ui.rounded(this, Ui.tonal(color), 18), 18)
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10))
        val name = Ui.text(this, title, 15, true)
        name.textSize = sp
        name.maxLines = 2
        name.ellipsize = TextUtils.TruncateAt.END
        t.addView(name)
        // The line takes the spare height and sits at its bottom; a bare View spacer would claim the whole height
        // when the page is first measured without a limit and squeeze the game card to nothing.
        val bottom = Ui.text(this, line(color), 14, false)
        bottom.gravity = Gravity.BOTTOM or Gravity.START
        t.addView(bottom, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, 0, this, 0, 2, 0, 0).apply { weight = 1f })
        t.setOnClickListener { startActivity(open) }
        return t
    }

    /**
     * Size of the tile titles: the largest from 15 down to 11 sp at which every word of every title fits a third of
     * the width, so a long title wraps between words, never inside one.
     */
    private fun titleSize(titles: Array<String>): Float {
        val m = resources.displayMetrics
        val inner = (m.widthPixels - Ui.dp(this, 32 + 2 * 12)) / 3f - Ui.dp(this, 24)
        val p = Paint()
        p.typeface = Typeface.DEFAULT_BOLD
        var sp = 15f
        while (sp > 11f) {
            p.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, m)
            if (titles.all { t -> t.split(' ').all { p.measureText(it) <= inner } }) break
            sp--
        }
        return sp
    }

    /** A number at 1.6 times the size (22 sp, large text), bold, in the tile's ink. */
    private fun count(n: Int): (Int) -> CharSequence = count(n.toString())

    /** "Teams: 3": its number as in [count], wherever the language puts it. */
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
