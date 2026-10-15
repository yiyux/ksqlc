package ksqlc.drivers

import ksqlc.core.DbKind
import ksqlc.drivers.dialect.CqlColumn
import ksqlc.drivers.dialect.cqlColumnOrder
import ksqlc.drivers.dialect.cqlCreateTable
import kotlin.test.*

class CassandraUrlTest {
    @Test fun buildsAndParsesWithTheLocalDatacenter() {
        assertEquals("jdbc:cassandra://h:9042/ks?localdatacenter=datacenter1", JdbcUrl.build(DbKind.CASSANDRA, "h", null, "ks", false))
        assertEquals("jdbc:cassandra://h:9043?localdatacenter=dc2&enableSsl=true", JdbcUrl.build(DbKind.CASSANDRA, "h", 9043, "", true, datacenter = "dc2"))
        for (p in listOf(JdbcUrl.Parts(DbKind.CASSANDRA, "h", 9042, "ks", false, datacenter = "datacenter1"), JdbcUrl.Parts(DbKind.CASSANDRA, "node", 9142, "", true, datacenter = "eu-west"))) {
            assertEquals(p, JdbcUrl.parse(JdbcUrl.build(p.kind, p.host, p.port, p.database, p.ssl, datacenter = p.datacenter)))
        }
        assertEquals(DbKind.CASSANDRA, JdbcUrl.kindOf("jdbc:cassandra://h:9042/ks?localdatacenter=d"))
        assertNull(JdbcUrl.parse("jdbc:cassandra://h:9042/ks?localdatacenter=d&consistencyLevel=ONE"))      // extra options stay a manual URL
    }

    @Test fun cassandraHasNoExplainNorNativeDump() {
        assertNull(explainScript(DbKind.CASSANDRA, "select 1", false))
        assertNotNull(nativeDumpBlocker(DbKind.CASSANDRA, null))
    }

    @Test fun cqlValuesAreTypedAndCollectionsRefused() {
        val id = "3f2c1c0e-7d52-4a6e-9a43-0d6a0c1b2c3d"
        assertEquals(java.util.UUID.fromString(id), parseParam(id, "uuid", DbKind.CASSANDRA))
        assertEquals(java.util.UUID.fromString(id), parseParam(id, "timeuuid", DbKind.CASSANDRA))
        assertEquals(java.math.BigInteger("123456789012345678901234567890"), parseParam("123456789012345678901234567890", "varint", DbKind.CASSANDRA))
        assertEquals(java.net.InetAddress.getByName("10.0.0.1"), parseParam("10.0.0.1", "inet", DbKind.CASSANDRA))
        assertEquals("hola", parseParam("hola", "text", DbKind.CASSANDRA))
        assertEquals(5L, parseParam("5", "int", DbKind.CASSANDRA))
        assertFailsWith<IllegalArgumentException> { parseParam("nope", "uuid", DbKind.CASSANDRA) }
        for (t in listOf("list<text>", "set<int>", "map<text, int>", "frozen<list<text>>", "tuple<int, text>")) assertFailsWith<IllegalArgumentException>(t) { parseParam("x", t, DbKind.CASSANDRA) }
    }
}

class CqlDdlTest {
    private fun col(name: String, kind: String, type: String, pos: Int = -1, desc: Boolean = false) = CqlColumn(name, kind, pos, type, desc)

    @Test fun columnsComeInKeyOrderThenByName() {
        val cols = listOf(col("z", "regular", "int"), col("ck2", "clustering", "int", 1), col("a", "regular", "text"), col("pk", "partition_key", "uuid", 0), col("ck1", "clustering", "text", 0), col("s", "static", "int"))
        assertEquals(listOf("pk", "ck1", "ck2", "a", "s", "z"), cqlColumnOrder(cols).map { it.name })
    }

    @Test fun createTableWithSimpleAndCompositeKeys() {
        assertEquals(
            "CREATE TABLE \"ks\".\"t\" (\n    \"id\" uuid,\n    \"name\" text,\n    PRIMARY KEY (\"id\")\n);",
            cqlCreateTable("ks", "t", listOf(col("id", "partition_key", "uuid", 0), col("name", "regular", "text")), null),
        )
        val sql = cqlCreateTable(
            "ks", "events",
            listOf(col("tenant", "partition_key", "text", 0), col("day", "partition_key", "date", 1), col("at", "clustering", "timestamp", 0, desc = true), col("body", "regular", "text"), col("tags", "static", "set<text>")),
            "it's a log",
        )
        assertTrue("PRIMARY KEY ((\"tenant\", \"day\"), \"at\")" in sql, sql)
        assertTrue("\"tags\" set<text> static" in sql, sql)
        assertTrue(sql.endsWith("WITH CLUSTERING ORDER BY (\"at\" DESC)\n    AND comment = 'it''s a log';"), sql)
    }

    @Test fun onePartitionKeyWithClusteringKeepsThemInOneList() {
        val sql = cqlCreateTable("ks", "t", listOf(col("a", "partition_key", "int", 0), col("b", "clustering", "int", 0), col("c", "clustering", "int", 1)), null)
        assertTrue("PRIMARY KEY (\"a\", \"b\", \"c\")" in sql, sql)
        assertFalse("CLUSTERING ORDER" in sql)
    }
}
