package dev.keel.app.pause

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import dev.keel.app.KeelApp
import dev.keel.app.ui.MainActivity
import dev.keel.domain.Choice
import dev.keel.domain.EntryContext
import dev.keel.domain.EntryDecision
import dev.keel.domain.PauseSuggestion
import dev.keel.domain.RuleMode
import dev.keel.domain.RuleSetting
import dev.keel.domain.UsageEvent
import dev.keel.domain.decideEntry
import dev.keel.domain.foregroundMillis
import java.time.LocalDate
import java.time.ZoneId

/**
 * Decides when the pause screen shows. Shared by the Accessibility and fallback triggers.
 * [currentTarget] reports what's in front right now. While the overlay is up it's rechecked every
 * second, because Android once delivered no launcher event after Home and left it stuck on screen.
 */
class PauseController(
    private val context: Context,
    private val windowType: Int,
    private val source: String,
    private val leave: (back: Boolean) -> Unit,
    private val isStillInFront: (target: String) -> Boolean,
) {
    private val repo get() = (context.applicationContext as KeelApp).repo
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: PauseOverlay? = null
    private var overlayTarget: String? = null
    private var overlaySuggestion: PauseSuggestion? = null
    private val allowedUntil = mutableMapOf<String, Long>()
    private var limitTarget: String? = null
    private var limitCheckAt = 0L

    private val recheck = object : Runnable {
        override fun run() {
            val target = overlayTarget ?: return
            if (isStillInFront(target)) {
                handler.postDelayed(this, RECHECK_MS)
            } else {
                hide(Choice.LEFT)
            }
        }
    }

    /** Whole-app rules. Called when an app is confirmed in front. */
    fun onForeground(pkg: String) {
        val target = overlayTarget
        if (target != null && target == pkg) return
        if (target != null && !target.startsWith(SURFACE_PREFIX)) hide(Choice.LEFT)
        repo.activeRule(pkg)?.let { enter(pkg, appLabel(pkg), it, isSurface = false) }
    }

    /** Surface rules, such as Shorts. Called with whether the rule currently matches. */
    fun onSurface(ruleId: String, label: String, visible: Boolean) {
        if (visible) {
            repo.activeRule(ruleId)?.let { enter(ruleId, label, it, isSurface = true) }
        } else if (overlayTarget == ruleId) {
            hide(Choice.LEFT)
        }
    }

    /** Applies the rule's mode, then either lets the app open or shows the pause screen. */
    private fun enter(target: String, label: String, setting: RuleSetting, isSurface: Boolean) {
        if (overlay != null) return
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed < (allowedUntil[target] ?: 0L)) return
        // Window events are frequent, so a running time limit isn't re-measured until it could be up.
        if (limitTarget == target && nowElapsed < limitCheckAt) return
        // Time per surface can't be measured, so surface rules are never time limits.
        val rule = if (isSurface && setting.mode == RuleMode.LIMIT) setting.copy(mode = RuleMode.PAUSE) else setting
        val (left, done) = repo.todayTaskCounts()
        val decision = decideEntry(
            rule,
            EntryContext(
                now = System.currentTimeMillis(),
                sprintUntil = repo.sprintUntil(),
                usedMillisToday = if (rule.mode == RuleMode.LIMIT) usedToday(target) else 0,
                todayTasksLeft = left,
                todayTasksDone = done,
            ),
        )
        when (decision) {
            is EntryDecision.Allow -> decision.recheckAfterMillis?.let { scheduleLimitCheck(target, it) }
            is EntryDecision.Pause -> show(target, label, decision.delaySeconds, decision.headline, isSurface)
        }
    }

    /** Pauses the app when its allowance runs out, if it's still in front by then. */
    private fun scheduleLimitCheck(target: String, afterMillis: Long) {
        handler.removeCallbacks(limitCheck)
        limitTarget = target
        limitCheckAt = SystemClock.elapsedRealtime() + afterMillis
        handler.postDelayed(limitCheck, afterMillis)
    }

    private val limitCheck = Runnable {
        val target = limitTarget ?: return@Runnable
        limitTarget = null
        if (overlay == null && isStillInFront(target)) onForeground(target)
    }

    private fun usedToday(pkg: String): Long? {
        if (!Permissions.usage(context)) return null
        val now = System.currentTimeMillis()
        val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(midnight, now)
        val event = UsageEvents.Event()
        val collected = mutableListOf<UsageEvent>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> collected += UsageEvent(event.timeStamp, event.packageName, true)
                UsageEvents.Event.ACTIVITY_PAUSED -> collected += UsageEvent(event.timeStamp, event.packageName, false)
            }
        }
        return foregroundMillis(collected, pkg, now)
    }

    private fun show(target: String, label: String, delaySeconds: Int, headline: String?, isSurface: Boolean) {
        val suggestion = runCatching { repo.suggestion() }.getOrNull()
        val view = PauseOverlay(context, windowType, delaySeconds, label, headline, suggestion) { choice, reason ->
            when (choice) {
                Choice.OPEN -> allow(target)
                Choice.DO_IT_NOW -> {
                    // Put the step on today's list, so "Do it now" never lands on an empty screen.
                    if (suggestion?.taskId == null && suggestion != null) {
                        runCatching {
                            repo.addTask(
                                suggestion.title,
                                LocalDate.now(),
                                suggestion.stepId,
                                "You chose this instead of opening $label",
                                suggestion.estimateMinutes,
                            )
                        }
                    }
                    // Keel opens on top. Pressing Back here as well could race and close Keel instead.
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_TODAY),
                    )
                }
                else -> leave(isSurface)
            }
            hide(choice, reason)
        }
        if (!view.show()) {
            // The overlay permission was revoked. The health check reports it, so don't crash.
            return
        }
        overlay = view
        overlayTarget = target
        overlaySuggestion = suggestion
        handler.postDelayed(recheck, RECHECK_MS)
    }

    private fun allow(target: String) {
        val until = SystemClock.elapsedRealtime() + ALLOW_MS
        allowedUntil[target] = until
        // When the time is up, pause again if the app is still in front.
        handler.postDelayed(
            {
                if (overlay == null && isStillInFront(target)) {
                    if (target.startsWith(SURFACE_PREFIX)) {
                        onExpired?.invoke(target)
                    } else {
                        onForeground(target)
                    }
                }
            },
            ALLOW_MS + 100,
        )
    }

    /** Lets the service re-run its surface scan when a surface allowance ends. */
    var onExpired: ((String) -> Unit)? = null

    private fun hide(choice: Choice, reason: String? = null) {
        val target = overlayTarget ?: return
        runCatching { repo.logIntercept(target, choice, reason, overlaySuggestion, source) }
        handler.removeCallbacks(recheck)
        // Pressing Home can send a video into a picture-in-picture window, which would carry on
        // playing past the pause. Keel can't close that window, but it can pause the playback.
        if (choice != Choice.OPEN) pausePlayback()
        overlay?.hide()
        overlay = null
        overlayTarget = null
        overlaySuggestion = null
    }

    private fun pausePlayback() {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        overlay?.hide()
        overlay = null
        overlayTarget = null
    }

    private fun appLabel(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    companion object {
        const val SURFACE_PREFIX = "surface:"
        private const val RECHECK_MS = 1000L
        private const val ALLOW_MS = 5 * 60_000L
    }
}
