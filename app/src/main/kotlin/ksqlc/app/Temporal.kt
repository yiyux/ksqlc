package ksqlc.app

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** What a date-like column holds, which decides what the picker offers and how the value is written. */
enum class TemporalKind(val hasDate: Boolean, val hasTime: Boolean, val hasOffset: Boolean) {
    DATE(true, false, false), TIME(false, true, false), DATETIME(true, true, false), DATETIME_TZ(true, true, true),
}

/** The picker kind for a column of [typeName] (as the JDBC driver reports it), or null for columns that are not dates or times. */
fun temporalKind(typeName: String, engine: ksqlc.core.DbKind? = null): TemporalKind? = when (typeName.uppercase().replace(Regex("""\(.*?\)"""), "").replace(Regex("\\s+"), " ").trim()) {
    "DATE" -> if (engine == ksqlc.core.DbKind.ORACLE) TemporalKind.DATETIME else TemporalKind.DATE      // an Oracle DATE has a time of day
    "TIME", "TIME WITHOUT TIME ZONE" -> TemporalKind.TIME
    "TIMESTAMP", "DATETIME", "DATETIME2", "SMALLDATETIME", "TIMESTAMP WITHOUT TIME ZONE", "TIMESTAMPLTZ", "TIMESTAMP WITH LOCAL TIME ZONE" -> TemporalKind.DATETIME
    "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE", "DATETIMEOFFSET" -> TemporalKind.DATETIME_TZ
    else -> null
}

/** A point on the calendar and the clock; [offset] only matters for [TemporalKind.DATETIME_TZ]. */
data class Moment(val date: LocalDate, val time: LocalTime, val offset: ZoneOffset) {
    fun withTime(h: Int = time.hour, m: Int = time.minute, s: Int = time.second) = copy(time = LocalTime.of(h.mod(24), m.mod(60), s.mod(60)))
}

fun now(): Moment {
    val t = java.time.ZonedDateTime.now()
    return Moment(t.toLocalDate(), t.toLocalTime().withNano(0), ZoneId.systemDefault().rules.getOffset(t.toInstant()))
}

private val DATE_TIME = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T](\d{1,2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?)?\s*(Z|[+-]\d{2}(?::?\d{2})?)?$""")
private val ONLY_TIME = Regex("""^(\d{1,2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?$""")

/** Reads what a user (or the database) wrote in a cell; lenient about `T`, fractions and missing parts. Null when it is not a date for [kind]. */
fun parseMoment(text: String, kind: TemporalKind): Moment? = runCatching {
    val s = text.trim()
    val base = now()
    if (kind == TemporalKind.TIME) {
        val m = ONLY_TIME.matchEntire(s) ?: return@runCatching null
        return@runCatching base.copy(time = LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].ifEmpty { "0" }.toInt()))
    }
    val m = DATE_TIME.matchEntire(s) ?: return@runCatching null
    val g = m.groupValues
    val date = LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt())
    val time = LocalTime.of(g[4].ifEmpty { "0" }.toInt(), g[5].ifEmpty { "0" }.toInt(), g[6].ifEmpty { "0" }.toInt())
    // without an offset the value is in the machine's zone, whose offset depends on the date (summer time)
    val offset = g[7].let { o -> if (o.isEmpty()) ZoneId.systemDefault().rules.getOffset(LocalDateTime.of(date, time)) else if (o == "Z") ZoneOffset.UTC else ZoneOffset.of(if (o.length == 5) o.substring(0, 3) + ":" + o.substring(3) else o) }
    Moment(date, time, offset)
}.getOrNull()

private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")

/** The text to store in the cell; exactly the shapes the editor sends to the database. */
fun formatMoment(m: Moment, kind: TemporalKind): String = when (kind) {
    TemporalKind.DATE -> m.date.format(DATE_FMT)
    TemporalKind.TIME -> m.time.format(TIME_FMT)
    TemporalKind.DATETIME -> m.date.format(DATE_FMT) + " " + m.time.format(TIME_FMT)
    TemporalKind.DATETIME_TZ -> m.date.format(DATE_FMT) + " " + m.time.format(TIME_FMT) + m.offset.id.let { if (it == "Z") "+00:00" else it }
}

/** The 42 days (six weeks, Monday first) a month page shows, including the tail of the previous month and the start of the next. */
fun monthPage(month: java.time.YearMonth): List<LocalDate> {
    val first = month.atDay(1)
    val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
    return List(42) { start.plusDays(it.toLong()) }
}

/** Values the cell menu can fill in for a column of [typeName]: the current moment for date-like columns, a random UUID for text and uuid columns. */
fun generators(typeName: String, engine: ksqlc.core.DbKind?): List<Pair<String, () -> String>> {
    val kind = temporalKind(typeName, engine)
    val t = typeName.uppercase()
    return when {
        kind != null -> listOf((if (kind == TemporalKind.DATE) "Fecha de hoy" else "Fecha y hora actual") to { formatMoment(now(), kind) })
        t == "UUID" || t == "UNIQUEIDENTIFIER" || t.contains("CHAR") || t == "TEXT" -> listOf("Generar UUID" to { java.util.UUID.randomUUID().toString() })
        else -> emptyList()
    }
}
