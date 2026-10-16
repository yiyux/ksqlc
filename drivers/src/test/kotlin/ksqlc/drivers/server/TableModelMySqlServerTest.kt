package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.Introspector
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.mariadb.MariaDBContainer
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

private val SETUP = listOf(
    "create database mt",
    "create table mt.parent(id int primary key)",
    """create table mt.t(
         id bigint unsigned not null auto_increment,
         name varchar(120) not null default 'it''s a \\ test' comment 'nombre',
         email varchar(100),
         parent_id int,
         price int default 0,
         qty int not null default 1,
         total decimal(12,2) generated always as (price * 2) stored,
         label varchar(20) generated always as (concat(name, '!')) virtual,
         flags bit(1) default b'1',
         uid varchar(36) default (uuid()),
         ts timestamp null default current_timestamp,
         n1 varchar(10) default null,
         n2 varchar(10) default 'NULL',
         primary key (id),
         unique key t_email_key (email),
         index t_name_idx (name desc, email),
         constraint t_parent_fk foreign key (parent_id) references mt.parent(id) on delete cascade on update set null,
         constraint t_price_ck check (price >= 0)
       ) comment = 'hola'""",
    "create table mt.zf(z int(5) unsigned zerofill, w int(7))",
    """create table mt.keep(id int primary key,
         upd timestamp not null default current_timestamp on update current_timestamp,
         upd6 timestamp(6) not null default current_timestamp(6) on update current_timestamp(6),
         onlyupd timestamp null on update current_timestamp,
         c varchar(10) character set utf8mb4 collate utf8mb4_bin, d varchar(10)) default charset utf8mb4 collate utf8mb4_general_ci""",
    "create table mt.skipped(a int, b varchar(50), c text, primary key (a), index pre_idx (b(10)), fulltext index ft_idx (c))",
)

/** Same expectations for MySQL and MariaDB: both go through [ksqlc.drivers.dialect.MySqlDialect]. */
abstract class TableModelMySqlBase {
    protected abstract val fx: ServerFixture
    private fun model(name: String = "t") = Introspector(fx.ds, fx.password).use { it.tableModel(DbObject(Namespace("mt", null), name, DbObjectKind.TABLE))!! }

    @Test fun readsTableAndColumns() = model().let { m ->
        assertEquals("t", m.name); assertEquals("hola", m.comment)
        assertEquals(listOf("id", "name", "email", "parent_id", "price", "qty", "total", "label", "flags", "uid", "ts", "n1", "n2"), m.columns.map { it.name })
        assertEquals(m.columns.map { it.name }, m.columns.map { it.origin })
        val id = m.columns[0]
        assertTrue(id.type.startsWith("bigint") && id.type.endsWith("unsigned"), id.type); assertTrue(id.notNull)   // MariaDB keeps the display width: bigint(20) unsigned
        assertNotNull(id.identity); assertNull(id.default)
        val name = m.columns[1]
        assertEquals("varchar(120)", name.type); assertTrue(name.notNull); assertEquals("nombre", name.comment)
        assertEquals("'it''s a \\\\ test'", name.default)       // a literal that can be pasted back into a statement
        assertFalse(m.columns[2].notNull); assertNull(m.columns[2].default)
        assertEquals("0", m.columns[4].default); assertEquals("1", m.columns[5].default)
        assertEquals(true, m.columns[6].generatedStored); assertNotNull(m.columns[6].generated); assertNull(m.columns[6].default)
        assertEquals(false, m.columns[7].generatedStored)
        assertEquals("b'1'", m.columns[8].default)
        val uid = m.columns[9].default!!                  // an expression default is pasted after `default `, so it needs its parentheses
        assertTrue(uid.startsWith("(") && uid.endsWith(")") && uid.contains("uuid()", ignoreCase = true), uid)
        assertTrue(m.columns[10].default!!.startsWith("current_timestamp", ignoreCase = true), m.columns[10].default)
        assertNull(m.columns[11].default); assertEquals("'NULL'", m.columns[12].default)
    }

    @Test fun readsKeysIndexesForeignKeysAndChecks() = model().let { m ->
        assertEquals(listOf("PRIMARY", "t_email_key"), m.keys.map { it.name })
        assertTrue(m.keys[0].primary); assertEquals(listOf("id"), m.keys[0].columns)
        assertFalse(m.keys[1].primary); assertEquals(listOf("email"), m.keys[1].columns)
        // the index MySQL makes for the foreign key is an ordinary index
        assertEquals(setOf("t_name_idx", "t_parent_fk"), m.indexes.map { it.name }.toSet())
        val ix = m.indexes.single { it.name == "t_name_idx" }
        assertEquals("btree", ix.method)
        assertEquals(listOf("name" to "DESC", "email" to ""), ix.columns.map { it.name to it.order })
        val fk = m.foreignKeys.single()
        assertEquals("t_parent_fk", fk.name); assertEquals(listOf("parent_id"), fk.columns)
        assertEquals(Namespace("mt", null), fk.refNs); assertEquals("parent", fk.refTable); assertEquals(listOf("id"), fk.refColumns)
        assertEquals("CASCADE", fk.onDelete); assertEquals("SET NULL", fk.onUpdate)
        val ck = m.checks.single()
        assertEquals("t_price_ck", ck.name); assertTrue(ck.expression.contains("price"), ck.expression)
    }

    @Test fun zerofillKeepsItsWidthButPlainIntegersLoseTheirs() = model("zf").let { m ->
        val z = m.columns[0].type
        assertTrue(z.contains("(5)") && z.contains("zerofill"), z)   // the width is the padding
        assertEquals("int", m.columns[1].type)
    }

    @Test fun onUpdateAndACollationDifferentFromTheTablesAreKept() = model("keep").let { m ->
        fun d(c: String) = m.columns.single { it.name == c }.default!!.lowercase().replace("()", "")   // MariaDB spells current_timestamp()
        assertEquals("current_timestamp on update current_timestamp", d("upd"))
        assertEquals("current_timestamp(6) on update current_timestamp(6)", d("upd6"))
        assertTrue(d("onlyupd").endsWith("on update current_timestamp"), d("onlyupd"))
        assertTrue(m.columns.single { it.name == "c" }.type.contains("collate utf8mb4_bin"), m.columns.single { it.name == "c" }.type)
        assertEquals("varchar(10)", m.columns.single { it.name == "d" }.type)
    }

    @Test fun prefixAndFulltextIndexesAreLeftOut() = model("skipped").let { m ->
        assertEquals(emptyList(), m.indexes.map { it.name })
        assertEquals(listOf("PRIMARY"), m.keys.map { it.name })
    }

    @Test fun aMissingTableIsAnError() {
        val e = assertFails { model("nope") }
        assertTrue(e.message!!.contains("nope"))
    }
}

class TableModelMySqlTest : TableModelMySqlBase() {
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

class TableModelMariaDbTest : TableModelMySqlBase() {
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
