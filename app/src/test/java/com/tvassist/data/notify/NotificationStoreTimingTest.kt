package com.tvassist.data.notify

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives the auto-dismiss / enlarge-timeout coroutines on a virtual clock (no real waiting). */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationStoreTimingTest {

    private fun NotificationStore.has(id: String) = items.value.any { it.id == id }

    @Test fun `transient auto-dismisses after its duration`() = runTest {
        val store = NotificationStore(dispatcher = StandardTestDispatcher(testScheduler))
        store.show(TvNotification(id = "a", message = "x", durationSec = 5))
        runCurrent()
        advanceTimeBy(4_000); runCurrent()
        assertTrue(store.has("a"))
        advanceTimeBy(1_500); runCurrent()
        assertFalse(store.has("a"))
    }

    @Test fun `persistent notification never auto-dismisses`() = runTest {
        val store = NotificationStore(dispatcher = StandardTestDispatcher(testScheduler))
        store.show(TvNotification(id = "p", message = "x", durationSec = 0))
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertTrue(store.has("p"))
    }

    @Test fun `opened notification is held until collapsed`() = runTest {
        val store = NotificationStore(dispatcher = StandardTestDispatcher(testScheduler))
        store.show(TvNotification(id = "i", message = "x", durationSec = 5, interactive = true))
        runCurrent()
        store.enlarge("i"); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertTrue("held open past its duration", store.has("i"))
        store.collapse()
        advanceUntilIdle()
        assertFalse("removed after BACK/collapse", store.has("i"))
    }

    @Test fun `enlarge timeout auto-collapses an opened notification`() = runTest {
        val store = NotificationStore(dispatcher = StandardTestDispatcher(testScheduler))
        store.enlargeTimeoutDefaultSec = 3
        store.show(TvNotification(id = "i", message = "x", durationSec = 10, interactive = true))
        runCurrent()
        store.enlarge("i"); runCurrent()
        advanceTimeBy(3_500); runCurrent()
        assertNull("auto-collapsed after the enlarge timeout", store.enlargedId.value)
    }

    @Test fun `per-notification enlargeTimeout overrides the default`() = runTest {
        val store = NotificationStore(dispatcher = StandardTestDispatcher(testScheduler))
        store.enlargeTimeoutDefaultSec = 0 // TV default: hold until BACK
        store.show(TvNotification(id = "i", message = "x", durationSec = 10, interactive = true, enlargeTimeout = 2))
        runCurrent()
        store.enlarge("i"); runCurrent()
        advanceTimeBy(2_500); runCurrent()
        assertNull(store.enlargedId.value)
    }
}
