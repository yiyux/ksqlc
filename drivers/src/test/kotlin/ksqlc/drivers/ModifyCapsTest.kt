package ksqlc.drivers

import ksqlc.drivers.dialect.MySqlDialect
import ksqlc.drivers.dialect.PostgresDialect
import ksqlc.drivers.dialect.SqlServerDialect
import kotlin.test.*

/** What each engine's Modify dialog offers, fixed so that separating a flag for one engine does not change another. */
class ModifyCapsTest {
    @Test fun postgresKeepsEverythingAndAShortLockTimeout() {
        val c = PostgresDialect.modifyCaps
        assertTrue(c.atomic && c.deferrable && c.indexColumnOptions && c.indexExtras && c.identityOptions)
        assertFalse(c.singleIdentity || c.identityLeadsKey || c.identityOnCreateOnly)
        assertEquals("Identity", c.identityName)
        assertEquals(listOf("set local lock_timeout = '5s'"), c.prelude)
    }

    @Test fun mySqlHasNeitherIndexExtrasNorTransactionsAndAutoIncrementLeadsAKey() {
        val c = MySqlDialect.modifyCaps
        assertFalse(c.atomic || c.deferrable || c.indexColumnOptions || c.indexExtras || c.identityOptions || c.identityOnCreateOnly)
        assertTrue(c.singleIdentity && c.identityLeadsKey)
        assertEquals("auto_increment", c.identityName)
        assertEquals(mySqlPrelude, c.prelude)
    }

    @Test fun sqlServerIsAtomicTakesIncludeAndFilterButNoColumnOptionsAndSetsIdentityOnlyOnCreate() {
        val c = SqlServerDialect.modifyCaps
        assertTrue(c.atomic && c.indexExtras && c.singleIdentity && c.identityOnCreateOnly && c.virtualColumns)
        assertFalse(c.deferrable || c.indexColumnOptions || c.identityOptions || c.identityLeadsKey || c.fkNeedsIndex || c.refInCatalog)
        assertEquals("Identity", c.identityName)
        assertEquals(listOf("clustered", "nonclustered"), c.indexMethods)
        assertFalse("RESTRICT" in c.fkActions)
        assertEquals(listOf("set lock_timeout 5000"), c.prelude)
    }
}
