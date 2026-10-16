package ksqlc.app

import ksqlc.core.*
import ksqlc.drivers.dialect.Dialect
import kotlin.test.*

class ModifyDraftTest {
    private val ns = Namespace(null, "app")
    private val tbl = DbObject(ns, "t", DbObjectKind.TABLE)
    private val before = TableModel(
        "t", null,
        columns = listOf(ColumnModel("id", "id", "bigint", true), ColumnModel("name", "name", "text")),
        keys = listOf(KeyModel("t_pkey", "t_pkey", true, listOf("id"))),
        indexes = listOf(IndexModel("t_name_idx", "t_name_idx", false, "btree", listOf(IndexColumn("name")))),
    )
    private fun draft() = ModifyDraft(tbl, before, Dialect.of(DbKind.POSTGRES))

    @Test fun anEngineWithoutTransactionalDdlWarnsInTheNoticeAndChecksAutoIncrement() {
        val d = ModifyDraft(DbObject(Namespace("app", null), "t", DbObjectKind.TABLE), TableModel("t", null, columns = listOf(ColumnModel("a", "a", "int", true), ColumnModel("b", "b", "int"))), Dialect.of(DbKind.MYSQL))
        assertTrue(d.notice!!.contains("cada sentencia"))
        assertNull(ModifyDraft(tbl, before, Dialect.of(DbKind.POSTGRES)).notice)
        d.updateColumn(0) { it.copy(identity = Identity()) }
        assertTrue(d.problem!!.contains("auto_increment"))
        assertEquals(emptyList(), d.statements)
    }

    @Test fun untouchedMeansNoStatements() = draft().let { assertNull(it.problem); assertEquals(emptyList(), it.statements) }

    @Test fun renamingAColumnRewritesItsReferencesAndOnlyRenames() {
        val d = draft()
        d.updateColumn(1) { it.copy(name = "title") }
        assertEquals("title", d.model.indexes[0].columns[0].name)
        assertEquals(listOf("alter table \"app\".\"t\" rename column \"name\" to \"title\""), d.statements)
        d.updateColumn(0) { it.copy(name = "pk") }
        assertEquals(listOf("pk"), d.model.keys[0].columns)
    }

    @Test fun mySqlOffersNeitherHashNorSetDefaultAndPostgresKeepsBoth() {
        val my = ModifyDraft(tbl, before, Dialect.of(DbKind.MYSQL)).caps
        assertEquals(listOf("btree"), my.indexMethods)
        assertFalse("SET DEFAULT" in my.fkActions)
        assertTrue("NO ACTION" in my.fkActions && "CASCADE" in my.fkActions)
        val pg = ModifyDraft(tbl, before, Dialect.of(DbKind.POSTGRES)).caps
        assertTrue("SET DEFAULT" in pg.fkActions && "hash" in pg.indexMethods)
    }

    @Test fun theNoticeListsWhatTheDialogCannotShow() {
        val d = ModifyDraft(tbl, before.copy(skipped = listOf("ft_idx", "PRIMARY")), Dialect.of(DbKind.MYSQL))
        assertTrue(d.notice!!.contains("ft_idx") && d.notice!!.contains("PRIMARY") && d.notice!!.contains("cada sentencia"), d.notice)
        assertNull(ModifyDraft(tbl, before, Dialect.of(DbKind.POSTGRES)).notice)
    }

    @Test fun removingTheIndexAForeignKeyRestsOnIsAProblemOnMySqlOnly() {
        val fk = ForeignKeyModel("fk", "fk", listOf("name"), Namespace("app", null), "parent", listOf("id"))
        val withFk = before.copy(foreignKeys = listOf(fk))
        val mysql = ModifyDraft(DbObject(Namespace("app", null), "t", DbObjectKind.TABLE), withFk, Dialect.of(DbKind.MYSQL))
        assertNull(mysql.problem)
        mysql.remove(Sel("indexes", 0))
        assertTrue(mysql.problem!!.contains("fk"), mysql.problem)
        assertEquals(emptyList(), mysql.statements)
        val pg = ModifyDraft(tbl, withFk, Dialect.of(DbKind.POSTGRES))
        pg.remove(Sel("indexes", 0))
        assertNull(pg.problem)
    }

