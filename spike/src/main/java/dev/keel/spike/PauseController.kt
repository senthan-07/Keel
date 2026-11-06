package dev.keel.spike

import android.content.Context
import android.content.Intent
import android.os.SystemClock

/** Decides when the pause screen shows. Shared by the Accessibility and UsageStats triggers. */
class PauseController(
    private val context: Context,
    private val windowType: Int,
    private val source: String,
    private val leave: (String) -> Unit,
) {
    private val settings = Settings(context)
    private var overlay: PauseOverlay? = null
    private var overlayKey: String? = null
    private val allowedUntil = mutableMapOf<String, Long>()

    /** Whole-app mode: called whenever the foreground app changes. */
    fun onForeground(pkg: String) {
        if (settings.mode != "app") return
        if (pkg == Settings.TARGET) show(pkg) else if (overlayKey == Settings.TARGET) hide("left_app")
    }

    /** Surface mode: called with whether a rule currently matches. */
    fun onSurface(ruleId: String, visible: Boolean) {
        if (settings.mode != "surface") return
        if (visible) show(ruleId) else if (overlayKey == ruleId) hide("left_surface")
    }

    private fun show(key: String) {
        if (overlay?.isShowing == true) return
        if (SystemClock.elapsedRealtime() < (allowedUntil[key] ?: 0L)) return
        val isSurface = key != Settings.TARGET
        val view = PauseOverlay(context, windowType, settings.delaySeconds, settings.audioFocus) { choice ->
            EventLog.log(context, "choice", "$source,$key,$choice")
            when (choice) {
                "open_5_min" -> allowedUntil[key] = SystemClock.elapsedRealtime() + 5 * 60_000L
                else -> leave(if (isSurface) "back" else "home")
            }
            hide("choice")
        }
        overlay = view
        overlayKey = key
        EventLog.log(context, "overlay_requested", "$source,$key")
        val added = view.show { EventLog.log(context, "overlay_drawn", "$source,$key") }
        if (!added) {
            EventLog.log(context, "health", "overlay_failed,$source,$key")
            overlay = null
            overlayKey = null
        }
    }

    private fun hide(reason: String) {
        if (overlay?.isShowing == true) EventLog.log(context, "overlay_hidden", "$source,$reason")
        overlay?.hide()
        overlay = null
        overlayKey = null
    }

    fun destroy() = hide("service_stopped")

    companion object {
        fun goHome(context: Context) {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
