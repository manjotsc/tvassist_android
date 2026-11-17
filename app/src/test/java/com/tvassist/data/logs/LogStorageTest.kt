package com.tvassist.data.logs

import com.tvassist.data.settings.LocalCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The collector's two stores, and the pure parts of the report header. */
class LogStorageTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun theRingKeepsTheNewestLinesByCount() {
        val r = LogRing(maxLines = 3, maxBytes = 1_000)
        (1..5).forEach { r.add("line $it") }
        assertEquals(listOf("line 3", "line 4", "line 5"), r.lines())
    }

    @Test fun theRingKeepsTheNewestLinesBySize() {
        val r = LogRing(maxLines = 100, maxBytes = 25)
        r.add("a".repeat(10))
        r.add("b".repeat(10))
        r.add("c".repeat(10)) // 33 bytes with newlines: the oldest goes
        assertEquals(listOf("b".repeat(10), "c".repeat(10)), r.lines())
    }

    @Test fun oneOversizedLineIsStillKept() {
        val r = LogRing(maxLines = 100, maxBytes = 5)
        r.add("much longer than five")
        assertEquals(1, r.size)
    }

    @Test fun theFileRotatesAndKeepsBothHalves() {
        val f = RotatingLogFile(tmp.root, maxBytes = 20)
        f.append("first-line-0123") // 16 bytes
        f.append("second-line-012") // 32 → rotates to previous.log
        f.append("third")
        assertEquals("first-line-0123\nsecond-line-012\nthird\n", f.readAll())
        f.append("fourth-line-0123") // current passes 20 again → the first two fall off
        assertEquals("third\nfourth-line-0123\n", f.readAll())
    }

    @Test fun theFileSurvivesANewInstanceAndDeletes() {
        // Closed as a dying process's handle would be (Windows will not delete an open file; Android would).
        RotatingLogFile(tmp.root, maxBytes = 1_000).apply { append("before restart"); close() }
        val after = RotatingLogFile(tmp.root, maxBytes = 1_000)
        after.append("after restart")
        assertEquals("before restart\nafter restart\n", after.readAll())
        after.delete()
        assertEquals("", after.readAll())
    }

    /** What a resumed capture hands back to `logcat -T`: the line's own stamp, exactly. */
    @Test fun aThreadtimeLinesStampIsWhatLogcatTakesBack() {
        assertEquals("11-17 00:02:12.641", LogCollector.stampOf("11-17 00:02:12.641  4242  4251 W HaCamera: stream failed"))
        assertEquals(null, LogCollector.stampOf("===== 2026-11-17 00:02:12 TV Assist 1.1.7: process started ====="))
    }

    @Test fun camerasAreDescribedWithoutTheirAddresses() {
        val lines = DeviceReport.cameraSummary(
            listOf(
                LocalCamera(id = "a", name = "Front door", streamUrl = "rtsp://admin:pw@10.0.0.5/main", lowResUrl = "rtsp://10.0.0.5/sub", player = "exoplayer"),
                LocalCamera(id = "b", name = "Garage", streamUrl = "http://10.0.0.6/clip.mp4", refresh = true),
            ),
            globalPlayer = "vlc",
        )
        assertEquals(
            listOf(
                "camera 1: rtsp, player exoplayer, rtsp tcp, low-res rtsp, snapshot no",
                "camera 2: http, player default (vlc), rtsp tcp, low-res none, snapshot no, rolling clip",
            ),
            lines,
        )
        // Names identify the household too; only numbers go in the report.
        assertTrue(lines.none { "Front door" in it || "10.0.0" in it })
    }

    @Test fun theVendorsSecureCodecFlagIsRead() {
        val ur3 = """<Settings>
            <Setting name="max-video-encoder-input-buffers" value="9" />
            <Setting name="supports-secure-with-non-secure-codec" value="false" />
        </Settings>"""
        assertEquals("false", DeviceReport.parseSecureFlag(ur3))
        assertEquals(null, DeviceReport.parseSecureFlag("<Settings/>"))
    }

    /** A failed stream's reason on one line: the causes, not a 40-line stack. */
    @Test fun anErrorIsLoggedAsItsChainOfCauses() {
        val e = IllegalStateException("Source error", java.net.ConnectException("failed to connect: ECONNREFUSED"))
        assertEquals(
            "IllegalStateException: Source error <- ConnectException: failed to connect: ECONNREFUSED",
            DiagLog.causeChain(e),
        )
    }

    /** R8 renames classes in release builds; `n: Source error` would name nothing. */
    @Test fun anObfuscatedClassNameIsLeftOut() {
        class n(msg: String) : Exception(msg)
        assertEquals("Source error", DiagLog.causeChain(n("Source error")))
    }

    @Test fun detailedLoggingReadsAsOffOnceExpired() {
        assertEquals("off", DeviceReport.detailedState(untilMs = 1_000, nowMs = 2_000))
        assertTrue(DeviceReport.detailedState(untilMs = 3_000, nowMs = 2_000).startsWith("on until"))
    }
}
