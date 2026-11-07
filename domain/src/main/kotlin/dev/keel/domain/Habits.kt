package dev.keel.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class HabitUnit(val singular: String, val plural: String) {
    MONTHS("month", "months"), YEARS("year", "years");

    fun label(amount: Int) = "$amount ${if (amount == 1) singular else plural}"
}

data class Habit(
    val id: Long,
    val title: String,
    val amount: Int,
    val unit: HabitUnit,
    val createdAt: Long = 0,
)

/** How many days in a row a habit needs when its run starts on [start]. Month lengths vary, so it depends on the date. */
fun daysNeeded(habit: Habit, start: LocalDate): Long {
    val end = when (habit.unit) {
        HabitUnit.MONTHS -> start.plusMonths(habit.amount.toLong())
        HabitUnit.YEARS -> start.plusYears(habit.amount.toLong())
    }
    return ChronoUnit.DAYS.between(start, end)
}

sealed interface HabitState {
    val best: Long

    /** Never ticked yet. */
    data class NotStarted(val needed: Long) : HabitState {
        override val best = 0L
    }

    /** Still going. [doneToday] false means today's tick is still open, and the streak survives until midnight. */
    data class Going(
        val streak: Long,
        val needed: Long,
        val doneToday: Boolean,
        val finishesOn: LocalDate,
        override val best: Long,
    ) : HabitState

    /** A whole day was missed, so the streak is 0 and the full time starts again from the next tick. */
    data class Broken(val needed: Long, override val best: Long) : HabitState

    data class Finished(val on: LocalDate, override val best: Long) : HabitState
}

/**
 * A strict streak: every day must be ticked, and one missed day sends it back to 0. Finishing takes one
 * unbroken run as long as the time you chose, and once that's happened it stays finished.
 */
fun habitState(habit: Habit, days: Set<LocalDate>, today: LocalDate): HabitState {
    val ticked = days.filter { !it.isAfter(today) }.sorted()
    if (ticked.isEmpty()) return HabitState.NotStarted(daysNeeded(habit, today))

    val runs = mutableListOf<Pair<LocalDate, LocalDate>>()
    for (day in ticked) {
        val last = runs.lastOrNull()
        if (last != null && last.second.plusDays(1) == day) {
            runs[runs.lastIndex] = last.first to day
        } else {
            runs += day to day
        }
    }
    fun length(run: Pair<LocalDate, LocalDate>) = ChronoUnit.DAYS.between(run.first, run.second) + 1
    val best = runs.maxOf(::length)

    runs.firstOrNull { length(it) >= daysNeeded(habit, it.first) }?.let { run ->
        return HabitState.Finished(run.first.plusDays(daysNeeded(habit, run.first) - 1), best)
    }

    val current = runs.last()
    if (current.second.isBefore(today.minusDays(1))) return HabitState.Broken(daysNeeded(habit, today), best)
    val needed = daysNeeded(habit, current.first)
    return HabitState.Going(
        streak = length(current),
        needed = needed,
        doneToday = current.second == today,
        finishesOn = current.first.plusDays(needed - 1),
        best = best,
    )
}
