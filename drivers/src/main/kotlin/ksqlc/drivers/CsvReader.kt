package ksqlc.drivers

import java.io.BufferedReader
import java.io.Reader

/**
 * Reads delimited text record by record (RFC 4180): quoted fields may hold the delimiter, line breaks and `""`.
 * An unquoted empty field, or an unquoted `\N`, comes back as null (SQL NULL); a quoted `""` is the empty string.
 * A leading BOM and blank lines are skipped.
 */
class CsvReader(reader: Reader, private val delimiter: Char = ',') : AutoCloseable {
    private val r = if (reader is BufferedReader) reader else BufferedReader(reader)
    private var pushed = NONE
    private var first = true

    private fun read(): Int {
        if (pushed != NONE) return pushed.also { pushed = NONE }
        val c = r.read()
        if (first) { first = false; if (c == 0xFEFF) return r.read() }
        return c
    }

    /** The next record, or null at the end of the input. */
    fun next(): List<String?>? {
        while (true) {
            var c = read()
            if (c == -1) return null
            val fields = ArrayList<String?>()
            var sawQuote = false
            while (true) {
                val sb = StringBuilder()
                var quoted = false
                if (c == '"'.code) {
                    quoted = true; sawQuote = true
                    while (true) {
                        c = read()
                        if (c == -1) break
                        if (c == '"'.code) {
                            val n = read()
                            if (n == '"'.code) sb.append('"') else { c = n; break }
                        } else sb.append(c.toChar())
                    }
                }
                while (c != -1 && c != delimiter.code && c != '\n'.code && c != '\r'.code) { sb.append(c.toChar()); c = read() }
                fields += if (!quoted && (sb.isEmpty() || sb.toString() == "\\N")) null else sb.toString()
                when (c) {
                    delimiter.code -> c = read()
                    '\r'.code -> { val n = read(); if (n != '\n'.code) pushed = n; break }
                    else -> break
                }
            }
            if (fields.size == 1 && fields[0] == null && !sawQuote) continue   // blank line
            return fields
        }
    }

    fun asSequence(): Sequence<List<String?>> = generateSequence { next() }

    override fun close() = r.close()

    private companion object { const val NONE = -2 }
}
