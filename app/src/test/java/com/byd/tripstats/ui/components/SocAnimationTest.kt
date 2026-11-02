package com.byd.tripstats.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The batteries animate only a SoC change of 2 points or more: a 0.1 % BMS or 1 % panel step is
 * drawn at once, which cost about 57 frames per step when it was animated.
 */
class SocAnimationTest {

    @Test
    fun `BMS and panel steps are drawn at once`() {
        assertFalse(socChangeAnimates(36.9f, 36.8f))
        assertFalse(socChangeAnimates(37f, 36f))
        assertFalse(socChangeAnimates(80.0f, 81.9f))
    }

    @Test
    fun `a larger change animates either way`() {
        assertTrue(socChangeAnimates(0f, 97f))
        assertTrue(socChangeAnimates(36f, 38f))
        assertTrue(socChangeAnimates(97f, 0f))
    }
}
