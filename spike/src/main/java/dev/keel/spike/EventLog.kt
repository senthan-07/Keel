package dev.keel.spike

import android.content.Context
import android.util.Log
import java.io.File

/** Writes `epochMillis,event,detail` to logcat (tag KEEL) and to filesDir/events.csv. Never logs screen text. */
object EventLog {
    fun log(context: Context, event: String, detail: String = "") {
        val line = "${System.currentTimeMillis()},$event,$detail"
        Log.i("KEEL", line)
        File(context.filesDir, "events.csv").appendText(line + "\n")
    }
}
