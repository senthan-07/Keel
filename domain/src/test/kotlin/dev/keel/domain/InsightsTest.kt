package dev.keel.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class InsightsTest {
    private val monday = LocalDate.of(2026, 11, 9)
    private fun goal(id: Long) = Goal(id, "Plan $id", Horizon.YEAR, GoalStatus.ACTIVE)
    private fun ranked(id: Long) = RankedGoal(goal(id), 1, null)

    @Test fun `plan picks are short, ranked, and skip finished or listed steps`() {
        val steps = listOf(
            PlanStep(1, 1, "done", position = 0, doneAt = 5),
            PlanStep(2, 1, "listed", position = 1),
            PlanStep(3, 1, "third", position = 3),
            PlanStep(4, 1, "second", position = 2),
            PlanStep(5, 1, "fourth", position = 4),
            PlanStep(6, 2, "other", position = 0),
        )
        val picks = nextPlanSteps(listOf(ranked(1), ranked(2)), steps, setOf(2L))
        assertEquals(listOf("second", "third", "other"), picks.map { it.step.title })
        assertEquals("Plan 2", picks.last().planTitle)
    }

    @Test fun `plan picks never flood Today`() {
        val goals = (1L..10L).map { ranked(it) }
        val steps = (1L..10L).flatMap { g -> (0..4).map { PlanStep(g * 10 + it, g, "s$it", position = it) } }
        assertEquals(6, nextPlanSteps(goals, steps, emptySet()).size)
    }

    @Test fun `plans outside the ranked list aren't suggested`() {
        val steps = listOf(PlanStep(1, 9, "library step"))
        assertTrue(nextPlanSteps(listOf(ranked(1)), steps, emptySet()).isEmpty())
    }

    @Test fun `check-in summary averages the latest weeks`() {
        val checks = (0L..5L).map { WeeklyCheck(monday.plusWeeks(it), 1 + it.toInt() % 5, 3, 4) }
        val summary = checkInSummary(checks, lastWeeks = 2)!!
        assertEquals(2, summary.weeks)
        assertEquals((5 + 1) / 2.0, summary.sleep, 0.001)
        assertNull(checkInSummary(emptyList()))
    }

    @Test fun `envelopes measure apps, and ones without apps stay unmeasured`() {
        val states = envelopeStates(
            listOf(
                TimeEnvelope(1, "Social", 60, listOf("a", "b")),
                TimeEnvelope(2, "Study", 600, emptyList()),
                TimeEnvelope(3, "Quiet", 60, listOf("c")),
            ),
            mapOf("a" to 50L, "b" to 40L),
        )
        assertEquals(90L, states[0].usedMinutes)
        assertEquals(30L, states[0].overMinutes)
        assertNull(states[1].usedMinutes)
        assertEquals(0L, states[1].overMinutes)
        assertEquals(0L, states[2].usedMinutes)
    }

    @Test fun `unassigned hours can go negative when overplanned`() {
        assertEquals(WAKING_MINUTES_PER_WEEK - 600, unassignedMinutes(listOf(TimeEnvelope(1, "x", 600, emptyList()))))
        assertTrue(unassignedMinutes(listOf(TimeEnvelope(1, "x", WAKING_MINUTES_PER_WEEK + 1, emptyList()))) < 0)
    }

    @Test fun `exchange counts only full blocks over budget`() {
        fun state(over: Long, per: Int? = 30, amount: Long? = 100) =
            EnvelopeState(TimeEnvelope(1, "x", 60, listOf("a"), per, amount), 60 + over, over)
        assertEquals(0L, exchangeOwed(state(29)))
        assertEquals(100L, exchangeOwed(state(30)))
        assertEquals(200L, exchangeOwed(state(75)))
        assertEquals(0L, exchangeOwed(state(75, per = null)))
        assertEquals(0L, exchangeOwed(state(75, amount = null)))
        assertEquals(0L, exchangeOwed(state(75, per = 0)))
    }

    @Test fun `experiment weeks alternate from the start, beginning with friction on`() {
        assertFalse(experimentWeekIsOff(null, monday))
        assertFalse(experimentWeekIsOff(monday, monday.minusDays(1)))
        assertFalse(experimentWeekIsOff(monday, monday))
        assertFalse(experimentWeekIsOff(monday, monday.plusDays(6)))
        assertTrue(experimentWeekIsOff(monday, monday.plusDays(7)))
        assertTrue(experimentWeekIsOff(monday, monday.plusDays(13)))
        assertFalse(experimentWeekIsOff(monday, monday.plusDays(14)))
    }

    @Test fun `friction-off weeks let pause rules through, and nothing else`() {
        val context = EntryContext(1_000, null, 0, 1, 0, frictionOffWeek = true)
        assertEquals(EntryDecision.Allow(), decideEntry(RuleSetting(true, 10), context))
        assertTrue(decideEntry(RuleSetting(true, 10, RuleMode.TASK_LOCK), context) is EntryDecision.Pause)
        val limit = decideEntry(RuleSetting(true, 10, RuleMode.LIMIT), context.copy(usedMillisToday = 99 * 60_000L))
        assertTrue(limit is EntryDecision.Pause)
        assertTrue(decideEntry(RuleSetting(true, 10), context.copy(sprintUntil = 9_000)) is EntryDecision.Pause)
    }

    private fun weeks(vararg minutes: Long) = minutes.mapIndexed { i, m -> ExperimentWeek(monday.plusWeeks(i.toLong()), m) }

    @Test fun `the experiment waits for enough weeks`() {
        assertEquals(ExperimentReport.NotEnough(2, 1), experimentReport(monday, weeks(100, 200, 100)))
    }

    @Test fun `a clear result needs the groups not to overlap`() {
        val report = experimentReport(monday, weeks(100, 200, 110, 210)) as ExperimentReport.Result
        assertTrue(report.clear)
        assertEquals(105L, report.onAverage)
        assertEquals(205L, report.offAverage)
    }

    @Test fun `overlapping or backwards weeks report no clear effect`() {
        assertFalse((experimentReport(monday, weeks(100, 200, 300, 210)) as ExperimentReport.Result).clear)
        assertFalse((experimentReport(monday, weeks(200, 100, 210, 110)) as ExperimentReport.Result).clear)
    }

    @Test fun `weeks before the start don't count`() {
        val early = ExperimentWeek(monday.minusWeeks(1), 5)
        assertEquals(ExperimentReport.NotEnough(0, 0), experimentReport(monday, listOf(early)))
    }

    @Test fun `a backup goes stale after a week`() {
        val day = 24 * 60 * 60 * 1000L
        assertTrue(backupIsStale(null, 0))
        assertFalse(backupIsStale(0, 7 * day))
        assertTrue(backupIsStale(0, 7 * day + 1))
    }
}
