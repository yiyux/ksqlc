package ksqlc.drivers.server

import ksqlc.core.*
import ksqlc.drivers.Introspector
import ksqlc.drivers.JdbcUrl
import ksqlc.drivers.dialect.Dialect
import ksqlc.drivers.dialect.SqlServerDialect
import ksqlc.drivers.testConnection
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.mssqlserver.MSSQLServerContainer
import java.sql.DriverManager
import kotlin.test.*

/** The native SQL Server dialect against a real 2022 server. */
class SqlServerIntrospectionTest {
    companion object {
        private var container: MSSQLServerContainer? = null
        private lateinit var fx: ServerFixture
        private lateinit var host: String
        private var port = 0

        @BeforeClass @JvmStatic fun start() {
            assumeDocker()
            val c = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense().also { it.start() }
            container = c; host = c.host; port = c.getMappedPort(1433)
            val url = JdbcUrl.build(DbKind.MSSQL, host, port, "", false)
            fx = fixture(DbKind.MSSQL, url, c.username, c.password, listOf(
                "create schema app",
                "create schema [my schema]",
                "create table app.customers(id int identity(10,5) primary key, email nvarchar(120) not null, note nvarchar(max) null, created datetime2(3) not null default (sysutcdatetime()), score decimal(10,2) null)",
                "create unique index uq_email on app.customers(email) include (note)",
                "create table app.orders(id int not null, line int not null, customer_id int not null, total decimal(10,2) default 0, " +
                    "double_total as (total * 2) persisted, constraint pk_orders primary key nonclustered (id, line), " +
                    "constraint fk_orders_customer foreign key (customer_id) references app.customers(id) on delete cascade)",
                "create index orders_customer_idx on app.orders(customer_id)",
                "create view app.big_orders as select id, total from app.orders where total > 100",
                "create sequence app.seq_orders as bigint start with 100 increment by 10 minvalue 1 maxvalue 100000 cycle",
                "create procedure app.add_order @id int as begin select @id end",
                "create function app.twice(@n int) returns int as begin return @n * 2 end",
                "create table [my schema].[odd ]]name](x int)",
                "exec sp_addextendedproperty 'MS_Description', 'Clientes', 'SCHEMA', 'app', 'TABLE', 'customers'",
            ))
        }

        @AfterClass @JvmStatic fun stop() { container?.stop() }
    }

    private val app = Namespace(null, "app")
    private fun intro() = Introspector(fx.ds, fx.password)
    private fun obj(name: String, kind: DbObjectKind = DbObjectKind.TABLE, ns: Namespace = app) = DbObject(ns, name, kind)

    @Test fun usesTheNativeDialect() = assertSame(SqlServerDialect, Dialect.of(DbKind.MSSQL))

    @Test fun quotingUsesBracketsAndDoublesTheClosingOne() {
        assertEquals("[a]]b]", SqlServerDialect.quote("a]b"))
        assertEquals("[app].[customers]", SqlServerDialect.qualified(obj("customers")))
    }

    @Test fun schemasHideSystemAndFixedRoleOnes() = intro().use { i ->
        val names = i.namespaces().map { it.label }
        assertTrue("app" in names && "dbo" in names && "my schema" in names, names.toString())
        assertTrue(names.none { it in setOf("sys", "INFORMATION_SCHEMA", "guest") || it.startsWith("db_") }, names.toString())
    }

    @Test fun objectsAreTablesViewsSequencesAndRoutinesWithComments() = intro().use { i ->
        val all = i.objects(app).associateBy { it.name }
        assertEquals(DbObjectKind.TABLE, all["customers"]?.kind); assertEquals(DbObjectKind.TABLE, all["orders"]?.kind)
        assertEquals(DbObjectKind.VIEW, all["big_orders"]?.kind)
        assertEquals(DbObjectKind.SEQUENCE, all["seq_orders"]?.kind)
        assertEquals(DbObjectKind.ROUTINE, all["add_order"]?.kind); assertEquals(DbObjectKind.ROUTINE, all["twice"]?.kind)
        assertEquals("Clientes", all["customers"]?.comment)
        assertTrue(all.keys.none { it.startsWith("sys") }, all.keys.toString())
    }

    @Test fun columnsCarryTypesNullabilityDefaultsAndIdentity() = intro().use { i ->
        val d = i.details(obj("customers"))
        assertEquals(listOf("id", "email", "note", "created", "score"), d.columns.map { it.name })
        val id = d.columns[0]; assertTrue(id.autoIncrement); assertEquals("int", id.typeName)
        val email = d.columns[1]; assertEquals("nvarchar", email.typeName); assertEquals(120, email.size); assertFalse(email.nullable)
        assertNull(d.columns[2].size)                                        // nvarchar(max)
        assertEquals("datetime2", d.columns[3].typeName); assertNotNull(d.columns[3].default)
        assertEquals(2, d.columns[4].scale)
        assertEquals(listOf("id"), d.primaryKey)
    }

