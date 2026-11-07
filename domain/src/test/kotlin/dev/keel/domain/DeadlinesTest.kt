package dev.keel.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DeadlinesTest {
    private val today = LocalDate.of(2026, 11, 20)

    @Test fun `reads the common ways of writing a date`() {
        assertEquals(LocalDate.of(2026, 11, 30), findDeadline("Offer check, by 30 Nov 2026: read it"))
        assertEquals(LocalDate.of(2026, 12, 31), findDeadline("By 31 December 2026: get an answer"))
        assertEquals(LocalDate.of(2027, 1, 5), findDeadline("Pay fees January 5, 2027"))
        assertEquals(LocalDate.of(2027, 1, 5), findDeadline("Pay fees jan 5 2027"))
    }

    @Test fun `a month on its own only counts as a deadline with a deadline word`() {
        assertEquals(LocalDate.of(2027, 5, 31), findDeadline("Stage 1: by May 2027"))
        assertEquals(LocalDate.of(2028, 2, 29), findDeadline("due February 2028"))
        assertNull(findDeadline("Since December 2025 the rules changed"))
    }

    @Test fun `a marked date beats an earlier passing mention`() {
        assertEquals(
            LocalDate.of(2026, 12, 1),
            findDeadline("Rules changed on 6 November 2026, so reply by 1 Dec 2026"),
        )
    }

    @Test fun `text without a usable date has no deadline`() {
        assertNull(findDeadline("Read one annual report"))
        assertNull(findDeadline("mentee session 28 January"))
        assertNull(findDeadline("Top 3 of 2026"))
        assertNull(findDeadline("31 Feb 2027"))
    }

    private fun step(id: Long, title: String, source: String? = null, doneAt: Long? = null, goal: Long = 1, position: Int = 0) =
        PlanStep(id, goal, title, source = source, doneAt = doneAt, position = position)

    @Test fun `lists overdue and soon items, soonest first`() {
        val deadlines = upcomingDeadlines(
            mapOf(1L to "Plan"),
            listOf(
                step(1, "Later by 30 Nov 2026"),
                step(2, "Missed by 10 Nov 2026"),
                step(3, "Today by 20 Nov 2026"),
                step(4, "Far by 30 Dec 2026"),
                step(5, "No date"),
            ),
            emptySet(),
            today,
        )
        assertEquals(listOf(2L, 3L, 1L), deadlines.map { it.step.id })
        assertEquals(-10L, deadlines[0].daysLeft)
        assertEquals("Plan", deadlines[0].planTitle)
    }

    @Test fun `the window edge is included`() {
        val edge = step(1, "by 4 Dec 2026")
        val past = step(2, "by 5 Dec 2026")
        assertEquals(listOf(1L), upcomingDeadlines(mapOf(1L to "P"), listOf(edge, past), emptySet(), today, windowDays = 14).map { it.step.id })
    }

    @Test fun `a step can inherit its section's date, and its own wins`() {
        val deadlines = upcomingDeadlines(
            mapOf(1L to "P"),
            listOf(
                step(1, "Plain step", source = "Do by 30 November 2026"),
                step(2, "Own date by 21 Nov 2026", source = "Do by 30 November 2026"),
            ),
            emptySet(),
            today,
        )
        assertEquals(listOf(2L, 1L), deadlines.map { it.step.id })
    }

    @Test fun `done, listed and ended-plan steps stay out`() {
        val steps = listOf(
            step(1, "by 25 Nov 2026", doneAt = 5),
            step(2, "by 25 Nov 2026"),
            step(3, "by 25 Nov 2026", goal = 9),
        )
        assertTrue(upcomingDeadlines(mapOf(1L to "P"), steps, setOf(2L), today).isEmpty())
    }

    @Test fun `labels read naturally`() {
        assertEquals("3 days overdue", deadlineLabel(-3))
        assertEquals("1 day overdue", deadlineLabel(-1))
        assertEquals("Due today", deadlineLabel(0))
        assertEquals("Due tomorrow", deadlineLabel(1))
        assertEquals("Due in 9 days", deadlineLabel(9))
    }
}
