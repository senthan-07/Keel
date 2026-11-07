package dev.keel.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ModesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private fun context(
        sprintUntil: Long? = null,
        used: Long? = 0,
        left: Int = 0,
        done: Int = 0,
    ) = EntryContext(1_000_000, sprintUntil, used, left, done)
    private val pause = RuleSetting(true, 10)
    private val limit = RuleSetting(true, 10, RuleMode.LIMIT, 30)
    private val locked = RuleSetting(true, 10, RuleMode.TASK_LOCK)

    @Test fun `pause mode always pauses with its own delay`() {
        assertEquals(EntryDecision.Pause(10), decideEntry(pause, context()))
    }

    @Test fun `a limit allows use and rechecks when the time runs out`() {
        assertEquals(EntryDecision.Allow(20 * minute), decideEntry(limit, context(used = 10 * minute)))
    }

    @Test fun `a used-up limit pauses for at least 30 seconds`() {
        val decision = decideEntry(limit, context(used = 30 * minute)) as EntryDecision.Pause
        assertEquals(LIMIT_REACHED_DELAY_SECONDS, decision.delaySeconds)
        assertTrue("30 minutes" in decision.headline!!)
        val strict = decideEntry(limit.copy(delaySeconds = 60), context(used = 31 * minute)) as EntryDecision.Pause
        assertEquals(60, strict.delaySeconds)
    }

    @Test fun `a limit that can't be measured pauses rather than allowing everything`() {
        assertTrue(decideEntry(limit, context(used = null)) is EntryDecision.Pause)
    }

    @Test fun `task lock holds until today's list is done`() {
        val held = decideEntry(locked, context(left = 2, done = 1)) as EntryDecision.Pause
        assertEquals(EMERGENCY_DELAY_SECONDS, held.delaySeconds)
        assertTrue("2 tasks left" in held.headline!!)
        assertTrue("1 task left" in (decideEntry(locked, context(left = 1)) as EntryDecision.Pause).headline!!)
        assertEquals(EntryDecision.Allow(), decideEntry(locked, context(left = 0, done = 3)))
    }

    @Test fun `an empty list doesn't unlock a task lock`() {
        val decision = decideEntry(locked, context(left = 0, done = 0)) as EntryDecision.Pause
        assertEquals(10, decision.delaySeconds)
        assertTrue("Nothing is planned" in decision.headline!!)
    }

    @Test fun `a sprint overrides every mode, including an unused limit and a finished list`() {
        val until = 1_000_000 + 3 * day
        for (setting in listOf(pause, limit, locked)) {
            val decision = decideEntry(setting, context(sprintUntil = until, done = 5)) as EntryDecision.Pause
            assertEquals(EMERGENCY_DELAY_SECONDS, decision.delaySeconds)
            assertTrue("3 days left" in decision.headline!!)
        }
    }

    @Test fun `a finished sprint stops applying`() {
        assertEquals(EntryDecision.Pause(10), decideEntry(pause, context(sprintUntil = 1_000_000)))
    }

    @Test fun `every paused state keeps a finite way out`() {
        val cases = listOf(
            decideEntry(pause, context()),
            decideEntry(limit, context(used = 99 * minute)),
            decideEntry(locked, context(left = 9)),
            decideEntry(locked, context(sprintUntil = 9_000_000)),
        )
        cases.forEach { assertTrue((it as EntryDecision.Pause).delaySeconds in 1..EMERGENCY_DELAY_SECONDS) }
    }

    @Test fun `mode changes follow strictness`() {
        assertTrue(isLoosening(pause, limit))
        assertTrue(isLoosening(locked, pause))
        assertTrue(isLoosening(locked, limit))
        assertFalse(isLoosening(limit, pause))
        assertFalse(isLoosening(pause, locked))
        assertFalse(isLoosening(limit, locked))
    }

    @Test fun `a bigger allowance loosens and a smaller one tightens`() {
        assertTrue(isLoosening(limit, limit.copy(dailyLimitMinutes = 60)))
        assertFalse(isLoosening(limit, limit.copy(dailyLimitMinutes = 15)))
    }

    @Test fun `loosening a mode waits until Monday`() {
        val wednesday = ZonedDateTime.of(2026, 1, 7, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val out = requestChange(RuleState(locked), pause, wednesday, zone)
        assertEquals(locked, out.current)
        assertEquals(pause, out.pending)
    }

    @Test fun `a new rule can be set up freely, then commitment starts`() {
        val created = ZonedDateTime.of(2026, 1, 7, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(RuleState(limit), requestChange(RuleState(pause), limit, created + 9 * minute, zone, created))
        val later = requestChange(RuleState(pause), limit, created + 10 * minute, zone, created)
        assertEquals(pause, later.current)
        assertEquals(limit, later.pending)
    }

    @Test fun `tightening a mode applies at once`() {
        assertEquals(RuleState(locked), requestChange(RuleState(pause), locked, 0, zone))
    }

    @Test fun `usage counts this app's sessions and the one still open`() {
        val events = listOf(
            UsageEvent(0, "yt", true), UsageEvent(10, "yt", false),
            UsageEvent(20, "other", true), UsageEvent(30, "other", false),
            UsageEvent(40, "yt", true),
        )
        assertEquals(10L + 60L, foregroundMillis(events, "yt", 100))
        assertEquals(10L, foregroundMillis(events, "other", 100))
        assertEquals(0L, foregroundMillis(events, "absent", 100))
    }

    @Test fun `a new sprint extends but never shortens`() {
        assertEquals(10 * day, startSprint(null, 10, 0))
        assertEquals(20 * day, startSprint(20 * day, 10, 0))
        assertEquals(21 * day, startSprint(5 * day, 21, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sprints over 21 days are refused`() {
        startSprint(null, 22, 0)
    }

    @Test fun `ending a sprint early waits for Monday, or its own end if sooner`() {
        val wednesday = ZonedDateTime.of(2026, 1, 7, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val monday = ZonedDateTime.of(2026, 1, 12, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(monday, endSprintEarly(wednesday + 14 * day, wednesday, zone))
        assertEquals(wednesday + day, endSprintEarly(wednesday + day, wednesday, zone))
    }
}