    @Test fun addingColumnsGivesThemUniqueNamesAndSelectsTheNewOne() {
        val d = draft()
        d.add("columns"); d.add("columns")
        assertEquals(listOf("id", "name", "column_name", "column_name_2"), d.model.columns.map { it.name })
        assertEquals(Sel("columns", 3), d.selected)
        assertEquals(2, d.statements.size)
        d.remove(Sel("columns", 3)); d.remove(Sel("columns", 2))
        assertEquals(emptyList(), d.statements)
        assertEquals(Sel("columns"), d.selected)
    }

    @Test fun addingKeysChecksIndexesAndForeignKeysStartOnTheFirstColumn() {
        val d = draft()
        d.add("keys"); d.add("checks"); d.add("indexes"); d.add("fks")
        assertEquals(listOf("id"), d.model.keys.last().columns)
        assertEquals(false, d.model.keys.last().primary)
        assertEquals("t_key", d.model.keys.last().name)
        assertEquals(listOf("id"), d.model.indexes.last().columns.map { it.name })
        assertEquals("t_idx", d.model.indexes.last().name)
        assertEquals(listOf("id"), d.model.foreignKeys.last().columns)
        assertNotNull(d.problem)      // the new check has no expression and the foreign key no table
        assertEquals(emptyList(), d.statements)
    }

    @Test fun duplicatingCopiesWithoutOriginAndANewName() {
        val d = draft()
        d.duplicate(Sel("columns", 1))
        val copy = d.model.columns[2]
        assertNull(copy.origin); assertEquals("name_copy", copy.name); assertEquals("text", copy.type)
        d.duplicate(Sel("columns", 1))
        assertEquals("name_copy_2", d.model.columns[3].name)
    }

    @Test fun aProblemBlocksTheStatements() {
        val d = draft()
        d.updateColumn(1) { it.copy(name = "id") }
        assertNotNull(d.problem); assertEquals(emptyList(), d.statements)
        d.updateColumn(1) { it.copy(name = "name") }
        d.updateKey(0) { it.copy(columns = emptyList()) }
        assertNotNull(d.problem)
    }

    @Test fun renamingOverAnotherColumnsNameNeverStealsItsReferences() {
        val d = draft()
        d.updateColumn(1) { it.copy(name = "id") }
        d.updateColumn(1) { it.copy(name = "idx") }
        assertEquals(listOf("id"), d.model.keys[0].columns)
        assertEquals(listOf("idx"), d.model.indexes[0].columns.map { it.name })
    }

    @Test fun anAliasReturnsToTheRealNameWhenTheOtherColumnIsRenamedAway() {
        val d = draft()
        d.updateColumn(1) { it.copy(name = "id") }
        d.updateColumn(0) { it.copy(name = "pk") }
        assertEquals(listOf("pk"), d.model.keys[0].columns)
        assertEquals(listOf("id"), d.model.indexes[0].columns.map { it.name })
        assertNull(d.problem)
    }

    @Test fun typingANameThatPassesThroughAnotherColumnsNameKeepsEveryReference() {
        val d = draft()
        for (n in 1.."identifier".length) d.updateColumn(1) { it.copy(name = "identifier".take(n)) }
        assertEquals(listOf("id"), d.model.keys[0].columns)
        assertEquals(listOf("identifier"), d.model.indexes[0].columns.map { it.name })
        assertEquals(listOf("alter table \"app\".\"t\" rename column \"name\" to \"identifier\""), d.statements)
    }

    @Test fun listHelpers() {
        assertEquals(listOf(1, 9, 3), listOf(1, 2, 3).replaced(1, 9))
        assertEquals(listOf(2, 1, 3), listOf(1, 2, 3).moved(0, 1))
        assertEquals(listOf(1, 2, 3), listOf(1, 2, 3).moved(0, -1))      // out of range: unchanged
    }
}
