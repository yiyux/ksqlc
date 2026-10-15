package ksqlc.sql

import kotlin.test.*

class DiagnosticsTest {
    private val schema = FakeSchema()
    private fun d(sql: String, s: SchemaInfo? = schema) = diagnose(sql, s).map { it.severity to it.message }

    @Test fun cleanSqlHasNoDiagnostics() {
        assertEquals(emptyList(), d("select c.id, name from customers c where id = 1 and name like 'x%'"))
        assertEquals(emptyList(), d("select 1"))
    }

    @Test fun unterminatedStringCommentAndQuotedName() {
        assertEquals(listOf(Severity.ERROR to "Cadena sin cerrar"), d("select 'abc"))
        assertEquals(listOf(Severity.ERROR to "Comentario sin cerrar"), d("select 1 /* abc"))
        assertEquals(listOf(Severity.ERROR to "Identificador entrecomillado sin cerrar"), d("select \"abc"))
        assertEquals(emptyList(), d("select 'it''s', \"a\"\"b\", /* c */ 1 -- x"))
    }

    @Test fun unbalancedParentheses() {
        assertEquals(listOf(Severity.ERROR to "Paréntesis sin cerrar"), d("select (1 + 2"))
        assertEquals(listOf(Severity.ERROR to "Paréntesis de cierre sin apertura"), d("select 1)"))
        assertEquals(listOf(Severity.ERROR to "Paréntesis sin cerrar"), d("select (1; select 2"))
    }

    @Test fun unknownTableIsAWarningOnlyWhenTheCatalogIsComplete() {
        assertEquals(listOf(Severity.WARNING to "Tabla desconocida: nada"), d("select * from nada"))
        assertEquals(emptyList(), d("select * from nada", FakeSchema(complete = false)))
        val r = diagnose("select * from nada", schema).single()
        assertEquals("nada", "select * from nada".substring(r.start, r.end))
    }

    @Test fun cteTablesCreatedInTheScriptAndSystemTablesAreKnown() {
        assertEquals(emptyList(), d("with x as (select 1 as a) select * from x"))
        assertEquals(emptyList(), d("create table nueva(id int); insert into nueva values (1); select * from nueva"))
        assertEquals(emptyList(), d("select * from information_schema.tables"))
        assertEquals(emptyList(), d("select * from sqlite_master"))
        assertEquals(emptyList(), d("select * from generate_series(1, 3)"))
    }

    @Test fun unknownColumnThroughAnAlias() {
        assertEquals(listOf(Severity.WARNING to "Columna desconocida: c.nope"), d("select c.nope from customers c"))
        assertEquals(emptyList(), d("select c.ID, c.* from customers c"))
    }

    @Test fun columnsStillLoadingAreNotJudged() = assertEquals(emptyList(), d("select v.anything from v_open v"))

    @Test fun unknownBareColumnInWhereWithASingleTable() {
        assertEquals(listOf(Severity.WARNING to "Columna desconocida: nope"), d("select * from customers where nope = 1"))
        assertEquals(emptyList(), d("select name as n from customers where id = 1 and name is not null and current_date is not null"))
        assertEquals(emptyList(), d("select * from customers where lower(name) = 'a' order by whatever"))
    }

    @Test fun twoTablesDisableBareColumnChecks() =
        assertEquals(emptyList(), d("select * from customers c join orders o on o.customer_id = c.id where total > 1"))

    @Test fun withoutASchemaOnlySyntaxIsChecked() {
        assertEquals(emptyList(), d("select * from nada where x = 1", null))
        assertEquals(listOf(Severity.ERROR to "Cadena sin cerrar"), d("select 'a", null))
    }
}
