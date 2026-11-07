package dev.keel.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HabitsTest {
    private val monthly = Habit(1, "Run", 1, HabitUnit.MONTHS)
    private val nov1 = LocalDate.of(2026, 11, 1)

    private fun run(start: LocalDate, count: Int) = (0 until count).map { start.plusDays(it.toLong()) }.toSet()

    @Test fun `the time needed follows the calendar`() {
        assertEquals(30L, daysNeeded(monthly, nov1))
        assertEquals(31L, daysNeeded(monthly, LocalDate.of(2026, 12, 1)))
        assertEquals(181L, daysNeeded(Habit(1, "x", 6, HabitUnit.MONTHS), LocalDate.of(2026, 11, 1)))
        assertEquals(365L, daysNeeded(Habit(1, "x", 1, HabitUnit.YEARS), LocalDate.of(2026, 11, 1)))
        assertEquals(366L, daysNeeded(Habit(1, "x", 1, HabitUnit.YEARS), LocalDate.of(2028, 1, 1)))
    }

    @Test fun `a new habit hasn't started until it's ticked`() {
        assertEquals(HabitState.NotStarted(30), habitState(monthly, emptySet(), nov1))
    }

    @Test fun `today ticked counts towards the streak`() {
        val state = habitState(monthly, run(nov1, 3), nov1.plusDays(2)) as HabitState.Going
        assertEquals(3L, state.streak)
        assertTrue(state.doneToday)
        assertEquals(LocalDate.of(2026, 11, 30), state.finishesOn)
    }

    @Test fun `the streak survives until today is over`() {
        val state = habitState(monthly, run(nov1, 3), nov1.plusDays(3)) as HabitState.Going
        assertEquals(3L, state.streak)
        assertFalse(state.doneToday)
    }

    @Test fun `one missed day breaks it and keeps the best`() {
        val state = habitState(monthly, run(nov1, 5), nov1.plusDays(6))
        assertEquals(HabitState.Broken(daysNeeded(monthly, nov1.plusDays(6)), 5), state)
    }

    @Test fun `ticking after a miss starts again from 1 with a new finish date`() {
        val back = nov1.plusDays(10)
        val state = habitState(monthly, run(nov1, 5) + back, back) as HabitState.Going
        assertEquals(1L, state.streak)
        assertEquals(5L, state.best)
        assertEquals(back.plusDays(daysNeeded(monthly, back) - 1), state.finishesOn)
    }

    @Test fun `a gap doesn't count even with enough ticks in total`() {
        val days = run(nov1, 15) + run(nov1.plusDays(16), 15)
        val today = nov1.plusDays(30)
        assertTrue(habitState(monthly, days, today) is HabitState.Going)
    }

    @Test fun `finishes on the last day of an unbroken run`() {
        val last = LocalDate.of(2026, 11, 30)
        assertTrue(habitState(monthly, run(nov1, 29), last.minusDays(1)) is HabitState.Going)
        assertEquals(HabitState.Finished(last, 30), habitState(monthly, run(nov1, 30), last))
    }

    @Test fun `finished stays finished after you stop`() {
        val state = habitState(monthly, run(nov1, 30), LocalDate.of(2027, 6, 1))
        assertEquals(HabitState.Finished(LocalDate.of(2026, 11, 30), 30), state)
    }

    @Test fun `a tick dated in the future is ignored`() {
        assertEquals(HabitState.NotStarted(30), habitState(monthly, setOf(nov1.plusDays(1)), nov1))
    }

    @Test fun `duration labels read naturally`() {
        assertEquals("1 month", HabitUnit.MONTHS.label(1))
        assertEquals("6 months", HabitUnit.MONTHS.label(6))
        assertEquals("2 years", HabitUnit.YEARS.label(2))
    }
}
