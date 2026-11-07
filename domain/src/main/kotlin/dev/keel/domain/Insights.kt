package dev.keel.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class PlanPick(val planTitle: String, val step: PlanStep)

/**
 * The next few steps from your Active plans for Today, in ranked order. Steps already on a list are
 * skipped. It's a short list on purpose, so a day can still end.
 */
fun nextPlanSteps(
    rankedActive: List<RankedGoal>,
    steps: List<PlanStep>,
    stepsOnALlist: Set<Long>,
    perPlan: Int = 2,
    total: Int = 6,
): List<PlanPick> = rankedActive.flatMap { ranked ->
    steps.filter { it.goalId == ranked.goal.id && it.doneAt == null && it.id !in stepsOnALlist }
        .sortedBy { it.position }
        .take(perPlan)
        .map { PlanPick(ranked.goal.title, it) }
}.take(total)

data class WeeklyCheck(val week: LocalDate, val sleep: Int, val exercise: Int, val mood: Int, val note: String = "")

data class CheckInSummary(val weeks: Int, val sleep: Double, val exercise: Double, val mood: Double)

/** Averages the most recent check-ins, without judging them or suggesting anything medical. */
fun checkInSummary(checks: List<WeeklyCheck>, lastWeeks: Int = 4): CheckInSummary? {
    val recent = checks.sortedByDescending { it.week }.take(lastWeeks)
    if (recent.isEmpty()) return null
    return CheckInSummary(
        recent.size,
        recent.map { it.sleep }.average(),
        recent.map { it.exercise }.average(),
        recent.map { it.mood }.average(),
    )
}

const val WAKING_MINUTES_PER_WEEK = 112 * 60

data class TimeEnvelope(
    val id: Long,
    val name: String,
    val minutesPerWeek: Int,
    /** Apps measured against this envelope. With none, it's a plan that Keel can't measure. */
    val apps: List<String>,
    val perMinutes: Int? = null,
    val amount: Long? = null,
    val payTo: String? = null,
)

data class EnvelopeState(val envelope: TimeEnvelope, val usedMinutes: Long?, val overMinutes: Long)

fun envelopeStates(envelopes: List<TimeEnvelope>, usedByPackage: Map<String, Long>): List<EnvelopeState> =
    envelopes.map { envelope ->
        val used = if (envelope.apps.isEmpty()) null else envelope.apps.sumOf { usedByPackage[it] ?: 0L }
        EnvelopeState(envelope, used, ((used ?: 0L) - envelope.minutesPerWeek).coerceAtLeast(0))
    }

/** Waking hours not yet given a job. Negative means more is planned than the week holds. */
fun unassignedMinutes(envelopes: List<TimeEnvelope>): Int =
    WAKING_MINUTES_PER_WEEK - envelopes.sumOf { it.minutesPerWeek }

/** A rule you chose: each full block of minutes over means setting aside the amount. */
fun exchangeOwed(state: EnvelopeState): Long {
    val per = state.envelope.perMinutes ?: return 0
    val amount = state.envelope.amount ?: return 0
    if (per <= 0) return 0
    return state.overMinutes / per * amount
}

/** The experiment runs in whole weeks from a Monday: friction on, off, on, off. */
fun experimentWeekIsOff(start: LocalDate?, today: LocalDate): Boolean {
    if (start == null || today.isBefore(start)) return false
    return ChronoUnit.WEEKS.between(start, today) % 2 == 1L
}

data class ExperimentWeek(val start: LocalDate, val minutes: Long)

sealed interface ExperimentReport {
    data class NotEnough(val onWeeks: Int, val offWeeks: Int) : ExperimentReport

    /** [Clear] is only reported when every friction week beat every other week. */
    data class Result(
        val onAverage: Long,
        val offAverage: Long,
        val clear: Boolean,
        val onWeeks: Int,
        val offWeeks: Int,
    ) : ExperimentReport
}

const val EXPERIMENT_MIN_WEEKS_EACH = 2

/**
 * Compares finished weeks with friction on and off. It's deliberately strict: with so few noisy weeks,
 * a difference only counts when the two groups don't overlap at all. Otherwise it reports no clear
 * effect, which is a useful answer too.
 */
fun experimentReport(start: LocalDate, weeks: List<ExperimentWeek>): ExperimentReport {
    val off = weeks.filter { experimentWeekIsOff(start, it.start) }
    val on = weeks.filter { !it.start.isBefore(start) && !experimentWeekIsOff(start, it.start) }
    if (on.size < EXPERIMENT_MIN_WEEKS_EACH || off.size < EXPERIMENT_MIN_WEEKS_EACH) {
        return ExperimentReport.NotEnough(on.size, off.size)
    }
    val onMinutes = on.map { it.minutes }
    val offMinutes = off.map { it.minutes }
    return ExperimentReport.Result(
        onMinutes.average().toLong(),
        offMinutes.average().toLong(),
        clear = onMinutes.max() < offMinutes.min(),
        onWeeks = on.size,
        offWeeks = off.size,
    )
}

/** Reminds you once a backup is over a week old, because nothing backs up automatically. */
fun backupIsStale(lastBackupAt: Long?, now: Long): Boolean =
    lastBackupAt == null || now - lastBackupAt > 7 * 24 * 60 * 60 * 1000L
