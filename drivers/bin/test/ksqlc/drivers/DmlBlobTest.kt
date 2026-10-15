package ksqlc.drivers

import ksqlc.core.*
import ksqlc.drivers.dialect.Dialect
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

/** A file's bytes loaded into a BLOB cell reach the database as bytes, and the preview does not dump them. */
class DmlBlobTest {
    private val file = Files.createTempFile("ksqlc-blob", ".db").also { it.toFile().deleteOnExit() }.toString()
    private val url = "jdbc:sqlite:$file"
    private val obj = DbObject(Namespace(null, "main"), "docs", DbObjectKind.TABLE)
    private val cols = listOf(Column("id", "INTEGER"), Column("body", "BLOB"))

    @Test fun insertAndUpdateStoreTheBytes() {
        DriverManager.getConnection(url).use { it.createStatement().execute("create table docs(id integer primary key, body blob)") }
        val data = byteArrayOf(0, 1, 2, -1, 127)
        val ins = buildDml(Dialect.of(DbKind.SQLITE), DbKind.SQLITE, obj, cols, listOf("id"), emptyList(), listOf(mapOf("id" to CellValue.Text("1"), "body" to CellValue.Bytes("a.bin", data))), emptyList())
        assertTrue("<5 bytes>" in ins.single().preview, ins.single().preview)
        Session(DataSource("t", "t", DbKind.SQLITE, url), null).use { s ->
            s.execute(ins).getOrThrow()
            val more = byteArrayOf(9, 8)
            val upd = buildDml(Dialect.of(DbKind.SQLITE), DbKind.SQLITE, obj, cols, listOf("id"), listOf(RowEdit(listOf(1L, data), mapOf("body" to CellValue.Bytes("b.bin", more)))), emptyList(), emptyList())
            s.execute(upd).getOrThrow()
        }
        DriverManager.getConnection(url).use { c ->
            val rs = c.createStatement().executeQuery("select body from docs")
            assertTrue(rs.next()); assertContentEquals(byteArrayOf(9, 8), rs.getBytes(1))
        }
    }
}
