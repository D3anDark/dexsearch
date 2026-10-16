package demo.dex

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

/** Shared data access, navigation, the palette and view helpers. Everything runs on the main thread. */
object Ui {
    private var dex: Dex? = null

    /**
     * Also picks the palette for the current day/night mode; every screen calls this first in onCreate. Loads the UI
     * text and names again when the language changed (about 0.5 MB more for Japanese names than English).
     */
    fun dex(c: Context): Dex {
        theme(c)
        val lang = lang(c)
        dex?.let { if (L.lang == lang) return it }
        val assets = c.applicationContext.assets
        val open = Dex.Assets { assets.open(it) }
        L.load(open, lang)
        return Dex.load(open, lang).also { dex = it }
    }

    /** Language picked in Settings, else the device's if the app has it, else English: one of [L.LANGS]. */
    fun lang(c: Context): String {
        val pick = prefs(c).getString("lang", "")!!
        if (pick.isNotEmpty()) return pick
        val device = c.resources.configuration.locales[0].language
        return if (device in L.LANGS) device else "en"
    }

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("dex", Context.MODE_PRIVATE)

    /** Selected game, Scarlet / Violet until the user picks another. */
    fun game(c: Context): Dex.Game = dex(c).game(prefs(c).getInt(key(c, "game"), 25))

    fun caught(c: Context): Set<String> = prefs(c).getStringSet(key(c, "caught"), emptySet())!!

    fun setCaught(c: Context, num: Int, on: Boolean) {
        val s = HashSet(caught(c))
        if (on) s.add(num.toString()) else s.remove(num.toString())
        prefs(c).edit().putStringSet(key(c, "caught"), s).apply()
    }

    /**
     * Workspaces keep playthroughs apart (a core game and a fan game played alongside): each has its own caught marks,
     * teams and selected game. "spaces" holds an "id\tname" line per workspace; workspace 0 uses the keys from before
     * workspaces existed, so an update keeps everything in it.
     */
    fun space(c: Context): Int = prefs(c).getInt("space", 0)

    /** Preference key of `base` in workspace `id`: "caught" in workspace 0, "caught.2" in workspace 2. */
    fun key(c: Context, base: String, id: Int = space(c)): String = if (id == 0) base else "$base.$id"

    fun spaces(c: Context): List<Pair<Int, String>> {
        val s = prefs(c).getString("spaces", "")!!
        if (s.isEmpty()) return listOf(0 to L.t("Main"))
        return s.split('\n').map { it.substringBefore('\t').toInt() to it.substringAfter('\t') }
    }

    fun spaceName(c: Context): String = spaces(c).firstOrNull { it.first == space(c) }?.second ?: ""

    fun saveSpaces(c: Context, spaces: List<Pair<Int, String>>) {
        prefs(c).edit().putString("spaces", spaces.joinToString("\n") { "${it.first}\t" + it.second.replace('\t', ' ').replace('\n', ' ') }).apply()
    }

    // --- navigation ------------------------------------------------------------------------------

    /** Opens whatever a row or pill refers to: a Pokémon, move, ability, or a type (its Pokédex). */
    fun open(a: Activity, ref: Any?) {
        when (ref) {
            is Dex.Mon -> openMon(a, ref.num, null)
            is Dex.Move -> a.startActivity(Intent(a, MoveActivity::class.java).putExtra("id", ref.id))
            is Dex.Ability -> a.startActivity(Intent(a, AbilityActivity::class.java).putExtra("id", ref.id))
            is String -> a.startActivity(Intent(a, PokedexActivity::class.java).putExtra("type", ref))
        }
    }

    /** @param list the species the user was browsing, for previous/next; null browses the national dex. */
    fun openMon(a: Activity, num: Int, list: IntArray?) {
        a.startActivity(Intent(a, MonActivity::class.java).putExtra("num", num).putExtra("list", list))
    }

    // --- palette ---------------------------------------------------------------------------------

    /** Page background, card surface, chip and track fill, active segment, text, secondary text. */
    var BG = 0
    var SURFACE = 0
    var FILL = 0
    var RAISED = 0
    var TEXT = 0
    var MUTED = 0
    /** Stat a nature raises (red) or lowers (blue), as in the games' summary screen. */
    var UP = 0
    var DOWN = 0
    var night = false
    /** Type label style picked in Settings: [LABEL_TEXT], [LABEL_ICON], [LABEL_GLYPH] or [LABEL_PIXEL]. */
    var labels = LABEL_TEXT
    const val LABEL_TEXT = 0
    /** Scarlet / Violet glyph before the name. */
    const val LABEL_ICON = 1
    /** Sun / Moon badge, which has the name in it. */
    const val LABEL_PIXEL = 2
    /** The Scarlet / Violet glyph alone; the name is its content description. */
    const val LABEL_GLYPH = 3
    /** Pokémon artwork picked in Settings: [ART_HOME], [ART_OFFICIAL] or [ART_PIXEL]. */
    var art = ART_HOME
    const val ART_HOME = 0
    const val ART_OFFICIAL = 1
    const val ART_PIXEL = 2
    /** Asset folder per artwork style, by index. */
    private val ART_DIRS = arrayOf("sprites", "art", "pixel")
    private val SHINY_DIRS = arrayOf("shiny", "shiny-art", "shiny-pixel")
    private val LOOK_DIRS = arrayOf("looks", "looks-art", "looks-pixel")
    private val SHINY_LOOK_DIRS = arrayOf("looks-shiny", "looks-shiny-art", "looks-shiny-pixel")
    /** Active chips; white text on it is 4.8:1. */
    const val ACCENT = 0xFFD8300C.toInt()
    const val WHITE = Color.WHITE

