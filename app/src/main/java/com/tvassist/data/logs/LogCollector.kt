package com.tvassist.data.logs

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects this app's own log so a user can hand it over with an issue report — downloaded from the
 * web console, or saved to the TV's Downloads folder. There is deliberately no viewer: the earlier
 * three-tier Logs screen is parked in `logproject/`, and the point here is getting a file to
 * whoever is fixing the bug, not reading it on a TV.
 *
 * **Only TV Assist's own process.** `logcat --pid=<us>` needs no permission; the whole system
 * buffer needs `READ_LOGS`, which apps cannot hold. ExoPlayer, MediaCodec's client side, OkHttp and
 * libVLC all log in our process, so a stream failing usually explains itself here — but the TV's
 * decoder *service* (a MediaTek `c2.mtk … errcode=12`, issue #5) logs in another process, and that
 * line needs `adb logcat`.
 *
 * Two modes, chosen in Settings → Logs:
 * - **Memory only** (default): the newest [MAX_LINES] lines / [MAX_BYTES], gone when the process
 *   ends. Enough for "reproduce it, then download".
 * - **Kept across restarts**: also written to two rotating files of [FILE_MAX_BYTES], so a crash or
 *   a restart does not wipe the evidence. Each process start writes a marker line.
 *
 * Every line is redacted ([LogRedactor]) before it is stored in either. Turning collection off
 * stops the capture and deletes what was held; turning persistence off deletes the files.
 */
class LogCollector(filesDir: File) {

    /** Replaced whenever the settings it is built from change (tokens, HA URL, cameras). */
    @Volatile
    var redactor: LogRedactor = LogRedactor.BASIC

    private val ring = LogRing(MAX_LINES, MAX_BYTES)
    private val files = RotatingLogFile(File(filesDir, "logs"), FILE_MAX_BYTES)
    private var persist = false
    private var process: Process? = null

    /** Whether collection is switched on: a capture that ends by itself is restarted only then. */
    private var wanted = false

    /** Set by the first [configure] in this process, so a later one is not marked "process started". */
    private var configuredOnce = false

    /**
     * The newest line's `MM-dd HH:mm:ss.SSS` stamp, and the lines already held at that exact stamp.
     * A restarted capture resumes from it with `logcat -T` rather than replaying the whole buffer,
     * and skips the lines it would otherwise repeat.
     */
    private var lastStamp: String? = null
    private val atLastStamp = HashSet<String>()

    /** Recent unexpected ends, for giving up on a logcat that dies the moment it starts. */
    private val endedAt = ArrayDeque<Long>()

    /**
     * How many times logcat ended by itself and was resumed, for the report header. Counted rather
     * than logged: on the UR3 it happens every minute or so (see [onCaptureEnded]), and a line each
     * time crowded the memory-only log with the collector talking about itself.
     */
    @Volatile
    var restarts: Int = 0
        private set

    /** Why the capture is not running although it should be, for the report header. */
    @Volatile
    var problem: String? = null
        private set

    val running: Boolean @Synchronized get() = process != null

    /** Applies the two switches. Cheap to call again with the same values. */
    @Synchronized
    fun configure(collect: Boolean, keepAcrossRestarts: Boolean) {
        wanted = collect
        if (!collect) {
            stop()
            ring.clear()
            files.delete()
            persist = false
            // Turning it back on must not bring back what this just deleted: Android's own buffer
            // still holds those lines, and a fresh capture would replay them. Resume from now.
            lastStamp = STAMP_FORMAT.format(Date())
            atLastStamp.clear()
            return
        }
        if (keepAcrossRestarts && !persist) {
            // The process just started, or a switch was turned on now. Mark which, and carry over
            // what memory already holds so the file does not begin mid-story.
            files.append(
                marker(
                    when {
                        !configuredOnce -> "process started"
                        ring.isEmpty() -> "collection turned on"
                        else -> "kept across restarts from here"
                    },
                ),
            )
            ring.lines().forEach(files::append)
        }
        configuredOnce = true
        if (!keepAcrossRestarts && persist) files.delete()
        persist = keepAcrossRestarts
        start(lastStamp)
    }

    private fun start(since: String? = null) {
        if (process != null) return
        problem = null
        val pid = android.os.Process.myPid()
        val proc = runCatching {
            // No -d: logcat prints what it already holds for this pid and then follows, so lines
            // written before the settings loaded (and so before this ran) are not lost. A restart
            // resumes from the newest line already held instead (-T).
            val args = mutableListOf("logcat", "-v", "threadtime", "--pid=$pid")
            if (since != null) args += listOf("-T", since)
            ProcessBuilder(args).redirectErrorStream(true).start()
        }.getOrElse {
            Log.w(TAG, "could not start logcat", it)
            problem = "This TV would not let the app read its own log (${it.javaClass.simpleName})."
            return
        }
        process = proc
        Thread({ pump(proc) }, "log-collector").apply { isDaemon = true; start() }
    }

    private fun stop() {
        runCatching { process?.destroy() }
        process = null
    }

    private fun pump(proc: Process) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        runCatching {
            BufferedReader(InputStreamReader(proc.inputStream)).use { input ->
                while (true) {
                    val raw = input.readLine() ?: break
                    // logcat's own banners carry nothing.
                    if (raw.startsWith("--------- beginning of")) continue
                    add(redactor.redact(raw))
                }
            }
        }
        onCaptureEnded(proc, startedAt)
    }

    /**
     * logcat ended without being asked to — routinely, not rarely. Watched on the UR3 (Android 10)
     * for 15 minutes: it ended 11 times, every 60–90 s, with no `am_kill`. The likely reason is
     * logd's: the main buffer is 1 MB and the TV's own MediaTek chatter fills it in minutes, while a
     * reader filtered to this pid only moves forward when the app writes — so a quiet app's reader
     * holds back pruning, and logd drops readers that do. Inferred from the pattern, not read off
     * logd. Nothing is lost: the restart resumes from the newest line held ([lastStamp]).
     */
    private fun onCaptureEnded(proc: Process, startedAt: Long) {
        val exit = runCatching { proc.waitFor(); proc.exitValue() }.getOrNull()
        val lived = (android.os.SystemClock.elapsedRealtime() - startedAt) / 1000
        synchronized(this) {
            if (process !== proc) return // stopped on purpose
            process = null
            if (!wanted) return
            val now = android.os.SystemClock.elapsedRealtime()
            endedAt.addLast(now)
            while (endedAt.isNotEmpty() && now - endedAt.first() > RESTART_WINDOW_MS) endedAt.removeFirst()
            if (endedAt.size > MAX_RESTARTS) {
                // Only this is worth a line: more than MAX_RESTARTS inside a minute is a logcat
                // that cannot run at all, not logd tidying up.
                val note = "capture ended after $lived s (logcat exit ${exit ?: "?"})"
                Log.w(TAG, "log collector gave up: $note")
                problem = "The log capture kept stopping ($note); gave up."
                return
            }
            restarts++
        }
        Thread({
            Thread.sleep(RESTART_DELAY_MS)
            synchronized(this) { if (wanted && process == null) start(lastStamp) }
        }, "log-collector-restart").apply { isDaemon = true; start() }
    }

    @Synchronized
    private fun add(line: String) {
        val stamp = stampOf(line)
        if (stamp != null) {
            // A resumed capture starts *at* the newest stamp already held, so the lines carrying
            // exactly that stamp come round again: drop the ones already kept.
            if (stamp == lastStamp && line in atLastStamp) return
            if (stamp != lastStamp) {
                lastStamp = stamp
                atLastStamp.clear()
            }
            atLastStamp += line
        }
        ring.add(line)
        if (persist) files.append(line)
    }

    /** Everything held, oldest first: the files when kept across restarts, else memory. */
    @Synchronized
    fun body(): String =
        if (persist) files.readAll().ifEmpty { ring.joined() } else ring.joined()

    @Synchronized
    fun lineCount(): Int = ring.size

    private fun marker(what: String): String =
        "===== ${STAMP.format(Date())} TV Assist ${com.tvassist.BuildConfig.VERSION_NAME} " +
            "(pid ${android.os.Process.myPid()}): $what ====="

    companion object {
        private const val TAG = "LogCollector"
        const val MAX_LINES = 2_000
        const val MAX_BYTES = 512 * 1024
        const val FILE_MAX_BYTES = 512L * 1024
        private const val RESTART_DELAY_MS = 2_000L
        private const val RESTART_WINDOW_MS = 60_000L
        private const val MAX_RESTARTS = 5

        /** `11-17 00:02:12.641`: the leading stamp of a `threadtime` line, which `logcat -T` takes back. */
        /** The same shape, for "now" — what [configure] resumes from after collection was switched off. */
        private val STAMP_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        private val STAMP_RE = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""")

        internal fun stampOf(line: String): String? = STAMP_RE.find(line)?.value
        private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}

/** The newest lines, capped by count *and* size: a flood of long lines would blow the byte budget long before the count. */
internal class LogRing(private val maxLines: Int, private val maxBytes: Int) {
    private val lines = ArrayDeque<String>()
    private var bytes = 0

    val size: Int get() = lines.size

    fun isEmpty() = lines.isEmpty()

    fun add(line: String) {
        lines.addLast(line)
        bytes += line.length + 1
        while (lines.size > maxLines || (bytes > maxBytes && lines.size > 1)) {
            bytes -= lines.removeFirst().length + 1
        }
    }

    fun lines(): List<String> = lines.toList()

    fun joined(): String = if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")

    fun clear() {
        lines.clear()
        bytes = 0
    }
}

/**
 * `current.log` plus the `previous.log` it rotated into once it passed [maxBytes]: at most twice
 * that on disk, and always the newest stretch. Flushed per line, because the case this exists for
 * is the process dying — a buffered tail would die with it.
 */
internal class RotatingLogFile(private val dir: File, private val maxBytes: Long) {
    private val current = File(dir, "current.log")
    private val previous = File(dir, "previous.log")
    private var writer: BufferedWriter? = null
    private var size = 0L

    fun append(line: String) {
        runCatching {
            val w = writer ?: open()
            w.write(line)
            w.write("\n")
            w.flush()
            size += line.length + 1
            if (size > maxBytes) rotate()
        }
    }

    private fun open(): BufferedWriter {
        dir.mkdirs()
        size = current.length()
        return BufferedWriter(OutputStreamWriter(FileOutputStream(current, true), Charsets.UTF_8)).also { writer = it }
    }

    private fun rotate() {
        close()
        previous.delete()
        current.renameTo(previous)
        size = 0
    }

    fun close() {
        runCatching { writer?.close() }
        writer = null
    }

    fun readAll(): String = buildString {
        writer?.flush()
        if (previous.exists()) append(previous.readText())
        if (current.exists()) append(current.readText())
    }

    fun delete() {
        close()
        previous.delete()
        current.delete()
        size = 0
    }
}
