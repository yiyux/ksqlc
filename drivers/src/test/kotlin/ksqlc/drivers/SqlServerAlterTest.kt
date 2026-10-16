package ksqlc.drivers

import ksqlc.core.*
import ksqlc.drivers.dialect.SqlServerDialect
import kotlin.test.*

class SqlServerAlterTest {
    private val tbl = DbObject(Namespace(null, "app"), "t", DbObjectKind.TABLE)
    private val q = "[app].[t]"
    private fun col(name: String, type: String = "int", notNull: Boolean = false, default: String? = null) = ColumnModel(name, name, type, notNull, default)
    private val base = TableModel(
        "t", null,
        columns = listOf(col("id", "int", true).copy(identity = Identity()), col("name", "nvarchar(50)"), col("price", "int")),
        keys = listOf(KeyModel("pk_t", "pk_t", true, listOf("id"), clustered = true), KeyModel("uq_name", "uq_name", false, listOf("name"), clustered = false)),
        indexes = listOf(IndexModel("ix_price", "ix_price", false, "nonclustered", listOf(IndexColumn("price")))),
        checks = listOf(CheckModel("ck_price", "ck_price", "[price]>(0)")),
    )
    private fun diff(after: TableModel, before: TableModel = base) = SqlServerDialect.alterTable(tbl, before, after)
    private fun dropDefault(c: String) =
        "declare @n sysname = (select dc.name from sys.default_constraints dc where dc.parent_object_id = object_id(N'[app].[t]') " +
            "and dc.parent_column_id = columnproperty(object_id(N'[app].[t]'), N'$c', 'ColumnId')); " +
            "if @n is not null begin declare @s nvarchar(max) = N'alter table ' + N'[app].[t]' + N' drop constraint ' + quotename(@n); exec(@s) end"

    @Test fun noChangesNoStatements() = assertEquals(emptyList(), diff(base))

    @Test fun renamingAColumnRecreatesTheCheckWithTheNewNameAndKeepsTheIndex() {
        val after = base.copy(
            columns = listOf(base.columns[0], base.columns[1], base.columns[2].copy(name = "cost")),
            indexes = listOf(base.indexes[0].copy(columns = listOf(IndexColumn("cost")))),
        )
        assertEquals(listOf(
            "alter table $q drop constraint [ck_price]",
            "exec sp_rename N'[app].[t].[price]', N'cost', N'COLUMN'",
            "alter table $q add constraint [ck_price] check ([cost]>(0))",
        ), diff(after))
    }

    @Test fun aNameInsideALiteralOfACheckIsNoUseOfTheColumn() {
        val before = base.copy(indexes = emptyList(), checks = listOf(CheckModel("ck_l", "ck_l", "[name]<>'[price]'")))
        val after = before.copy(columns = listOf(base.columns[0], base.columns[1], base.columns[2].copy(name = "cost")))
        assertEquals(listOf("exec sp_rename N'[app].[t].[price]', N'cost', N'COLUMN'"), diff(after, before))
        val uses = before.copy(checks = listOf(CheckModel("ck_l", "ck_l", "[price]<>'[price]'")))
        assertEquals(listOf(
            "alter table $q drop constraint [ck_l]",
            "exec sp_rename N'[app].[t].[price]', N'cost', N'COLUMN'",
            "alter table $q add constraint [ck_l] check ([cost]<>'[price]')",
        ), diff(after.copy(checks = uses.checks), uses))
    }

    @Test fun aFilteredIndexIsRecreatedWithTheNewNameWhenItsColumnIsRenamed() {
        val before = base.copy(checks = emptyList(), indexes = listOf(IndexModel("ix_f", "ix_f", false, "nonclustered", listOf(IndexColumn("price")), condition = "([price] IS NOT NULL)")))
        val after = before.copy(
            columns = listOf(base.columns[0], base.columns[1], base.columns[2].copy(name = "cost")),
            indexes = listOf(before.indexes[0].copy(columns = listOf(IndexColumn("cost")))),
        )
        assertEquals(listOf(
            "drop index [ix_f] on $q",
            "exec sp_rename N'[app].[t].[price]', N'cost', N'COLUMN'",
            "create nonclustered index [ix_f] on $q ([cost]) where ([cost] IS NOT NULL)",
        ), diff(after, before))
    }

    @Test fun addingAColumnWithEverything() {
        val after = base.copy(columns = base.columns + ColumnModel(null, "note", "nvarchar(20)", true, "N'x'", "it's"))
        assertEquals(listOf(
            "alter table $q add [note] nvarchar(20) not null default (N'x')",
            "exec sp_addextendedproperty N'MS_Description', N'it''s', N'SCHEMA', N'app', N'TABLE', N't', N'COLUMN', N'note'",
        ), diff(after))
    }

