package com.hozinking.cerpen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit test ReadingTracker (v1.1.30): state machine murni, waktu di-inject. */
class ReadingTrackerTest {

    /** Jam palsu yang bisa dimajukan per detik. */
    private class FakeClock(var t: Long = 0L) {
        fun now(): Long = t
        fun advanceSeconds(s: Long) { t += s * 1000L }
        fun advanceMs(ms: Long) { t += ms }
    }

    private fun tracker(clock: FakeClock) = ReadingTracker(nowMs = { clock.now() })

    /** Kondisi standar: terlihat + aktif + sudah scroll. */
    private fun ready(clock: FakeClock): ReadingTracker {
        val tr = tracker(clock)
        tr.noteVisible(true)
        tr.noteActive()
        tr.noteScrolled(100)
        return tr
    }

    @Test
    fun countsSecondOnlyWhenVisibleActiveAndScrolled() {
        val clock = FakeClock()
        val tr = tracker(clock)
        // Belum terlihat -> tidak dihitung.
        assertFalse(tr.tick())
        tr.noteVisible(true)
        // Belum scroll -> tidak dihitung.
        assertFalse(tr.tick())
        tr.noteScrolled(50)
        // Terlihat + scroll, tapi aktivitas sudah >15 dtk lalu.
        clock.advanceMs(16_000)
        assertFalse(tr.tick())
        // Aktivitas baru -> dihitung.
        tr.noteActive()
        assertTrue(tr.tick())
        assertEquals(1, tr.pendingSeconds)
    }

    @Test
    fun tickEverySecondAccumulates() {
        val clock = FakeClock()
        val tr = ready(clock)
        repeat(10) {
            clock.advanceSeconds(1)
            tr.noteActive()
            assertTrue(tr.tick())
        }
        assertEquals(10, tr.pendingSeconds)
    }

    @Test
    fun flushDueAtThirtySeconds() {
        val clock = FakeClock()
        val tr = ready(clock)
        assertFalse(tr.flushDue())
        repeat(30) {
            clock.advanceSeconds(1)
            tr.noteActive()
            tr.tick()
        }
        assertTrue(tr.flushDue())
        assertEquals(30, tr.takeFlush())
    }

    @Test
    fun flushCapsAt120Seconds() {
        val clock = FakeClock()
        val tr = ready(clock)
        repeat(200) {
            clock.advanceSeconds(1)
            tr.noteActive()
            tr.tick()
        }
        assertEquals(200, tr.pendingSeconds)
        assertEquals(120, tr.takeFlush())
        tr.confirmFlushed(120)
        assertEquals(80, tr.pendingSeconds)
    }

    @Test
    fun pendingCapsAt600Seconds() {
        val clock = FakeClock()
        val tr = ready(clock)
        repeat(700) {
            clock.advanceSeconds(1)
            tr.noteActive()
            tr.tick()
        }
        assertEquals(600, tr.pendingSeconds)
    }

    @Test
    fun confirmFlushedNeverGoesNegative() {
        val clock = FakeClock()
        val tr = ready(clock)
        repeat(5) {
            clock.advanceSeconds(1)
            tr.noteActive()
            tr.tick()
        }
        tr.confirmFlushed(999)
        assertEquals(0, tr.pendingSeconds)
    }

    @Test
    fun hiddenStopsCounting() {
        val clock = FakeClock()
        val tr = ready(clock)
        clock.advanceSeconds(1)
        tr.noteActive()
        assertTrue(tr.tick())
        tr.noteVisible(false)
        clock.advanceSeconds(1)
        assertFalse(tr.tick())
        assertEquals(1, tr.pendingSeconds)
    }

    @Test
    fun maxScrollTracksFurthest() {
        val clock = FakeClock()
        val tr = ready(clock)
        tr.noteScrolled(300)
        tr.noteScrolled(150)
        assertEquals(300, tr.maxScroll)
    }

    @Test
    fun resetClearsAll() {
        val clock = FakeClock()
        val tr = ready(clock)
        clock.advanceSeconds(1)
        tr.noteActive()
        tr.tick()
        tr.reset()
        assertEquals(0, tr.pendingSeconds)
        assertFalse(tr.scrolledOnce)
        assertEquals(0, tr.maxScroll)
        assertFalse(tr.tick())
    }
}
