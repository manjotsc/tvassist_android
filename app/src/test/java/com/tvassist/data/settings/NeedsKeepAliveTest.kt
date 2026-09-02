package com.tvassist.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the one predicate that decides whether KeepAliveService should be running.
 *
 * It exists because the decision used to be written out by hand in four places — the boot receiver,
 * the activity's launch, and the keep-alive and notification toggles — and they disagreed. Turning
 * keep-alive off stopped the service outright, taking the notification server, the dim/clock
 * overlays and the Assist voice bar down with it, none of which the switch claims to control.
 *
 * The asserts are one per dependant, because each one is a feature that silently stops working
 * rather than reporting an error: the voice bar in particular is drawn by that service's window and
 * by nothing else, so losing it makes the mic button do nothing at all.
 */
class NeedsKeepAliveTest {

    /** Nothing on but keep-alive itself, which the user has switched off. */
    private val idle = Settings(keepAlive = false)

    @Test
    fun `nothing needs it when every dependant is off`() {
        assertFalse(idle.needsKeepAlive)
    }

    @Test
    fun `keep-alive alone is reason enough`() {
        assertTrue(idle.copy(keepAlive = true).needsKeepAlive)
    }

    @Test
    fun `each dependant holds it up on its own`() {
        assertTrue("notifications", idle.copy(notificationsEnabled = true).needsKeepAlive)
        assertTrue("screen dimming", idle.copy(dimLevel = 40).needsKeepAlive)
        assertTrue("clock", idle.copy(clockEnabled = true).needsKeepAlive)
        assertTrue("bound mic key", idle.copy(micKeyCode = 84).needsKeepAlive)
    }

    /** Dimming is off at 0, not merely quiet — a 0 % dim must not pin the service up. */
    @Test
    fun `a zero dim level is not a dependant`() {
        assertFalse(idle.copy(dimLevel = 0).needsKeepAlive)
    }

    /** 0 is "no key bound", the same sentinel KeyCaptureService uses to ignore the mic key. */
    @Test
    fun `an unbound mic key is not a dependant`() {
        assertFalse(idle.copy(micKeyCode = 0).needsKeepAlive)
    }

    /**
     * The regression that prompted this: the toggles ask what the settings will look like *after*
     * the switch, so turning one thing off while another still needs the service keeps it running.
     */
    @Test
    fun `turning keep-alive off leaves it up for the others`() {
        val withNotifications = Settings(keepAlive = true, notificationsEnabled = true)
        assertTrue(withNotifications.copy(keepAlive = false).needsKeepAlive)

        val withMicKey = Settings(keepAlive = true, micKeyCode = 84)
        assertTrue(withMicKey.copy(keepAlive = false).needsKeepAlive)
    }

    @Test
    fun `turning notifications off leaves it up for keep-alive`() {
        val both = Settings(keepAlive = true, notificationsEnabled = true)
        assertTrue(both.copy(notificationsEnabled = false).needsKeepAlive)
    }

    /** …and genuinely comes down once the last dependant goes. */
    @Test
    fun `the last dependant going takes it down`() {
        val onlyNotifications = Settings(keepAlive = false, notificationsEnabled = true)
        assertFalse(onlyNotifications.copy(notificationsEnabled = false).needsKeepAlive)
    }
}