    @Test fun addingAnIdentityAndAComputedColumn() {
        val after = base.copy(columns = base.columns + col("seq", "int", true).copy(origin = null, identity = Identity()) +
            ColumnModel(null, "dbl", "int", generated = "[price]*(2)", generatedStored = true) + ColumnModel(null, "tri", "int", generated = "[price]*(3)", generatedStored = false))
        assertEquals(listOf(
            "alter table $q add [seq] int identity(1,1) not null",
            "alter table $q add [dbl] as ([price]*(2)) persisted",
            "alter table $q add [tri] as ([price]*(3))",
        ), diff(after))
    }

    @Test fun changingTheTypeDropsAndCreatesAgainWhatDependsOnTheColumn() {
        val after = base.copy(columns = listOf(base.columns[0], base.columns[1], base.columns[2].copy(type = "bigint")))
        assertEquals(listOf(
            "alter table $q drop constraint [ck_price]",
            "drop index [ix_price] on $q",
            "alter table $q alter column [price] bigint null",
            "create nonclustered index [ix_price] on $q ([price])",
            "alter table $q add constraint [ck_price] check ([price]>(0))",
        ), diff(after))
    }

    @Test fun changingNullabilityOfAKeyColumnRecreatesTheKey() {
        val after = base.copy(columns = listOf(base.columns[0], base.columns[1].copy(notNull = true), base.columns[2]))
        assertEquals(listOf(
            "alter table $q drop constraint [uq_name]",
            "alter table $q alter column [name] nvarchar(50) not null",
            "alter table $q add constraint [uq_name] unique nonclustered ([name])",
        ), diff(after))
    }

    @Test fun aChangeOfTypeKeepsAColumnsOwnCollationInItsType() {
        val after = base.copy(columns = listOf(base.columns[0], base.columns[1].copy(type = "nvarchar(80) collate Latin1_General_CS_AS"), base.columns[2]))
        assertEquals(listOf(
            "alter table $q drop constraint [uq_name]",
            "alter table $q alter column [name] nvarchar(80) collate Latin1_General_CS_AS null",
            "alter table $q add constraint [uq_name] unique nonclustered ([name])",
        ), diff(after))
    }

    @Test fun droppingAColumnDropsItsDefaultFirst() {
        val before = base.copy(columns = listOf(base.columns[0], base.columns[1], base.columns[2].copy(default = "(0)")))
        val after = before.copy(columns = before.columns.take(2), indexes = emptyList(), checks = emptyList())
        assertEquals(listOf(
            "alter table $q drop constraint [ck_price]",
            dropDefault("price"),
            "drop index [ix_price] on $q",
            "alter table $q drop column [price]",
        ), diff(after, before))
    }

    @Test fun defaultsAreAddedChangedAndRemoved() {
        fun withDefault(d: String?) = base.copy(columns = listOf(base.columns[0], base.columns[1], base.columns[2].copy(default = d)))
        assertEquals(listOf("alter table $q add default (5) for [price]"), diff(withDefault("5")))
        assertEquals(listOf(dropDefault("price"), "alter table $q add default (1) for [price]"), diff(withDefault("1"), withDefault("0")))
        assertEquals(listOf(dropDefault("price")), diff(withDefault(null), withDefault("0")))
        // the type cannot change under a default: it goes and comes back
        val retyped = withDefault("0").let { it.copy(columns = it.columns.take(2) + it.columns[2].copy(type = "bigint")) }
        assertEquals(listOf(
            "alter table $q drop constraint [ck_price]", dropDefault("price"), "drop index [ix_price] on $q",
            "alter table $q alter column [price] bigint null",
            "alter table $q add default (0) for [price]",
            "create nonclustered index [ix_price] on $q ([price])",
            "alter table $q add constraint [ck_price] check ([price]>(0))",
        ), diff(retyped, withDefault("0")))
    }

    @Test fun renamingOnlyAKeyAForeignKeyACheckOrAnIndexIsASpRename() {
        val fk = ForeignKeyModel("fk_a", "fk_a", listOf("price"), Namespace(null, "app"), "p", listOf("id"))
        val before = base.copy(foreignKeys = listOf(fk))
        val after = before.copy(
            keys = listOf(base.keys[0].copy(name = "pk_new"), base.keys[1]),
            foreignKeys = listOf(fk.copy(name = "fk_b")),
            checks = listOf(base.checks[0].copy(name = "ck_new")),
            indexes = listOf(base.indexes[0].copy(name = "ix_new")),
        )
        assertEquals(listOf(
            "exec sp_rename N'[app].[pk_t]', N'pk_new', N'OBJECT'",
            "exec sp_rename N'[app].[fk_a]', N'fk_b', N'OBJECT'",
            "exec sp_rename N'[app].[ck_price]', N'ck_new', N'OBJECT'",
            "exec sp_rename N'[app].[t].[ix_price]', N'ix_new', N'INDEX'",
        ), diff(after, before))
    }

