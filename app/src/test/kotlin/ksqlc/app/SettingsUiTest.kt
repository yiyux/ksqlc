package ksqlc.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ksqlc.core.DataSource
import ksqlc.core.DbKind
import ksqlc.storage.ConnectionStore
import ksqlc.storage.InMemorySecretStore
import ksqlc.storage.PasswordStorage
import ksqlc.storage.SettingsStore
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class SettingsUiTest {
    @get:Rule val rule = createComposeRule()

    private val dir = Files.createTempDirectory("ksqlc-ui-s")
    private val secrets = InMemorySecretStore()
    private val store = ConnectionStore(dir, secrets)
    private val state = AppState(CoroutineScope(SupervisorJob() + Dispatchers.Default), store, SettingsStore(Files.createTempDirectory("ksqlc-ui-ss")), preload = false)

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }
    @After fun tearDown() = state.dispose()

    private fun frames(n: Int = 5) = repeat(n) { rule.mainClock.advanceTimeByFrame() }
    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test fun forgettingPasswordsMovesThemOutOfTheStoreAndSavesTheSetting() {
        store.save(DataSource("1", "a", DbKind.POSTGRES, "jdbc:postgresql://h/db"), "pw")
        rule.setContent { App(state) }
        rule.onNodeWithTag("settings-button").performClick(); frames()
        assert(exists("settings-dialog"))
        rule.onNodeWithTag("pw-none").performClick(); frames()
        rule.onNodeWithTag("settings-apply").performClick()
        val end = System.nanoTime() + 20_000_000_000
        while (exists("settings-dialog")) { check(System.nanoTime() < end) { "timeout" }; Thread.sleep(50); frames(1) }
        assertEquals(PasswordStorage.NONE, state.settings.passwordStorage)
        assertNull(secrets.get("1"))                       // gone from the old store
        assertEquals("pw", store.password("1"))            // kept by the new, in-memory one
    }

    @Test fun encryptedFileNeedsAMatchingMasterPassword() {
        rule.setContent { App(state) }
        rule.onNodeWithTag("settings-button").performClick(); frames()
        rule.onNodeWithTag("pw-file").performClick(); frames()
        rule.onNodeWithTag("pw-master").performTextInput("abc")
        rule.onNodeWithTag("pw-repeat").performTextInput("xyz")
        rule.onNodeWithTag("settings-apply").performClick(); frames()
        assert(exists("settings-error"))
        assertEquals(PasswordStorage.KEYCHAIN, state.settings.passwordStorage)
    }
}
