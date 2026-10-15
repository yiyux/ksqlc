package ksqlc.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaFilterTest {
    private val names = listOf("public", "app_sales", "app_hr", "Ventas", "pg_catalog", "other")
    private fun shown(f: SchemaFilter) = names.filter(f::accepts)

    @Test fun theDefaultListsEverything() {
        assertTrue(SchemaFilter().isDefault)
        assertEquals(names, shown(SchemaFilter()))
    }

    @Test fun chosenSchemasAreTheOnlyOnesAnyCaseAndSystemOnesCanBeChosen() {
        assertEquals(listOf("public", "pg_catalog"), shown(SchemaFilter(false, listOf("PUBLIC", "pg_catalog"))))
        assertEquals(emptyList(), shown(SchemaFilter(false)))
        assertFalse(SchemaFilter(false).isDefault)
    }

    @Test fun patternsUseStarAndQuestionMarkAndSeveralCanBeGiven() {
        assertEquals(listOf("app_sales", "app_hr"), shown(SchemaFilter(pattern = "app_*")))
        assertEquals(listOf("app_hr", "Ventas"), shown(SchemaFilter(pattern = "app_h?, ventas")))
        assertEquals(listOf("public", "app_sales", "app_hr"), shown(SchemaFilter(false, listOf("public"), "app_*")))      // chosen ones and pattern matches
        assertEquals(emptyList(), shown(SchemaFilter(pattern = "o.her")))      // the dot is a dot, not "any character"
        assertEquals(listOf("other"), shown(SchemaFilter(pattern = "o?her")))
        assertEquals(emptyList(), shown(SchemaFilter(pattern = "x(")))      // other regex characters are literal too
        assertFalse(SchemaFilter(pattern = "a*").isDefault)
    }
}
