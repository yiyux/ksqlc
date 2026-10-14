package ksqlc.sql

import kotlin.test.*

class StatementsTest {
    private fun texts(s: String) = splitStatements(s).map { it.text(s) }

    @Test fun splitsOnSemicolons() =
        assertEquals(listOf("select 1", "select 2"), texts("select 1; select 2;"))

    @Test fun lastStatementWithoutSemicolon() =
        assertEquals(listOf("select 1", "select 2"), texts("select 1;\nselect 2"))

    @Test fun ignoresSemicolonsInStringsAndQuotedIdentifiers() =
        assertEquals(listOf("select 'a;b', \"c;d\", `e;f`", "select 2"), texts("select 'a;b', \"c;d\", `e;f`; select 2"))

    @Test fun doubledQuoteIsAnEscape() =
        assertEquals(listOf("select 'it''s; fine'"), texts("select 'it''s; fine';"))

    @Test fun ignoresSemicolonsInComments() =
        assertEquals(listOf("select 1 -- a;b\n, 2", "select /* ; */ 3"), texts("select 1 -- a;b\n, 2; select /* ; */ 3"))

    @Test fun dollarQuotedBodiesStayWhole() {
        val body = "create function f() returns int as \$\$ begin return 1; end \$\$ language plpgsql"
        assertEquals(listOf(body, "select 1"), texts("$body; select 1"))
        val tagged = "do \$fn\$ begin perform 1; end \$fn\$"
        assertEquals(listOf(tagged), texts("$tagged;"))
    }

    @Test fun positionalParameterIsNotADollarQuote() =
        assertEquals(listOf("select \$1", "select 2"), texts("select \$1; select 2"))

    @Test fun dropsEmptyAndCommentOnlyStatements() =
        assertEquals(listOf("select 1"), texts(";; -- nothing\n; /* x */ ; select 1;"))

    @Test fun statementAtCaret() {
        val s = "select 1;\nselect 2;\n\nselect 3"
        assertEquals("select 1", statementAt(s, 3)!!.text(s))
        assertEquals("select 1", statementAt(s, 9)!!.text(s))      // right after the ';'
        assertEquals("select 2", statementAt(s, 12)!!.text(s))
        assertEquals("select 2", statementAt(s, 20)!!.text(s))     // blank line falls back to previous
        assertEquals("select 3", statementAt(s, s.length)!!.text(s))
    }

    @Test fun statementAtOnEmptyInput() = assertNull(statementAt("  \n ", 1))

    @Test fun firstKeywordSkipsCommentsAndCase() {
        assertEquals("SELECT", firstKeyword("  select 1"))
        assertEquals("CREATE", firstKeyword("-- note\n/* x */ Create table t(a int)"))
        assertNull(firstKeyword("-- only a comment"))
        assertNull(firstKeyword("(select 1)"))
    }

    @Test fun changesSchemaOnlyForDdl() {
        assertTrue(changesSchema("create view v as select 1"))
        assertTrue(changesSchema("/* c */ DROP TABLE t"))
        assertTrue(changesSchema("alter table t add c int"))
        assertFalse(changesSchema("select 'create table'"))
        assertFalse(changesSchema("insert into t values (1)"))
    }

    @Test fun codeStartSkipsLeadingCommentsButNotInnerOnes() {
        val s = "-- nota\n/* x */ select 1 -- fin\n, 2;"
        val r = splitStatements(s).single()
        assertEquals(s.indexOf("select"), r.codeStart(s))
        assertEquals(0, splitStatements("select 1").single().codeStart("select 1"))
    }

    private fun texts(sql: String, slash: Boolean) = splitStatements(sql, slash).map { it.text(sql) }

    @Test fun plsqlBlocksEndAtASlashLineWhenAsked() {
        val sql = "select 1 from dual;\nbegin\n  insert into t values (1);\n  commit;\nend;\n/\nselect 2 from dual;"
        assertEquals(listOf("select 1 from dual", "begin\n  insert into t values (1);\n  commit;\nend;", "select 2 from dual"), texts(sql, true))
        assertEquals(5, texts(sql, false).size)       // without the flag every ';' splits, as in other engines
    }

    @Test fun createProcedureAndFunctionAreBlocksButCreateTableIsNot() {
        val sql = "create or replace procedure p as begin null; end;\n/\ncreate table t(a int);\ncreate function f return number is begin return 1; end;\n/"
        assertEquals(listOf("create or replace procedure p as begin null; end;", "create table t(a int)", "create function f return number is begin return 1; end;"), texts(sql, true))
        assertEquals(listOf("declare x number; begin x := 1; end;"), texts("declare x number; begin x := 1; end;\n/", true))
    }

    @Test fun aSlashThatIsNotAloneOnItsLineDoesNotEndABlock() {
        val sql = "begin\n  x := 4 / 2;\n  y := a /\n b;\nend;\n/"
        assertEquals(listOf("begin\n  x := 4 / 2;\n  y := a /\n b;\nend;"), texts(sql, true))
    }

    @Test fun anUnterminatedLastBlockRunsToTheEnd() = assertEquals(listOf("begin null; end;"), texts("begin null; end;", true))

    @Test fun statementAtFindsTheWholeBlock() {
        val sql = "select 1 from dual;\nbegin\n  null;\nend;\n/\n"
        val r = statementAt(sql, sql.indexOf("null"), slashBlocks = true)!!
        assertEquals("begin\n  null;\nend;", r.text(sql))
    }
}
