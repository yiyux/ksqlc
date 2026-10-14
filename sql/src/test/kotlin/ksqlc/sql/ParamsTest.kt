package ksqlc.sql

import kotlin.test.Test
import kotlin.test.assertEquals

class ParamsTest {
    private fun names(sql: String, positional: Boolean = true) = findParams(sql, positional).map { it.name }

    @Test fun findsNamedAndPositionalParameters() {
        assertEquals(listOf(":id", ":name"), names("select * from t where id = :id and name = :name"))
        assertEquals(listOf(":a", "?", ":a"), names("select :a, ?, :a"))
        assertEquals(listOf(":id"), names("select * from t where id = :id", positional = false))
        assertEquals(emptyList(), names("select ? from t", positional = false))
    }

    @Test fun ignoresStringsCommentsCastsAndSlices() {
        assertEquals(emptyList(), names("select ':x', \":y\", '?' -- :z ?\n, /* :w */ 1"))
        assertEquals(emptyList(), names("select a::int, b::text, arr[1:n], x::date from t"))
        assertEquals(emptyList(), names("begin :new.a := :old.b; end;"))
        assertEquals(listOf(":p"), names("select (:p)"))
    }

    @Test fun fillsEachOccurrenceInPlace() {
        val sql = "select :a, ?, ':a', :a"
        val refs = findParams(sql, true)
        assertEquals("select 1, 'x', ':a', 1", fillParams(sql, refs, listOf("1", "'x'", "1")))
        assertEquals(sql, fillParams(sql, emptyList(), emptyList()))
    }
}
