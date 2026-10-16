package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.Introspector
import ksqlc.drivers.dialect.Dialect
import ksqlc.drivers.mySqlPrelude
import ksqlc.drivers.openConnection
import ksqlc.drivers.runStepwise
import org.junit.Assume.assumeTrue
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.mariadb.MariaDBContainer
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

private val SETUP = listOf(
    "create database at",
    "create table at.parent(id int primary key)",
    """create table at.t(
         id int not null auto_increment, name varchar(50) not null default 'x', parent_id int, price int,
         primary key (id), unique key t_name_key (name), index t_price_idx (price),
         constraint t_parent_fk foreign key (parent_id) references at.parent(id),
         constraint t_price_ck check (price > 0))""",
    "insert into at.parent values (1)",
    "insert into at.t(name, parent_id, price) values ('a', 1, 5), ('b', 1, 6)",
)

abstract class TableAlterMySqlBase(private val kind: DbKind) {
    protected abstract val fx: ServerFixture
    private fun obj(name: String) = DbObject(Namespace("at", null), name, DbObjectKind.TABLE)
    private fun intro() = Introspector(fx.ds, fx.password)
    private fun model(name: String) = intro().use { it.tableModel(obj(name))!! }
    private fun statements(name: String, f: (TableModel) -> TableModel): List<String> {
        val before = model(name)
        return Dialect.of(kind).alterTable(obj(name), before, f(before))
    }
    @BeforeTest fun fresh() { runStepwise(fx.ds, fx.password, listOf("drop table if exists at.t2", "create table at.t2(a int, b int)")) }

    @Test fun aLoadedModelProducesNoStatements() {
        for (t in listOf("t", "parent", "t2")) assertEquals(emptyList(), statements(t) { it }, t)
    }

    @Test fun appliesAColumnAndConstraintChangeAndReadsBackWithNoDifference() {
        val sqlList = statements("t2") { m ->
            m.copy(
                comment = "tabla t2",
                columns = listOf(
                    m.columns[0].copy(name = "uid", notNull = true, type = "bigint"),
                    m.columns[1],
                    ColumnModel(null, "label", "varchar(30)", default = "'n'", comment = "etiqueta"),
                    ColumnModel(null, "ref", "int"),
                ),
                keys = listOf(KeyModel(null, "PRIMARY", true, listOf("uid"))),
                indexes = listOf(IndexModel(null, "t2_label_idx", false, "btree", listOf(IndexColumn("label", order = "DESC")))),
                checks = listOf(CheckModel(null, "t2_uid_ck", "`uid` > 0")),
                foreignKeys = listOf(ForeignKeyModel(null, "t2_ref_fk", listOf("ref"), Namespace("at", null), "parent", listOf("id"), onDelete = "SET NULL")),
            )
        }
        val r = runStepwise(fx.ds, fx.password, sqlList)
        assertNull(r.error, sqlList.joinToString("\n")); assertEquals(sqlList.size, r.applied)
        val m = model("t2")
        assertEquals("tabla t2", m.comment)
        assertEquals(listOf("uid", "b", "label", "ref"), m.columns.map { it.name })
        assertEquals("bigint", m.columns[0].type); assertTrue(m.columns[0].notNull)
        assertEquals("etiqueta", m.columns[2].comment); assertEquals("'n'", m.columns[2].default)
        assertEquals(listOf("PRIMARY"), m.keys.map { it.name })
        assertEquals("SET NULL", m.foreignKeys.single().onDelete)
        assertEquals("DESC", m.indexes.single { it.name == "t2_label_idx" }.columns[0].order)
        assertEquals(emptyList(), statements("t2") { it })      // what was applied reads back with no difference
    }

    @Test fun renamingAColumnKeepsDataIndexAndCheck() {
        val sqlList = statements("t") { m ->
            m.copy(
                columns = m.columns.map { if (it.name == "price") it.copy(name = "cost") else it },
                indexes = m.indexes.map { ix -> ix.copy(columns = ix.columns.map { c -> if (c.name == "price") c.copy(name = "cost") else c }) },
            )
        }
        val r = runStepwise(fx.ds, fx.password, sqlList)
        assertNull(r.error, sqlList.joinToString("\n"))
        val m = model("t")
        assertEquals(listOf("id", "name", "parent_id", "cost"), m.columns.map { it.name })
        assertEquals(listOf("cost"), m.indexes.single { it.name == "t_price_idx" }.columns.map { it.name })
        assertTrue(m.checks.single().expression.contains("cost"), m.checks.single().expression)
    }

