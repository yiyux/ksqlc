package ksqlc.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Window layout and look, restored on the next start. Values are clamped by the UI that reads them. */
@Serializable
data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val explorerVisible: Boolean = true,
    val explorerWidthDp: Int = 300,
    /** Share of the editor area given to the results panel, 0..1. */
    val resultsRatio: Float = 0.5f,
)

/** `settings.json` in [dir]. A missing or unreadable file yields the defaults instead of an error. */
class SettingsStore(dir: Path) {
    private val file = dir.resolve("settings.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): Settings =
        if (!file.exists()) Settings()
        else runCatching { json.decodeFromString<Settings>(file.readText()) }.getOrDefault(Settings())

    fun save(settings: Settings) = atomicWrite(file, json.encodeToString(settings))

    companion object {
        fun default() = SettingsStore(Path.of(System.getProperty("user.home"), ".ksqlc"))
    }
}
