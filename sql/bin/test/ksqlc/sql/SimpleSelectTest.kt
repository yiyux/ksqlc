package ksqlc.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SimpleSelectTest {
    private fun name(sql: String) = simpleSelectTable(sql)?.let { (it.schema?.plus(".") ?: "") + it.name }

    @Test fun plainSelectsNameTheirTable() {
        assertEquals("people", name("select * from people"))
        assertEquals("public.people", name("SELECT id, name FROM public.people WHERE id > 3 ORDER BY id LIMIT 10;"))
        assertEquals("people", name("select p.id, p.name from people p where p.id = 1"))
        assertEquals("people", name("select p.* from \"people\" as p"))
    }

    @Test fun anythingThatChangesTheRowsOrNamesIsRefused() {
        for (sql in listOf(
            "select id as key from people", "select id key from people", "select count(*) from people", "select distinct name from people",
            "select a.id from a join b on a.id = b.id", "select id from a, b", "select id from a union select id from b",
            "select name from people group by name", "select id from people where id in (select id from other)",
            "select 1", "with c as (select 1) select * from c", "update people set a = 1", "select upper(name) from people",
        )) assertNull(simpleSelectTable(sql), sql)
    }
}
