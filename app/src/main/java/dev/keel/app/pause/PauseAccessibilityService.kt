package dev.keel.app.pause

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import dev.keel.domain.isGenuineForeground
import org.json.JSONObject

data class SurfaceRule(
    val id: String,
    val label: String,
    val pkg: String,
    val viewIds: List<String>,
    val testedOnVersion: String,
)

object SurfaceRules {
    /** Rules are data, so a broken one can be fixed without touching code. */
    fun load(context: Context): List<SurfaceRule> {
        val text = context.assets.open("surface_rules.json").bufferedReader().use { it.readText() }
        val rules = JSONObject(text).getJSONArray("rules")
        return (0 until rules.length()).map { i ->
            val r = rules.getJSONObject(i)
            val ids = r.getJSONArray("anyViewIds")
            SurfaceRule(
                r.getString("id"),
                r.getString("label"),
                r.getString("package"),
                (0 until ids.length()).map { ids.getString(it) },
                r.getString("testedOnVersion"),
            )
        }
    }

    /** A rule tested on another app version may have stopped working, so say so plainly. */
    fun versionWarnings(context: Context): List<String> = load(context).mapNotNull { rule ->
        val installed = try {
            context.packageManager.getPackageInfo(rule.pkg, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            return@mapNotNull null
        }
        if (installed == rule.testedOnVersion) {
            null
        } else {
            "${rule.label} detection was tested on version ${rule.testedOnVersion}, and this phone has " +
                "$installed. It may have stopped working."
        }
    }
}

/**
 * The main trigger. It uses fixed rules you approved, and reads package names and view IDs, never
 * screen text. Windows from the keyboard, System UI and Keel don't count as leaving an app.
 */
class PauseAccessibilityService : AccessibilityService() {
    private lateinit var controller: PauseController
    private lateinit var rules: List<SurfaceRule>
    private val handler = Handler(Looper.getMainLooper())
    private var ignored = emptySet<String>()
    private var lastScan = 0L

    override fun onServiceConnected() {
        rules = SurfaceRules.load(this)
        ignored = (
            getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName } +
                listOf(packageName, "com.android.systemui")
            ).toSet()
        controller = PauseController(
            this,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            "accessibility",
            leave = { back -> performGlobalAction(if (back) GLOBAL_ACTION_BACK else GLOBAL_ACTION_HOME) },
            isStillInFront = ::isStillInFront,
        )
        controller.onExpired = { rootInActiveWindow?.packageName?.toString()?.let(::scan) }
        stopService(android.content.Intent(this, FallbackPauseService::class.java))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::controller.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in ignored) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) confirmForeground(pkg, RETRIES)
        scan(pkg)
    }

    private fun confirmForeground(pkg: String, retries: Int) {
        val active = rootInActiveWindow?.packageName?.toString()
        when {
            // Checking the active window first skips the slower usage query on most events.
            active == pkg || isGenuineForeground(pkg, active, lastResumedPackage()) -> controller.onForeground(pkg)
            retries > 0 -> handler.postDelayed({ confirmForeground(pkg, retries - 1) }, RETRY_MS)
        }
    }

    private fun isStillInFront(target: String): Boolean {
        val rule = rules.firstOrNull { it.id == target }
        val active = rootInActiveWindow?.packageName?.toString()
        return if (rule != null) {
            active == null || active in ignored || active == rule.pkg
        } else {
            // The active window can lag, and the last resumed app settles it either way.
            active == target || lastResumedPackage() == target
        }
    }

    private fun lastResumedPackage(): String? {
        val now = System.currentTimeMillis()
        val events = getSystemService(UsageStatsManager::class.java).queryEvents(now - LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = event.packageName
        }
        return last
    }

    /** At most one tree scan every 250 ms. Only runs in apps that have a surface rule. */
    private fun scan(pkg: String) {
        val matching = rules.filter { it.pkg == pkg }
        if (matching.isEmpty()) {
            // Any app can send an event from the background, so only end a pause if its app really left.
            val active = rootInActiveWindow?.packageName?.toString()
            rules.filter { active != null && active != it.pkg && active !in ignored }
                .forEach { controller.onSurface(it.id, it.label, false) }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastScan < SCAN_GAP_MS) return
        lastScan = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        for (rule in matching) {
            val visible = rule.viewIds.any { id ->
                root.findAccessibilityNodeInfosByViewId(id).any { it.isVisibleToUser }
            }
            controller.onSurface(rule.id, rule.label, visible)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::controller.isInitialized) controller.destroy()
        super.onDestroy()
    }

    private companion object {
        const val RETRIES = 3
        const val RETRY_MS = 100L
        const val SCAN_GAP_MS = 250L
        const val LOOKBACK_MS = 15_000L
    }
}