    /** Contrast checked for TEXT and MUTED on BG, SURFACE and FILL: all at least 4.5:1 in both modes. */
    private fun theme(c: Context) {
        night = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        labels = prefs(c).getInt("labels", LABEL_TEXT)
        art = prefs(c).getInt("art", ART_HOME)
        BG = (if (night) 0xFF111215 else 0xFFF3F4F6).toInt()
        SURFACE = (if (night) 0xFF1C1E23 else 0xFFFFFFFF).toInt()
        FILL = (if (night) 0xFF2A2D34 else 0xFFE6E8EC).toInt()
        RAISED = (if (night) 0xFF3B3F48 else 0xFFFFFFFF).toInt()
        TEXT = (if (night) 0xFFF1F2F4 else 0xFF15171A).toInt()
        MUTED = (if (night) 0xFF9BA1AB else 0xFF5B616B).toInt()
        UP = (if (night) 0xFFFF8A80 else 0xFFC62828).toInt()
        DOWN = (if (night) 0xFF82B1FF else 0xFF1F4FB5).toInt()
    }

    private val COLORS = mapOf(
        "normal" to 0xFFA8A77A, "fire" to 0xFFEE8130, "water" to 0xFF6390F0,
        "electric" to 0xFFF7D02C, "grass" to 0xFF7AC74C, "ice" to 0xFF96D9D6,
        "fighting" to 0xFFC22E28, "poison" to 0xFFA33EA1, "ground" to 0xFFE2BF65,
        "flying" to 0xFFA98FF3, "psychic" to 0xFFF95587, "bug" to 0xFFA6B91A,
        "rock" to 0xFFB6A136, "ghost" to 0xFF735797, "dragon" to 0xFF6F35FC,
        "dark" to 0xFF705746, "steel" to 0xFFB7B7CE, "fairy" to 0xFFD685AD,
        "physical" to 0xFFC92112, "special" to 0xFF4F5870, "status" to 0xFF8C888C,
    )

    fun color(key: String): Int = COLORS[key]?.toInt() ?: Color.GRAY

    /** Near-black or white, whichever has more contrast on `background` (they tie at luminance 0.2). */
    fun on(background: Int): Int = if (Color.luminance(background) > 0.2f) 0xFF15171A.toInt() else WHITE

    /** `color` darkened until white text on it reaches 4.5:1 (luminance at most 0.18): heroes and cards. */
    fun deep(color: Int): Int {
        var c = color
        var f = 1f
        while (Color.luminance(c) > 0.18f) {
            f -= 0.04f
            c = Color.rgb((Color.red(color) * f).roundToInt(), (Color.green(color) * f).roundToInt(), (Color.blue(color) * f).roundToInt())
        }
        return c
    }

