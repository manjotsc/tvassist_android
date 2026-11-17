package com.tvassist.data.logs

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Getting a collected log off the TV. The web console's download is the other way out. */
object LogExport {

    /** `tvassist-log-20261116-2304.txt`: sorts by time, and says what it is. */
    fun fileName(now: Date = Date()): String =
        "tvassist-log-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(now)}.txt"

    /**
     * Writes [text] to the TV's Downloads folder and returns where it went, for the UI to show.
     *
     * Android 10+ goes through MediaStore, which needs no permission for the app's own files — the
     * same route backups take. Older TVs (minSdk is 26) would need the storage permission for the
     * public folder, so they get the app's own external folder instead, which needs none and is
     * still reachable with `adb pull`.
     */
    fun saveToDownloads(context: Context, text: String): String {
        val name = fileName()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("Could not create the log file")
            try {
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: throw IOException("Could not open the log file for writing")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return "Download/$name"
        }
        val dir = context.getExternalFilesDir(null) ?: throw IOException("No storage available")
        val f = File(dir, name)
        f.writeText(text)
        return f.absolutePath
    }
}
