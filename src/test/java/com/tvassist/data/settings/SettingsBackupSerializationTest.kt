package com.tvassist.data.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** Guards backup/restore: every field must survive a JSON round-trip, and an older backup that
 *  predates newer fields must decode to their defaults (backward compatibility). */
class SettingsBackupSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `backup round-trips with non-default values`() {
        val original = SettingsBackup(
            announceSpeakMode = "separate",
            announceSoundRepeat = "loop",
            announceSpeakRepeat = "loop",
            announceRepeatGap = 5,
            interactiveEnlargeTimeout = 120,
            notificationDefaultDuration = 12,
        )
        val encoded = json.encodeToString(SettingsBackup.serializer(), original)
        val decoded = json.decodeFromString(SettingsBackup.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test fun `an older backup missing new fields decodes to defaults`() {
        val decoded = json.decodeFromString(SettingsBackup.serializer(), "{}")
        assertEquals(2, decoded.announceRepeatGap)
        assertEquals("once", decoded.announceSoundRepeat)
        assertEquals("both", decoded.announceSpeakMode)
        assertEquals(0, decoded.interactiveEnlargeTimeout)
    }
}
