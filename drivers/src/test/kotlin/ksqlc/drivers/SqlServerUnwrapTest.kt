package ksqlc.drivers

import ksqlc.drivers.dialect.SqlServerDialect
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlServerUnwrapTest {
    private fun u(s: String) = SqlServerDialect.unwrap(s)

    @Test fun removesExactlyOneOuterPair() {
        assertEquals("(0)", u("((0))"))
        assertEquals("N'x'", u("(N'x')"))
        assertEquals("[c]>(0)", u("([c]>(0))"))
    }

    @Test fun leavesTextWhoseFirstParenDoesNotCloseAtTheEnd() {
        assertEquals("(a)+(b)", u("(a)+(b)"))
        assertEquals("f(x)", u("f(x)"))
        assertEquals("1", u("1"))
    }

    @Test fun parenthesesInsideStringsAndBracketedNamesDoNotCount() {
        assertEquals("(a)+(b)", u("((a)+(b))"))
        assertEquals("'))'", u("('))')"))
        assertEquals("'it''s )'", u("('it''s )')"))
        assertEquals("[a)b]+1", u("([a)b]+1)"))
    }
}
