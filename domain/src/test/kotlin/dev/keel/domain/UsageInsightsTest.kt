package dev.keel.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class UsageInsightsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val monday = LocalDate.of(2026, 11, 9)

    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun session(pkg: String, day: LocalDate, fromHour: Int, fromMinute: Int, toHour: Int, toMinute: Int) =
        UsageSession(pkg, at(day, fromHour, fromMinute), at(day, toHour, toMinute))

    private val min = 60_000L

    @Test fun `a day splits into 24 hourly bars, and a session across an hour is shared`() {
        val sessions = listOf(session("yt", monday, 9, 50, 10, 20))
        val report = usageReport(sessions, UsageRange.DAY, monday, at(monday, 23), zone)
        assertEquals(24, report.bars.size)
        assertEquals(10 * min, report.bars[9])
        assertEquals(20 * min, report.bars[10])
        assertEquals(30 * min, report.total)
    }

    @Test fun `a week has seven bars from Monday, and a session over midnight counts on both days`() {
        val tuesday = monday.plusDays(1)
        val sessions = listOf(UsageSession("yt", at(monday, 23, 30), at(tuesday, 0, 15)))
        val report = usageReport(sessions, UsageRange.WEEK, tuesday, at(tuesday, 12), zone)
        assertEquals(7, report.bars.size)
        assertEquals(30 * min, report.bars[0])
        assertEquals(15 * min, report.bars[1])
        assertEquals(2, report.daysSoFar)
    }

    @Test fun `use outside the period is left out`() {
        val sessions = listOf(session("yt", monday.minusDays(1), 10, 0, 11, 0))
        assertEquals(0L, usageReport(sessions, UsageRange.DAY, monday, at(monday, 12), zone).total)
    }

    @Test fun `the comparison stops at the same point last week`() {
        val lastWeek = monday.minusWeeks(1)
        val sessions = listOf(
            session("yt", lastWeek, 9, 0, 10, 0),
            // Later in last week than "now", so not part of a fair comparison yet.
            session("yt", lastWeek.plusDays(3), 9, 0, 10, 0),
            session("yt", monday, 9, 0, 9, 30),
        )
        val report = usageReport(sessions, UsageRange.WEEK, monday, at(monday, 12), zone)
        assertEquals(30 * min, report.total)
        assertEquals(60 * min, report.previousTotal)
        assertEquals(60 * min, report.apps.single().previousMillis)
    }

    @Test fun `apps are ranked by time, and excluded apps never appear`() {
        val sessions = listOf(
            session("a", monday, 9, 0, 9, 10),
            session("b", monday, 10, 0, 10, 40),
            session("home", monday, 11, 0, 13, 0),
        )
        val report = usageReport(sessions, UsageRange.DAY, monday, at(monday, 23), zone, exclude = setOf("home"))
        assertEquals(listOf("b", "a"), report.apps.map { it.pkg })
        assertEquals(50 * min, report.total)
    }

    @Test fun `the busiest stretch can run past midnight`() {
        val hours = LongArray(24).also {
            it[23] = 40 * min
            it[0] = 40 * min
            it[1] = 40 * min
            it[12] = 60 * min
        }
        assertEquals(23, busiestStart(hours))
        assertNull(busiestStart(LongArray(24)))
    }

    @Test fun `hour totals add up across days`() {
        val sessions = listOf(
            session("a", monday, 21, 0, 21, 30),
            session("a", monday.plusDays(1), 21, 15, 21, 45),
        )
        val expected = LongArray(24).also { it[21] = 60 * min }
        assertArrayEquals(expected, hourOfDayTotals(sessions, at(monday, 0), at(monday.plusDays(7), 0), zone))
    }

    @Test fun `visits are cut to the day, in order, with quick returns joined`() {
        val sessions = listOf(
            session("yt", monday, 9, 5, 9, 10),
            session("yt", monday, 9, 10, 9, 12),
            session("wa", monday, 8, 0, 8, 3),
            session("yt", monday, 11, 0, 11, 5),
            UsageSession("late", at(monday, 23, 50), at(monday.plusDays(1), 0, 20)),
        )
        val day = visits(sessions, at(monday, 0), at(monday.plusDays(1), 0))
        assertEquals(listOf("wa", "yt", "yt", "late"), day.map { it.pkg })
        assertEquals(at(monday, 9, 5) to at(monday, 9, 12), day[1].start to day[1].end)
        assertEquals(at(monday.plusDays(1), 0), day[3].end)
    }

    @Test fun `labels read naturally`() {
        assertEquals("0 min", durationLabel(0))
        assertEquals("Under 1 min", durationLabel(30_000))
        assertEquals("45 min", durationLabel(45 * min))
        assertEquals("2 h", durationLabel(120 * min))
        assertEquals("2 h 5 min", durationLabel(125 * min))
        assertEquals("12 am", hourLabel(0))
        assertEquals("9 am", hourLabel(9))
        assertEquals("12 pm", hourLabel(12))
        assertEquals("11 pm", hourLabel(23))
        assertEquals("2 am", hourLabel(26))
    }

    @Test fun `the change line says which way and against what`() {
        assertEquals("38 min more than this time last week", changeLabel(98 * min, 60 * min, UsageRange.WEEK, false))
        assertEquals("1 h less than the day before", changeLabel(60 * min, 120 * min, UsageRange.DAY, true))
        assertNull(changeLabel(60 * min, 0, UsageRange.WEEK, false))
        assertNull(changeLabel(60 * min, 60 * min + 10_000, UsageRange.WEEK, false))
    }
}
