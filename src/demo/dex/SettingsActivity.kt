package demo.dex

import android.app.Activity
import android.app.UiModeManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Language, theme, Pokémon artwork, type labels, Pokédex layout, forms and units, the caught list, credits. Choices apply on tap. */
class SettingsActivity : Activity() {
    private lateinit var prefs: SharedPreferences

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val dex = Ui.dex(this)
        prefs = Ui.prefs(this)
        val root = Ui.vertical(this)
        root.addView(Ui.title(this, L.t("Settings")))

        val look = section(root, L.t("Appearance"))
        label(look, L.t("Language"))
        val langs = arrayOf(L.t("System")) + LANG_NAMES
        look.addView(segments(langs, L.LANGS.indexOf(prefs.getString("lang", "")) + 1) { i ->
            prefs.edit().putString("lang", if (i == 0) "" else L.LANGS[i - 1]).apply()
            if (Ui.lang(this) == L.lang) return@segments
            // Every open screen holds text in the old language: start over from the home screen, back here on top.
            startActivities(arrayOf(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                Intent(this, SettingsActivity::class.java)))
        })
        label(look, L.t("Theme"))
        look.addView(segments(THEMES.map { L.t(it) }.toTypedArray(), prefs.getInt("theme", 0)) { i ->
            prefs.edit().putInt("theme", i).apply()
            // The system keeps this per app across restarts and recreates open screens when day/night flips.
            getSystemService(UiModeManager::class.java).setApplicationNightMode(NIGHT[i])
        })
        label(look, L.t("Pokémon artwork"))
        look.addView(options(ARTS.map { L.t(it) }.toTypedArray(), Ui.art, ARTS.size, { i ->
            val img = ImageView(this)
            img.setImageDrawable(Ui.sprite(this, PREVIEW_MON, 1, i))
            img.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 72))
            img
        }) { i ->
            Ui.art = i
            prefs.edit().putInt("art", i).apply()
        })
        label(look, L.t("Type labels"))
        look.addView(options(LABELS.map { L.t(it) }.toTypedArray(), LABEL_STYLES.indexOf(Ui.labels), 2, { k -> labelPreview(LABEL_STYLES[k]) }) { k ->
            Ui.labels = LABEL_STYLES[k]
            prefs.edit().putInt("labels", Ui.labels).apply()
        })

        val list = section(root, L.t("Pokédex"))
        label(list, L.t("Layout"))
        list.addView(segments(LAYOUTS.map { L.t(it) }.toTypedArray(), prefs.getInt("layout", 0)) { i -> prefs.edit().putInt("layout", i).apply() })
        label(list, L.t("Megas and other forms"))
        list.addView(segments(FORMS.map { L.t(it) }.toTypedArray(), prefs.getInt("forms", 0)) { i -> prefs.edit().putInt("forms", i).apply() })
        label(list, L.t("Height and weight"))
        val chomp = dex.mon(445)
        val example = Ui.muted(this, "", 13)
        val units = { i: Int ->
            example.text = "${chomp.name}: ${Dex.height(chomp.height, i == 1)}, ${Dex.weight(chomp.weight, i == 1)}"
        }
        list.addView(segments(UNITS.map { L.t(it) }.toTypedArray(), prefs.getInt("units", 0)) { i ->
            prefs.edit().putInt("units", i).apply()
            units(i)
        })
        units(prefs.getInt("units", 0))
        list.addView(example, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 4, 8, 0, 0))

        caught(section(root, L.t("Data")))
        section(root, L.t("About")).addView(Ui.muted(this, L.t(CREDITS), 13))

        val v = ScrollView(this)
        v.addView(root)
        Ui.show(this, v)
    }

    /** Caption over a new panel in `root`; returns the panel. */
    private fun section(root: LinearLayout, heading: String): LinearLayout {
        val cap = Ui.caption(this, heading)
        cap.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 6))
        root.addView(cap)
        val p = Ui.panel(this, null)
        root.addView(p, Ui.wide(this, 0, 10))
        return p
    }

    private fun label(p: LinearLayout, s: String) {
        p.addView(Ui.text(this, s, 15, true), Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, if (p.childCount == 0) 0 else 18, 0, 8))
    }

    /** Segmented control with `current` highlighted; `picked` runs after the highlight moves. */
    private fun segments(labels: Array<String>, current: Int, picked: (Int) -> Unit): View {
        val bar = LinearLayout(this)
        lateinit var segs: Array<TextView>
        segs = Ui.segments(this, bar, labels) { i ->
            Ui.segmentActive(segs, i)
            picked(i)
        }
        Ui.segmentActive(segs, current)
        return bar
    }

    /** Tiles, `perRow` to a line, each a preview over its label; the picked one is outlined. `picked` runs after. */
    private fun options(labels: Array<String>, current: Int, perRow: Int, preview: (Int) -> View, picked: (Int) -> Unit): View {
        val grid = Ui.vertical(this)
        val tiles = ArrayList<LinearLayout>()
        lateinit var row: LinearLayout
        for ((k, text) in labels.withIndex()) {
            if (k % perRow == 0) {
                row = LinearLayout(this)
                grid.addView(row)
            }
            val t = Ui.vertical(this)
            t.gravity = Gravity.CENTER_HORIZONTAL
            t.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10))
            t.addView(preview(k))
            val name = Ui.text(this, text, 13, true)
            name.gravity = Gravity.CENTER
            t.addView(name, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 0, 8, 0, 0))
            t.setOnClickListener {
                outline(tiles, k)
                picked(k)
            }
            tiles.add(t)
            val last = k % perRow == perRow - 1
            row.addView(t, Ui.margins(0, ViewGroup.LayoutParams.MATCH_PARENT, this, 0, 0, if (last) 0 else 8, 8).apply { weight = 1f })
        }
        outline(tiles, current)
        return grid
    }

    private fun outline(tiles: List<LinearLayout>, active: Int) {
        for ((k, t) in tiles.withIndex()) {
            val on = k == active
            val fill = Ui.rounded(this, Ui.FILL, 14)
            if (on) fill.setStroke(Ui.dp(this, 2), if (Ui.night) 0xFFFF8A65.toInt() else Ui.ACCENT)
            t.background = Ui.pressable(this, fill, 14)
            t.isSelected = on
            (t.getChildAt(1) as TextView).setTextColor(if (on) Ui.TEXT else Ui.MUTED)
        }
    }

    /** Fire and Water pills in `style`, sharing the tile's width; not clickable, so a tap picks the tile. */
    private fun labelPreview(style: Int): View {
        val row = LinearLayout(this)
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        for (type in arrayOf("fire", "water")) {
            val p = Ui.pill(this, type, style)
            p.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5))
            row.addView(p, Ui.margins(0, ViewGroup.LayoutParams.WRAP_CONTENT, this, 3, 0, 3, 0).apply { weight = 1f })
        }
        return row
    }

    /** Count of Pokémon marked caught, and a confirmed Clear. */
    private fun caught(p: LinearLayout) {
        val row = Ui.settingRow(this, L.t("Caught Pokémon"))
        val count = Ui.muted(this, "", 14)
        row.addView(count, Ui.margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, this, 8, 0, 12, 0))
        val clear = Ui.chip(this, L.t("Clear"), null)
        row.addView(clear)
        p.addView(row)
        val show = {
            val n = Ui.caught(this).size
            count.text = if (n == 0) L.t("None") else L.f("Marked: %d", n)
            clear.isEnabled = n > 0
            clear.alpha = if (n > 0) 1f else 0.4f
        }
        clear.setOnClickListener {
            Ui.confirm(this, L.t("Clear caught marks?"), L.f("Removes the caught mark from every game in %s. Pokémon: %d. This cannot be undone.",
                Ui.spaceName(this), Ui.caught(this).size), L.t("Clear")) {
                prefs.edit().remove(Ui.key(this, "caught")).apply()
                show()
            }
        }
        show()
    }

    companion object {
        private val CREDITS = L.k("Data: PokeAPI and pokemondb.net. Artwork and type icons: PokeAPI sprites. Sets: Smogon University, via pkmn. Pokémon names and images © Nintendo / Game Freak.")
        /** Each language in itself, in [L.LANGS] order. */
        private val LANG_NAMES = arrayOf("English", "Polski", "Español", "日本語")
        private val THEMES = arrayOf(L.k("System"), L.k("Light"), L.k("Dark"))
        private val NIGHT = intArrayOf(UiModeManager.MODE_NIGHT_AUTO, UiModeManager.MODE_NIGHT_NO, UiModeManager.MODE_NIGHT_YES)
        private val ARTS = arrayOf(L.k("3D (HOME)"), L.k("Official art"), L.k("Pixel art"))
        /** Tile order; the stored value is the Ui.LABEL_* constant, so older saves keep their meaning. */
        private val LABELS = arrayOf(L.k("Text"), L.k("Icon and name"), L.k("Icon only"), L.k("Pixel art"))
        private val LABEL_STYLES = intArrayOf(Ui.LABEL_TEXT, Ui.LABEL_ICON, Ui.LABEL_GLYPH, Ui.LABEL_PIXEL)
        private val LAYOUTS = arrayOf(L.k("Cards"), L.k("List"))
        private val FORMS = arrayOf(L.k("On their Pokémon"), L.k("In the list too"))
        private val UNITS = arrayOf(L.k("Metric"), L.k("Imperial"))
        /** Pikachu, in all three artwork styles. */
        private const val PREVIEW_MON = 25
    }
}
