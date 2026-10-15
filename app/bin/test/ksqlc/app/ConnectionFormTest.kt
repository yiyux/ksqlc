package ksqlc.app

import ksqlc.core.DataSource
import ksqlc.core.DbKind
import kotlin.test.*

class ConnectionFormTest {
    @Test fun aUrlTheFormCanBuildComesBackAsFields() {
        val f = ConnectionForm()
        f.load(DataSource("1", "ventas", DbKind.POSTGRES, "jdbc:postgresql://db.local:6543/ventas?sslmode=require", "ana"))
        assertFalse(f.customUrl)
        assertEquals("db.local", f.host); assertEquals("6543", f.portText); assertEquals("ventas", f.database)
        assertTrue(f.ssl); assertEquals("ana", f.user); assertEquals(DbKind.POSTGRES, f.kind)
        assertEquals("jdbc:postgresql://db.local:6543/ventas?sslmode=require", f.url)   // nothing was lost
    }

    @Test fun anyOtherUrlStaysManualAndIsKeptVerbatim() {
        val f = ConnectionForm()
        val url = "jdbc:postgresql://h:5432/db?currentSchema=app"
        f.load(DataSource("1", "x", DbKind.POSTGRES, url))
        assertTrue(f.customUrl); assertEquals(url, f.url)
    }

    @Test fun sharedSqliteMemoryLoadsAsAnEmptyDatabaseField() {
        val f = ConnectionForm()
        f.load(DataSource("1", "m", DbKind.SQLITE, ksqlc.drivers.JdbcUrl.SQLITE_SHARED_MEMORY))
        assertFalse(f.customUrl); assertEquals("", f.database)
    }

    @Test fun anOldPrivateMemoryUrlStaysThePrivateDatabase() {
        val f = ConnectionForm()
        f.load(DataSource("1", "m", DbKind.SQLITE, "jdbc:sqlite::memory:"))
        assertEquals("jdbc:sqlite::memory:", f.url)   // not rewritten to the shared in-memory database
        assertEquals(":memory:", f.database)
    }

    @Test fun copyFromCopiesEveryField() {
        val a = ConnectionForm().apply { name = "a"; kind = DbKind.MYSQL; host = "h"; portText = "1"; database = "d"; ssl = true; user = "u"; password = "p"; customUrl = true; urlText = "jdbc:x"; currentId = "id" }
        val b = ConnectionForm().also { it.copyFrom(a) }
        assertEquals(listOf("a", "h", "1", "d", "u", "p", "jdbc:x", "id"), listOf(b.name, b.host, b.portText, b.database, b.user, b.password, b.urlText, b.currentId))
        assertEquals(DbKind.MYSQL, b.kind); assertTrue(b.ssl && b.customUrl)
    }
}
