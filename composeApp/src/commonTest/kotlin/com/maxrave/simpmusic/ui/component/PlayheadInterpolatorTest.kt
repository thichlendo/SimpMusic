package com.maxrave.simpmusic.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayheadInterpolatorTest {
    @Test
    fun interpolatesAtPlaybackSpeedWithLatencyAndUserOffset() {
        val interpolator = PlayheadInterpolator()
        val t0 = 1_000_000_000L

        assertEquals(950L, interpolator.advance(t0, reportedPosMs = 1_000L, speed = 1.0f, offsetMs = 50L))
        // 16ms later at 1.5x speed -> 1000 + 24 - 50 - 20 = 954ms
        val t1 = t0 + 16_000_000L
        assertEquals(954L, interpolator.advance(t1, reportedPosMs = 1_000L, speed = 1.5f, offsetMs = 70L))
    }

    @Test
    fun monotonicSmoothingIgnoresSmallBackwardJitter() {
        val interpolator = PlayheadInterpolator()
        val t0 = 1_000_000_000L
        val first = interpolator.advance(t0, reportedPosMs = 5_000L, speed = 1.0f, offsetMs = 50L)

        // Small backward report (-30ms, inside SNAP_THRESHOLD_MS) should not step backwards.
        val t1 = t0 + 16_000_000L
        val second = interpolator.advance(t1, reportedPosMs = 4_970L, speed = 1.0f, offsetMs = 50L)
        assertTrue(second >= first, "Expected monotonic advance: $first -> $second")
    }

    @Test
    fun largeSeekForwardOrBackwardSnapsImmediately() {
        val interpolator = PlayheadInterpolator()
        val t0 = 1_000_000_000L
        interpolator.advance(t0, reportedPosMs = 10_000L, speed = 1.0f, offsetMs = 50L)

        val t1 = t0 + 16_000_000L
        assertEquals(49_950L, interpolator.advance(t1, reportedPosMs = 50_000L, speed = 1.0f, offsetMs = 50L))

        val t2 = t1 + 16_000_000L
        assertEquals(1_950L, interpolator.advance(t2, reportedPosMs = 2_000L, speed = 1.0f, offsetMs = 50L))
    }

    @Test
    fun capsExtrapolationWhenPlayerStalls() {
        val interpolator = PlayheadInterpolator()
        val t0 = 1_000_000_000L
        interpolator.advance(t0, reportedPosMs = 1_000L, speed = 1.0f, offsetMs = 0L)

        // Advance several frames without a new position report past MAX_EXTRAPOLATION_MS (250ms).
        var pos = 0L
        for (step in 1..10) {
            pos = interpolator.advance(t0 + step * 50_000_000L, reportedPosMs = 1_000L, speed = 1.0f, offsetMs = 0L)
        }
        assertTrue(pos <= 1_250L, "Expected extrapolation capped at 1250ms, got $pos")
    }
}
