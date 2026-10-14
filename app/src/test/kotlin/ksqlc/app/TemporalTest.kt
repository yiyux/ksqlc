package ksqlc.app

import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.test.*

class TemporalTest {
    @Test fun typeNamesMapToPickerKinds() {
        assertEquals(TemporalKind.DATE, temporalKind("date")); assertEquals(TemporalKind.DATE, temporalKind("DATE"))
        assertEquals(TemporalKind.TIME, temporalKind("time")); assertEquals(TemporalKind.TIME, temporalKind("time without time zone"))
        for (t in listOf("timestamp", "datetime", "DATETIME2", "timestamp(6)", "smalldatetime", "timestamp without time zone")) assertEquals(TemporalKind.DATETIME, temporalKind(t), t)
        for (t in listOf("timestamptz", "timestamp with time zone", "datetimeoffset")) assertEquals(TemporalKind.DATETIME_TZ, temporalKind(t), t)
        for (t in listOf("text", "int4", "varchar(10)", "bytea", "")) assertNull(temporalKind(t), t)
    }

    @Test fun kindsDecideWhatThePickerOffers() {
        assertTrue(TemporalKind.DATE.hasDate && !TemporalKind.DATE.hasTime)
        assertTrue(!TemporalKind.TIME.hasDate && TemporalKind.TIME.hasTime)
        assertTrue(TemporalKind.DATETIME.hasDate && TemporalKind.DATETIME.hasTime && !TemporalKind.DATETIME.hasOffset)
        assertTrue(TemporalKind.DATETIME_TZ.hasOffset)
    }

    @Test fun parsingIsLenientAboutShapeButStrictAboutValues() {
        assertEquals(LocalDate.of(2026, 1, 31), parseMoment("2026-01-31", TemporalKind.DATE)!!.date)
        assertEquals(LocalDate.of(2026, 1, 5), parseMoment(" 2026-1-5 ", TemporalKind.DATE)!!.date)
        val ts = parseMoment("2026-01-31T10:20:30.123", TemporalKind.DATETIME)!!
        assertEquals(LocalTime.of(10, 20, 30), ts.time)
        assertEquals(LocalTime.of(10, 20, 0), parseMoment("2026-01-31 10:20", TemporalKind.DATETIME)!!.time)
        assertEquals(ZoneOffset.ofHours(1), parseMoment("2026-01-31 10:20:30+01:00", TemporalKind.DATETIME_TZ)!!.offset)
        assertEquals(ZoneOffset.ofHoursMinutes(-5, -30), parseMoment("2026-01-31 10:20:30-0530", TemporalKind.DATETIME_TZ)!!.offset)
        assertEquals(ZoneOffset.UTC, parseMoment("2026-01-31 10:20:30Z", TemporalKind.DATETIME_TZ)!!.offset)
        assertEquals(LocalTime.of(9, 5, 7), parseMoment("9:05:07", TemporalKind.TIME)!!.time)
        for (bad in listOf("", "abc", "2026-13-01", "2026-02-30", "10:20")) assertNull(parseMoment(bad, TemporalKind.DATE), bad)
        assertNull(parseMoment("25:00:00", TemporalKind.TIME))
    }

    @Test fun formattingWritesWhatTheDatabaseColumnNeeds() {
        val m = Moment(LocalDate.of(2026, 2, 3), LocalTime.of(4, 5, 6), ZoneOffset.ofHours(2))
        assertEquals("2026-02-03", formatMoment(m, TemporalKind.DATE))
        assertEquals("04:05:06", formatMoment(m, TemporalKind.TIME))
        assertEquals("2026-02-03 04:05:06", formatMoment(m, TemporalKind.DATETIME))
        assertEquals("2026-02-03 04:05:06+02:00", formatMoment(m, TemporalKind.DATETIME_TZ))
        assertEquals("2026-02-03 04:05:06+00:00", formatMoment(m.copy(offset = ZoneOffset.UTC), TemporalKind.DATETIME_TZ))
    }

    @Test fun whatIsFormattedIsAcceptedByTheEditorsOwnParser() {
        val m = Moment(LocalDate.of(2026, 12, 31), LocalTime.of(23, 59, 59), ZoneOffset.ofHoursMinutes(5, 30))
        for ((kind, type, pgKind) in listOf(
            Triple(TemporalKind.DATE, "date", ksqlc.core.DbKind.POSTGRES), Triple(TemporalKind.TIME, "time", ksqlc.core.DbKind.POSTGRES),
            Triple(TemporalKind.DATETIME, "timestamp", ksqlc.core.DbKind.POSTGRES), Triple(TemporalKind.DATETIME_TZ, "timestamptz", ksqlc.core.DbKind.POSTGRES),
            Triple(TemporalKind.DATETIME, "datetime", ksqlc.core.DbKind.MYSQL),
        )) assertNotNull(ksqlc.drivers.parseParam(formatMoment(m, kind), type, pgKind), "$kind/$type")
    }

    @Test fun timeStepsWrapAround() {
        val m = Moment(LocalDate.of(2026, 1, 1), LocalTime.of(23, 59, 59), ZoneOffset.UTC)
        assertEquals(LocalTime.of(0, 59, 59), m.withTime(h = 24).time)
        assertEquals(LocalTime.of(23, 0, 59), m.withTime(m = 60).time)
        assertEquals(LocalTime.of(23, 59, 59), m.withTime(s = -1).time)
    }

    @Test fun aMonthPageHasSixMondayFirstWeeks() {
        val page = monthPage(YearMonth.of(2026, 2))     // 1 Feb 2026 is a Sunday
        assertEquals(42, page.size)
        assertEquals(LocalDate.of(2026, 1, 26), page.first())
        assertEquals(java.time.DayOfWeek.MONDAY, page.first().dayOfWeek)
        assertTrue(page.contains(LocalDate.of(2026, 2, 28)))
        assertEquals(LocalDate.of(2026, 3, 8), page.last())
        val mon = monthPage(YearMonth.of(2026, 6))     // 1 Jun 2026 is a Monday: starts on the 1st
        assertEquals(LocalDate.of(2026, 6, 1), mon.first())
    }
}
