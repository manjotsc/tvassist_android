package com.tvassist

import android.app.Application
import com.tvassist.data.ha.HaRepository
import com.tvassist.data.notify.FixedNotificationStore
import com.tvassist.data.notify.NotificationStore
import com.tvassist.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Application-scoped dependency container. Kept deliberately simple (no DI framework)
 * for the MVP — singletons are created lazily and shared across activities/services.
 */
class TvAssistApp : Application() {

    val appScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob()) }

    val settingsStore: SettingsStore by lazy { SettingsStore(this) }

    val haRepository: HaRepository by lazy { HaRepository(settingsStore, appScope) }

    /** Active pushed notifications (shared by the REST server and the notification overlay).
     *  Persistent (duration-0) ones are saved to disk so they survive restarts and reboots. */
    val notificationStore: NotificationStore by lazy {
        NotificationStore(
            load = { settingsStore.readPersistentNotifications() },
            save = { settingsStore.writePersistentNotifications(it) },
        )
    }

    /** Pinned persistent pills (the tv_assist.persistent service / notify_fixed endpoint).
     *  Persisted to disk so they survive process restarts and reboots. */
    val fixedStore: FixedNotificationStore by lazy {
        FixedNotificationStore(
            load = { settingsStore.readFixedPills() },
            save = { settingsStore.writeFixedPills(it) },
        )
    }

    companion object {
        lateinit var instance: TvAssistApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
