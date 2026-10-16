package ksqlc.app

import kotlin.test.*

class ModifyHistoryTest {
    private val sql = listOf("alter a", "alter b", "alter c")

    @Test fun anAtomicApplyShareTheOutcomeOfEveryStatement() {
        assertEquals(listOf(true, true, true), modifyHistory(sql, atomic = true, ran = 0, error = null, preludeFailed = false, now = 1).map { it.ok })
        assertEquals(listOf(false, false, false), modifyHistory(sql, atomic = true, ran = 0, error = "boom", preludeFailed = false, now = 1).map { it.ok })
    }

    @Test fun aStepwiseApplyRecordsWhatRanAndTheOneThatFailed() {
        val h = modifyHistory(sql, atomic = false, ran = 1, error = "boom", preludeFailed = false, now = 1)
        assertEquals(listOf("alter a;" to true, "alter b;" to false), h.map { it.sql to it.ok })
        assertEquals(sql.map { "$it;" }, modifyHistory(sql, atomic = false, ran = 3, error = null, preludeFailed = false, now = 1).map { it.sql })
    }

    @Test fun nothingIsRecordedWhenTheSessionFailedBeforeAnyStatement() {
        assertEquals(emptyList(), modifyHistory(sql, atomic = false, ran = 0, error = "no connection", preludeFailed = true, now = 1))
    }
}
