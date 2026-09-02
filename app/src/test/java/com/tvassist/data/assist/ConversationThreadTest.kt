package com.tvassist.data.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether two questions are one conversation.
 *
 * Worth pinning because every failure here is silent and plausible: a thread handed to the wrong
 * agent gets a confident answer to a question nobody asked, and a transcript that outlives its
 * thread reads as though the exchange above it were still going.
 */
private const val MINUTE = 60_000L

class ConversationThreadTest {

    private var clock = 0L
    private fun thread() = ConversationThread { clock }

    @Test
    fun `nothing to continue before anything is asked`() {
        val t = thread()
        assertNull(t.forAgent("conversation.home_assistant"))
        assertNull(t.begin("conversation.home_assistant"))
        assertTrue(t.turns.value.isEmpty())
    }

    @Test
    fun `the agent that was given a thread continues it`() {
        val t = thread()
        t.begin("agent.a")
        t.adopt("agent.a", "thread-1")
        assertEquals("thread-1", t.forAgent("agent.a"))
        assertEquals("thread-1", t.begin("agent.a"))
    }

    @Test
    fun `another agent never gets someone else's thread`() {
        val t = thread()
        t.adopt("agent.a", "thread-1")
        // HA scopes a conversation id to the agent that issued it; handing it on is at best ignored.
        assertNull(t.forAgent("agent.b"))
    }

    @Test
    fun `a thread that has gone cold is not continued`() {
        val t = thread()
        t.adopt("agent.a", "thread-1")
        clock += 4 * MINUTE
        assertEquals("thread-1", t.forAgent("agent.a"))
        clock += 2 * MINUTE
        // Six minutes idle: HA has very likely dropped its own history by now, and "turn it off"
        // resolved against a thread that old is a worse answer than starting fresh.
        assertNull(t.forAgent("agent.a"))
    }

    @Test
    fun `asking again keeps a long conversation alive`() {
        val t = thread()
        t.adopt("agent.a", "thread-1")
        // Four minutes between turns, twice: neither gap reaches the timeout, so the thread holds.
        clock += 4 * MINUTE
        assertEquals("thread-1", t.begin("agent.a"))
        clock += 4 * MINUTE
        assertEquals("thread-1", t.forAgent("agent.a"))
    }

    @Test
    fun `changing agent drops the thread and the transcript with it`() {
        val t = thread()
        t.adopt("agent.a", "thread-1")
        t.record(mine = true, text = "is the garage open?")
        t.record(mine = false, text = "Yes.")

        assertNull(t.begin("agent.b"))
        assertNull(t.id.value) // dropped, not merely ignored — a stale id must never be sent later
        assertTrue(t.turns.value.isEmpty())
    }

    @Test
    fun `a cold thread takes its transcript with it too`() {
        val t = thread()
        t.adopt("agent.a", "thread-1")
        t.record(mine = false, text = "Yes.")
        clock += 6 * MINUTE

        assertNull(t.begin("agent.a"))
        assertTrue(t.turns.value.isEmpty())
    }

    @Test
    fun `the same agent keeps its history`() {
        val t = thread()
        t.adopt("agent.a", "thread-1")
        t.record(mine = true, text = "turn on the lamp")
        t.begin("agent.a")
        assertEquals(1, t.turns.value.size)
    }

    @Test
    fun `history is shown to its own agent only`() {
        val t = thread()
        // No thread yet: whatever has been said is the live conversation, whoever is asking.
        assertTrue(t.ownsHistory("agent.a"))

        t.adopt("agent.a", "thread-1")
        assertTrue(t.ownsHistory("agent.a"))
        assertFalse(t.ownsHistory("agent.b"))

        clock += 6 * MINUTE
        // Cold counts as someone else's: the card must not render yesterday's exchange as current.
        assertFalse(t.ownsHistory("agent.a"))
    }

    @Test
    fun `a reply with no thread id leaves the one we had`() {
        val t = thread()
        t.adopt("agent.a", "thread-1")
        t.adopt("agent.a", null)
        t.adopt("agent.a", "")
        assertEquals("thread-1", t.forAgent("agent.a"))
    }

    @Test
    fun `blank turns are not recorded`() {
        val t = thread()
        t.record(mine = true, text = "   ")
        t.record(mine = false, text = "")
        assertTrue(t.turns.value.isEmpty())
    }

    @Test
    fun `the transcript is bounded and keeps the newest`() {
        val t = thread()
        repeat(45) { t.record(mine = it % 2 == 0, text = "turn $it") }
        assertEquals(40, t.turns.value.size)
        assertEquals("turn 5", t.turns.value.first().text)
        assertEquals("turn 44", t.turns.value.last().text)
    }

    @Test
    fun `reset forgets everything`() {
        val t = thread()
        t.adopt("agent.a", "thread-1")
        t.record(mine = true, text = "hello")
        t.reset()
        assertNull(t.id.value)
        assertTrue(t.turns.value.isEmpty())
        // Including the owner: after a reset the next agent to ask is not competing with anyone.
        assertTrue(t.ownsHistory("agent.b"))
    }
}
