package com.tvassist.data.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one part of the voice path with no Android in it: turning microphone loudness into the 0..1
 * the dots animate on. Worth pinning down because getting the curve wrong is not a crash — it is an
 * animation that sits still while someone talks, which is easy to mistake for the mic not working.
 */
class VoiceLevelTest {

    /** Little-endian PCM16 buffer of one repeated sample value. */
    private fun pcm(sample: Int, samples: Int = 256): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            out[i * 2] = (sample and 0xFF).toByte()
            out[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `silence measures zero`() {
        val buf = pcm(0)
        assertEquals(0f, pcm16Rms(buf, buf.size), 0.001f)
    }

    @Test
    fun `a constant amplitude measures that amplitude`() {
        val buf = pcm(10_000)
        assertEquals(10_000f, pcm16Rms(buf, buf.size), 1f)
    }

    @Test
    fun `negative samples are not mistaken for loud ones`() {
        // Sign matters: reading the high byte unsigned would turn -10000 into something near full
        // scale and peg the animation at maximum on any real waveform, which swings both ways.
        val buf = pcm(-10_000)
        assertEquals(10_000f, pcm16Rms(buf, buf.size), 1f)
    }

    @Test
    fun `only the first length bytes are measured`() {
        // The capture buffer is reused and only partly filled; stale samples past `length` are the
        // previous chunk's and must not count.
        val buf = ByteArray(512)
        val loud = pcm(20_000, 128)
        loud.copyInto(buf)
        val overFilledPortion = pcm16Rms(buf, 256)
        val overWholeBuffer = pcm16Rms(buf, 512)
        assertEquals(20_000f, overFilledPortion, 1f)
        assertTrue("zeroed tail must drag the average down", overWholeBuffer < overFilledPortion)
    }

    @Test
    fun `silence and full scale sit at the ends of the range`() {
        assertEquals(0f, rmsToLevel(0f), 0.001f)
        assertEquals(1f, rmsToLevel(32_768f), 0.001f)
    }

    @Test
    fun `speech at couch distance lands in the visible middle of the range`() {
        // ~-35 dBFS. Under a linear mapping this would be 0.018 — indistinguishable from silence,
        // which is the whole reason the conversion goes through dB.
        val level = rmsToLevel(600f)
        assertTrue("expected a clearly visible level, got $level", level in 0.25f..0.6f)
    }

    @Test
    fun `level rises with loudness`() {
        val quiet = rmsToLevel(200f)
        val talking = rmsToLevel(1_500f)
        val loud = rmsToLevel(8_000f)
        assertTrue("$quiet < $talking < $loud", quiet < talking && talking < loud)
    }

    @Test
    fun `recogniser levels are clamped to the range`() {
        assertEquals(0f, recognizerRmsToLevel(-50f), 0.001f)
        assertEquals(1f, recognizerRmsToLevel(99f), 0.001f)
        assertTrue(recognizerRmsToLevel(4f) in 0.4f..0.6f)
    }
}
