package dev.keel.app.pause

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager

/**
 * Keeps whole-app pauses working when Accessibility is off, by polling usage events. It can't do
 * Shorts-only blocking. Keel starts it when you open the app and Accessibility is off, and the
 * Accessibility service stops it again.
 */
class FallbackPauseService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var controller: PauseController
    private lateinit var usage: UsageStatsManager
    private var since = 0L
    private var foreground: String? = null
    private val seen = LinkedHashSet<String>()

    private val poll = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            // Events can be recorded late, so re-read an overlapping window and skip ones already seen.
            val events = usage.queryEvents(since - OVERLAP_MS, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                if (!seen.add("${event.timeStamp}/${event.packageName}/${event.className}")) continue
                if (seen.size > MAX_SEEN) seen.remove(seen.first())
                if (event.packageName == packageName) {
                    foreground = packageName
                    continue
                }
                foreground = event.packageName
                controller.onForeground(event.packageName)
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
            "fallback",
            leave = {
                startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            isStillInFront = { target -> foreground == target },
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Keel fallback", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Keel is watching in fallback mode")
            .setContentText("Turn on Accessibility for Shorts-only blocking.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        since = System.currentTimeMillis()
        handler.post(poll)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controller.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "fallback"
        private const val NOTIFICATION_ID = 2
        private const val POLL_MS = 500L
        private const val OVERLAP_MS = 3_000L
        private const val MAX_SEEN = 200

        fun stop(context: Context) {
            context.stopService(Intent(context, FallbackPauseService::class.java))
        }

        /** Starts or stops the fallback to match the current permissions. Called from the open app. */
        fun sync(context: Context) {
            val intent = Intent(context, FallbackPauseService::class.java)
            if (!Permissions.accessibility(context) && Permissions.overlay(context) && Permissions.usage(context)) {
                context.startForegroundService(intent)
            } else {
                context.stopService(intent)
            }
        }
    }
}
