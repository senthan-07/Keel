package dev.keel.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class RuleSetting(
    val enabled: Boolean,
    val delaySeconds: Int,
    val mode: RuleMode = RuleMode.PAUSE,
    val dailyLimitMinutes: Int = 30,
)

const val NEW_RULE_GRACE_MILLIS = 10 * 60_000L

data class RuleState(
    val current: RuleSetting,
    val pending: RuleSetting? = null,
    val pendingAt: Long? = null,
)

fun isLoosening(from: RuleSetting, to: RuleSetting): Boolean = when {
    !from.enabled -> false
    !to.enabled -> true
    to.mode.ordinal != from.mode.ordinal -> to.mode.ordinal < from.mode.ordinal
    else -> to.delaySeconds < from.delaySeconds ||
        (to.mode == RuleMode.LIMIT && to.dailyLimitMinutes > from.dailyLimitMinutes)
}

/** The start of next Monday, local time. */
fun nextWeekStart(now: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        .with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        .atStartOfDay(zone).toInstant().toEpochMilli()

/**
 * Commitment rule: tightening applies at once, loosening waits until next week.
 * Any new request replaces a pending one, so a rule can't be loosened sooner by asking twice.
 * A rule just created has no commitment behind it yet, so it can be set up freely during [createdAt]'s
 * grace period.
 */
fun requestChange(
    state: RuleState,
    requested: RuleSetting,
    now: Long,
    zone: ZoneId,
    createdAt: Long = 0,
): RuleState {
    val live = effective(state, now)
    val settingUp = now - createdAt < NEW_RULE_GRACE_MILLIS
    return if (!settingUp && isLoosening(live.current, requested)) {
        live.copy(pending = requested, pendingAt = nextWeekStart(now, zone))
    } else {
        RuleState(requested)
    }
}

/** Applies a pending change once its date has arrived. */
fun effective(state: RuleState, now: Long): RuleState {
    val pending = state.pending
    val at = state.pendingAt
    return if (pending != null && at != null && now >= at) RuleState(pending) else state
}

/**
 * A window event doesn't prove an app is in front: YouTube fired one about a second after Home,
 * with the launcher focused. The active window alone lags behind a real launch, so the last
 * resumed app is accepted too.
 */
fun isGenuineForeground(eventPackage: String, activeWindow: String?, lastResumed: String?): Boolean =
    eventPackage == activeWindow || eventPackage == lastResumed

enum class Choice(val abandoned: Boolean) {
    DONT_OPEN(true), DO_IT_NOW(true), LEFT(true), OPEN(false)
}

data class InterceptEvent(
    val at: Long,
    val target: String,
    val choice: Choice,
    val reason: String? = null,
)

data class UsageEvent(val at: Long, val pkg: String, val resumed: Boolean)

data class UsageSession(val pkg: String, val start: Long, val end: Long)

data class SessionResult(val sessions: List<UsageSession>, val openPkg: String?, val openSince: Long?)

/** Turns resume and pause events into sessions. A resume closes whatever was still open. */
fun buildSessions(events: List<UsageEvent>, openPkg: String? = null, openSince: Long? = null): SessionResult {
    val sessions = mutableListOf<UsageSession>()
    var pkg = openPkg
    var since = openSince
    for (event in events.sortedBy { it.at }) {
        if (event.resumed) {
            if (pkg == event.pkg) continue
            if (pkg != null && since != null && event.at > since) sessions += UsageSession(pkg, since, event.at)
            pkg = event.pkg
            since = event.at
        } else if (pkg == event.pkg && since != null) {
            if (event.at > since) sessions += UsageSession(event.pkg, since, event.at)
            pkg = null
            since = null
        }
    }
    return SessionResult(sessions, pkg, since)
}

data class WeekStats(
    val intercepted: Int,
    val abandoned: Int,
    val opened: Int,
    val abandonedPercent: Int?,
    val byTarget: Map<String, Int>,
    val reasons: Map<String, Int>,
)

/** The headline number: opens you changed your mind about. */
fun weekStats(events: List<InterceptEvent>): WeekStats {
    val abandoned = events.count { it.choice.abandoned }
    return WeekStats(
        intercepted = events.size,
        abandoned = abandoned,
        opened = events.size - abandoned,
        abandonedPercent = if (events.isEmpty()) null else abandoned * 100 / events.size,
        byTarget = events.groupingBy { it.target }.eachCount(),
        reasons = events.mapNotNull { it.reason }.groupingBy { it }.eachCount(),
    )
}

fun weekStart(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/**
 * The review opens on Sunday. If Sunday was missed, last week's stays open with no penalty.
 * Weeks before [firstWeek], when Keel wasn't in use yet, are never due.
 */
fun reviewWeekDue(today: LocalDate, reviewedWeeks: Set<LocalDate>, firstWeek: LocalDate? = null): LocalDate? {
    val thisWeek = weekStart(today)
    if (today.dayOfWeek == DayOfWeek.SUNDAY && thisWeek !in reviewedWeeks) return thisWeek
    val lastWeek = thisWeek.minusWeeks(1)
    if (firstWeek != null && lastWeek.isBefore(firstWeek)) return null
    return if (lastWeek !in reviewedWeeks) lastWeek else null
}

/** Used instead of a streak, because a single miss doesn't wipe this number out. */
fun daysDoneInLast30(doneDays: Set<LocalDate>, today: LocalDate): Int =
    doneDays.count { !it.isAfter(today) && it.isAfter(today.minusDays(30)) }

data class HealthInput(
    val accessibilityOn: Boolean,
    val overlayOn: Boolean,
    val usageOn: Boolean,
    val anyRuleEnabled: Boolean,
)

/** Plain statements of what can't work right now. An empty list means healthy. */
fun healthProblems(input: HealthInput): List<String> = buildList {
    if (!input.overlayOn) add("The pause screen can't appear: \"Display over other apps\" is off.")
    if (!input.usageOn) add("Keel can't confirm which app is open or record usage: Usage access is off.")
    if (!input.accessibilityOn) {
        add(
            "Accessibility is off, so Shorts-only blocking isn't working and pauses may show slower. " +
                "A force-stop turns this off.",
        )
    }
    if (!input.anyRuleEnabled) add("No apps are being paused: every rule is off.")
}
