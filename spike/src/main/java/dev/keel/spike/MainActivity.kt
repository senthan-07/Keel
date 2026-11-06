package dev.keel.spike

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Plain test console. The adb extras below drive repeatable tests:
 * mode=off|app|surface, delay=<seconds>, discovery=true|false, fallback=start|stop, run=bench|backuptest.
 */
class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        status = TextView(this).apply { textSize = 15f }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(status)
            addView(action("Create encrypted backup") {
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/octet-stream")
                        .putExtra(Intent.EXTRA_TITLE, "keel-spike-backup.bin"),
                    REQUEST_CREATE,
                )
            })
            addView(action("Restore and verify backup") {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                    REQUEST_OPEN,
                )
            })
            addView(action("Run storage speed test") { runBench() })
        }
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(column) })
        applyExtras(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun applyExtras(intent: Intent) {
        intent.getStringExtra("mode")?.let { settings.mode = it }
        if (intent.hasExtra("delay")) settings.delaySeconds = intent.getIntExtra("delay", 10)
        intent.getStringExtra("discovery")?.let { settings.discovery = it == "true" }
        intent.getStringExtra("audiofocus")?.let { settings.audioFocus = it == "true" }
        when (intent.getStringExtra("fallback")) {
            "start" -> startForegroundService(Intent(this, UsagePollService::class.java))
            "stop" -> stopService(Intent(this, UsagePollService::class.java))
        }
        when (intent.getStringExtra("run")) {
            "bench" -> runBench()
            "backuptest" -> thread(name = "backup") {
                runCatching { Backup.selfTest(this) }
                    .onFailure { EventLog.log(this, "backup_failed", it.toString().replace(',', ';')) }
            }
        }
        refresh()
    }

    /** The health alarm in miniature: report plainly when the blocker is not able to work. */
    private fun refresh() {
        val component = ComponentName(this, PauseAccessibilityService::class.java).flattenToString()
        val enabled = AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(":")?.contains(component) == true
        status.text = buildString {
            appendLine("Keel Spike: test build, targets YouTube only")
            appendLine("Mode: ${settings.mode}, delay: ${settings.delaySeconds}s, discovery: ${settings.discovery}")
            appendLine("Accessibility: ${if (enabled) "on" else "OFF, blocking is not working"}")
            appendLine("Overlay permission: ${if (AndroidSettings.canDrawOverlays(this@MainActivity)) "on" else "OFF"}")
        }
        EventLog.log(this, "health", "accessibility=$enabled,overlay=${AndroidSettings.canDrawOverlays(this)}")
    }

    private fun runBench() = thread(name = "bench") {
        runCatching { Bench.run(this) }.onFailure { EventLog.log(this, "bench_failed", it.toString().replace(',', ';')) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            EventLog.log(this, "backup_cancelled", "request=$requestCode")
            return
        }
        thread(name = "backup") {
            runCatching {
                if (requestCode == REQUEST_CREATE) Backup.write(this, uri) else Backup.restore(this, uri)
            }.onFailure { EventLog.log(this, "backup_failed", it.toString().replace(',', ';')) }
        }
    }

    private fun action(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private companion object {
        const val REQUEST_CREATE = 1
        const val REQUEST_OPEN = 2
    }
}