    @Test fun changingThePrimaryKeyOfATableWithAutoIncrementWorks() {
        runStepwise(fx.ds, fx.password, listOf("drop table if exists at.pk", "create table at.pk(id int not null auto_increment, g int not null default 0, primary key (id), key pk_g (g))"))
        val sqlList = statements("pk") { m -> m.copy(keys = listOf(m.keys[0].copy(columns = listOf("id", "g")))) }
        assertEquals(1, sqlList.size, sqlList.joinToString("\n"))
        assertNull(runStepwise(fx.ds, fx.password, sqlList).error)
        assertEquals(listOf("id", "g"), model("pk").keys.single().columns)
        runStepwise(fx.ds, fx.password, listOf("drop table at.pk"))
    }

    @Test fun addingAutoIncrementAfterTheKey() {
        val sqlList = statements("t2") { m ->
            m.copy(columns = listOf(m.columns[0].copy(notNull = true, identity = Identity()), m.columns[1]), keys = listOf(KeyModel(null, "PRIMARY", true, listOf("a"))))
        }
        assertNull(runStepwise(fx.ds, fx.password, sqlList).error, sqlList.joinToString("\n"))
        assertNotNull(model("t2").columns[0].identity)
    }

    @Test fun aFailureInTheMiddleStopsAndLeavesWhatRanApplied() {
        val r = runStepwise(fx.ds, fx.password, listOf(
            "alter table at.t2 add column c1 int",
            "alter table at.t2 add column c1 int",         // duplicate: fails
            "alter table at.t2 add column c2 int",
        ))
        assertEquals(1, r.applied); assertNotNull(r.error); assertTrue(r.error!!.contains("c1"))
        assertEquals(listOf("a", "b", "c1"), model("t2").columns.map { it.name })      // the first stayed, the third never ran
    }

    @Test fun renamingTheTable() {
        val sqlList = statements("t2") { it.copy(name = "t3") }
        try {
            assertNull(runStepwise(fx.ds, fx.password, sqlList).error)
            assertEquals(listOf("a", "b"), model("t3").columns.map { it.name })
        } finally { runStepwise(fx.ds, fx.password, listOf("drop table if exists at.t3")) }
    }

    private fun exec(vararg sql: String) { assertNull(runStepwise(fx.ds, fx.password, sql.toList()).error, sql.joinToString("\n")) }
    private fun scalar(sql: String): String? = openConnection(fx.ds, fx.password).use { c ->
        c.createStatement().executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
    }

    @Test fun droppingAutoIncrementTogetherWithThePrimaryKeyWorks() {
        exec("drop table if exists at.ai", "create table at.ai(id int not null auto_increment, g int, primary key (id))")
        val sqlList = statements("ai") { m -> m.copy(columns = listOf(m.columns[0].copy(identity = null), m.columns[1]), keys = emptyList()) }
        assertNull(runStepwise(fx.ds, fx.password, sqlList).error, sqlList.joinToString("\n"))
        val m = model("ai")
        assertNull(m.columns[0].identity); assertEquals(emptyList(), m.keys)
        exec("drop table at.ai")
    }

    @Test fun deletingTheAutoIncrementColumnTogetherWithItsPrimaryKeyWorks() {
        exec("drop table if exists at.ai2", "create table at.ai2(id int not null auto_increment, g int, primary key (id))")
        val sqlList = statements("ai2") { m -> m.copy(columns = m.columns.drop(1), keys = emptyList()) }
        assertNull(runStepwise(fx.ds, fx.password, sqlList).error, sqlList.joinToString("\n"))
        assertEquals(listOf("g"), model("ai2").columns.map { it.name })
        exec("drop table at.ai2")
    }

