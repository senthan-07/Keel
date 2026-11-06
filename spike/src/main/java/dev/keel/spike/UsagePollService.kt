package dev.keel.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager

/** Fallback trigger for when Accessibility is unavailable: polls usage events, then draws a normal overlay. */
class UsagePollService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var controller: PauseController
    private lateinit var usage: UsageStatsManager
    private var since = 0L
    private val seen = LinkedHashSet<String>()

    /** Returns 0 for a new event, and Long.MAX_VALUE for one already handled. */
    private fun lastTimestampSeen(id: String): Long {
        if (!seen.add(id)) return Long.MAX_VALUE
        if (seen.size > 200) seen.remove(seen.first())
        return 0L
    }

    private val poll = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            // Events can be recorded late, so re-read an overlapping window and skip ones already seen.
            val events = usage.queryEvents(since - OVERLAP_MS, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val id = "${event.timeStamp}/${event.packageName}/${event.className}"
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED &&
                    event.packageName != packageName && event.timeStamp > lastTimestampSeen(id)
                ) {
                    EventLog.log(
                        this@UsagePollService,
                        "foreground_event",
                        "usage,${event.packageName},lagMs=${now - event.timeStamp}",
                    )
                    controller.onForeground(event.packageName)
                }
            }
            since = now
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        usage = getSystemService(UsageStatsManager::class.java)
        controller = PauseController(
            this,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            "usage",
        ) { PauseController.goHome(this) }
        val channel = NotificationChannel("spike", "Keel Spike", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = Notification.Builder(this, "spike")
            .setContentTitle("Keel Spike fallback test")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        since = System.currentTimeMillis()
        handler.post(poll)
        EventLog.log(this, "service_connected", "usage,pollMs=$POLL_MS")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controller.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val POLL_MS = 500L
        const val OVERLAP_MS = 3_000L
    }
}
