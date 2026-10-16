package ksqlc.drivers

import ksqlc.core.*
import ksqlc.drivers.dialect.MySqlDialect
import kotlin.test.*

class MySqlAlterTest {
    private val tbl = DbObject(Namespace("app", null), "t", DbObjectKind.TABLE)
    private val q = "`app`.`t`"
    private fun col(name: String, type: String = "int", notNull: Boolean = false, default: String? = null) = ColumnModel(name, name, type, notNull, default)
    private val base = TableModel(
        "t", null,
        columns = listOf(col("id", "bigint", true).copy(identity = Identity()), col("name", "varchar(50)"), col("price", "int")),
        keys = listOf(KeyModel("PRIMARY", "PRIMARY", true, listOf("id")), KeyModel("t_name_key", "t_name_key", false, listOf("name"))),
        indexes = listOf(IndexModel("t_price_idx", "t_price_idx", false, "btree", listOf(IndexColumn("price")))),
        checks = listOf(CheckModel("t_price_ck", "t_price_ck", "(`price` > 0)")),
    )
    private fun diff(after: TableModel, before: TableModel = base) = MySqlDialect.alterTable(tbl, before, after)

    @Test fun noChangesNoStatements() = assertEquals(emptyList(), diff(base))

    @Test fun renamingAColumnKeepsKeysAndIndexesButRecreatesTheCheckWithTheNewName() {
        val after = base.copy(
            columns = listOf(base.columns[0], base.columns[1], base.columns[2].copy(name = "cost")),
            indexes = listOf(base.indexes[0].copy(columns = listOf(IndexColumn("cost")))),
            checks = listOf(base.checks[0]),
        )
        assertEquals(listOf(
            "alter table $q drop constraint `t_price_ck`",
            "alter table $q rename column `price` to `cost`",
            "alter table $q add constraint `t_price_ck` check ((`cost` > 0))",
        ), diff(after))
    }

    @Test fun addingAColumnWithEverything() {
        val after = base.copy(columns = base.columns + ColumnModel(null, "note", "varchar(20)", true, "'x'", "it's"))
        assertEquals(listOf("alter table $q add column `note` varchar(20) not null default 'x' comment 'it''s'"), diff(after))
    }

    @Test fun onUpdateWithoutADefaultIsWrittenWithoutTheDefaultKeyword() {
        val after = base.copy(columns = base.columns + ColumnModel(null, "d", "datetime", true, "on update CURRENT_TIMESTAMP"))
        assertEquals(listOf("alter table $q add column `d` datetime not null on update CURRENT_TIMESTAMP"), diff(after))
    }

    @Test fun commentsEscapeBackslashesAndQuotes() {
        val after = base.copy(columns = base.columns + ColumnModel(null, "c", "int", comment = "a\\b'c"))
        assertEquals(listOf("alter table $q add column `c` int null comment 'a\\\\b''c'"), diff(after))
    }

    @Test fun changingTypeNotNullDefaultAndCommentModifiesTheWholeDefinition() {
        val after = base.copy(columns = listOf(base.columns[0], base.columns[1].copy(type = "varchar(80)", notNull = true, default = "'a'", comment = "n"), base.columns[2]))
        assertEquals(listOf("alter table $q modify column `name` varchar(80) not null default 'a' comment 'n'"), diff(after))
    }

    @Test fun droppingAColumnDropsTheIndexAndTheCheckThatUseIt() {
        val after = base.copy(columns = base.columns.take(2), indexes = emptyList(), checks = emptyList())
        assertEquals(listOf(
            "alter table $q drop constraint `t_price_ck`",
            "alter table $q drop index `t_price_idx`",
            "alter table $q drop column `price`",
        ), diff(after))
    }

