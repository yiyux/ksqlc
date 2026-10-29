package ksqlc.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.streams.toList
import kotlin.test.Test
import kotlin.test.assertTrue

/** Every `tr("…")` key in the main sources must have an English line in `en.tsv`, so a missing translation breaks the build. */
class I18nCoverageTest {
    private val root = Path.of("..").toAbsolutePath().normalize()
    private val tsv = root.resolve("core/src/main/resources/ksqlc/i18n/en.tsv").readText().lines().filter { it.isNotEmpty() }

    @Test fun `tsv lines have one tab and unique keys`() {
        val bad = tsv.filter { it.count { c -> c == '\t' } != 1 }
        assertTrue(bad.isEmpty(), "lines without exactly one TAB: $bad")
        val dup = tsv.map { it.substringBefore('\t') }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(dup.isEmpty(), "duplicate keys: $dup")
    }

    @Test fun `every tr key has an english translation`() {
        val keys = tsv.map { it.substringBefore('\t') }.toSet()
        val re = Regex("""\btr\("((?:[^"\\]|\\.)*)"""")
        val missing = listOf("app", "drivers", "storage").flatMap { m ->
            Files.walk(root.resolve("$m/src/main/kotlin")).use { it.toList() }.filter { f -> f.extension == "kt" }.flatMap { f ->
                re.findAll(f.readText()).map { unescape(it.groupValues[1]) }.filter { it !in keys }.map { "${f.fileName}: $it" }.toList()
            }
        }
        assertTrue(missing.isEmpty(), "missing English translations (${missing.size}):\n" + missing.take(40).joinToString("\n"))
    }

    // the TSV stores real newlines/tabs as \n and \t, Kotlin sources as escapes
    private fun unescape(s: String) = Regex("""\\(.)""").replace(s) { m ->
        when (val c = m.groupValues[1]) { "n" -> "\\n"; "t" -> "\\t"; else -> c }
    }
}
