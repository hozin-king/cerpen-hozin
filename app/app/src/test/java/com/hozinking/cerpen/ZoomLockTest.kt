package com.hozinking.cerpen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uji kunci zoom viewer komik (v1.1.39): skala terkunci di fit,
 *  pinch (2+ jari) ditelan, tap/scroll 1 jari diteruskan. */
class ZoomLockTest {

    @Test
    fun `skala terkunci di fit berapa pun yang diminta`() {
        assertEquals(1f, ZoomLock.applyScale(2.5f), 0f)
        assertEquals(1f, ZoomLock.applyScale(0.5f), 0f)
        assertEquals(1f, ZoomLock.applyScale(1f), 0f)
        assertEquals(ZoomLock.LOCKED_SCALE, ZoomLock.applyScale(10f), 0f)
    }

    @Test
    fun `sentuhan 1 jari diteruskan`() {
        assertFalse(ZoomLock.shouldConsumeTouch(1))
        assertFalse(ZoomLock.shouldConsumeTouch(0))
    }

    @Test
    fun `pinch 2 jari atau lebih ditelan`() {
        assertTrue(ZoomLock.shouldConsumeTouch(2))
        assertTrue(ZoomLock.shouldConsumeTouch(3))
        assertTrue(ZoomLock.shouldConsumeTouch(5))
    }
}
