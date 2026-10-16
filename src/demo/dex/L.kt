package demo.dex

import java.io.FileNotFoundException
import java.util.Locale

/**
 * UI text in the chosen language. The English text is the key, so a missing translation shows English. Tables are
 * assets/lang/<lang>.tsv, "English\ttranslation" per line with "\n" for a line break; Dex.main checks every key used
 * here has a row in each table. No Android deps.
 */
object L {
    val LANGS = arrayOf("en", "pl", "es", "ja")
    /** Language of the loaded table: one of [LANGS]. */
    var lang = "en"
        private set
    /** Number format of the language: a decimal comma in Polish and Spanish. */
    var locale: Locale = Locale.ROOT
        private set
    private var table: Map<String, String> = emptyMap()

    fun load(assets: Dex.Assets, lang: String) {
        this.lang = lang
        locale = if (lang == "en") Locale.ROOT else Locale.forLanguageTag(lang)
        table = if (lang == "en") emptyMap() else read(assets, lang)
    }

    fun read(assets: Dex.Assets, lang: String): Map<String, String> = try {
        assets.open("lang/$lang.tsv").bufferedReader().useLines { lines ->
            lines.filter { it.isNotEmpty() }.associate { line ->
                val tab = line.indexOf('\t')
                line.substring(0, tab).replace("\\n", "\n") to line.substring(tab + 1).replace("\\n", "\n")
            }
        }
    } catch (e: FileNotFoundException) {
        emptyMap()
    }

    /** `s` in the current language. Pass a string literal, so the self-check finds the key. */
    fun t(s: String): String = table[s] ?: s

    /** [t] then String.format in the language's locale; a translation may reorder arguments with %1$s. */
    fun f(s: String, vararg args: Any?): String = t(s).format(locale, *args)

    /** Marks a literal stored now and shown later through [t] (sort labels, tab names), so the self-check finds it. */
    fun k(s: String): String = s
}
