package dev.keel.spike

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager

/**
 * Primary trigger. Uses fixed, human-defined rules only, and reads view IDs, never text.
 * Windows from the keyboard, System UI and this app don't count as leaving the target app.
 */
class PauseAccessibilityService : AccessibilityService() {
    private lateinit var controller: PauseController
    private lateinit var rules: List<SurfaceRule>
    private lateinit var settings: Settings
    private var ignored = emptySet<String>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastScan = 0L
    private var lastDiscovery = emptySet<String>()

    override fun onServiceConnected() {
        settings = Settings(this)
        rules = SurfaceRules.load(this)
        val keyboards = getSystemService(InputMethodManager::class.java)
            .enabledInputMethodList.map { it.packageName }
        ignored = (keyboards + listOf(packageName, "com.android.systemui")).toSet()
        controller = PauseController(
            this,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            "accessibility",
        ) { how ->
            if (how == "back") performGlobalAction(GLOBAL_ACTION_BACK)
            else performGlobalAction(GLOBAL_ACTION_HOME)
        }
        EventLog.log(this, "service_connected", "accessibility,rules=${rules.size}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg in ignored) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            EventLog.log(this, "foreground_event", "accessibility,$pkg")
            confirmForeground(pkg, retries = 3)
        }
        if (settings.mode == "surface" || settings.discovery) scan(pkg)
    }

    /**
     * A window event doesn't prove an app is in front: YouTube fired one about a second after Home,
     * with the launcher focused. The active window alone lags behind a real launch, which let the
     * app show first on cold starts, so the last resumed activity from UsageStats is also accepted.
     */
    private fun confirmForeground(pkg: String, retries: Int) {
        val active = rootInActiveWindow?.packageName?.toString()
        when {
            active == pkg || lastResumedPackage() == pkg -> controller.onForeground(pkg)
            retries > 0 -> handler.postDelayed({ confirmForeground(pkg, retries - 1) }, 100)
            else -> EventLog.log(this, "ignored_background_event", "$pkg,active=$active")
        }
    }

    private fun lastResumedPackage(): String? {
        val now = System.currentTimeMillis()
        val events = getSystemService(UsageStatsManager::class.java).queryEvents(now - 15_000, now)
        val event = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = event.packageName
        }
        return last
    }

    /** At most one tree scan every 250 ms, to limit battery use. */
    private fun scan(pkg: String) {
        val matching = rules.filter { it.pkg == pkg }
        if (matching.isEmpty()) {
            rules.forEach { controller.onSurface(it.id, false) }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastScan < 250) return
        lastScan = now
        val root = rootInActiveWindow ?: return
        val started = SystemClock.elapsedRealtimeNanos()
        for (rule in matching) {
            val visible = rule.anyViewIds.any { id ->
                root.findAccessibilityNodeInfosByViewId(id).any { it.isVisibleToUser }
            }
            controller.onSurface(rule.id, visible)
        }
        if (settings.discovery) discover(root)
        val micros = (SystemClock.elapsedRealtimeNanos() - started) / 1000
        if (settings.mode == "surface") EventLog.log(this, "scan", "micros=$micros")
    }

    private fun discover(root: AccessibilityNodeInfo) {
        val ids = sortedSetOf<String>()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 60) return
            if (node.isVisibleToUser) node.viewIdResourceName?.let { ids += it }
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        if (ids != lastDiscovery) {
            lastDiscovery = ids
            EventLog.log(this, "view_ids", ids.joinToString("|"))
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::controller.isInitialized) controller.destroy()
        super.onDestroy()
    }
}
