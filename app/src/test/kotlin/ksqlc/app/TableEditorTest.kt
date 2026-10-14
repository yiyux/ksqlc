package ksqlc.app

import ksqlc.core.*
import ksqlc.drivers.CellValue
import ksqlc.drivers.Session
import ksqlc.drivers.dialect.Dialect
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

/** Pending changes and sending them, on a real SQLite file (no window). */
class TableEditorTest {
    private val file = Files.createTempFile("ksqlc-te", ".db").also { it.toFile().deleteOnExit() }.toString()
    private val url = "jdbc:sqlite:$file"
    private val ns = Namespace(null, "main")
    private fun t(s: String) = CellValue.Text(s)

    init {
        DriverManager.getConnection(url).use {
            it.createStatement().apply {
                execute("create table people(id integer primary key, name text not null, age integer)")
                execute("insert into people values (1, 'Ada', 36), (2, 'Grace', 85), (3, 'Linus', 54)")
                execute("create table loose(a text, b text)")
                execute("insert into loose values ('x', 'y')")
                execute("create view v_people as select * from people")
            }
        }
    }

    private fun names(sql: String = "select name from people order by id") = DriverManager.getConnection(url).use { c ->
        c.createStatement().executeQuery(sql).let { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun open(table: String, key: List<String>, kind: DbObjectKind = DbObjectKind.TABLE, manual: Boolean = false): DataTab {
        val obj = DbObject(ns, table, kind)
        val d = Dialect.of(DbKind.SQLITE)
        val session = Session(DataSource("t", "t", DbKind.SQLITE, url, manualTx = manual), null)
        val sql = d.selectAll(obj)
        val rows = session.runScript(listOf(sql)).single() as ksqlc.core.StatementResult.Rows
        return DataTab(obj, ResultTab(table, rows).also { it.loadMore(PAGE) }, session, sql, TableEditor(obj, d, DbKind.SQLITE, key))
    }

    @Test fun settingACellBackToItsOriginalValueRemovesTheEdit() {
        open("people", listOf("id")).use { tab ->
            val ed = tab.editor
            ed.setCell(0, 1, t("Ada L."), tab.grid); assertTrue(ed.hasChanges); assertEquals(1, ed.changeCount)
            ed.setCell(0, 1, t("Ada"), tab.grid); assertFalse(ed.hasChanges)
            ed.setCell(0, 2, CellValue.Null, tab.grid); assertTrue(ed.hasChanges)
            ed.revert(); assertFalse(ed.hasChanges)
        }
    }

    @Test fun editSendReloadsAndPersists() {
        open("people", listOf("id")).use { tab ->
            tab.editor.setCell(1, 1, t("Grace H."), tab.grid)
            tab.editor.setCell(1, 2, CellValue.Null, tab.grid)
            assertNull(tab.submit())
            assertEquals(1L, tab.affected)
            assertFalse(tab.editor.hasChanges)
            assertEquals("Grace H.", tab.grid.rows[1][1]); assertNull(tab.grid.rows[1][2])      // the grid shows what the database holds
            assertEquals(listOf("Ada", "Grace H.", "Linus"), names())
        }
    }

    @Test fun newRowsAreInsertedAndDefaultsLeftToTheDatabase() {
        open("people", listOf("id")).use { tab ->
            tab.editor.addRow(3)
            val row = tab.grid.rows.size                                   // first new row
            tab.editor.setCell(row, 1, t("Alan"), tab.grid)
            tab.editor.setCell(row, 2, t("41"), tab.grid)
            assertEquals(1, tab.editor.inserts.size)
            assertNull(tab.submit())
            assertEquals(4, tab.grid.rows.size)
            assertEquals(listOf("Ada", "Grace", "Linus", "Alan"), names())
            assertEquals(0, tab.editor.inserts.size)
        }
    }

    @Test fun deletedRowsAreRemoved() {
        open("people", listOf("id")).use { tab ->
            tab.editor.cursorRow = 0; tab.editor.selectedRows.add(2)
            assertEquals(1, tab.editor.deleteSelected(tab.grid))           // selectedRows wins over the cursor row
            assertTrue(2 in tab.editor.deleted)
            assertNull(tab.submit())
            assertEquals(listOf("Ada", "Grace"), names())
        }
    }

    @Test fun deletingANewRowJustDropsIt() {
        open("people", listOf("id")).use { tab ->
            tab.editor.addRow(3)
            tab.editor.cursorRow = tab.grid.rows.size
            assertEquals(1, tab.editor.deleteSelected(tab.grid))
            assertEquals(0, tab.editor.inserts.size)
        }
    }

    @Test fun anEditedRowThatIsAlsoDeletedIsOnlyDeleted() {
        open("people", listOf("id")).use { tab ->
            tab.editor.setCell(0, 1, t("changed"), tab.grid)
            tab.editor.selectedRows.add(0); tab.editor.deleteSelected(tab.grid)
            assertFalse(tab.editor.edits.isNotEmpty())
            assertEquals(listOf("delete"), tab.editor.statements(tab.grid).map { it.sql.substringBefore(' ') })
        }
    }

    @Test fun aFailedSendKeepsEverythingPending() {
        open("people", listOf("id")).use { tab ->
            tab.editor.setCell(0, 1, CellValue.Null, tab.grid)             // name is NOT NULL
            tab.editor.setCell(1, 1, t("fine"), tab.grid)
            val error = tab.submit()
            assertNotNull(error); assertContains(error, "update")          // names the statement
            assertTrue(tab.editor.hasChanges)
            assertEquals(listOf("Ada", "Grace", "Linus"), names())          // all or nothing
        }
    }

    @Test fun aValueThatDoesNotFitIsReportedBeforeSending() {
        open("people", listOf("id")).use { tab ->
            tab.editor.setCell(0, 2, t("old"), tab.grid)                    // age is INTEGER
            val error = tab.submit()
            assertNotNull(error); assertContains(error, "age")
            assertTrue(tab.editor.hasChanges)
        }
    }

    @Test fun tablesWithoutAKeyAllowInsertsOnly() {
        open("loose", emptyList()).use { tab ->
            val ed = tab.editor
            assertTrue(ed.canInsert); assertFalse(ed.canEditRows)
            assertNotNull(ed.readOnlyReason()); assertContains(ed.readOnlyReason()!!, "clave primaria")
            ed.setCell(0, 0, t("nope"), tab.grid); assertFalse(ed.hasChanges)       // ignored
            ed.selectedRows.add(0); assertEquals(0, ed.deleteSelected(tab.grid))
            ed.addRow(2); ed.setCell(1, 0, t("new"), tab.grid)
            assertNull(tab.submit())
            assertEquals(listOf("x", "new"), names("select a from loose order by rowid"))
        }
    }

    @Test fun viewsAreReadOnly() {
        open("v_people", listOf("id"), DbObjectKind.VIEW).use { tab ->
            assertFalse(tab.editor.canInsert); assertFalse(tab.editor.canEditRows)
            tab.editor.addRow(3); assertEquals(0, tab.editor.inserts.size)
            assertContains(tab.editor.readOnlyReason()!!, "vista")
        }
    }

    @Test fun manualModeLeavesTheSendPendingUntilCommit() {
        open("people", listOf("id"), manual = true).use { tab ->
            tab.editor.setCell(0, 1, t("Pending"), tab.grid)
            assertNull(tab.submit())
            assertTrue(tab.pending); assertTrue(tab.unsaved)
            assertEquals("Pending", tab.grid.rows[0][1])                    // the tab sees its own change
            assertEquals(listOf("Ada", "Grace", "Linus"), names())           // other connections do not
            tab.commit()
            assertFalse(tab.pending)
            assertEquals(listOf("Pending", "Grace", "Linus"), names())
        }
    }

    @Test fun rollbackDropsTheUnconfirmedChanges() {
        open("people", listOf("id"), manual = true).use { tab ->
            tab.editor.setCell(0, 1, t("Gone"), tab.grid); tab.submit()
            tab.rollback()
            assertFalse(tab.pending)
            assertEquals(listOf("Ada", "Grace", "Linus"), names())
        }
    }

    @Test fun previewShowsTheValuesWritten() {
        open("people", listOf("id")).use { tab ->
            tab.editor.setCell(0, 1, t("O'Neil"), tab.grid)
            val p = tab.editor.statements(tab.grid).single().preview
            assertContains(p, "'O''Neil'"); assertContains(p, "= 1")
        }
    }

    private fun DataTab.use(block: (DataTab) -> Unit) = try { block(this) } finally { close() }
}
