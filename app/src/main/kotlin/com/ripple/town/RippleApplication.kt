package com.ripple.town

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.ripple.town.notifications.NotificationHelper
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class RippleApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Idempotent (re-creating an existing channel with the same id is a no-op)
        // and cheap, so this runs unconditionally on every process start rather than
        // only after the user opts in — the channel existing doesn't mean anything
        // gets posted to it; canPostNotifications()/the opt-in still gate that.
        NotificationHelper.ensureChannel(this)
    }
}