    @Test fun keysAndIndexesRenameInPlaceAndTheyAreRecreatedWhenTheirContentChanges() {
        val renamed = base.copy(keys = listOf(base.keys[0], base.keys[1].copy(name = "uq_name")), indexes = listOf(base.indexes[0].copy(name = "idx_price")))
        assertEquals(listOf(
            "alter table $q rename index `t_name_key` to `uq_name`",
            "alter table $q rename index `t_price_idx` to `idx_price`",
        ), diff(renamed))
        val changed = base.copy(indexes = listOf(base.indexes[0].copy(method = "hash", columns = listOf(IndexColumn("price", order = "DESC"), IndexColumn("name")))))
        assertEquals(listOf(
            "alter table $q drop index `t_price_idx`",
            "create index `t_price_idx` using hash on $q (`price` desc, `name`)",
        ), diff(changed))
    }

    @Test fun primaryKeyRenameDoesNothingAndChangingItsColumnsIsOneStatement() {
        assertEquals(emptyList(), diff(base.copy(keys = listOf(base.keys[0].copy(name = "pk"), base.keys[1]))))
        val after = base.copy(keys = listOf(base.keys[0].copy(columns = listOf("id", "name")), base.keys[1]))
        assertEquals(listOf("alter table $q drop primary key, add primary key (`id`, `name`)"), diff(after))
    }

    @Test fun uniqueKeysIndexExpressionsAndChecks() {
        val after = base.copy(
            keys = base.keys + KeyModel(null, "t_price_key", false, listOf("price", "name")),
            indexes = base.indexes + IndexModel(null, "t_lower", false, "btree", listOf(IndexColumn("lower(`name`)", expression = true))),
            checks = base.checks + CheckModel(null, "t_name_ck", "`name` <> ''"),
        )
        assertEquals(listOf(
            "alter table $q add constraint `t_price_key` unique (`price`, `name`)",
            "alter table $q add constraint `t_name_ck` check (`name` <> '')",
            "create index `t_lower` using btree on $q ((lower(`name`)))",
        ), diff(after))
    }

    @Test fun foreignKeysAreDroppedAndCreatedAndDefaultToTheTablesOwnDatabase() {
        val fk = ForeignKeyModel(null, "t_ref_fk", listOf("price"), Namespace(null, null), "parent", listOf("id"), onDelete = "CASCADE", onUpdate = "SET NULL")
        val withFk = diff(base.copy(foreignKeys = listOf(fk)))
        assertEquals(listOf("alter table $q add constraint `t_ref_fk` foreign key (`price`) references `app`.`parent` (`id`) on delete CASCADE on update SET NULL"), withFk)
        val before = base.copy(foreignKeys = listOf(fk.copy(origin = "t_ref_fk", refNs = Namespace("app", null))))
        assertEquals(listOf("alter table $q drop foreign key `t_ref_fk`"), diff(base, before))
        val other = fk.copy(refNs = Namespace("other", null))
        assertTrue(diff(base.copy(foreignKeys = listOf(other))).single().contains("references `other`.`parent`"))
    }

    @Test fun auto_incrementIsAddedAfterTheKeyThatSupportsIt() {
        val before = base.copy(columns = listOf(col("id", "bigint", true), base.columns[1], base.columns[2]), keys = emptyList(), indexes = emptyList(), checks = emptyList())
        val after = before.copy(columns = listOf(before.columns[0].copy(identity = Identity()), before.columns[1], before.columns[2]), keys = listOf(KeyModel(null, "PRIMARY", true, listOf("id"))))
        assertEquals(listOf(
            "alter table $q add primary key (`id`)",
            "alter table $q modify column `id` bigint not null auto_increment",
        ), diff(after, before))
        // taking it away
        assertEquals(listOf("alter table $q modify column `id` bigint not null"), diff(base.copy(columns = listOf(base.columns[0].copy(identity = null)) + base.columns.drop(1))))
    }

    @Test fun generatedColumnsAreOnlyCreatedAndKeepStoredOrVirtual() {
        val stored = ColumnModel(null, "g1", "int", generated = "price * 2")
        val virtual = ColumnModel(null, "g2", "int", generated = "price * 3", generatedStored = false)
        assertEquals(listOf(
            "alter table $q add column `g1` int generated always as (price * 2) stored null",
            "alter table $q add column `g2` int generated always as (price * 3) virtual null",
        ), diff(base.copy(columns = base.columns + stored + virtual)))
    }

