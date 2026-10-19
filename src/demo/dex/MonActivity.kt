package demo.dex

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * One Pokémon: type-colored header, then tabs Info (forms and looks, flavor, abilities, stats, type defenses,
 * breeding), Evolution (the family tree), Moves and Builds (Smogon sets). The arrows by the number, or a horizontal
 * swipe on the header, walk the list the user came from. The tabs stay pinned at the top once scrolled past. Rows in the list part link to moves and
 * other Pokémon.
 */
class MonActivity : Activity() {
    private val adapter = Ui.Adapter()
    private lateinit var dex: Dex
    private lateinit var game: Dex.Game
    private var list: IntArray? = null
    private var num = 0
    private var tab = 0
    private var method = 0
    /** Smogon format whose sets the Builds tab shows, "" for the first one. */
    private var format = ""
    private lateinit var info: View
    private lateinit var evo: LinearLayout
    private lateinit var moveHead: View
    private lateinit var moveNote: TextView
    private lateinit var moveBar: LinearLayout
    private lateinit var buildBox: LinearLayout
    private lateinit var picture: ImageView
    /** Header shows the shiny coloring; kept while paging through Pokémon. */
    private var shiny = false
    private lateinit var shinyToggle: ImageView
    /** Look in the header picture, a looks/ file; null for the Pokémon itself. */
    private var worn: String? = null
    /** [forms] tiles, the one in the header shaded; empty when the Pokémon has a single look. */
    private var tiles: List<View> = emptyList()
    /** Species and form thumbnails in [forms] with their ids, redrawn when [shiny] flips. */
    private var formThumbs: List<Pair<ImageView, Int>> = emptyList()
    /** Look thumbnails in [forms] with their files, redrawn when [shiny] flips. */
    private var lookThumbs: List<Pair<ImageView, String>> = emptyList()
    private lateinit var tabs: Array<TextView>
    private lateinit var tabBar: LinearLayout
    /** Copy of the tabs over the top of the page, shown while [tabBar] is scrolled past. */
    private lateinit var pinned: View
    private lateinit var pinnedTabs: Array<TextView>
    private lateinit var page: ListView
    /** Blank end of [page], tall enough for [pinTabs] to scroll a short tab up under [pinned]; else 0. */
    private lateinit var spacer: View
    /** Status bar backdrop from [Ui.show]: hero color, the page color once the hero is scrolled away ([bare]). */
    private lateinit var strip: View
    private var bare = false
    private lateinit var head: View
    private lateinit var heroView: View
    /** The gesture began on the header, so a horizontal fling pages to the previous or next Pokémon. */
    private var swiping = false
    /** -1 or 1 once [swipe] has seen a fling, until [dispatchTouchEvent] acts on it. */
    private var flung = 0
    private val swipe by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || abs(vx) < 2 * abs(vy) || abs(e2.x - e1.x) < Ui.dp(this@MonActivity, 48)) return false
                flung = if (vx < 0) 1 else -1
                return true
            }
        })
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        dex = Ui.dex(this)
        game = Ui.game(this)
        list = intent.getIntArrayExtra("list")
        num = intent.getIntExtra("num", 1)
        if (state != null) {
            num = state.getInt("num", num)
            tab = state.getInt("tab")
            method = state.getInt("method")
            format = state.getString("format", "")
            shiny = state.getBoolean("shiny")
        }
        show(num, state?.getString("worn"))
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("num", num)
        out.putInt("tab", tab)
        out.putInt("method", method)
        out.putString("format", format)
        out.putBoolean("shiny", shiny)
        out.putString("worn", worn)
    }

    /** Rebuilds the page for Pokémon `n`, its header picture in `look` (a looks/ file) if given. */
    private fun show(n: Int, look: String? = null) {
        num = n
        worn = look
        val m = dex.mon(n)
        val color = color(m)
        val head = Ui.vertical(this)
        this.head = head
        heroView = hero(m, color)
        head.addView(heroView)

        tabBar = LinearLayout(this)
        tabs = Ui.segments(this, tabBar, Array(TABS.size) { L.t(TABS[it]) }, this::setTab)
        head.addView(tabBar, Ui.wide(this, 16, 12))
        info = info(m)
        head.addView(info)
        evo = Ui.vertical(this)
        head.addView(evo)
        val mh = Ui.vertical(this)
        moveNote = Ui.muted(this, "", 13)
        moveNote.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 8))
        mh.addView(moveNote)
        moveBar = LinearLayout(this)
        mh.addView(Ui.chipBar(this, moveBar))
        moveHead = mh
        head.addView(mh)
        buildBox = Ui.vertical(this)
        head.addView(buildBox)

        val lv = Ui.list(this, false) as ListView
        lv.setPadding(0, 0, 0, Ui.dp(this, 16))
        lv.addHeaderView(head, null, false)
        spacer = View(this)
        lv.addFooterView(spacer, null, false)
        lv.adapter = adapter
        lv.setOnItemClickListener { parent, _, i, _ ->
            val item = parent.getItemAtPosition(i) as? Ui.Item ?: return@setOnItemClickListener
            when (val ref = item.ref) {
                is Dex.Mon -> show(ref.num)
                else -> Ui.open(this, ref)
            }
        }
        page = lv
        val bar = LinearLayout(this)
        pinnedTabs = Ui.segments(this, bar, Array(TABS.size) { L.t(TABS[it]) }, this::setTab)
        val pin = FrameLayout(this)
        pin.setBackgroundColor(Ui.BG)
        pin.setPadding(Ui.dp(this, 16), Ui.dp(this, PIN), Ui.dp(this, 16), Ui.dp(this, 8))
        pin.addView(bar)
        pin.visibility = View.GONE
        pinned = pin
        bare = false
        lv.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView, state: Int) {}
            override fun onScroll(v: AbsListView, first: Int, count: Int, total: Int) {
                pin.visibility = if (tabGap() < 0) View.VISIBLE else View.GONE
                val past = first > 0 || head.isLaidOut && head.top + heroView.bottom <= 0
                if (past == bare) return
                bare = past
                // The hero is gone: a band of its color over the page looked like a stray bar.
                strip.setBackgroundColor(if (past) Ui.BG else color)
                val dark = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                window.insetsController?.setSystemBarsAppearance(if (past && !Ui.night) dark else 0, dark)
            }
        })
        val root = FrameLayout(this)
        root.addView(lv)
        root.addView(pin, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        strip = Ui.show(this, root, color)
        setTab(tab)
    }

    /** [tabBar]'s distance below the place where [pinned] shows its copy, negative once scrolled past it. */
    private fun tabGap() = when {
        page.firstVisiblePosition > 0 -> -1
        !head.isLaidOut -> 1  // every top is 0 before the first layout
        else -> head.top + tabBar.top - Ui.dp(this, PIN)
    }

    /**
     * Slides the page out the way the finger went, builds the neighbor's while it is hidden (the one long frame lands
     * there), then slides that in from the other side. [show] makes a new root, so each half animates its own. A second
     * step mid-way cancels the first: the end action only runs when its animation finishes.
     */
    private fun go(step: Int) {
        val n = neighbor(step)
        if (n == 0) return
        // Already the target, so a second swipe while this page slides out goes one further, as before the slide.
        num = n
        val old = page.parent as View
        val shift = step * Ui.dp(this, 96f).toFloat()
        old.animate().cancel()
        old.animate().translationX(-shift).alpha(0f).setDuration(140).setInterpolator(AccelerateInterpolator()).withEndAction {
            show(n)
            val v = page.parent as View
            v.translationX = shift
            v.alpha = 0f
            v.animate().translationX(0f).alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        }.start()
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val r = Rect()
            val x = e.x.toInt()
            val y = e.y.toInt()
            swiping = heroView.getGlobalVisibleRect(r) && r.contains(x, y)
        }
        if (!swiping || !swipe.onTouchEvent(e) || flung == 0) return super.dispatchTouchEvent(e)
        // The button or chip under the finger gets a cancel, not a click.
        e.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(e)
        val step = flung
        flung = 0
        swiping = false
        go(step)
        return true
    }

    private fun hero(m: Dex.Mon, color: Int): View {
        val h = Ui.hero(this, color)
        // One line each, so the hero keeps its height and the arrows their place on every page: a form's full name
        // ("Paldean Tauros (Combat Breed)") wrapped to three. Big, the species, shrunk if a narrow screen needs it;
        // small, the form's name ahead of the genus. Not baseline-aligned: that sat the number on the name's baseline,
        // 6 dp below the arrows.
        val nameRow = LinearLayout(this)
        nameRow.isBaselineAligned = false
        val side = Ui.dp(this, 40)
        val name = Ui.white(this, dex.mon(m.species).name, 30, true)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        name.setAutoSizeTextTypeUniformWithConfiguration(18, 30, 1, TypedValue.COMPLEX_UNIT_SP)
        name.gravity = Gravity.CENTER_VERTICAL
        nameRow.addView(name, LinearLayout.LayoutParams(0, side, 1f))
        nameRow.addView(navButton(neighbor(-1), -1), LinearLayout.LayoutParams(side, side))
        val number = Ui.white(this, Ui.national(m.species), 17, true)
        number.fontFeatureSettings = "tnum"
        number.gravity = Gravity.CENTER_VERTICAL
        nameRow.addView(number, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, side))
        nameRow.addView(navButton(neighbor(1), 1), LinearLayout.LayoutParams(side, side))
        // Inside the hero's padding: past it the hero clipped the next arrow's ripple. It lines up with the shiny toggle.
        h.addView(nameRow, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 10, 0, 0))
        val genus = Ui.white(this, if (m.form()) "${m.name}  ·  ${m.genus}" else m.genus, 15, false)
        genus.maxLines = 1
        genus.ellipsize = TextUtils.TruncateAt.END
        h.addView(genus)

        val pills = LinearLayout(this)
        pills.gravity = Gravity.CENTER_VERTICAL
        for (t in dex.types(game, m)) {
            val p = Ui.typeGlass(this, t)
            p.textSize = 13f
            p.setPadding(Ui.dp(this, 14), Ui.dp(this, 5), Ui.dp(this, 14), Ui.dp(this, 5))
            p.setOnClickListener { Ui.open(this, t) }
            pills.addView(p, Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 0, 6, 0))
        }
        pills.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        shinyToggle = ImageView(this)
        shinyToggle.contentDescription = L.t("Shiny")
        shinyToggle.tooltipText = L.t("Shiny")
        val pad = Ui.dp(this, 10)
        shinyToggle.setPadding(pad, pad, pad, pad)
        paintShiny(color)
        pills.addView(shinyToggle, LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)))
        h.addView(pills, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 8, 0, 0))

        picture = ImageView(this)
        picture.setImageDrawable(pictured(m))
        h.addView(picture, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 200)))
        shinyToggle.setOnClickListener { flipShiny(m) }
        // The whole Pokédex has no regional number to show.
        if (game !== dex.whole) {
            val regional = game.byNum[m.num]
            val where = Ui.white(this, if (regional == null) L.f("Not in %s", game.name)
                                       else L.f("%s #%d  ·  %s", regional.dex, regional.number, game.name), 13, true)
            where.gravity = Gravity.CENTER
            h.addView(where, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val actions = LinearLayout(this)
        val caught = action("")
        paintCaught(caught, m.num.toString() in Ui.caught(this), color)
        caught.setOnClickListener {
            val on = m.num.toString() !in Ui.caught(this)
            Ui.setCaught(this, m.num, on)
            paintCaught(caught, on, color)
        }
        val team = action(L.t("Add to team"))
        team.background = Ui.pressable(this, Ui.rounded(this, 0x2E000000, 999), 999)
        team.setOnClickListener { addToTeam(m) }
        actions.addView(caught, Ui.margins(0, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 0, 5, 0).apply { weight = 1f })
        actions.addView(team, Ui.margins(0, ViewGroup.LayoutParams.WRAP_CONTENT, this, 5, 0, 0, 0).apply { weight = 1f })
        h.addView(actions, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 14, 0, 0))
        return h
    }

    /** White arrow beside the number to the previous/next Pokémon; hidden at either end, keeping the number in place. */
    private fun navButton(target: Int, step: Int): View {
        val b = ImageView(this)
        val pad = Ui.dp(this, 12)
        b.setPadding(pad, pad, pad, pad)
        b.setImageDrawable(Ui.Chevron(Ui.WHITE, step < 0))
        b.background = Ui.pressable(this, null, 999)
        if (target == 0) {
            b.visibility = View.INVISIBLE
            return b
        }
        val name = dex.mon(target).name
        b.contentDescription = if (step < 0) L.f("Previous: %s", name) else L.f("Next: %s", name)
        b.tooltipText = name
        b.setOnClickListener { go(step) }
        return b
    }

    /**
     * Card of tiles: the species and its forms, each opening its page, then looks that only change the header picture
     * (female Venusaur, Alcremie's 63); the one in the header shaded. A form without looks of its own keeps its
     * species' (Mega Venusaur's card still has female Venusaur, opening her page). Null for a single look. Past [FOLD] tiles only
     * the first [FOLD] show until "Show all", unless the shaded one is further on. Thumbnails decode at 128 px, 64 KB
     * each, when their row shows: 0.5 MB folded, 4 MB for all of Alcremie's.
     */
    private fun forms(m: Dex.Mon): View? {
        tiles = emptyList()
        formThumbs = emptyList()
        lookThumbs = emptyList()
        val family = dex.family(m)
        val owner = if (dex.looks(m.num).isNotEmpty()) m else family[0]
        val looks = dex.looks(owner.num)
        if (family.size + maxOf(looks.size - 1, 0) < 2) return null
        val card = Ui.panel(this, L.t("Forms and looks"))
        val grid = Ui.vertical(this)
        card.addView(grid)
        val all = ArrayList<View>()
        val add = { name: String, l: View.OnClickListener ->
            if (all.size % COLS == 0) grid.addView(LinearLayout(this), Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, -6, 0, -6, 0))
            tile(grid.getChildAt(grid.childCount - 1) as LinearLayout, name, l).also { all.add(it.parent as View) }
        }
        // owner's own look is named in looks ("Male", "Curly Form") when it has others
        formThumbs = family.map { x ->
            add(if (x === owner && looks.isNotEmpty()) looks[0].second else x.name) { v -> if (x === m) wear(v, null) else show(x.num) } to x.num
        }
        lookThumbs = looks.drop(1).map { (file, name) -> add(name) { v -> if (owner === m) wear(v, file) else show(owner.num, file) } to file }
        // Spacers keep the last row's tiles a quarter wide; a fixed height would squash the row's tiles to it.
        val last = grid.getChildAt(grid.childCount - 1) as LinearLayout
        while (last.childCount < COLS) last.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        tiles = all
        val own = if (worn == null) family.indexOfFirst { it === m } else family.size + lookThumbs.indexOfFirst { it.second == worn }
        select(all[own])
        if (all.size > FOLD + COLS && own < FOLD) {
            for (i in FOLD / COLS until grid.childCount) grid.getChildAt(i).visibility = View.GONE
            val more = Ui.chip(this, L.f("Show all (%d)", all.size), null)
            more.setOnClickListener {
                for (i in 0 until grid.childCount) grid.getChildAt(i).visibility = View.VISIBLE
                card.removeView(more)
                paintThumbs()
            }
            card.addView(more, Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 8, 0, 0))
        }
        paintThumbs()
        return card
    }

    /** Draws the [forms] thumbnails in shown rows, in the [shiny] coloring; folded rows wait for "Show all". */
    private fun paintThumbs() {
        val shown = { img: View -> (img.parent.parent as View).visibility == View.VISIBLE }
        for ((img, n) in formThumbs) if (shown(img)) img.setImageDrawable(Ui.sprite(this, n, 2, shiny = shiny))
        for ((img, file) in lookThumbs) if (shown(img)) img.setImageDrawable(Ui.look(this, file, 2, shiny))
    }

    /** A [forms] tile in `row`, a quarter of it wide: thumbnail over `name`. Returns the thumbnail, to fill. */
    private fun tile(row: LinearLayout, name: String, l: View.OnClickListener): ImageView {
        val t = Ui.vertical(this)
        t.gravity = Gravity.CENTER_HORIZONTAL
        t.setPadding(Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8))
        t.contentDescription = name
        t.tooltipText = name
        t.setOnClickListener(l)
        val img = ImageView(this)
        t.addView(img, LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)))
        // A zero-width space lets "Mega-Venusaur" wrap after its hyphen; Android split it "Mega-Venus" / "aur".
        val label = Ui.muted(this, name.replace("-", "-\u200B"), 12)
        label.gravity = Gravity.CENTER
        // Three lines: on two, Alcremie's "Vanilla Cream Strawberry Sweet" and "...Star Sweet" both ended "Vanilla Cream St...".
        label.maxLines = 3
        label.ellipsize = TextUtils.TruncateAt.END
        t.addView(label, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 4, 0, 0))
        row.addView(t, Ui.margins(0, ViewGroup.LayoutParams.MATCH_PARENT, this, 2, 0, 2, 0).apply { weight = 1f })
        return img
    }

    /** Shades `tile` as the look in the header, like this Pokémon's row in the Evolution tab, and bolds its name. */
    private fun select(tile: View?) {
        for (t in tiles) {
            val on = t === tile
            t.isSelected = on
            t.background = Ui.pressable(this, if (on) Ui.rounded(this, Ui.FILL, 14) else null, 14)
            val label = (t as ViewGroup).getChildAt(1) as TextView
            label.setTextColor(if (on) Ui.TEXT else Ui.MUTED)
            label.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    /** Shows `look` (a looks/ file, null for the Pokémon itself) in the header picture and shades its `tile`. */
    private fun wear(tile: View?, look: String?) {
        worn = look
        picture.setImageDrawable(pictured(dex.mon(num)))
        select(tile)
        // A tile far down the card would change a picture scrolled out of sight.
        val r = Rect()
        if (!picture.getGlobalVisibleRect(r) || r.height() < picture.height) page.smoothScrollToPositionFromTop(0, 0)
    }

    private fun flipShiny(m: Dex.Mon) {
        shiny = !shiny
        paintShiny(color(m))
        paintThumbs()
        picture.setImageDrawable(pictured(m))
    }

    private fun action(label: String): TextView {
        val t = Ui.white(this, label, 14, true)
        t.gravity = Gravity.CENTER
        t.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10))
        return t
    }

    /** Picks a team (or starts one in the selected game), adds this Pokémon and opens it in the team builder. */
    /** @param member the new member, `m` with a Smogon set applied; null adds `m` with its first ability at level 50. */
    private fun addToTeam(m: Dex.Mon, member: Team.Member? = null) {
        val teams = TeamsActivity.load(this)
        val labels = teams.map { "${it.name}  (${it.members.size}/${Team.SIZE}, ${dex.game(it.game).name})" } + L.t("New team")
        Ui.pickOne(this, L.f("Add %s to", m.name), labels, -1) { i ->
            if (i == teams.size) teams.add(Team(L.f("Team %d", i + 1), game.id))
            val t = teams[i]
            if (t.members.size >= Team.SIZE) {
                Toast.makeText(this, L.f("%s already has %d Pokémon", t.name, Team.SIZE), Toast.LENGTH_SHORT).show()
                return@pickOne
            }
            t.members.add(member ?: TeamsActivity.member(m))
            TeamsActivity.save(this, teams)
            MemberActivity.open(this, i, t.members.size - 1)
        }
    }

    /** The header picture, 256 KB decoded: the [worn] look, else the Pokémon itself; shiny when switched on. */
    private fun pictured(m: Dex.Mon): Drawable? = worn?.let { Ui.look(this, it, 1, shiny) } ?: Ui.sprite(this, m.num, 1, shiny = shiny)

    /** Header color: the first type's, deepened for white text. */
    private fun color(m: Dex.Mon) = Ui.deep(Ui.color(dex.types(game, m)[0]))

    private fun paintShiny(color: Int) {
        shinyToggle.isSelected = shiny
        shinyToggle.background = Ui.pressable(this, Ui.rounded(this, if (shiny) Ui.WHITE else 0x2E000000, 999), 999)
        shinyToggle.setImageDrawable(Ui.Sparkle(if (shiny) color else Ui.WHITE))
    }

    private fun paintCaught(t: TextView, on: Boolean, color: Int) {
        t.text = if (on) L.t("Caught") else L.t("Mark caught")
        t.background = Ui.pressable(this, Ui.rounded(this, if (on) Ui.WHITE else 0x2E000000, 999), 999)
        t.setTextColor(if (on) color else Ui.WHITE)
    }

    /**
     * Pokémon before/after this one in the list the user came from, else in national order: also when that list holds
     * nothing but this species' forms (a search for "charizard"). 0 at either end.
     */
    private fun neighbor(step: Int): Int {
        val species = dex.mon(num).species
        val n = list?.takeIf { l -> l.any { dex.mon(it).species != species } }?.let { dex.neighbor(it, num, step) } ?: -1
        return if (n >= 0) n else dex.neighbor(dex.national.map { it.mon.num }.toIntArray(), num, step)
    }

    private fun setTab(t: Int) {
        tab = t
        Ui.segmentActive(tabs, t)
        Ui.segmentActive(pinnedTabs, t)
        info.visibility = if (t == 0) View.VISIBLE else View.GONE
        evo.visibility = if (t == 1) View.VISIBLE else View.GONE
        moveHead.visibility = if (t == 2) View.VISIBLE else View.GONE
        buildBox.visibility = if (t == 3) View.VISIBLE else View.GONE
        val m = dex.mon(num)
        if (t == 1 && evo.childCount == 0) evolutions(m)
        if (t == 3 && buildBox.childCount == 0) builds(m)
        if (t == 2) moves(m) else adapter.set(emptyList())
        spacer.minimumHeight = 0
        // Also when pinTabs put the tabs exactly there, which hides the pinned copy.
        if (tabGap() <= 0) pinTabs()
    }

    /**
     * Picked with the tabs at the top: the new tab starts right under them instead of at the old scroll position. A tab too
     * short to scroll that far (Evolution, a few sets) gets [spacer] for the difference, so the page does not jump
     * back to the hero.
     */
    private fun pinTabs() {
        val top = Ui.dp(this, PIN)
        page.setSelectionFromTop(0, top - tabBar.top)
        page.post {
            val short = head.top + tabBar.top - top
            if (page.firstVisiblePosition != 0 || short <= 0) return@post
            // Content shorter than the page also leaves a gap under its end.
            val gap = page.height - page.paddingBottom - page.getChildAt(page.childCount - 1).bottom
            spacer.minimumHeight = short + maxOf(0, gap)
            page.setSelectionFromTop(0, top - tabBar.top)
        }
    }

    private fun info(m: Dex.Mon): View {
        val box = Ui.vertical(this)
        forms(m)?.let { box.addView(it, Ui.wide(this, 0, 12)) }
        val seen = dex.info(game, m)
        val about = Ui.panel(this, null)
        about.addView(Ui.text(this, seen[1].ifEmpty { m.flavor }, 15, false))
        val facts = LinearLayout(this)
        facts.setPadding(0, Ui.dp(this, 14), 0, 0)
        val imperial = Ui.prefs(this).getInt("units", 0) == 1
        column(facts, Dex.height(m.height, imperial), L.t("Height"))
        column(facts, Dex.weight(m.weight, imperial), L.t("Weight"))
        column(facts, L.f("Gen %d", m.gen), L.t("Introduced"))
        about.addView(facts)
        box.addView(about, Ui.wide(this, 0, 12))
        if (seen[0].isNotEmpty()) {
            val where = Ui.panel(this, L.f("Where to find in %s", game.name))
            where.addView(Ui.text(this, seen[0], 15, false))
            box.addView(where, Ui.wide(this, 0, 12))
        }

        val abilities = Ui.panel(this, L.t("Abilities"))
        for (id in m.abilities) abilities.addView(abilityRow(dex.ability(id), false))
        if (m.hidden != 0) abilities.addView(abilityRow(dex.ability(m.hidden), true))
        box.addView(abilities, Ui.wide(this, 0, 12))

        val stats = Ui.panel(this, L.t("Base stats"))
        for ((i, s) in Dex.STATS.withIndex()) stats.addView(statRow(L.t(s), m.stats[i], true))
        stats.addView(statRow(L.t("Total"), m.bst, false))
        stats.addView(Ui.chip(this, L.t("Compare")) { CompareActivity.open(this, m.num, game.id) },
            Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 10, 0, 0))
        box.addView(stats, Ui.wide(this, 0, 12))

        val defenses = Ui.panel(this, L.t("Type defenses"))
        defenses.addView(defenses(this, dex, game, m, 4))
        box.addView(defenses, Ui.wide(this, 0, 12))

        val breeding = Ui.panel(this, L.t("Breeding and training"))
        fact(breeding, L.t("Catch rate"), m.captureRate.toString())
        fact(breeding, L.t("Base friendship"), m.happiness.toString())
        fact(breeding, L.t("Growth rate"), L.t(m.growth))
        fact(breeding, L.t("Egg groups"), m.eggGroups)
        fact(breeding, L.t("Egg cycles"), m.hatchCounter.toString())
        fact(breeding, L.t("Gender"), if (m.genderRate < 0) L.t("Genderless") else L.f("%.1f%% male, %.1f%% female",
            100 - m.genderRate * 12.5, m.genderRate * 12.5))
        box.addView(breeding, Ui.wide(this, 0, 4))
        return box
    }

    private fun column(row: LinearLayout, value: String, label: String) {
        val col = Ui.vertical(this)
        col.addView(Ui.text(this, value, 16, true))
        col.addView(Ui.muted(this, label, 12))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun abilityRow(a: Dex.Ability, hidden: Boolean): View {
        val row = Ui.vertical(this)
        row.background = Ui.pressable(this, null, 12)
        row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6))
        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(Ui.text(this, a.name, 15, true))
        if (hidden) {
            val tag = Ui.caption(this, L.t("Hidden"))
            tag.background = Ui.rounded(this, Ui.FILL, 999)
            tag.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2))
            top.addView(tag, Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 8, 0, 0, 0))
        }
        row.addView(top)
        row.addView(Ui.muted(this, a.shortEffect, 13))
        row.setOnClickListener { Ui.open(this, a) }
        return row
    }

    /** Label, value, and a rounded bar scaled to 255 (the highest possible base stat). */
    private fun statRow(label: String, value: Int, bar: Boolean): View {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4))
        row.addView(Ui.muted(this, label, 14), LinearLayout.LayoutParams(Ui.dp(this, 48), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(Ui.text(this, value.toString(), 14, true), LinearLayout.LayoutParams(Ui.dp(this, 44), ViewGroup.LayoutParams.WRAP_CONTENT))
        if (bar) {
            val track = LinearLayout(this)
            track.background = Ui.rounded(this, Ui.FILL, 999)
            track.weightSum = 255f
            val fill = View(this)
            val c = when {
                value < 50 -> 0xFFF34444
                value < 80 -> 0xFFFF7F0F
                value < 100 -> 0xFFFFC107
                value < 120 -> 0xFFA0E515
                else -> 0xFF23CD5E
            }
            fill.background = Ui.rounded(this, c.toInt(), 999)
            track.addView(fill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, value.toFloat()))
            row.addView(track, LinearLayout.LayoutParams(0, Ui.dp(this, 8), 1f))
        }
        return row
    }

    private fun fact(box: LinearLayout, key: String, value: String) {
        val row = LinearLayout(this)
        row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4))
        row.addView(Ui.muted(this, key, 14), LinearLayout.LayoutParams(Ui.dp(this, 130), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(Ui.text(this, value, 14, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)
    }

    /**
     * The family into [evo] as a tree: each Pokémon a step right of the one it evolves from, joined to it by a line,
     * with how it evolves. Applin's three apples hang side by side from Applin, Hydrapple under Dipplin; regional
     * forms start trees of their own beside it. Rows open their Pokémon; this one's is shaded, or its species' for a
     * form outside the tree (Mega Charizard). Sprites decode here, 64 KB each, so only when the tab opens.
     */
    private fun evolutions(m: Dex.Mon) {
        val rows = dex.chain(m)
        if (rows.size == 1) {
            val none = Ui.caption(this, L.f("%s does not evolve", m.name))
            none.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6))
            evo.addView(none)
        }
        val here = if (rows.any { it[0] == m.num }) m.num else m.species
        for ((i, r) in rows.withIndex()) {
            val x = dex.mon(r[0])
            val depth = r[1]
            // more[k]: a later row still hangs from this row's depth-k ancestor, so that line runs on down
            val more = BooleanArray(depth) { k -> rows.drop(i + 1).firstOrNull { it[1] <= k + 1 }?.get(1) == k + 1 }
            val kids = rows.getOrNull(i + 1)?.get(1) == depth + 1
            val cell = Branch(this, depth, more, kids)
            if (x.num == here) {
                cell.background = InsetDrawable(Ui.rounded(this, Ui.FILL, 14), Ui.dp(this, 8), 0, Ui.dp(this, 8), 0)
                cell.setPadding(0, 0, 0, 0)  // the inset would pad the row and move its sprite off the lines
            }
            val row = Ui.row(this)
            val alone = depth == 0 && !kids && rows.size > 1
            Ui.bind(row, Ui.Item.mon(x, dex.types(game, x), "", if (alone) L.t("Does not evolve") else how(dex.evo(x.num))))
            row.setPadding(Ui.dp(this, 16 + depth * INDENT), row.paddingTop, row.paddingRight, row.paddingBottom)
            row.setOnClickListener { show(x.num) }
            cell.addView(row)
            evo.addView(cell)
        }
    }

    /** "Male only  ·  Dawn Stone" with the gender bold in Water blue, Fairy pink for females; "" for a first stage. */
    private fun how(e: Dex.Evo?): CharSequence {
        if (e == null || e.gender == 0) return e?.how ?: ""
        val tag = if (e.gender == 1) L.t("Female only") else L.t("Male only")
        val s = SpannableStringBuilder(tag).append("  ·  ").append(e.how)
        s.setSpan(ForegroundColorSpan(Ui.ink(Ui.color(if (e.gender == 1) "fairy" else "water"))), 0, tag.length, 0)
        s.setSpan(StyleSpan(Typeface.BOLD), 0, tag.length, 0)
        return s
    }

    /** Smogon sets for this Pokémon and its forms in the selected game's format files, one format at a time. */
    private fun builds(m: Dex.Mon) {
        val sets = dex.builds(game, dex.family(m).mapTo(HashSet()) { it.num })
        val note = Ui.muted(this, if (sets.isEmpty()) L.f("No Smogon sets for %s in %s.", m.name, game.name) else L.f("Smogon sets for %s", game.name), 13)
        note.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 10))
        buildBox.addView(note)
        if (sets.isEmpty()) return
        buildBox.addView(MemberActivity.buildList(this, dex, game, sets, m.num, format, Ui.SURFACE, { b ->
            val row = LinearLayout(this)
            val add = Ui.chip(this, L.t("Add to team")) { addToTeam(dex.mon(b.num), MemberActivity.member(dex, b)) }
            Ui.chipActive(add, true)
            // Half the card each, wrapping to two lines: "Copiar para Showdown" overran the card on one.
            for ((i, chip) in listOf(add, Ui.chip(this, L.t("Copy for Showdown")) { copy(b) }).withIndex()) {
                chip.setSingleLine(false)
                chip.maxLines = 2
                row.addView(chip, Ui.margins(0, ViewGroup.LayoutParams.MATCH_PARENT, this, 4 * i, 0, 4 - 4 * i, 0).apply { weight = 1f })
            }
            row
        }) { format = it })
    }

    private fun copy(b: Dex.Build) {
        val one = Team(b.name, game.id)
        one.members.add(MemberActivity.member(dex, b))
        getSystemService(ClipboardManager::class.java)!!.setPrimaryClip(ClipData.newPlainText(b.name, one.export(dex)))
        // Android 13 and later show their own confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(this, L.t("Copied"), Toast.LENGTH_SHORT).show()
    }

    private fun moves(m: Dex.Mon) {
        val g = dex.learnGame(game, m)
        val cols = g?.let { dex.learnset(it, m) }
        moveBar.removeAllViews()
        if (g == null || cols == null) {
            moveNote.text = L.f("No move data for %s.", m.name)
            adapter.set(emptyList())
            return
        }
        moveNote.text = if (g === game || game.virtual) L.f("Moves in %s", g.name) else L.f("No %s moves for %s. Showing %s.", game.name, m.name, g.name)
        if (cols[method].isEmpty()) {
            val k = cols.indexOfFirst { it.isNotEmpty() }
            if (k >= 0) method = k
        }
        for ((k, col) in cols.withIndex()) {
            if (col.isEmpty()) continue
            val chip = Ui.chip(this, L.t(Dex.METHODS[k]) + "  " + (if (k == 0) col.size / 2 else col.size)) {
                method = k
                moves(m)
            }
            Ui.chipActive(chip, k == method)
            Ui.addChip(moveBar, chip)
        }
        // Moves as they were in g, the game the list is from: Red falls back to Gold for Snubbull, where Bite is Dark.
        val out = ArrayList<Ui.Item>()
        val col = cols[method]
        if (method == 0) {
            for (i in col.indices step 2) out.add(Ui.Item.move(dex.move(g, col[i + 1]), if (col[i] == 0) L.t("Evo") else L.f("Lv %d", col[i])))
        } else {
            val ms = col.map { dex.move(g, it) }.sortedBy { if (method == 1) g.tms[it.id] ?: "" else it.key }
            for (mv in ms) out.add(Ui.Item.move(mv, if (method == 1) g.tms[mv.id] ?: L.t("TM") else ""))
        }
        adapter.set(out)
    }

    /**
     * Tree lines behind an evolution row at `depth`: an elbow from the parent's sprite, a line on down at each level
     * `more` marks, and a stub from the sprite down to its own evolutions if `kids`. Sprites are 56 dp, 16 dp in plus
     * [INDENT] per level, as [evolutions] lays them out.
     */
    private class Branch(c: Context, private val depth: Int, private val more: BooleanArray, private val kids: Boolean) : FrameLayout(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val unit = c.resources.displayMetrics.density

        init {
            setWillNotDraw(false)
            paint.color = Ui.alpha(Ui.TEXT, 0x40)
            paint.strokeWidth = 2 * unit
        }

        /** Center of a sprite at `level`. */
        private fun x(level: Int) = (16 + 28 + level * INDENT) * unit

        override fun onDraw(canvas: Canvas) {
            val mid = height / 2f
            val bottom = height.toFloat()
            for (k in 0 until depth) {
                if (k == depth - 1) {
                    canvas.drawLine(x(k), 0f, x(k), if (more[k]) bottom else mid, paint)
                    canvas.drawLine(x(k), mid, x(depth) - 28 * unit, mid, paint)
                } else if (more[k]) {
                    canvas.drawLine(x(k), 0f, x(k), bottom, paint)
                }
            }
            if (kids) canvas.drawLine(x(depth), mid + 28 * unit, x(depth), bottom, paint)
        }
    }

    companion object {
        /** dp each evolution stage sits right of the one before. */
        private const val INDENT = 40
        /** dp from the top of the page to the pinned tabs. */
        private const val PIN = 8
        /** [forms] tiles per row, and how many show before "Show all" when there are over a row more. */
        private const val COLS = 4
        private const val FOLD = 8
        private val TABS = arrayOf(L.k("Info"), L.k("Evolution"), L.k("Moves"), L.k("Builds"))
        /** Attacking types under "Weak x4" down to "Immune", `perRow` pills a row; empty groups are left out. */
        fun defenses(a: Activity, dex: Dex, game: Dex.Game, m: Dex.Mon, perRow: Int): LinearLayout {
            val col = Ui.vertical(a)
            for ((i, f) in DEFENSE.withIndex()) {
                val hit = dex.types(game).filter { dex.factor(game, it, dex.types(game, m)) == f }
                if (hit.isEmpty()) continue
                val label = Ui.caption(a, L.t(DEFENSE_LABEL[i]))
                label.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 2))
                col.addView(label)
                col.addView(Ui.typePills(a, hit, perRow))
            }
            return col
        }

        private val DEFENSE = intArrayOf(400, 200, 50, 25, 0)
        private val DEFENSE_LABEL = arrayOf(L.k("Weak x4"), L.k("Weak x2"), L.k("Resists x1/2"), L.k("Resists x1/4"), L.k("Immune"))
    }
}
