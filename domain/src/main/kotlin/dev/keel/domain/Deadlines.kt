package dev.keel.domain

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

private const val MONTHS = "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec"
private val DAY_MONTH_YEAR = Regex("""\b(\d{1,2})\s+($MONTHS)[a-z]*\.?,?\s+(\d{4})\b""", RegexOption.IGNORE_CASE)
private val MONTH_DAY_YEAR = Regex("""\b($MONTHS)[a-z]*\.?\s+(\d{1,2}),?\s+(\d{4})\b""", RegexOption.IGNORE_CASE)
private val BY_MONTH_YEAR = Regex("""\b(?:by|before|due)\s+($MONTHS)[a-z]*\.?,?\s+(\d{4})\b""", RegexOption.IGNORE_CASE)
private val DEADLINE_WORD = Regex("""\b(?:by|before|due)\s+$""", RegexOption.IGNORE_CASE)

private fun month(name: String): Int = MONTHS.split('|').indexOf(name.take(3).lowercase()) + 1

private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? =
    runCatching { LocalDate.of(year, month, day) }.getOrNull()

/**
 * Finds a deadline in plain text, like "by 30 Nov 2026", "31 December 2026" or "by May 2027" (the end of
 * that month). When a line has several dates, one marked with "by", "before" or "due" wins over the rest.
 */
fun findDeadline(text: String): LocalDate? {
    val dated = (
        DAY_MONTH_YEAR.findAll(text).mapNotNull { match ->
            val (day, name, year) = match.destructured
            dateOrNull(year.toInt(), month(name), day.toInt())?.let { match.range.first to it }
        } + MONTH_DAY_YEAR.findAll(text).mapNotNull { match ->
            val (name, day, year) = match.destructured
            dateOrNull(year.toInt(), month(name), day.toInt())?.let { match.range.first to it }
        }
        ).sortedBy { it.first }.toList()
    dated.firstOrNull { DEADLINE_WORD.containsMatchIn(text.substring(0, it.first)) }?.let { return it.second }
    BY_MONTH_YEAR.find(text)?.let { match ->
        val (name, year) = match.destructured
        return YearMonth.of(year.toInt(), month(name)).atEndOfMonth()
    }
    return dated.firstOrNull()?.second
}

data class Deadline(
    val planTitle: String,
    val step: PlanStep,
    val due: LocalDate,
    val daysLeft: Long,
)

/** How a deadline reads next to its step. */
fun deadlineLabel(daysLeft: Long): String = when {
    daysLeft < -1 -> "${-daysLeft} days overdue"
    daysLeft == -1L -> "1 day overdue"
    daysLeft == 0L -> "Due today"
    daysLeft == 1L -> "Due tomorrow"
    else -> "Due in $daysLeft days"
}

const val DEADLINE_WINDOW_DAYS = 30L

/**
 * Unfinished steps that are overdue or due soon, soonest first. A step's own line decides its date, and
 * otherwise the section it sits under does. Steps already on a list are left out, because they're in view.
 */
fun upcomingDeadlines(
    plans: Map<Long, String>,
    steps: List<PlanStep>,
    stepsOnAList: Set<Long>,
    today: LocalDate,
    windowDays: Long = DEADLINE_WINDOW_DAYS,
): List<Deadline> = steps.asSequence()
    .filter { it.doneAt == null && it.goalId in plans && it.id !in stepsOnAList }
    .mapNotNull { step ->
        val due = findDeadline(step.title) ?: step.source?.let(::findDeadline) ?: return@mapNotNull null
        val daysLeft = ChronoUnit.DAYS.between(today, due)
        if (daysLeft > windowDays) return@mapNotNull null
        Deadline(plans.getValue(step.goalId), step, due, daysLeft)
    }
    .sortedWith(compareBy({ it.daysLeft }, { it.step.position }))
    .toList()
