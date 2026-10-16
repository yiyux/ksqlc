package ksqlc.drivers

import ksqlc.core.*
import ksqlc.drivers.dialect.ModifyCaps
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

    @Test fun aForeignKeyThatStaysNeedsAnIndexStartingWithItsColumns() {
        val fk = ForeignKeyModel("fk", "fk", listOf("b"), Namespace("app", null), "parent", listOf("id"))
        val before = TableModel("t", null, columns = listOf(ColumnModel("a", "a", "int", true), ColumnModel("b", "b", "int")),
            keys = listOf(KeyModel("PRIMARY", "PRIMARY", true, listOf("a"))),
            foreignKeys = listOf(fk), indexes = listOf(IndexModel("b_idx", "b_idx", false, "btree", listOf(IndexColumn("b")))))
        assertNull(fkIndexProblem(before, before))
        val noIndex = before.copy(indexes = emptyList())
        assertTrue(fkIndexProblem(before, noIndex)!!.contains("fk"))
        assertNull(fkIndexProblem(before, noIndex.copy(keys = noIndex.keys + KeyModel("uk_b", "uk_b", false, listOf("b", "a")))))   // a key counts as an index
        assertNull(fkIndexProblem(before, noIndex.copy(foreignKeys = emptyList())))                                               // the foreign key goes away too
        assertNull(fkIndexProblem(before, noIndex.copy(foreignKeys = listOf(fk.copy(columns = listOf("a"))))))                     // a changed one: the server makes its own index
        val renamed = noIndex.copy(columns = listOf(before.columns[0], before.columns[1].copy(name = "c")), foreignKeys = listOf(fk.copy(columns = listOf("c"))),
            indexes = listOf(IndexModel("b_idx", "b_idx", false, "btree", listOf(IndexColumn("c")))))
        assertNull(fkIndexProblem(before, renamed))                                                                                // the rename carried the index with it
    }

    @Test fun withASingleIdentityThereCanBeOnlyOneAndItMustLeadAKey() {
        val caps = ModifyCaps(singleIdentity = true)
        val id = ColumnModel("a", "a", "int", true, identity = Identity())
        val ok = TableModel("t", null, columns = listOf(id, ColumnModel("b", "b", "int")), keys = listOf(KeyModel("PRIMARY", "PRIMARY", true, listOf("a"))))
        assertNull(tableProblem(ok, caps))
        assertTrue(tableProblem(ok.copy(keys = emptyList()), caps)!!.contains("clave"))
        assertTrue(tableProblem(ok.copy(keys = listOf(KeyModel("PRIMARY", "PRIMARY", true, listOf("b", "a")))), caps)!!.contains("clave"))
        assertTrue(tableProblem(ok.copy(columns = ok.columns + id.copy(name = "c", origin = "c")), caps)!!.contains("una"))
        assertNull(tableProblem(ok.copy(keys = emptyList(), columns = ok.columns), ModifyCaps()))     // PostgreSQL: no such rule
    }
}
