package dev.keel.spike

import android.content.Context

/** Test configuration. Only YouTube is ever targeted by this spike. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("spike", Context.MODE_PRIVATE)

    /** off = do nothing, app = pause the whole app, surface = pause only matching surface rules. */
    var mode: String
        get() = prefs.getString("mode", "off")!!
        set(v) = prefs.edit().putString("mode", v).apply()

    var delaySeconds: Int
        get() = prefs.getInt("delay", 10)
        set(v) = prefs.edit().putInt("delay", v).apply()

    /** Logs view resource IDs (never text) seen in the target app, to find rule IDs. */
    var discovery: Boolean
        get() = prefs.getBoolean("discovery", false)
        set(v) = prefs.edit().putBoolean("discovery", v).apply()

    /** Test switch for measuring what requesting audio focus changes. */
    var audioFocus: Boolean
        get() = prefs.getBoolean("audioFocus", true)
        set(v) = prefs.edit().putBoolean("audioFocus", v).apply()

    companion object {
        const val TARGET = "com.google.android.youtube"
    }
}
