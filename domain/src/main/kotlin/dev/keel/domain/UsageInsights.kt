package dev.keel.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class UsageRange { DAY, WEEK }

/** One app's time in a period, and in the same stretch of the period before, for comparing. */
data class AppTime(val pkg: String, val millis: Long, val previousMillis: Long)

data class UsageReport(
    val from: Long,
    val to: Long,
    /** Time per bar: 24 hours for a day, 7 days (Monday first) for a week. */
    val bars: List<Long>,
    val total: Long,
    /** The period before, cut at the same point, so a half-finished week isn't compared with a whole one. */
    val previousTotal: Long,
    val apps: List<AppTime>,
    /** The hour that starts the busiest 3-hour stretch of the day, or null with no use. */
    val busiestHour: Int?,
    /** Days the period has had so far, for a fair daily average. */
    val daysSoFar: Int,
)

private fun overlap(session: UsageSession, from: Long, to: Long): Long =
    (minOf(session.end, to) - maxOf(session.start, from)).coerceAtLeast(0)

/** Time in each hour of the day, added up across the period. */
fun hourOfDayTotals(sessions: List<UsageSession>, from: Long, to: Long, zone: ZoneId): LongArray {
    val hours = LongArray(24)
    for (session in sessions) {
        var cursor = maxOf(session.start, from)
        val end = minOf(session.end, to)
        while (cursor < end) {
            val at = Instant.ofEpochMilli(cursor).atZone(zone)
            val nextHour = at.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
            val piece = minOf(nextHour, end) - cursor
            hours[at.hour] += piece
            cursor += piece
        }
    }
    return hours
}

/** The start of the 3-hour stretch with the most use. It can run past midnight, like 11 pm to 2 am. */
fun busiestStart(hours: LongArray, width: Int = 3): Int? {
    if (hours.all { it == 0L }) return null
    return (0 until 24).maxBy { start -> (0 until width).sumOf { hours[(start + it) % 24] } }
}

/**
 * Screen time for the day or week holding [anchor]. Apps in [exclude], like the home screen and Keel itself,
 * are left out, because opening them isn't really using the phone.
 */
fun usageReport(
    sessions: List<UsageSession>,
    range: UsageRange,
    anchor: LocalDate,
    now: Long,
    zone: ZoneId,
    exclude: Set<String> = emptySet(),
): UsageReport {
    val used = sessions.filter { it.pkg !in exclude }
    val firstDay = if (range == UsageRange.DAY) anchor else weekStart(anchor)
    val days = if (range == UsageRange.DAY) 1L else 7L
    val start = firstDay.atStartOfDay(zone)
    val from = start.toInstant().toEpochMilli()
    val to = firstDay.plusDays(days).atStartOfDay(zone).toInstant().toEpochMilli()
    val edges = if (range == UsageRange.DAY) {
        (0..24).map { minOf(start.plusHours(it.toLong()).toInstant().toEpochMilli(), to) }
    } else {
        (0..7).map { firstDay.plusDays(it.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() }
    }
    val bars = edges.zipWithNext { a, b -> used.sumOf { overlap(it, a, b) } }

    // Compare like with like: the period before, up to the same point.
    val previousFrom = (if (range == UsageRange.DAY) firstDay.minusDays(1) else firstDay.minusWeeks(1))
        .atStartOfDay(zone).toInstant().toEpochMilli()
    val elapsed = (minOf(now, to) - from).coerceAtLeast(0)
    val previousTo = minOf(previousFrom + elapsed, from)

    val byApp = used.groupBy { it.pkg }.map { (pkg, list) ->
        AppTime(pkg, list.sumOf { overlap(it, from, to) }, list.sumOf { overlap(it, previousFrom, previousTo) })
    }.filter { it.millis > 0 }.sortedByDescending { it.millis }

    val daysSoFar = when {
        now >= to -> days.toInt()
        now < from -> 0
        else -> (ChronoUnit.DAYS.between(firstDay, Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) + 1).toInt()
    }
    return UsageReport(
        from = from,
        to = to,
        bars = bars,
        total = bars.sum(),
        previousTotal = used.sumOf { overlap(it, previousFrom, previousTo) },
        apps = byApp,
        busiestHour = busiestStart(hourOfDayTotals(used, from, to, zone)),
        daysSoFar = daysSoFar,
    )
}

/**
 * Each time an app was used inside the window, oldest first and cut to the window. Going back to the same
 * app within [joinGapMillis], like after a notification, counts as one visit, because that's how it feels.
 */
fun visits(
    sessions: List<UsageSession>,
    from: Long,
    to: Long,
    joinGapMillis: Long = 60_000,
): List<UsageSession> {
    val clipped = sessions
        .map { UsageSession(it.pkg, maxOf(it.start, from), minOf(it.end, to)) }
        .filter { it.end > it.start }
        .sortedBy { it.start }
    val joined = mutableListOf<UsageSession>()
    for (session in clipped) {
        val last = joined.lastOrNull()
        if (last != null && last.pkg == session.pkg && session.start - last.end <= joinGapMillis) {
            joined[joined.lastIndex] = last.copy(end = maxOf(last.end, session.end))
        } else {
            joined += session
        }
    }
    return joined
}

/** "2 h 5 min", "45 min", "Under 1 min" or "0 min". */
fun durationLabel(millis: Long): String {
    if (millis <= 0) return "0 min"
    val minutes = millis / 60_000
    if (minutes == 0L) return "Under 1 min"
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0L -> "$rest min"
        rest == 0L -> "$hours h"
        else -> "$hours h $rest min"
    }
}

/** An hour of the day the way people say it: "12 am", "9 am", "12 pm", "9 pm". */
fun hourLabel(hour: Int): String {
    val h = ((hour % 24) + 24) % 24
    val twelve = if (h % 12 == 0) 12 else h % 12
    return "$twelve ${if (h < 12) "am" else "pm"}"
}

/** "38 min more than this time last week", or null when the change is under a minute. */
fun changeLabel(now: Long, before: Long, range: UsageRange, finished: Boolean): String? {
    val difference = now - before
    if (before == 0L || kotlin.math.abs(difference) < 60_000) return null
    val word = if (difference > 0) "more" else "less"
    val than = when {
        range == UsageRange.DAY && finished -> "the day before"
        range == UsageRange.DAY -> "this time yesterday"
        finished -> "the week before"
        else -> "this time last week"
    }
    return "${durationLabel(kotlin.math.abs(difference))} $word than $than"
}
