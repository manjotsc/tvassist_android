package com.tvassist

import android.app.Application
import com.tvassist.data.ha.HaRepository
import com.tvassist.data.notify.FixedNotificationStore
import com.tvassist.data.notify.NotificationStore
import com.tvassist.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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

    /**
     * The one TextToSpeech engine for the whole process. App-scoped rather than owned by
     * [com.tvassist.overlay.KeepAliveService] because the Assist card speaks agent replies from the
     * overlay too, and two engines would fight over audio focus. Still lazy, so a TV that never
     * speaks never initialises one; never shut down, since it lives as long as the process.
     */
    val tts: com.tvassist.data.notify.TtsManager by lazy {
        com.tvassist.data.notify.TtsManager(this)
    }

    /**
     * The one live voice exchange — see [com.tvassist.data.assist.VoiceController]. App-scoped
     * because the window that draws the voice bar is added and removed around a single question,
     * and the conversation thread has to outlive both it and the typed card.
     */
    val voice: com.tvassist.data.assist.VoiceController by lazy {
        com.tvassist.data.assist.VoiceController(this, haRepository, settingsStore, sound, appScope)
    }

    /**
     * The one sound/audio player for the process. App-scoped for the same reason as [tts]: the
     * Assist bar plays Home Assistant's spoken replies through it from a service-owned window, and
     * two players would fight over audio focus with the notification sounds.
     */
    val sound: com.tvassist.data.notify.SoundPlayer by lazy {
        com.tvassist.data.notify.SoundPlayer(this)
    }

    /** Pinned persistent pills (the tv_assist.persistent service / notify_fixed endpoint).
     *  Persisted to disk so they survive process restarts and reboots. */
    val fixedStore: FixedNotificationStore by lazy {
        FixedNotificationStore(
            load = { settingsStore.readFixedPills() },
            save = { settingsStore.writeFixedPills(it) },
        )
    }

    /**
     * This process's own log, for issue reports (Settings → Logs, and the web console's download).
     * App-scoped so it starts with the process, whichever component started it — on a cold boot
     * that is often the accessibility service, long before any activity.
     */
    val logs: com.tvassist.data.logs.LogCollector by lazy { com.tvassist.data.logs.LogCollector(filesDir) }

    /** The exported log: the device header, then everything collected. */
    suspend fun logReport(): String {
        val s = settingsStore.settings.first()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.tvassist.data.logs.DeviceReport.build(this@TvAssistApp, s, logs)
        }
    }

    /** Keeps the collector, its redaction and detailed logging in step with settings. */
    private fun startLogs() {
        appScope.launch {
            settingsStore.settings
                .map { s ->
                    LogConfig(
                        collect = s.collectLogs,
                        persist = s.persistLogs,
                        detailedUntil = s.detailedLogsUntil,
                        secrets = listOf(s.token, s.notificationToken, s.googleMapsApiKey),
                        hosts = com.tvassist.data.logs.hostPlaceholders(
                            s.baseUrl,
                            s.localCameras.map { listOf(it.streamUrl, it.snapshotUrl, it.lowResUrl) },
                        ),
                    )
                }
                .distinctUntilChanged()
                .collect { c ->
                    // Redaction first: the capture may already be running, and lines arriving after
                    // a token or camera was added must be scrubbed of it.
                    logs.redactor = com.tvassist.data.logs.LogRedactor(c.secrets, c.hosts)
                    com.tvassist.data.logs.DiagLog.untilMs = c.detailedUntil
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        logs.configure(c.collect, c.persist)
                    }
                }
        }
    }

    private data class LogConfig(
        val collect: Boolean,
        val persist: Boolean,
        val detailedUntil: Long,
        val secrets: List<String>,
        val hosts: Map<String, String>,
    )

    companion object {
        lateinit var instance: TvAssistApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startLogs()
    }
}
