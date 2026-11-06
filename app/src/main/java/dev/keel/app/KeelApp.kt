package dev.keel.app

import android.app.Application
import dev.keel.app.data.DatabaseKey
import dev.keel.app.data.Repo
import dev.keel.app.data.SyncJob

class KeelApp : Application() {
    /** Opened once for the whole process. A raw key reopened in about 31 ms on the test phone. */
    val repo: Repo by lazy { Repo.open(this, DatabaseKey.load(this)) }

    override fun onCreate() {
        super.onCreate()
        SyncJob.schedule(this)
    }
}