    @Test fun keysIndexesAndForeignKeys() = intro().use { i ->
        val o = i.details(obj("orders"))
        assertEquals("pk_orders", o.primaryKeyName); assertEquals(listOf("id", "line"), o.primaryKey)
        assertEquals(listOf("orders_customer_idx"), o.indexes.map { it.name })
        val fk = o.foreignKeys.single()
        assertEquals("fk_orders_customer", fk.name); assertEquals(listOf("customer_id"), fk.columns)
        assertEquals("customers", fk.refTable); assertEquals(listOf("id"), fk.refColumns); assertEquals("app", fk.refNs.schema)
        val c = i.details(obj("customers"))
        assertEquals(listOf(IndexInfo("uq_email", true, listOf("email"))), c.indexes)
    }

    @Test fun viewsShowTheirColumnsOnly() = intro().use { i ->
        val d = i.details(obj("big_orders", DbObjectKind.VIEW))
        assertEquals(listOf("id", "total"), d.columns.map { it.name }); assertTrue(d.primaryKey.isEmpty())
    }

    @Test fun tableDdlIsNativeTsqlAndReplaysCleanly() = intro().use { i ->
        val ddl = i.ddl(obj("customers"))
        assertContains(ddl, "create table [app].[customers]")
        assertContains(ddl, "[id] int identity(10,5) not null")
        assertContains(ddl, "[email] nvarchar(120) collate")
        assertContains(ddl, "[note] nvarchar(max)")
        assertContains(ddl, "[created] datetime2(3) not null default")
        assertContains(ddl, "[score] decimal(10,2) null")
        assertContains(ddl, "constraint [")
        assertContains(ddl, "create unique nonclustered index [uq_email] on [app].[customers] ([email]) include ([note]);")

        val orders = i.ddl(obj("orders"))
        assertContains(orders, "[double_total] as ")
        assertContains(orders, "persisted")
        assertContains(orders, "primary key nonclustered ([id], [line])")
        assertContains(orders, "references [app].[customers] ([id]) on delete cascade")

        // the text must be valid T-SQL that recreates the same table
        val before = i.details(obj("orders"))
        DriverManager.getConnection(fx.ds.url, fx.ds.user, fx.password).use { c ->
            c.createStatement().use { st ->
                st.execute("drop table app.orders")
                orders.split(Regex(";\\s*(\\n|$)")).filter { it.isNotBlank() }.forEach { st.execute(it) }
            }
        }
        val after = i.details(obj("orders"))
        assertEquals(before.columns.map { it.name to it.typeName }, after.columns.map { it.name to it.typeName })
        assertEquals(before.primaryKey, after.primaryKey); assertEquals(before.foreignKeys.map { it.name }, after.foreignKeys.map { it.name })
    }

    @Test fun viewRoutineAndSequenceDdl() = intro().use { i ->
        assertContains(i.ddl(obj("big_orders", DbObjectKind.VIEW)), "create view app.big_orders")
        assertContains(i.ddl(obj("add_order", DbObjectKind.ROUTINE)), "create procedure app.add_order")
        assertContains(i.ddl(obj("twice", DbObjectKind.ROUTINE)), "create function app.twice")
        val seq = i.ddl(obj("seq_orders", DbObjectKind.SEQUENCE))
        assertContains(seq, "create sequence [app].[seq_orders] as bigint start with 100 increment by 10 minvalue 1 maxvalue 100000 cycle;")
    }

    @Test fun namesWithSpacesAndClosingBracketsWork() = intro().use { i ->
        val ns = Namespace(null, "my schema")
        val t = i.objects(ns).single()
        assertEquals("odd ]name", t.name)
        assertEquals(listOf("x"), i.details(t).columns.map { it.name })
        assertContains(i.ddl(t), "create table [my schema].[odd ]]name]")
    }

    @Test fun selectAllUsesBrackets() = assertEquals("select * from [app].[customers]", SqlServerDialect.selectAll(obj("customers")))

    @Test fun encryptedConnectionsNeedTheCertificateToBeTrustedWhenItIsSelfSigned() {
        val strict = JdbcUrl.build(DbKind.MSSQL, host, port, "", ssl = true)
        val trusting = JdbcUrl.build(DbKind.MSSQL, host, port, "", ssl = true, trustCert = true)
        val user = fx.ds.user
        val refused = runCatching { testConnection(fx.ds.copy(url = strict, user = user), fx.password) }
        assertTrue(refused.isFailure, "un certificado autofirmado debería rechazarse sin confiar en él")
        assertContains(refused.exceptionOrNull()!!.message.orEmpty() + refused.exceptionOrNull()?.cause?.message.orEmpty(), "certificate", ignoreCase = true)
        val ok = testConnection(fx.ds.copy(url = trusting, user = user), fx.password)
        assertContains(ok, "SQL Server")
    }
}
