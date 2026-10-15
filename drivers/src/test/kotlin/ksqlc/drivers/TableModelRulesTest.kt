package ksqlc.drivers

import ksqlc.core.*
import kotlin.test.*

class TableModelRulesTest {
    private val ns = Namespace(null, "app")
    private val ok = TableModel(
        "t", null,
        columns = listOf(ColumnModel("id", "id", "bigint", true), ColumnModel("name", "name", "text")),
        keys = listOf(KeyModel("t_pkey", "t_pkey", true, listOf("id"))),
        indexes = listOf(IndexModel("ix", "ix", false, "btree", listOf(IndexColumn("name")))),
    )

    @Test fun aValidModelHasNoProblem() = assertNull(tableProblem(ok))

    @Test fun reportsTheFirstProblem() {
        assertNotNull(tableProblem(ok.copy(name = " ")))
        assertNotNull(tableProblem(ok.copy(columns = emptyList())))
        assertNotNull(tableProblem(ok.copy(columns = ok.columns + ColumnModel(null, "ID", "int"))))      // repeated, ignoring case
        assertNotNull(tableProblem(ok.copy(columns = listOf(ok.columns[0].copy(type = " "), ok.columns[1]))))
        assertNotNull(tableProblem(ok.copy(columns = listOf(ok.columns[0].copy(identity = Identity(), notNull = false), ok.columns[1]))))
        assertNotNull(tableProblem(ok.copy(keys = listOf(ok.keys[0].copy(columns = emptyList())))))
        assertNotNull(tableProblem(ok.copy(keys = listOf(ok.keys[0].copy(columns = listOf("ghost"))))))
        assertNotNull(tableProblem(ok.copy(keys = ok.keys + KeyModel(null, "t_pk2", true, listOf("name")))))      // two primary keys
        assertNotNull(tableProblem(ok.copy(checks = listOf(CheckModel(null, "ck", " ")))))
        assertNotNull(tableProblem(ok.copy(checks = listOf(CheckModel(null, "t_pkey", "(id > 0)")))))      // name already used by a key
        assertNotNull(tableProblem(ok.copy(indexes = listOf(ok.indexes[0].copy(columns = emptyList())))))
        assertNotNull(tableProblem(ok.copy(indexes = listOf(ok.indexes[0].copy(columns = listOf(IndexColumn("ghost")))))))
        assertNull(tableProblem(ok.copy(indexes = listOf(ok.indexes[0].copy(columns = listOf(IndexColumn("lower(name)", expression = true)))))))
        assertNotNull(tableProblem(ok.copy(foreignKeys = listOf(ForeignKeyModel(null, "fk", listOf("id"), ns, "", listOf("id"))))))
        assertNotNull(tableProblem(ok.copy(foreignKeys = listOf(ForeignKeyModel(null, "fk", listOf("id"), ns, "p", listOf("a", "b"))))))
        assertNull(tableProblem(ok.copy(foreignKeys = listOf(ForeignKeyModel(null, "fk", listOf("id"), ns, "p", listOf("id"))))))
    }

    @Test fun droppedColumnsAndTypeChangesLoseData() {
        assertEquals(emptyList(), destructiveChanges(ok, ok))
        val dropped = ok.copy(columns = listOf(ok.columns[0]))
        assertEquals(1, destructiveChanges(ok, dropped).size)
        assertTrue(destructiveChanges(ok, dropped).single().contains("name"))
        val retyped = ok.copy(columns = listOf(ok.columns[0], ok.columns[1].copy(type = "integer")))
        assertTrue(destructiveChanges(ok, retyped).single().contains("name"))
        val renamed = ok.copy(columns = listOf(ok.columns[0], ok.columns[1].copy(name = "title")))
        assertEquals(emptyList(), destructiveChanges(ok, renamed))
    }
}
