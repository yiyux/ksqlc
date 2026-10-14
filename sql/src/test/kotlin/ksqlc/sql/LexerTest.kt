package ksqlc.sql

import ksqlc.sql.TokenKind.*
import kotlin.test.*

class LexerTest {
    /** Non-whitespace tokens as "KIND:text". */
    private fun lex(sql: String) = tokenize(sql).filter { it.kind != WHITESPACE }.map { "${it.kind}:${it.text(sql)}" }

    @Test fun tokensCoverTheInputWithoutGaps() {
        val sql = "select a.b, 'x''y' /* c */ from \"T\" -- end\nwhere n >= 1.5e3 and \$1 = \$\$ q \$\$;"
        val t = tokenize(sql)
        assertEquals(0, t.first().start)
        assertEquals(sql.length, t.last().end)
        t.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
        assertEquals(sql, t.joinToString("") { it.text(sql) })
    }

    @Test fun keywordsTypesAndIdentifiers() {
        assertEquals(listOf("KEYWORD:Select", "IDENT:name", "KEYWORD:from", "IDENT:users"), lex("Select name from users"))
        assertEquals(listOf("IDENT:id", "TYPE:integer", "KEYWORD:primary", "KEYWORD:key"), lex("id integer primary key"))
        assertEquals(listOf("IDENT:_x1"), lex("_x1"))
    }

    @Test fun stringsWithEscapes() {
        assertEquals(listOf("STRING:'it''s'"), lex("'it''s'"))
        assertEquals(listOf("STRING:'a\\'b'"), lex("'a\\'b'"))
        assertEquals(listOf("STRING:'sin cerrar"), lex("'sin cerrar"))
    }

    @Test fun quotedIdentifiers() {
        assertEquals(listOf("QUOTED_IDENT:\"we\"\"ird\"", "QUOTED_IDENT:`t`"), lex("\"we\"\"ird\" `t`"))
    }

    @Test fun commentsStopWhereTheyShould() {
        assertEquals(listOf("COMMENT:-- uno", "KEYWORD:select"), lex("-- uno\nselect"))
        assertEquals(listOf("KEYWORD:select", "COMMENT:/* a;b */", "NUMBER:1"), lex("select/* a;b */1"))
        assertEquals(listOf("COMMENT:/* sin cerrar"), lex("/* sin cerrar"))
    }

    @Test fun numbers() {
        assertEquals(listOf("NUMBER:42", "NUMBER:3.14", "NUMBER:.5", "NUMBER:1e-3", "NUMBER:0xFF"), lex("42 3.14 .5 1e-3 0xFF"))
        assertEquals(listOf("IDENT:t", "PUNCT:.", "IDENT:col"), lex("t.col"))
        assertEquals(listOf("NUMBER:1", "PUNCT:.", "IDENT:x"), lex("1.x"))
    }

    @Test fun dollarQuotingAndParameters() {
        assertEquals(listOf("STRING:\$\$ a;b \$\$"), lex("\$\$ a;b \$\$"))
        assertEquals(listOf("STRING:\$fn\$ x \$fn\$"), lex("\$fn\$ x \$fn\$"))
        assertEquals(listOf("PARAM:\$1", "OPERATOR:=", "PARAM:\$22"), lex("\$1 = \$22"))
    }

    @Test fun operatorsAndPunctuation() {
        assertEquals(listOf("IDENT:a", "OPERATOR:>=", "IDENT:b", "PUNCT:,", "PUNCT:(", "PUNCT:)", "PUNCT:;"), lex("a>=b,();"))
        assertEquals(listOf("IDENT:a", "OPERATOR:=", "COMMENT:--b"), lex("a=--b"))
        assertEquals(listOf("IDENT:x", "OPERATOR:::", "TYPE:int"), lex("x::int"))
    }

    @Test fun emptyInput() = assertEquals(emptyList(), tokenize(""))
}
