package dev.keel.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

enum class Horizon(val label: String) {
    LIFE("Life"), YEAR("Year"), QUARTER("Quarter"), WEEK("Week");

    /** Long-term ambitions wait in the cooling-off lane. Shorter plans start straight away. */
    val coolsOff get() = this == LIFE || this == YEAR
}

enum class GoalStatus { COOLING, ACTIVE, DONE, KILLED }

data class Goal(
    val id: Long,
    val title: String,
    val horizon: Horizon,
    val status: GoalStatus,
    val wish: String = "",
    val outcome: String = "",
    val obstacle: String = "",
    val plan: String = "",
    val createdAt: Long = 0,
    val coolingUntil: Long? = null,
    val endedReason: String? = null,
)

data class PlanStep(
    val id: Long,
    val goalId: Long,
    val title: String,
    val estimateMinutes: Int? = null,
    val ifThen: String? = null,
    val position: Int = 0,
    val doneAt: Long? = null,
    val source: String? = null,
)

data class Task(
    val id: Long,
    val title: String,
    val day: LocalDate,
    val stepId: Long? = null,
    val source: String? = null,
    val estimateMinutes: Int? = null,
    val doneAt: Long? = null,
    val createdAt: Long = 0,
)

const val COOLING_DAYS = 30L

fun coolingUntil(horizon: Horizon, now: Long): Long? =
    if (horizon.coolsOff) now + Duration.ofDays(COOLING_DAYS).toMillis() else null

/** Whole days left, rounded up, so a goal with any time remaining never shows 0. */
fun coolingDaysLeft(until: Long, now: Long): Long {
    if (until <= now) return 0
    val day = Duration.ofDays(1).toMillis()
    return (until - now + day - 1) / day
}

enum class GoalSort(val label: String) {
    MONTHS_RETURNED("Months returned"), LAST_TOUCHED("Last touched"), NEWEST("Newest")
}

data class RankedGoal(val goal: Goal, val monthsReturned: Int, val lastTouch: Long?)

/** The default order rewards what you keep coming back to, not what feels urgent. */
fun rankGoals(
    goals: List<Goal>,
    touches: Map<Long, List<Long>>,
    zone: ZoneId,
    sort: GoalSort = GoalSort.MONTHS_RETURNED,
): List<RankedGoal> {
    val ranked = goals.map { goal ->
        val times = touches[goal.id].orEmpty()
        RankedGoal(
            goal,
            times.map { YearMonth.from(Instant.ofEpochMilli(it).atZone(zone)) }.toSet().size,
            times.maxOrNull(),
        )
    }
    return when (sort) {
        GoalSort.MONTHS_RETURNED -> ranked.sortedWith(
            compareByDescending<RankedGoal> { it.monthsReturned }
                .thenByDescending { it.lastTouch ?: 0 }
                .thenBy { it.goal.id },
        )
        GoalSort.LAST_TOUCHED -> ranked.sortedWith(
            compareByDescending<RankedGoal> { it.lastTouch ?: 0 }.thenBy { it.goal.id },
        )
        GoalSort.NEWEST -> ranked.sortedWith(
            compareByDescending<RankedGoal> { it.goal.createdAt }.thenBy { it.goal.id },
        )
    }
}

const val STALLED_AFTER_DAYS = 14L

/** A plan's real state, for example "3 of 8 steps, stalled 21 days", rather than its ambitious title. */
fun realState(steps: List<PlanStep>, lastTouch: Long?, now: Long): String {
    val parts = mutableListOf<String>()
    parts += if (steps.isEmpty()) "No steps yet" else "${steps.count { it.doneAt != null }} of ${steps.size} steps"
    if (lastTouch != null) {
        val idle = Duration.ofMillis(now - lastTouch).toDays()
        if (idle >= STALLED_AFTER_DAYS) parts += "stalled $idle days"
    }
    return parts.joinToString(", ")
}

data class PauseSuggestion(
    val title: String,
    val serves: String?,
    val estimateMinutes: Int?,
    val stepId: Long?,
    val taskId: Long?,
)

/** Today's unfinished tasks come first. Otherwise, the next step of the highest-ranked goal. */
fun pickNext(
    todayTasks: List<Task>,
    rankedActive: List<RankedGoal>,
    steps: List<PlanStep>,
): PauseSuggestion? {
    val goalsById = rankedActive.associateBy { it.goal.id }
    val stepsById = steps.associateBy { it.id }
    todayTasks.filter { it.doneAt == null }.minByOrNull { it.createdAt }?.let { task ->
        val goal = task.stepId?.let { stepsById[it] }?.let { goalsById[it.goalId] }?.goal
        return PauseSuggestion(task.title, goal?.title, task.estimateMinutes, task.stepId, task.id)
    }
    for (ranked in rankedActive) {
        val step = steps.filter { it.goalId == ranked.goal.id && it.doneAt == null }
            .minByOrNull { it.position } ?: continue
        return PauseSuggestion(step.title, ranked.goal.title, step.estimateMinutes, step.id, null)
    }
    return null
}

sealed interface DayState {
    data object Empty : DayState
    data class InProgress(val left: Int, val done: Int) : DayState
    data class Finished(val done: Int) : DayState
}

/** A day has an end: once the last item is done, nothing more is suggested. */
fun dayState(tasks: List<Task>): DayState {
    if (tasks.isEmpty()) return DayState.Empty
    val left = tasks.count { it.doneAt == null }
    val done = tasks.size - left
    return if (left == 0) DayState.Finished(done) else DayState.InProgress(left, done)
}
