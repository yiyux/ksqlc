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
import ksqlc.storage.SettingsStore
import org.junit.Rule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun ds(name: String, folder: String = "", color: String? = null) =
    DataSource(name, name, DbKind.SQLITE, "jdbc:sqlite:", folder = folder, color = color)

class ConnectionRowsTest {
    @Test fun foldersComeFirstByNameAndAClosedOneHidesItsConnections() {
        val saved = listOf(ds("a"), ds("p1", "Prod"), ds("d1", "dev"), ds("p2", "Prod"), ds("b", folder = "  "))
        val names = { rows: List<ConnectionRow> -> rows.map { if (it is ConnectionRow.Folder) "[${it.name}${if (it.open) "" else " cerrada"}]" else (it as ConnectionRow.Item).ds.name } }
        assertEquals(listOf("[dev]", "d1", "[Prod]", "p1", "p2", "a", "b"), names(connectionRows(saved, emptySet())))
        assertEquals(listOf("[dev]", "d1", "[Prod cerrada]", "a", "b"), names(connectionRows(saved, setOf("Prod"))))
        assertEquals(listOf("a"), names(connectionRows(listOf(ds("a")), emptySet())))
    }

    @Test fun folderAndColourRoundTripThroughTheForm() {
        val f = ConnectionForm().also { it.load(ds("x", "Prod", "azul")) }
        assertEquals("Prod", f.folder); assertEquals("azul", f.color)
        f.folder = " Staging "; f.color = null
        f.toDataSource().let { assertEquals("Staging", it.folder); assertNull(it.color) }
        ConnectionForm().also { it.copyFrom(f.also { g -> g.color = "rojo" }) }.let { assertEquals("rojo", it.color) }
    }
}

@OptIn(ExperimentalTestApi::class)
class ConnectionFoldersUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theExplorerListsFoldersAndTheyCollapse() {
        rule.mainClock.autoAdvance = false
        val store = ConnectionStore(Files.createTempDirectory("ksqlc-folders"), InMemorySecretStore())
        store.save(ds("alfa", "Prod", "rojo"), ""); store.save(ds("beta"), "")
        val state = AppState(CoroutineScope(SupervisorJob() + Dispatchers.Default), store, SettingsStore(Files.createTempDirectory("ksqlc-folders-settings")))
        try {
            rule.setContent { App(state) }
            repeat(3) { rule.mainClock.advanceTimeByFrame() }
            rule.onNodeWithTag("folder:Prod").assertExists()
            rule.onNodeWithTag("ds-node:alfa").assertExists()
            rule.onNodeWithTag("ds-node:beta").assertExists()
            rule.onNodeWithTag("folder:Prod").performClick(); repeat(3) { rule.mainClock.advanceTimeByFrame() }
            rule.onNodeWithTag("ds-node:alfa").assertDoesNotExist()
            rule.onNodeWithTag("ds-node:beta").assertExists()
            rule.onNodeWithTag("folder:Prod").performClick(); repeat(3) { rule.mainClock.advanceTimeByFrame() }
            rule.onNodeWithTag("ds-node:alfa").assertExists()
        } finally { state.dispose() }
    }
}
