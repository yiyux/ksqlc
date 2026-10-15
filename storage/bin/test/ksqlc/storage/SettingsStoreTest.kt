package ksqlc.storage

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.*

class SettingsStoreTest {
    private val dir = Files.createTempDirectory("ksqlc-settings")
    private val store = SettingsStore(dir)

    @Test fun defaultsWhenThereIsNoFile() { assertEquals(Settings(), store.load()) }

    @Test fun roundTrip() {
        val s = Settings(ThemeMode.DARK, explorerVisible = false, explorerWidthDp = 340, resultsRatio = 0.3f)
        store.save(s)
        assertEquals(s, SettingsStore(dir).load())
    }

    @Test fun corruptFileFallsBackToDefaults() {
        dir.resolve("settings.json").writeText("{ esto no es json")
        assertEquals(Settings(), store.load())
    }

    @Test fun unknownKeysAndMissingKeysAreTolerated() {
        dir.resolve("settings.json").writeText("""{"themeMode":"LIGHT","futuro":42}""")
        assertEquals(Settings(themeMode = ThemeMode.LIGHT), store.load())
    }

    @Test fun savingLeavesNoTempFiles() {
        store.save(Settings()); store.save(Settings(themeMode = ThemeMode.LIGHT))
        assertEquals(listOf("settings.json"), Files.list(dir).use { it.map { p -> p.fileName.toString() }.toList() })
    }
}