    @Test fun newKeysIndexesChecksAndForeignKeys() {
        val after = base.copy(
            keys = base.keys + KeyModel(null, "uq_new", false, listOf("name", "price")),
            indexes = base.indexes + IndexModel(null, "ix_new", true, "clustered", listOf(IndexColumn("name", order = "DESC")), listOf("price"), "[price] IS NOT NULL"),
            checks = base.checks + CheckModel(null, "ck_new", "[price]<100"),
            foreignKeys = listOf(ForeignKeyModel(null, "fk_new", listOf("price"), Namespace(null, "other"), "p", listOf("id"), "CASCADE", "SET NULL")),
        )
        assertEquals(listOf(
            "alter table $q add constraint [uq_new] unique ([name], [price])",
            "create unique clustered index [ix_new] on $q ([name] desc) include ([price]) where [price] IS NOT NULL",
            "alter table $q add constraint [ck_new] check ([price]<100)",
            "alter table $q add constraint [fk_new] foreign key ([price]) references [other].[p] ([id]) on delete CASCADE on update SET NULL",
        ), diff(after))
    }

    @Test fun changingWhetherAKeyIsClusteredRecreatesIt() {
        val after = base.copy(keys = listOf(base.keys[0].copy(clustered = false), base.keys[1]))
        assertEquals(listOf(
            "alter table $q drop constraint [pk_t]",
            "alter table $q add constraint [pk_t] primary key nonclustered ([id])",
        ), diff(after))
        assertEquals(listOf("alter table $q add constraint [pk_x] primary key ([id])"),
            diff(base.copy(keys = listOf(KeyModel(null, "pk_x", true, listOf("id")), base.keys[1])).let { it.copy(keys = it.keys) }, base.copy(keys = listOf(base.keys[1]))))
    }

    @Test fun commentsAreAddedUpdatedAndDropped() {
        val before = base.copy(comment = "old", columns = listOf(base.columns[0], base.columns[1].copy(comment = "n"), base.columns[2].copy(comment = "p")))
        val after = before.copy(comment = "it's", columns = listOf(before.columns[0], before.columns[1].copy(comment = "n2"), before.columns[2].copy(comment = null)))
        assertEquals(listOf(
            "exec sp_updateextendedproperty N'MS_Description', N'n2', N'SCHEMA', N'app', N'TABLE', N't', N'COLUMN', N'name'",
            "exec sp_dropextendedproperty N'MS_Description', N'SCHEMA', N'app', N'TABLE', N't', N'COLUMN', N'price'",
            "exec sp_updateextendedproperty N'MS_Description', N'it''s', N'SCHEMA', N'app', N'TABLE', N't'",
        ), diff(after, before))
    }

    @Test fun renamingTheTableComesLastAndCommentsUseTheOldName() {
        val after = base.copy(name = "t2", comment = "x")
        assertEquals(listOf(
            "exec sp_addextendedproperty N'MS_Description', N'x', N'SCHEMA', N'app', N'TABLE', N't'",
            "exec sp_rename N'[app].[t]', N't2'",
        ), diff(after))
    }

    @Test fun identityCannotBeAddedOrRemovedOnAColumnThatExists() {
        val after = base.copy(columns = listOf(base.columns[0], base.columns[1], base.columns[2].copy(identity = Identity(), notNull = true)))
        assertTrue(assertFailsWith<IllegalStateException> { diff(after) }.message!!.contains("identity"))
    }

    @Test fun namesAreQuotedWithBracketsAndLiteralsDoubleTheirQuotes() {
        val odd = DbObject(Namespace(null, "my schema"), "a]b", DbObjectKind.TABLE)
        val m = TableModel("a]b", null, columns = listOf(col("x")))
        val out = SqlServerDialect.alterTable(odd, m, m.copy(columns = m.columns + ColumnModel(null, "y]", "int", comment = "o'k")))
        assertEquals(listOf(
            "alter table [my schema].[a]]b] add [y]]] int null",
            "exec sp_addextendedproperty N'MS_Description', N'o''k', N'SCHEMA', N'my schema', N'TABLE', N'a]b', N'COLUMN', N'y]'",
        ), out)
    }
}
