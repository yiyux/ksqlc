package ksqlc.sql

import kotlin.test.Test
import kotlin.test.assertEquals

class LineDiffTest {
    private fun d(a: String, b: String) = diffLines(a.split(' '), b.split(' ')).map { it.kind.name[0] + (it.left ?: "") + "|" + (it.right ?: "") }

    @Test fun identicalAndEmpty() {
        assertEquals(listOf("Sa|a", "Sb|b"), d("a b", "a b"))
        assertEquals(emptyList(), diffLines(emptyList(), emptyList()))
    }

    @Test fun addedAndRemovedLines() {
        assertEquals(listOf("Sa|a", "A|x", "Sb|b"), d("a b", "a x b"))
        assertEquals(listOf("Sa|a", "Rx|", "Sb|b"), d("a x b", "a b"))
        assertEquals(listOf("A|a", "A|b"), diffLines(emptyList(), listOf("a", "b")).map { it.kind.name[0] + (it.left ?: "") + "|" + (it.right ?: "") })
    }

    @Test fun aRemovedRunNextToAnAddedRunBecomesChangedRows() {
        assertEquals(listOf("Sa|a", "Cx|y", "Cz|w", "A|v", "Sb|b"), d("a x z b", "a y w v b"))
        assertEquals(listOf("Cone|uno", "Rtwo|"), d("one two", "uno"))
    }

    @Test fun commonLinesAreMatchedAcrossReorderedMiddles() {
        // LCS keeps a, c, e; b and d are removed, x added
        val rows = diffLines(listOf("a", "b", "c", "d", "e"), listOf("a", "c", "x", "e"))
        assertEquals(listOf(DiffKind.SAME, DiffKind.REMOVED, DiffKind.SAME, DiffKind.CHANGED, DiffKind.SAME), rows.map { it.kind })
        assertEquals("d" to "x", rows[3].left to rows[3].right)
    }

    @Test fun hugeMiddlesFallBackToOneBlock() {
        val a = (1..3000).map { "a$it" }; val b = (1..3000).map { "b$it" }
        val rows = diffLines(a, b)
        assertEquals(3000, rows.size)
        assertEquals(setOf(DiffKind.CHANGED), rows.map { it.kind }.toSet())
    }
}
