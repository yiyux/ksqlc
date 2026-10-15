package ksqlc.drivers

import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals

class CsvReaderTest {
    private fun read(text: String, delimiter: Char = ',') = CsvReader(StringReader(text), delimiter).use { it.asSequence().toList() }

    @Test fun plainRecordsWithAnyLineEnding() {
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2"), listOf("3", "4")), read("a,b\n1,2\r\n3,4"))
        assertEquals(listOf(listOf("a", "b")), read("a,b\r\n"))
        assertEquals(emptyList(), read(""))
    }

    @Test fun quotedFieldsHoldDelimitersQuotesAndLineBreaks() {
        assertEquals(listOf(listOf("x, y", "dice \"hola\"", "dos\nlíneas")), read("\"x, y\",\"dice \"\"hola\"\"\",\"dos\nlíneas\"\n"))
    }

    @Test fun emptyFieldsAreNullButQuotedEmptyIsAString() {
        assertEquals(listOf(listOf(null, "", null, "x", null)), read(",\"\",\\N,x,"))
        assertEquals(listOf(listOf("\\N")), read("\"\\N\""))
    }

    @Test fun skipsBomAndBlankLinesAndHonoursTheDelimiter() {
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2,5")), read("\uFEFFa;b\n\n1;2,5\n\n", ';'))
        assertEquals(listOf(listOf("a b", "c")), read("a b\tc", '\t'))
    }
}
