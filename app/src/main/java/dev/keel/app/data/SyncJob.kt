package dev.keel.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import dev.keel.app.KeelApp
import dev.keel.app.pause.Permissions
import dev.keel.app.ui.MainActivity
import dev.keel.domain.UsageEvent
import dev.keel.domain.buildSessions
import kotlin.concurrent.thread

/**
 * Copies usage events into Keel's database, because Android keeps them only for a few days, and
 * checks that blocking still works. It's a scheduled job, because Android froze a plain background
 * thread when the phone locked. A force-stop also stops this job, so that case surfaces on next open.
 */
class SyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "keel-sync") {
            runCatching {
                sync(this)
                warnIfBroken(this)
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val JOB_ID = 1
        private const val CHANNEL = "health"
        private const val LAST_SYNC = "usage_last_sync"
        private const val OPEN_PKG = "usage_open_pkg"
        private const val OPEN_SINCE = "usage_open_since"
        private const val FIRST_RUN_LOOKBACK = 3L * 24 * 60 * 60 * 1000

        fun schedule(context: Context) {
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, SyncJob::class.java))
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build()
            context.getSystemService(JobScheduler::class.java).schedule(info)
        }

        @Synchronized
        fun sync(context: Context, now: Long = System.currentTimeMillis()) {
            if (!Permissions.usage(context)) return
            val repo = (context.applicationContext as KeelApp).repo
            val since = repo.setting(LAST_SYNC)?.toLongOrNull() ?: (now - FIRST_RUN_LOOKBACK)
            val stats = context.getSystemService(UsageStatsManager::class.java)
            val events = stats.queryEvents(since, now)
            val event = UsageEvents.Event()
            val collected = mutableListOf<UsageEvent>()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> collected += UsageEvent(event.timeStamp, event.packageName, true)
                    UsageEvents.Event.ACTIVITY_PAUSED -> collected += UsageEvent(event.timeStamp, event.packageName, false)
                }
            }
            val result = buildSessions(
                collected,
                repo.setting(OPEN_PKG)?.ifEmpty { null },
                repo.setting(OPEN_SINCE)?.toLongOrNull(),
            )
            repo.saveSessions(result.sessions)
            repo.setSetting(OPEN_PKG, result.openPkg.orEmpty())
            repo.setSetting(OPEN_SINCE, result.openSince?.toString().orEmpty())
            repo.setSetting(LAST_SYNC, now.toString())
        }

        private fun warnIfBroken(context: Context) {
            val repo = (context.applicationContext as KeelApp).repo
            if (repo.setting("onboarded") != "true") return
            val problems = Permissions.problems(context)
            val manager = context.getSystemService(NotificationManager::class.java)
            if (problems.isEmpty()) {
                manager.cancel(1)
                return
            }
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Keel health", NotificationManager.IMPORTANCE_DEFAULT),
            )
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(
                1,
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Keel isn't fully working")
                    .setContentText(problems.first())
                    .setStyle(Notification.BigTextStyle().bigText(problems.joinToString("\n")))
                    .setContentIntent(open)
                    .setOnlyAlertOnce(true)
                    .build(),
            )
        }
    }
}