    @Test fun commentAndRenameOfTheTableGoLast() {
        val after = base.copy(name = "t2", comment = "it's")
        assertEquals(listOf("alter table $q comment = 'it''s'", "alter table $q rename to `app`.`t2`"), diff(after))
    }

    @Test fun namesWithBackticksAndReservedWordsAreQuoted() {
        val after = base.copy(columns = base.columns + ColumnModel(null, "we`ird order", "int"))
        assertEquals(listOf("alter table $q add column `we``ird order` int null"), diff(after))
    }

    @Test fun renamingOnlyAForeignKeyDropsAndCreatesIt() {
        val fk = ForeignKeyModel("t_ref_fk", "t_ref_fk", listOf("price"), Namespace("app", null), "parent", listOf("id"), onDelete = "CASCADE", onUpdate = "SET NULL")
        val before = base.copy(foreignKeys = listOf(fk))
        val after = base.copy(foreignKeys = listOf(fk.copy(name = "t_ref2_fk")))
        assertEquals(listOf(
            "alter table $q drop foreign key `t_ref_fk`",
            "alter table $q add constraint `t_ref2_fk` foreign key (`price`) references `app`.`parent` (`id`) on delete CASCADE on update SET NULL",
        ), diff(after, before))
    }

    @Test fun renamingOnlyACheckDropsAndCreatesIt() {
        val after = base.copy(checks = listOf(base.checks[0].copy(name = "t_cost_ck")))
        assertEquals(listOf(
            "alter table $q drop constraint `t_price_ck`",
            "alter table $q add constraint `t_cost_ck` check ((`price` > 0))",
        ), diff(after))
    }

    @Test fun autoIncrementGoesAwayBeforeItsKeyIsDropped() {
        val unticked = base.copy(columns = listOf(base.columns[0].copy(identity = null)) + base.columns.drop(1), keys = listOf(base.keys[1]))
        assertEquals(listOf(
            "alter table $q modify column `id` bigint not null",
            "alter table $q drop primary key",
        ), diff(unticked))
        // the column is deleted together with its key
        val deleted = base.copy(columns = base.columns.drop(1), keys = listOf(base.keys[1]))
        assertEquals(listOf(
            "alter table $q modify column `id` bigint not null",
            "alter table $q drop primary key",
            "alter table $q drop column `id`",
        ), diff(deleted))
        // unticked and renamed: the old name is the one the table has at that point
        val renamed = base.copy(columns = listOf(base.columns[0].copy(identity = null, name = "uid")) + base.columns.drop(1), keys = listOf(base.keys[0].copy(columns = listOf("uid")), base.keys[1]))
        assertEquals(listOf(
            "alter table $q modify column `id` bigint not null",
            "alter table $q rename column `id` to `uid`",
        ), diff(renamed))
    }

    @Test fun functionalIndexOnARenamedColumnIsRecreatedWithTheNewName() {
        val ix = IndexModel("t_lower", "t_lower", false, "btree", listOf(IndexColumn("lower(`name`)", expression = true)))
        val before = base.copy(indexes = listOf(ix))
        val after = base.copy(columns = listOf(base.columns[0], base.columns[1].copy(name = "title"), base.columns[2]), indexes = listOf(ix.copy(columns = listOf(IndexColumn("lower(`name`)", expression = true)))), keys = listOf(base.keys[0], base.keys[1].copy(columns = listOf("title"))))
        assertEquals(listOf(
            "alter table $q drop index `t_lower`",
            "alter table $q rename column `name` to `title`",
            "create index `t_lower` using btree on $q ((lower(`title`)))",
        ), diff(after, before))
    }

    @Test fun renamesAreAppliedInOnePassSoASwapDoesNotChain() {
        val ck = CheckModel("t_ck", "t_ck", "(`price` > `id`)")
        val before = base.copy(checks = listOf(ck))
        val after = before.copy(columns = listOf(base.columns[0].copy(name = "price"), base.columns[1], base.columns[2].copy(name = "cost")))
        val create = diff(after, before).single { "add constraint" in it }
        assertEquals("alter table $q add constraint `t_ck` check ((`cost` > `price`))", create)
    }
}
