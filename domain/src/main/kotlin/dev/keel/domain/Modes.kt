package dev.keel.domain

import java.time.ZoneId

/** Ordered from least to most strict, which is what decides whether a mode change is loosening. */
enum class RuleMode(val label: String) {
    LIMIT("Time limit"), PAUSE("Pause"), TASK_LOCK("Task-locked")
}

const val EMERGENCY_DELAY_SECONDS = 60
const val LIMIT_REACHED_DELAY_SECONDS = 30

sealed interface EntryDecision {
    /** Let the app open. If [recheckAfterMillis] is set, decide again after that long. */
    data class Allow(val recheckAfterMillis: Long? = null) : EntryDecision

    data class Pause(val delaySeconds: Int, val headline: String? = null) : EntryDecision
}

/**
 * What's known when an app or surface opens. [usedMillisToday] is null when usage can't be read, and
 * [sprintUntil] is null when no Sprint is running.
 */
data class EntryContext(
    val now: Long,
    val sprintUntil: Long?,
    val usedMillisToday: Long?,
    val todayTasksLeft: Int,
    val todayTasksDone: Int,
)

/**
 * Decides what happens when something opens. Every pause keeps a way out after its countdown, because
 * people mostly abandon total blocks, and the way out is what the evidence favours.
 */
fun decideEntry(setting: RuleSetting, context: EntryContext): EntryDecision {
    val sprintUntil = context.sprintUntil
    if (sprintUntil != null && context.now < sprintUntil) {
        val days = coolingDaysLeft(sprintUntil, context.now)
        return EntryDecision.Pause(
            maxOf(setting.delaySeconds, EMERGENCY_DELAY_SECONDS),
            "Sprint: ${if (days == 1L) "1 day" else "$days days"} left.",
        )
    }
    return when (setting.mode) {
        RuleMode.PAUSE -> EntryDecision.Pause(setting.delaySeconds)
        RuleMode.LIMIT -> {
            val used = context.usedMillisToday
                // Without usage data the limit can't be measured, so fail towards pausing.
                ?: return EntryDecision.Pause(setting.delaySeconds, "Keel can't read today's usage.")
            val left = setting.dailyLimitMinutes * 60_000L - used
            if (left > 0) {
                EntryDecision.Allow(recheckAfterMillis = left)
            } else {
                EntryDecision.Pause(
                    maxOf(setting.delaySeconds, LIMIT_REACHED_DELAY_SECONDS),
                    "Today's ${setting.dailyLimitMinutes} minutes are used.",
                )
            }
        }
        RuleMode.TASK_LOCK -> when {
            context.todayTasksLeft > 0 -> {
                val left = context.todayTasksLeft
                EntryDecision.Pause(
                    EMERGENCY_DELAY_SECONDS,
                    "Locked until today's list is done: $left ${if (left == 1) "task" else "tasks"} left.",
                )
            }
            context.todayTasksDone > 0 -> EntryDecision.Allow()
            // An empty list mustn't count as finished, or locking could be avoided by planning nothing.
            else -> EntryDecision.Pause(setting.delaySeconds, "Nothing is planned for today, so this isn't unlocked yet.")
        }
    }
}

/** Time spent in [pkg] from the given events, with an unfinished session counted up to [now]. */
fun foregroundMillis(events: List<UsageEvent>, pkg: String, now: Long): Long {
    val result = buildSessions(events)
    val finished = result.sessions.filter { it.pkg == pkg }.sumOf { it.end - it.start }
    val since = result.openSince
    return finished + if (result.openPkg == pkg && since != null) maxOf(0, now - since) else 0
}

const val MAX_SPRINT_DAYS = 21

/** A new Sprint can extend a running one, but never shorten it. */
fun startSprint(currentUntil: Long?, days: Int, now: Long): Long {
    require(days in 1..MAX_SPRINT_DAYS) { "A Sprint lasts 1 to $MAX_SPRINT_DAYS days" }
    val requested = now + days * 24 * 60 * 60 * 1000L
    return if (currentUntil != null && currentUntil > requested) currentUntil else requested
}

/** Ending a Sprint is a loosening, so it waits until next Monday, or its own end if that's sooner. */
fun endSprintEarly(until: Long, now: Long, zone: ZoneId): Long = minOf(until, nextWeekStart(now, zone))
