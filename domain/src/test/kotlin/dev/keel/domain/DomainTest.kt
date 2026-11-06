package dev.keel.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DomainTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) =
        ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun goal(id: Long, title: String = "g$id", createdAt: Long = 0) =
        Goal(id, title, Horizon.YEAR, GoalStatus.ACTIVE, createdAt = createdAt)

    @Test fun `ranking counts separate months, not total touches`() {
        val ranked = rankGoals(
            listOf(goal(1), goal(2)),
            mapOf(
                1L to List(20) { at(2026, 5, 1 + it) },
                2L to listOf(at(2026, 1, 1), at(2026, 2, 1), at(2026, 3, 1)),
            ),
            zone,
        )
        assertEquals(listOf(2L, 1L), ranked.map { it.goal.id })
        assertEquals(3, ranked[0].monthsReturned)
        assertEquals(1, ranked[1].monthsReturned)
    }

    @Test fun `months use the local time zone`() {
        val lateJan = ZonedDateTime.of(2026, 1, 31, 23, 30, 0, 0, zone).toInstant().toEpochMilli()
        val earlyFeb = ZonedDateTime.of(2026, 2, 1, 1, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(2, rankGoals(listOf(goal(1)), mapOf(1L to listOf(lateJan, earlyFeb)), zone)[0].monthsReturned)
    }

    @Test fun `sort order can be changed`() {
        val goals = listOf(goal(1, createdAt = 10), goal(2, createdAt = 20))
        val touches = mapOf(1L to listOf(at(2026, 6, 1)), 2L to listOf(at(2026, 1, 1)))
        assertEquals(listOf(2L, 1L), rankGoals(goals, touches, zone, GoalSort.NEWEST).map { it.goal.id })
        assertEquals(listOf(1L, 2L), rankGoals(goals, touches, zone, GoalSort.LAST_TOUCHED).map { it.goal.id })
    }

    @Test fun `only long horizons cool off, and days round up`() {
        assertEquals(null, coolingUntil(Horizon.WEEK, 0))
        assertEquals(null, coolingUntil(Horizon.QUARTER, 0))
        val until = coolingUntil(Horizon.LIFE, 0)!!
        assertEquals(30, coolingDaysLeft(until, 0))
        assertEquals(1, coolingDaysLeft(until, until - 1))
        assertEquals(0, coolingDaysLeft(until, until))
    }

    @Test fun `real state reports progress and stalling`() {
        val steps = listOf(PlanStep(1, 1, "a", doneAt = 5), PlanStep(2, 1, "b"))
        val now = at(2026, 3, 22)
        assertEquals("1 of 2 steps", realState(steps, at(2026, 3, 20), now))
        assertEquals("1 of 2 steps, stalled 21 days", realState(steps, at(2026, 3, 1), now))
        assertEquals("No steps yet", realState(emptyList(), null, now))
    }

    @Test fun `today's task comes before plan steps and names its goal`() {
        val ranked = listOf(RankedGoal(goal(1, "Safety net"), 2, null))
        val steps = listOf(PlanStep(7, 1, "Compare plans", estimateMinutes = 15))
        val task = Task(3, "Call bank", LocalDate.of(2026, 1, 1), stepId = 7, createdAt = 1)
        val picked = pickNext(listOf(task), ranked, steps)!!
        assertEquals("Call bank", picked.title)
        assertEquals("Safety net", picked.serves)
        assertEquals(3L, picked.taskId)
    }

    @Test fun `falls back to the top goal's first unfinished step, then to nothing`() {
        val ranked = listOf(RankedGoal(goal(1, "A"), 2, null), RankedGoal(goal(2, "B"), 1, null))
        val steps = listOf(
            PlanStep(1, 1, "done", position = 0, doneAt = 1),
            PlanStep(2, 2, "b1", position = 0),
            PlanStep(3, 1, "a2", position = 2),
            PlanStep(4, 1, "a1", position = 1),
        )
        assertEquals("a1", pickNext(emptyList(), ranked, steps)!!.title)
        assertNull(pickNext(emptyList(), ranked, listOf(steps[0])))
    }

    @Test fun `finished tasks are skipped`() {
        val done = Task(1, "x", LocalDate.of(2026, 1, 1), doneAt = 5)
        assertNull(pickNext(listOf(done), emptyList(), emptyList()))
    }

    @Test fun `a day ends`() {
        val d = LocalDate.of(2026, 1, 1)
        assertEquals(DayState.Empty, dayState(emptyList()))
        assertEquals(DayState.InProgress(1, 1), dayState(listOf(Task(1, "a", d, doneAt = 1), Task(2, "b", d))))
        assertEquals(DayState.Finished(1), dayState(listOf(Task(1, "a", d, doneAt = 1))))
    }

    @Test fun `tightening is immediate`() {
        val state = RuleState(RuleSetting(true, 10))
        assertEquals(RuleState(RuleSetting(true, 30)), requestChange(state, RuleSetting(true, 30), at(2026, 1, 7), zone))
        assertEquals(
            RuleState(RuleSetting(true, 5)),
            requestChange(RuleState(RuleSetting(false, 5)), RuleSetting(true, 5), at(2026, 1, 7), zone),
        )
    }

    @Test fun `loosening waits until next Monday`() {
        val now = at(2026, 1, 7) // A Wednesday.
        val monday = ZonedDateTime.of(2026, 1, 12, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        for (ask in listOf(RuleSetting(true, 3), RuleSetting(false, 10))) {
            val out = requestChange(RuleState(RuleSetting(true, 10)), ask, now, zone)
            assertEquals(RuleSetting(true, 10), out.current)
            assertEquals(ask, out.pending)
            assertEquals(monday, out.pendingAt)
            assertEquals(RuleSetting(true, 10), effective(out, monday - 1).current)
            assertEquals(ask, effective(out, monday).current)
        }
    }

    @Test fun `on a Monday, loosening waits a full week`() {
        val monday = at(2026, 1, 12)
        val next = ZonedDateTime.of(2026, 1, 19, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(next, nextWeekStart(monday, zone))
    }

    @Test fun `tightening cancels a pending loosening`() {
        val now = at(2026, 1, 7)
        val pending = requestChange(RuleState(RuleSetting(true, 10)), RuleSetting(false, 10), now, zone)
        assertEquals(RuleState(RuleSetting(true, 20)), requestChange(pending, RuleSetting(true, 20), now, zone))
    }

    @Test fun `a due change is applied before judging the next request`() {
        val now = at(2026, 1, 7)
        val pending = requestChange(RuleState(RuleSetting(true, 10)), RuleSetting(true, 5), now, zone)
        val later = at(2026, 1, 13)
        // The live delay is already 5 by then, so asking for 8 tightens and applies at once.
        assertEquals(RuleState(RuleSetting(true, 8)), requestChange(pending, RuleSetting(true, 8), later, zone))
    }

    @Test fun `a stray window event is not a launch`() {
        val yt = "com.google.android.youtube"
        val home = "com.sec.android.app.launcher"
        assertFalse(isGenuineForeground(yt, home, home))
        assertTrue(isGenuineForeground(yt, home, yt))
        assertTrue(isGenuineForeground(yt, yt, home))
        assertFalse(isGenuineForeground(yt, null, null))
    }

    @Test fun `sessions close on pause or on the next resume`() {
        val out = buildSessions(
            listOf(
                UsageEvent(10, "a", true),
                UsageEvent(20, "a", false),
                UsageEvent(30, "b", true),
                UsageEvent(40, "c", true),
            ),
        )
        assertEquals(listOf(UsageSession("a", 10, 20), UsageSession("b", 30, 40)), out.sessions)
        assertEquals("c", out.openPkg)
        assertEquals(40L, out.openSince)
    }

    @Test fun `an open session carries across syncs, and repeat resumes don't split it`() {
        val out = buildSessions(
            listOf(UsageEvent(50, "c", true), UsageEvent(60, "c", false)),
            openPkg = "c",
            openSince = 40,
        )
        assertEquals(listOf(UsageSession("c", 40, 60)), out.sessions)
        assertNull(out.openPkg)
    }

    @Test fun `week stats count every way of changing your mind`() {
        val stats = weekStats(
            listOf(
                InterceptEvent(1, "yt", Choice.DONT_OPEN),
                InterceptEvent(2, "yt", Choice.DO_IT_NOW),
                InterceptEvent(3, "ig", Choice.LEFT),
                InterceptEvent(4, "yt", Choice.OPEN, "Just bored"),
            ),
        )
        assertEquals(4, stats.intercepted)
        assertEquals(3, stats.abandoned)
        assertEquals(75, stats.abandonedPercent)
        assertEquals(3, stats.byTarget["yt"])
        assertEquals(1, stats.reasons["Just bored"])
        assertNull(weekStats(emptyList()).abandonedPercent)
    }

    @Test fun `review is due on Sunday, and a missed one stays open`() {
        val sunday = LocalDate.of(2026, 11, 8)
        val monday = sunday.minusDays(6)
        val lastMonday = monday.minusWeeks(1)
        assertEquals(monday, reviewWeekDue(sunday, setOf(lastMonday)))
        assertEquals(lastMonday, reviewWeekDue(sunday, setOf(monday)))
        assertNull(reviewWeekDue(sunday, setOf(monday, lastMonday)))
        assertEquals(lastMonday, reviewWeekDue(sunday.minusDays(2), emptySet()))
        assertNull(reviewWeekDue(sunday.minusDays(2), setOf(lastMonday)))
    }

    @Test fun `weeks before first use are never due`() {
        val wednesday = LocalDate.of(2026, 11, 4)
        val thisWeek = weekStart(wednesday)
        assertNull(reviewWeekDue(wednesday, emptySet(), firstWeek = thisWeek))
        assertEquals(thisWeek.minusWeeks(1), reviewWeekDue(wednesday, emptySet(), firstWeek = thisWeek.minusWeeks(1)))
        val sunday = LocalDate.of(2026, 11, 8)
        assertEquals(weekStart(sunday), reviewWeekDue(sunday, emptySet(), firstWeek = weekStart(sunday)))
    }

    @Test fun `days done uses a rolling window and survives a gap`() {
        val today = LocalDate.of(2026, 3, 31)
        val days = setOf(today, today.minusDays(5), today.minusDays(29), today.minusDays(30), today.plusDays(1))
        assertEquals(3, daysDoneInLast30(days, today))
    }

    @Test fun `health lists only what is broken`() {
        assertTrue(healthProblems(HealthInput(true, true, true, true)).isEmpty())
        val problems = healthProblems(HealthInput(false, false, false, false))
        assertEquals(4, problems.size)
        assertTrue(problems.any { "force-stop" in it })
    }
}
