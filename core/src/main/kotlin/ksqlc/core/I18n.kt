package ksqlc.core

import kotlinx.serialization.Serializable

@Serializable
enum class Language { ES, EN }

/** The Spanish text is the key; [tr] returns it as is for [Language.ES] and looks it up in `en.tsv` for [Language.EN]. */
object I18n {
    /** Set once at startup; changing it in Settings only takes effect on the next start. */
    @Volatile var language = Language.ES

    // shortcut: one TSV per language loaded on first use, a third language needs a map per Language
    private val en: Map<String, String> by lazy {
        val text = I18n::class.java.getResourceAsStream("/ksqlc/i18n/en.tsv")?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
        text.lineSequence().filter { it.isNotEmpty() }.associate { line ->
            val tab = line.indexOf('\t')
            unescape(line.substring(0, tab)) to unescape(line.substring(tab + 1))
        }
    }

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\t", "\t")

    internal fun lookup(es: String): String = if (language == Language.EN) en[es] ?: es else es
}

/** Translates [es] (the Spanish literal) into the current language and formats [args] with `%s`-style placeholders. */
fun tr(es: String, vararg args: Any?): String {
    val text = I18n.lookup(es)
    return if (args.isEmpty()) text else text.format(*args)
}