    @Test fun aCommentOnlyEditKeepsOnUpdateAndTheColumnsOwnCollation() {
        exec(
            "drop table if exists at.keep",
            """create table at.keep(id int primary key,
                 upd timestamp not null default current_timestamp on update current_timestamp,
                 upd6 timestamp(6) not null default current_timestamp(6) on update current_timestamp(6),
                 onlyupd timestamp null on update current_timestamp,
                 nn datetime not null on update current_timestamp,
                 c varchar(10) character set utf8mb4 collate utf8mb4_bin, unique key keep_c (c)) default charset utf8mb4 collate utf8mb4_general_ci""",
            "insert into at.keep(id, nn, c) values (1, now(), 'a'), (2, now(), 'A')",
        )
        assertEquals(emptyList(), statements("keep") { it })
        val sqlList = statements("keep") { m -> m.copy(columns = m.columns.map { if (it.name == "id") it else it.copy(comment = "c") }) }
        assertTrue(sqlList.isNotEmpty())
        assertNull(runStepwise(fx.ds, fx.password, sqlList).error, sqlList.joinToString("\n"))
        fun extra(c: String) = scalar("select lower(extra) from information_schema.columns where table_schema = 'at' and table_name = 'keep' and column_name = '$c'")!!
        for (c in listOf("upd", "upd6", "onlyupd", "nn")) assertTrue("on update" in extra(c), "$c: ${extra(c)}")
        assertEquals("utf8mb4_bin", scalar("select collation_name from information_schema.columns where table_schema = 'at' and table_name = 'keep' and column_name = 'c'"))
        assertEquals(emptyList(), statements("keep") { it })
        exec("drop table at.keep")
    }

    @Test fun anExpressionIndexOnARenamedColumnIsRecreatedWithTheNewName() {
        assumeTrue("MariaDB has no functional indexes", kind == DbKind.MYSQL)
        exec("drop table if exists at.fi", "create table at.fi(id int primary key, name varchar(20), index fi_lower ((lower(name))))")
        val sqlList = statements("fi") { m -> m.copy(columns = m.columns.map { if (it.name == "name") it.copy(name = "title") else it }) }
        assertNull(runStepwise(fx.ds, fx.password, sqlList).error, sqlList.joinToString("\n"))
        assertTrue(model("fi").indexes.single().columns.single().name.contains("title"))
        exec("drop table at.fi")
    }

    @Test fun theStepwisePreludeSurvivesNoBackslashEscapes() {
        exec("drop table if exists at.bs", "create table at.bs(a int)")
        val sqlList = statements("bs") { it.copy(comment = "a\\b") }
        val r = runStepwise(fx.ds, fx.password, sqlList, listOf("set session sql_mode = concat(@@sql_mode, ',NO_BACKSLASH_ESCAPES')") + mySqlPrelude)
        assertNull(r.error, sqlList.joinToString("\n"))
        assertEquals("a\\b", model("bs").comment)
        exec("drop table at.bs")
    }

    @Test fun aFailingPreludeStopsBeforeTheStatements() {
        val r = runStepwise(fx.ds, fx.password, listOf("alter table at.t2 add column c9 int"), prelude = listOf("alter table at.nope add column x int"))
        assertEquals(0, r.applied); assertNotNull(r.error)
        assertEquals(listOf("a", "b"), model("t2").columns.map { it.name })
    }
}

class TableAlterMySqlTest : TableAlterMySqlBase(DbKind.MYSQL) {
    override val fx get() = Companion.fx
    companion object {
        private var container: MySQLContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MySQLContainer("mysql:8.4").also { it.start() }
            container = c
            fx = fixture(DbKind.MYSQL, c.jdbcUrl, "root", c.password, SETUP)
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}

class TableAlterMariaDbTest : TableAlterMySqlBase(DbKind.MARIADB) {
    override val fx get() = Companion.fx
    companion object {
        private var container: MariaDBContainer? = null
        private lateinit var fx: ServerFixture
        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MariaDBContainer("mariadb:11.4").also { it.start() }
            container = c
            fx = fixture(DbKind.MARIADB, c.jdbcUrl, "root", c.password, SETUP)
        }
        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }
}