    fun alpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a shl 24)

    /** `a` moved `t` (0 to 1) of the way to `b`, per channel. */
    fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt())

    /** SURFACE washed with `color`: home tiles. TEXT on it is at least 9:1 in both modes; MUTED is not (3.9:1). */
    fun tonal(color: Int): Int = mix(SURFACE, color, if (night) 0.22f else 0.16f)

    /** `color` for large text on its [tonal]: at least 3.9:1 for the home tile colors (3:1 needed). */
    fun ink(color: Int): Int {
        if (!night) return deep(color)
        var c = color
        var f = 0f
        while (Color.luminance(c) < 0.3f) {
            f += 0.05f
            c = mix(color, WHITE, f)
        }
        return c
    }

    // --- drawables -------------------------------------------------------------------------------

    fun rounded(c: Context, color: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(c, radiusDp).toFloat()
        return d
    }

    /** `content` (may be null) with a touch ripple clipped to the same rounded shape. */
    fun pressable(c: Context, content: Drawable?, radiusDp: Int): Drawable {
        val ripple = if (night) 0x33FFFFFF else 0x1F000000
        return RippleDrawable(ColorStateList.valueOf(ripple), content, rounded(c, Color.WHITE, radiusDp))
    }

    /** Faint Poké Ball outline in the bottom-right corner, clipped to the rounded shape it sits on. */
    class Pokeball(c: Context, radiusDp: Int, sizeDp: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        private val box = RectF()
        private val radius = dp(c, radiusDp).toFloat()
        private val size = dp(c, sizeDp).toFloat()

        init {
            paint.color = 0x26FFFFFF
            paint.style = Paint.Style.STROKE
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            box.set(b)
            clip.reset()
            clip.addRoundRect(box, radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            val r = size / 2
            val cx = b.right - r * 0.55f
            val cy = b.bottom - r * 0.55f
            val w = r * 0.16f
            paint.strokeWidth = w
            canvas.drawCircle(cx, cy, r - w / 2, paint)
            canvas.drawLine(cx - r, cy, cx - r * 0.34f, cy, paint)
            canvas.drawLine(cx + r * 0.34f, cy, cx + r, cy, paint)
            canvas.drawCircle(cx, cy, r * 0.26f, paint)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** The shiny mark in `ink`: a large and a small four-pointed star. */
    class Sparkle(private val ink: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            path.reset()
            star(b.left + w * 0.4f, b.top + h * 0.6f, w * 0.4f)
            star(b.left + w * 0.8f, b.top + h * 0.2f, w * 0.2f)
            paint.color = ink
            canvas.drawPath(path, paint)
        }

        /** Sides curve in toward the center, so the points come out sharp. */
        private fun star(x: Float, y: Float, r: Float) {
            path.moveTo(x, y - r)
            path.quadTo(x, y, x + r, y)
            path.quadTo(x, y, x, y + r)
            path.quadTo(x, y, x - r, y)
            path.quadTo(x, y, x, y - r)
            path.close()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Arrowhead in `ink` pointing back (`back`) or forward, for previous/next buttons. */
    class Chevron(private val ink: Int, private val back: Boolean) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        init {
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            val tip = if (back) b.left + w * 0.3f else b.right - w * 0.3f
            val tail = if (back) b.left + w * 0.65f else b.right - w * 0.65f
            path.reset()
            path.moveTo(tail, b.top + h * 0.15f)
            path.lineTo(tip, b.top + h * 0.5f)
            path.lineTo(tail, b.top + h * 0.85f)
            paint.color = ink
            paint.strokeWidth = w * 0.16f
            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Pixel art at the largest whole-pixel scale that fits, centered and unfiltered, so pixels stay square. */
    class Pixels(c: Context, private val b: Bitmap) : Drawable() {
        private val paint = Paint()
        private val box = Rect()
        /** About 1.2 dp per pixel: a 48 x 18 type badge is as tall as a text pill. */
        private val scale = maxOf(1, (c.resources.displayMetrics.density * 1.2f).toInt())

        init {
            paint.isFilterBitmap = false
        }

        override fun draw(canvas: Canvas) {
            val r = bounds
            val k = maxOf(1, minOf(r.width() / b.width, r.height() / b.height))
            val w = b.width * k
            val h = b.height * k
            box.set(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() - w / 2 + w, r.centerY() - h / 2 + h)
            canvas.drawBitmap(b, null, box, paint)
        }

        override fun getIntrinsicWidth() = b.width * scale
        override fun getIntrinsicHeight() = b.height * scale
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Colored rounded card with a Poké Ball watermark and a press ripple: home tiles, Pokédex cards. */
    fun cardBackground(c: Context, color: Int, ballDp: Int): Drawable = cardBackground(c, color, color, ballDp)

    /** Same card shaded diagonally from `from` to `to`: a paired release (Scarlet / Violet) in both versions' colors. */
    fun cardBackground(c: Context, from: Int, to: Int, ballDp: Int): Drawable {
        val r = 18
        val fill = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(from, to))
        fill.cornerRadius = dp(c, r).toFloat()
        return pressable(c, LayerDrawable(arrayOf(fill, Pokeball(c, r, ballDp))), r)
    }

    /** Colored header block, rounded at the bottom; give [show] the same color. */
    fun hero(c: Context, color: Int): LinearLayout {
        val h = vertical(c)
        val r = dp(c, 28).toFloat()
        val bg = GradientDrawable()
        bg.setColor(color)
        bg.cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        h.background = LayerDrawable(arrayOf(bg, Pokeball(c, 28, 240)))
        h.setPadding(dp(c, 20), dp(c, 8), dp(c, 20), dp(c, 24))
        return h
    }

    // --- views -----------------------------------------------------------------------------------

    /**
     * Sets the content view, edge to edge, with system bar and keyboard insets as `root` padding. A nonzero
     * `bar` paints the status bar area in that color (matching a [hero]) with light icons, so content
     * scrolling under it stays out of the way. Returns that strip, for a page that recolors it.
     */
    fun show(a: Activity, root: View, bar: Int = 0): View {
        a.actionBar?.hide()
        a.window.setBackgroundDrawable(ColorDrawable(BG))
        a.window.setDecorFitsSystemWindows(false)
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        a.window.insetsController?.setSystemBarsAppearance(
            if (night) 0 else if (bar != 0) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else light, light)
        val frame = FrameLayout(a)
        frame.addView(root)
        val strip = View(a)
        strip.setBackgroundColor(bar)
        frame.addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        frame.setOnApplyWindowInsetsListener { _, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            // The keyboard shortens the page instead of padding it: a ScrollView only scrolls the focused box above
            // the keyboard when its own height changes.
            root.setPadding(i.left, i.top, i.right, if (ime > 0) 0 else i.bottom)
            (root.layoutParams as FrameLayout.LayoutParams).bottomMargin = ime
            root.requestLayout()
            strip.layoutParams.height = if (bar == 0) 0 else i.top
            strip.requestLayout()
            WindowInsets.CONSUMED
        }
        a.setContentView(frame)
        return strip
    }

    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density).roundToInt()

    fun dp(c: Context, v: Int): Int = dp(c, v.toFloat())

    fun text(c: Context, s: CharSequence, sp: Int, bold: Boolean): TextView {
        val t = TextView(c)
        t.text = s
        t.textSize = sp.toFloat()
        t.setTextColor(TEXT)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    fun muted(c: Context, s: CharSequence, sp: Int): TextView {
        val t = text(c, s, sp, false)
        t.setTextColor(MUTED)
        return t
    }

    fun white(c: Context, s: CharSequence, sp: Int, bold: Boolean): TextView {
        val t = text(c, s, sp, bold)
        t.setTextColor(WHITE)
        return t
    }

    /** Screen title, 16 dp side margin built in. */
    fun title(c: Context, s: String): TextView {
        val t = text(c, s, 30, true)
        t.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 8))
        return t
    }

    /** Small uppercase label above a group of rows. */
    fun caption(c: Context, s: String): TextView {
        val t = text(c, s.uppercase(Locale.ROOT), 12, true)
        t.setTextColor(MUTED)
        t.letterSpacing = 0.06f
        return t
    }

    fun vertical(c: Context): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    fun margins(w: Int, h: Int, c: Context, l: Int, t: Int, r: Int, b: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(w, h)
        lp.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b))
        return lp
    }

    /** Full width minus 16 dp each side, `bottom` dp below. */
    fun wide(c: Context, top: Int, bottom: Int): LinearLayout.LayoutParams =
        margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, c, 16, top, 16, bottom)

    /** Rounded surface with 16 dp padding and an optional heading; place it with [wide]. */
    fun panel(c: Context, heading: String?): LinearLayout {
        val p = vertical(c)
        p.background = rounded(c, SURFACE, 18)
        p.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 16))
        if (heading != null) {
            val h = text(c, heading, 17, true)
            h.setPadding(0, 0, 0, dp(c, 10))
            p.addView(h)
        }
        return p
    }

    /** Pill-shaped filter chip; see [chipActive]. */
    fun chip(c: Context, label: String, l: View.OnClickListener?): TextView {
        val t = text(c, label, 14, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8))
        t.setSingleLine()
        t.setOnClickListener(l)
        chipActive(t, false)
        return t
    }

    fun chipActive(t: TextView, on: Boolean) {
        t.background = pressable(t.context, rounded(t.context, if (on) ACCENT else FILL, 999), 999)
        t.setTextColor(if (on) WHITE else TEXT)
    }

    /** Horizontally scrolling row for chips; add them with [addChip]. */
    fun chipBar(c: Context, chips: LinearLayout): HorizontalScrollView {
        val s = HorizontalScrollView(c)
        s.isHorizontalScrollBarEnabled = false
        s.clipToPadding = false
        s.setPadding(dp(c, 16), 0, dp(c, 8), 0)
        s.addView(chips)
        return s
    }

    fun addChip(bar: LinearLayout, chip: TextView) {
        bar.addView(chip, margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, bar.context, 0, 0, 8, 0))
    }

    /** Segmented control filling `into`; `picked` gets the index. Highlight one with [segmentActive]. */
    fun segments(c: Context, into: LinearLayout, labels: Array<String>, picked: (Int) -> Unit): Array<TextView> {
        into.removeAllViews()
        into.background = rounded(c, FILL, 14)
        into.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4))
        return Array(labels.size) { k ->
            val t = text(c, labels[k], 14, true)
            t.gravity = Gravity.CENTER
            // Longer labels in other languages ("Movimientos") shrink to fit instead of being cut; not setSingleLine,
            // whose horizontal scrolling turns autosize off.
            t.maxLines = 1
            t.setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, TypedValue.COMPLEX_UNIT_SP)
            t.setPadding(dp(c, 4), dp(c, 9), dp(c, 4), dp(c, 9))
            t.setOnClickListener { picked(k) }
            into.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            t
        }
    }

    fun segmentActive(segs: Array<TextView>, active: Int) {
        for ((i, s) in segs.withIndex()) {
            s.background = if (i == active) rounded(s.context, RAISED, 11) else null
            s.setTextColor(if (i == active) TEXT else MUTED)
        }
    }

    /** Rounded search box; `changed` runs on every edit. */
    fun search(c: Context, hint: String, changed: () -> Unit): EditText {
        val e = EditText(c)
        e.hint = hint
        e.setSingleLine()
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        e.imeOptions = EditorInfo.IME_ACTION_SEARCH
        e.textSize = 16f
        e.setTextColor(TEXT)
        e.setHintTextColor(MUTED)
        e.background = rounded(c, SURFACE, 14)
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        val icon = c.getDrawable(android.R.drawable.ic_menu_search)!!.mutate()
        icon.setTint(MUTED)
        icon.setBounds(0, 0, dp(c, 22), dp(c, 22))
        e.setCompoundDrawablesRelative(icon, null, null, null)
        e.compoundDrawablePadding = dp(c, 8)
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = changed()
        })
        return e
    }

    fun title(s: String): String = s.replaceFirstChar { it.uppercaseChar() }

    fun national(num: Int): String = "#%04d".format(Locale.ROOT, num)

    /** "Pow 90 · Acc 100 · PP 15", with "-" where a move has no power or never misses. */
    fun moveStats(m: Dex.Move): String = L.f("Pow %s  ·  Acc %s  ·  PP %d",
        if (m.power == 0) "-" else m.power.toString(), if (m.accuracy == 0) "-" else m.accuracy.toString(), m.pp)

    /** A type or move category as shown, in the app's language. */
    fun label(key: String): String = dex?.label(key) ?: title(key)

    /** Rounded label in a type's or move category's color; types follow the Settings label style unless `style` is given. */
    fun pill(c: Context, key: String, style: Int = labels): TextView {
        val t = text(c, label(key), 12, true)
        val color = color(key)
        t.background = rounded(c, color, 999)
        t.setTextColor(on(color))
        t.gravity = Gravity.CENTER
        t.setSingleLine()
        t.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3))
        if (key !in CATEGORIES) typeLabel(t, key, on(color), style)
        return t
    }

    /** [glassPill] for a type, in the Settings label style. */
    fun typeGlass(c: Context, type: String): TextView {
        val t = glassPill(c, label(type))
        typeLabel(t, type, WHITE, labels)
        return t
    }

    private val CATEGORIES = setOf("physical", "special", "status")
    /** Decoded type images, "icon/fire" to bitmap: at most 36, each under 7 KB. Null when a type has none. */
    private val typeImages = HashMap<String, Bitmap?>()

    /** Adds the glyph tinted `ink` (LABEL_ICON), shows it alone (LABEL_GLYPH) or swaps in the pixel badge (LABEL_PIXEL). */
    private fun typeLabel(t: TextView, type: String, ink: Int, style: Int) {
        val dir = when (style) {
            LABEL_ICON, LABEL_GLYPH -> "icon"
            LABEL_PIXEL -> "pixel"
            else -> return
        }
        val c = t.context
        val b = typeImages.getOrPut("$dir/$type") {
            try {
                c.assets.open("types/$dir/$type.png").use { BitmapFactory.decodeStream(it) }
            } catch (e: IOException) {
                null  // a type without art (Stellar) keeps its text
            }
        } ?: return
        if (style == LABEL_PIXEL) {
            t.text = ""
            t.contentDescription = label(type)
            t.background = Pixels(c, b)
            t.setPadding(0, 0, 0, 0)
            return
        }
        val d = BitmapDrawable(c.resources, b)
        val h = (t.textSize * if (style == LABEL_GLYPH) 1.3f else 1.1f).roundToInt()
        d.setBounds(0, 0, h * b.width / b.height, h)
        d.setTint(ink)
        if (style == LABEL_GLYPH) t.contentDescription = label(type)
        // In the text, not a compound drawable, so glyph and name center together in a fixed-width pill.
        val s = SpannableString(if (style == LABEL_GLYPH) " " else "  " + t.text)
        s.setSpan(ImageSpan(d, DynamicDrawableSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        t.text = s
    }

    /** White label on a translucent dark pill, for colored cards and heroes (darkening keeps 4.5:1). */
    fun glassPill(c: Context, s: String): TextView {
        val t = white(c, s, 12, true)
        t.background = rounded(c, 0x2E000000, 999)
        t.gravity = Gravity.CENTER
        t.setSingleLine()
        t.setPadding(dp(c, 10), dp(c, 3), dp(c, 10), dp(c, 3))
        return t
    }

    /** Type pills that open that type's Pokédex, `perRow` to a line sharing its width, so narrow screens fit. */
    fun typePills(a: Activity, types: List<String>, perRow: Int): LinearLayout {
        val rows = vertical(a)
        lateinit var row: LinearLayout
        for ((i, type) in types.withIndex()) {
            if (i % perRow == 0) {
                row = LinearLayout(a)
                row.weightSum = perRow.toFloat()
                rows.addView(row)
            }
            val p = pill(a, type)
            p.setPadding(0, dp(a, 5), 0, dp(a, 5))
            p.setOnClickListener { open(a, type) }
            val lp = margins(0, ViewGroup.LayoutParams.WRAP_CONTENT, a, 0, 3, 6, 3)
            lp.weight = 1f
            row.addView(p, lp)
        }
        return rows
    }

    /**
     * Pokémon image in the Settings artwork style unless `style` is given. HOME renders and official artwork are
     * 256 px; sample 2 decodes 128 px for list rows (64 KB ARGB), 1 the full image for detail headers (256 KB).
     * Pixel sprites decode whole (at most 96 px, 36 KB) and draw unfiltered. No cache: only bound rows hold a
     * bitmap. A form without its own image (cosplay Pikachu, Koraidon's ride modes) gets its species', then the
     * HOME render; null if all are missing. `shiny` picks the shiny coloring from gen_art.py's shiny folders.
     */
    fun sprite(c: Context, num: Int, sample: Int, style: Int = art, shiny: Boolean = false): Drawable? {
        val m = dex?.monOrNull(num)
        val dirs = if (shiny) SHINY_DIRS else ART_DIRS
        for (dir in intArrayOf(style, ART_HOME).distinct()) for (n in intArrayOf(num, m?.species ?: num)) {
            decode(c, "${dirs[dir]}/$n.webp", dir == ART_PIXEL, sample)?.let { return it }
        }
        return null
    }

    /**
     * A look from gen_looks.py (female Venusaur, Vivillon's patterns) in the Settings artwork style, decoded like
     * [sprite]; the HOME render where the style has none (official artwork has no female looks).
     */
    fun look(c: Context, file: String, sample: Int, shiny: Boolean = false): Drawable? {
        val dirs = if (shiny) SHINY_LOOK_DIRS else LOOK_DIRS
        for (dir in intArrayOf(art, ART_HOME).distinct()) decode(c, "${dirs[dir]}/$file.webp", dir == ART_PIXEL, sample)?.let { return it }
        return null
    }

    /** assets/`path` at 1/`sample`, or whole and unfiltered for a pixel sprite; null if the file is missing. */
    private fun decode(c: Context, path: String, pixel: Boolean, sample: Int): Drawable? = try {
        c.assets.open(path).use { input ->
            val o = BitmapFactory.Options()
            o.inSampleSize = if (pixel) 1 else sample
            BitmapDrawable(c.resources, BitmapFactory.decodeStream(input, null, o)).apply { isFilterBitmap = !pixel }
        }
    } catch (e: IOException) {
        null
    }

    /** The item's 24 px bag icon from assets/items (see gen_items.py), unfiltered like pixel sprites, 2.3 KB decoded, no cache. */
    fun itemIcon(c: Context, id: Int): Drawable? = try {
        c.assets.open("items/$id.webp").use { BitmapDrawable(c.resources, BitmapFactory.decodeStream(it)).apply { isFilterBitmap = false } }
    } catch (e: IOException) {
        null
    }

    /** The item's name after its icon at 24 dp, for a line of text; the bare name for an item without one. */
    fun itemName(c: Context, item: Dex.Item): CharSequence {
        val d = itemIcon(c, item.id) ?: return item.name
        d.setBounds(0, 0, dp(c, 24), dp(c, 24))
        val s = SpannableString("  " + item.name)
        s.setSpan(ImageSpan(d, DynamicDrawableSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    // --- list rows -------------------------------------------------------------------------------

    /**
     * One list entry. Rows show the sprite (an item's icon for a [Dex.Item] ref) with `badge` as a caption above the title or, without a sprite,
     * `badge` in a box on the left. Cards show `badge` top right. A null ref makes it a section header.
     */
    class Item(val sprite: Int, val badge: String, val title: String, val subtitle: CharSequence, val pills: List<String>, val ref: Any?) {
        companion object {
            fun header(title: String) = Item(0, "", title, "", emptyList(), null)

            fun mon(m: Dex.Mon, types: List<String>, badge: String, subtitle: CharSequence) = Item(m.num, badge, m.name, subtitle, types, m)

            fun move(m: Dex.Move, badge: String) = Item(0, badge, m.name, moveStats(m), listOf(m.type, m.category), m)
        }
    }

    fun row(c: Context): View {
        val row = LinearLayout(c)
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(c, 64)
        row.setPadding(dp(c, 16), dp(c, 6), dp(c, 16), dp(c, 6))
        row.background = pressable(c, null, 0)
        val size = dp(c, 56)
        val img = ImageView(c)
        img.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4))
        row.addView(img, margins(size, size, c, 0, 0, 12, 0))
        val box = text(c, "", 13, true)
        box.gravity = Gravity.CENTER
        box.setSingleLine()
        box.background = rounded(c, FILL, 10)
        row.addView(box, margins(dp(c, 58), dp(c, 36), c, 0, 0, 12, 0))
        val texts = vertical(c)
        val cap = caption(c, "")
        val title = text(c, "", 16, true)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        val sub = muted(c, "", 13)
        texts.addView(cap)
        texts.addView(title)
        texts.addView(sub)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pills = vertical(c)
        pills.gravity = Gravity.END
        row.addView(pills)
        row.tag = arrayOf(img, box, cap, title, sub, pills)
        return row
    }

    fun bind(row: View, item: Item) {
        val c = row.context
        val v = row.tag as Array<*>
        val img = v[0] as ImageView
        val icon = (item.ref as? Dex.Item)?.let { itemIcon(c, it.id) }
        // An item without an icon keeps the empty circle, so its row lines up with the others.
        val hasSprite = item.sprite != 0 || item.ref is Dex.Item
        img.visibility = if (hasSprite) View.VISIBLE else View.GONE
        img.setImageDrawable(icon ?: if (item.sprite != 0) sprite(c, item.sprite, 2) else null)
        // A 24 px item icon at 36 dp, 1.5 times its inline size; a Pokémon fills the circle.
        val inset = dp(c, if (icon == null) 4 else 10)
        img.setPadding(inset, inset, inset, inset)
        if (hasSprite) {
            val circle = GradientDrawable()
            circle.shape = GradientDrawable.OVAL
            circle.setColor(alpha(color(item.pills.firstOrNull() ?: ""), 0x38))
            img.background = circle
        }
        val box = v[1] as TextView
        box.text = item.badge
        box.visibility = if (!hasSprite && item.badge.isNotEmpty()) View.VISIBLE else View.GONE
        val cap = v[2] as TextView
        cap.text = item.badge
        cap.visibility = if (hasSprite && item.badge.isNotEmpty()) View.VISIBLE else View.GONE
        (v[3] as TextView).text = item.title
        val sub = v[4] as TextView
        sub.text = item.subtitle
        sub.visibility = if (item.subtitle.isEmpty()) View.GONE else View.VISIBLE
        val pills = v[5] as LinearLayout
        pills.removeAllViews()
        for (p in item.pills) {
            // ponytail: an icon and a long name ("Electric") outgrow 72 dp, so icon pills size to fit
            val pill = pill(c, p)
            pill.minWidth = dp(c, if (labels == LABEL_GLYPH && p !in CATEGORIES) 44 else 72)
            pills.addView(pill, margins(if (labels == LABEL_ICON || labels == LABEL_GLYPH) ViewGroup.LayoutParams.WRAP_CONTENT else dp(c, 72),
                ViewGroup.LayoutParams.WRAP_CONTENT, c, 8, 2, 0, 2))
        }
    }

    /** Pokédex grid card: primary type color, name, type pills, sprite bottom right, number top right. */
    fun card(c: Context): View {
        val f = FrameLayout(c)
        f.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 118))
        val img = ImageView(c)
        val imgLp = FrameLayout.LayoutParams(dp(c, 84), dp(c, 84), Gravity.BOTTOM or Gravity.END)
        imgLp.setMargins(0, 0, dp(c, 6), dp(c, 6))
        f.addView(img, imgLp)
        val num = white(c, "", 13, true)
        val numLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        numLp.setMargins(0, dp(c, 11), dp(c, 12), 0)
        f.addView(num, numLp)
        val left = vertical(c)
        left.setPadding(dp(c, 12), dp(c, 10), 0, dp(c, 10))
        val name = white(c, "", 16, true)
        name.setSingleLine()
        name.ellipsize = TextUtils.TruncateAt.END
        left.addView(name, margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, c, 0, 0, 48, 4))
        val pills = vertical(c)
        left.addView(pills)
        val sub = white(c, "", 11, true)
        left.addView(sub, margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, c, 0, 4, 0, 0))
        f.addView(left)
        f.tag = arrayOf(img, num, name, pills, sub)
        return f
    }

    fun bindCard(card: View, item: Item) {
        val c = card.context
        val v = card.tag as Array<*>
        card.background = cardBackground(c, deep(color(item.pills[0])), 104)
        (v[0] as ImageView).setImageDrawable(sprite(c, item.sprite, 2))
        (v[1] as TextView).text = item.badge
        (v[2] as TextView).text = item.title
        val pills = v[3] as LinearLayout
        pills.removeAllViews()
        for (p in item.pills) {
            pills.addView(typeGlass(c, p), margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, c, 0, 2, 0, 2))
        }
        val sub = v[4] as TextView
        sub.text = item.subtitle
        sub.visibility = if (item.subtitle.isEmpty()) View.GONE else View.VISIBLE
    }

    /** @param cards Pokédex grid cards instead of rows; every item must then be a Pokémon. */
    class Adapter(private val cards: Boolean = false) : BaseAdapter() {
        private var items: List<Item> = emptyList()

        fun set(list: List<Item>) {
            items = list
            notifyDataSetChanged()
        }

        fun items() = items

        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(i: Int) = if (items[i].ref == null) 1 else 0
        override fun areAllItemsEnabled() = false
        override fun isEnabled(i: Int) = items[i].ref != null

        override fun getView(i: Int, view: View?, parent: ViewGroup): View {
            val item = items[i]
            val c = parent.context
            if (item.ref == null) {
                val t = view as TextView? ?: caption(c, "")
                t.setPadding(dp(c, 16), dp(c, 18), dp(c, 16), dp(c, 6))
                t.text = item.title.uppercase(Locale.ROOT)
                return t
            }
            val v = view ?: if (cards) card(c) else row(c)
            if (cards) bindCard(v, item) else bind(v, item)
            return v
        }
    }

    /** A ListView, or a 2-column GridView of cards, without dividers or the theme's selector. */
    fun list(c: Context, cards: Boolean): AbsListView {
        val l: AbsListView
        if (cards) {
            val g = GridView(c)
            g.numColumns = 2
            g.horizontalSpacing = dp(c, 10)
            g.verticalSpacing = dp(c, 10)
            g.setPadding(dp(c, 16), dp(c, 4), dp(c, 16), dp(c, 16))
            g.stretchMode = GridView.STRETCH_COLUMN_WIDTH
            l = g
        } else {
            val lv = ListView(c)
            lv.divider = null
            l = lv
        }
        // Shows only on lists four or more screens long.
        l.isFastScrollEnabled = true
        l.clipToPadding = false
        l.selector = ColorDrawable(Color.TRANSPARENT)
        l.cacheColorHint = Color.TRANSPARENT
        return l
    }

    /** `items` under a header per [Dex.ITEM_GROUPS] section, alphabetical within one. */
    fun itemSections(items: List<Dex.Item>, row: (Dex.Item) -> Item): List<Item> {
        val out = ArrayList<Item>()
        for (g in Dex.ITEM_GROUPS) {
            val some = items.filter { it.group == g }.sortedBy { it.key }
            if (some.isEmpty()) continue
            out.add(Item.header(L.t(g)))
            some.mapTo(out, row)
        }
        return out
    }

    /** Species numbers of the Pokémon rows, in order, for previous/next on the detail screen. */
    fun nums(items: List<Item>): IntArray = items.mapNotNull { (it.ref as? Dex.Mon)?.num }.distinct().toIntArray()

    // --- dialogs ---------------------------------------------------------------------------------

    /** Checkbox dialog; `changed` runs on every toggle so results update live behind it. */
    fun <T> pickMany(c: Context, heading: String, options: List<T>, selected: MutableSet<T>, label: (T) -> String,
                     changed: () -> Unit) {
        val labels = Array(options.size) { label(options[it]) }
        val checked = BooleanArray(options.size) { options[it] in selected }
        AlertDialog.Builder(c)
            .setTitle(heading)
            .setMultiChoiceItems(labels, checked) { _, i, on ->
                if (on) selected.add(options[i]) else selected.remove(options[i])
                changed()
            }
            .setPositiveButton(L.t("Done"), null)
            .setNeutralButton(L.t("Clear")) { _, _ ->
                selected.clear()
                changed()
            }
            .show()
    }

    /** One-line text box starting at `start`; `done` gets the trimmed text, never empty. */
    fun askName(c: Context, heading: String, start: String, done: (String) -> Unit) {
        val e = EditText(c)
        e.setText(start)
        e.setSingleLine()
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        e.setSelectAllOnFocus(true)
        val box = vertical(c)
        box.setPadding(dp(c, 20), dp(c, 8), dp(c, 20), 0)
        box.addView(e)
        AlertDialog.Builder(c).setTitle(heading).setView(box)
            .setPositiveButton(L.t("Save")) { _, _ ->
                val s = e.text.toString().trim()
                if (s.isNotEmpty()) done(s)
            }
            .setNegativeButton(L.t("Cancel"), null)
            .show()
    }

    fun pickOne(c: Context, heading: String, labels: List<String>, current: Int, picked: (Int) -> Unit) {
        AlertDialog.Builder(c)
            .setTitle(heading)
            .setSingleChoiceItems(labels.toTypedArray(), current) { d, i ->
                picked(i)
                d.dismiss()
            }
            .show()
    }

    /**
     * Dialog with a search box over a long list (Pokémon, moves, items); `picked` gets the tapped row's ref.
     * Headers show only while the search is empty. Rows match on their shown or English name.
     */
    fun pickSearch(a: Activity, heading: String, items: List<Item>, picked: (Any?) -> Unit) {
        pickSearch(a, heading, emptyArray(), listOf(items), picked)
    }

    /** With two or more tabs, a segmented control above the search box switches lists; the search applies to each. */
    fun pickSearch(a: Activity, heading: String, tabs: Array<String>, lists: List<List<Item>>, picked: (Any?) -> Unit) {
        val adapter = Adapter()
        val lv = list(a, false) as ListView
        lv.adapter = adapter
        var tab = 0
        lateinit var box: EditText
        val filter = {
            val q = Dex.fold(box.text.toString())
            // distinct: a list may repeat an entry in a section of suggestions on top
            adapter.set(if (q.isEmpty()) lists[tab] else lists[tab].filter { it.ref != null && q in key(it) }.distinctBy { it.ref })
        }
        box = search(a, L.t("Search"), filter)
        filter()
        val content = vertical(a)
        if (tabs.size > 1) {
            val bar = LinearLayout(a)
            lateinit var segs: Array<TextView>
            segs = segments(a, bar, tabs) { k ->
                tab = k
                segmentActive(segs, k)
                filter()
                lv.setSelection(0)
            }
            segmentActive(segs, 0)
            content.addView(bar, wide(a, 8, 8))
        }
        content.addView(box, wide(a, 8, 8))
        val tall = minOf(dp(a, 520), a.resources.displayMetrics.heightPixels * 3 / 5)
        content.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tall))
        val d = AlertDialog.Builder(a).setTitle(heading).setView(content).setNegativeButton(L.t("Cancel"), null).show()
        lv.setOnItemClickListener { _, _, i, _ ->
            picked(adapter.getItem(i).ref)
            d.dismiss()
        }
    }

    private fun key(i: Item): String = when (val r = i.ref) {
        is Dex.Mon -> r.key
        is Dex.Move -> r.key
        is Dex.Ability -> r.key
        is Dex.Item -> r.key
        else -> Dex.fold(i.title)
    }

    fun titles(words: Iterable<String>): List<String> = words.map { title(it) }

    /** Label on the left, current value and a tap target on the right. */
    fun setting(c: Context, label: String, value: CharSequence, l: View.OnClickListener): View {
        val row = settingRow(c, label)
        val v = text(c, value, 15, true)
        v.gravity = Gravity.END
        v.setTextColor(if (night) 0xFFFF8A65.toInt() else ACCENT)
        row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        row.background = pressable(c, rounded(c, SURFACE, 10), 10)
        row.setOnClickListener(l)
        return row
    }

    fun settingRow(c: Context, label: String): LinearLayout {
        val row = LinearLayout(c)
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(c, 48)
        row.setPadding(0, dp(c, 4), 0, dp(c, 4))
        row.addView(text(c, label, 15, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }
}
