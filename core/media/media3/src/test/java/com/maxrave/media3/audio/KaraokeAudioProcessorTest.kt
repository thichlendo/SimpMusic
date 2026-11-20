package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class KaraokeAudioProcessorTest {
    @Test
    fun bypassReturnsInputSamplesUnchanged() {
        val processor = newConfiguredProcessor(sampleRate = 48_000, channels = 2)
        processor.isKaraokeEnabled = false

        val input = shortArrayOf(1200, -3400, 15000, -15000, 0, 42)
        val output = processBuffer(processor, input)

        assertArrayEquals(input, output)
    }

    @Test
    fun monoStreamIsRejectedAsNotSet() {
        val processor = KaraokeAudioProcessor()
        val format = AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT)
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, processor.configure(format))
        assertFalse(processor.isActive)
    }

    @Test
    fun centered1kHzVocalIsRemovedAndGuideVocalRestoresBlend() {
        val frames = 4_800
        val input = sineStereo(frames, sampleRate = 48_000, freqHz = 1_000.0, ampL = 10_000.0, ampR = 10_000.0)
        val inputRms = rms(input)

        val removedProc = newConfiguredProcessor(sampleRate = 48_000, channels = 2)
        removedProc.isKaraokeEnabled = true
        removedProc.guideVocalLevel = 0.0f
        val removed = processBuffer(removedProc, input)
        assertTrue(
            "Expected centered 1kHz vocal to drop below 5% RMS, got ${rms(removed) / inputRms}",
            rms(removed) < inputRms * 0.05,
        )

        val guideProc = newConfiguredProcessor(sampleRate = 48_000, channels = 2)
        guideProc.isKaraokeEnabled = true
        guideProc.guideVocalLevel = 0.3f
        val guided = processBuffer(guideProc, input)
        val ratio = rms(guided) / inputRms
        assertTrue("Expected ~0.30 guide vocal RMS ratio, got $ratio", ratio in 0.25..0.35)
    }

    @Test
    fun centered60HzBassIsPreserved() {
        val frames = 9_600
        val input = sineStereo(frames, sampleRate = 48_000, freqHz = 60.0, ampL = 10_000.0, ampR = 10_000.0)

        val processor = newConfiguredProcessor(sampleRate = 48_000, channels = 2)
        processor.isKaraokeEnabled = true
        processor.guideVocalLevel = 0.0f
        val output = processBuffer(processor, input)

        // Skip initial 100ms filter warm-up before comparing RMS.
        val skip = 4_800 * 2
        val inRms = rms(input, skip)
        val outRms = rms(output, skip)
        assertTrue("Expected 60Hz bass >85% RMS, got ${outRms / inRms}", outRms > inRms * 0.85)
    }

    @Test
    fun stereoSideAccompanimentIsPreserved() {
        val frames = 4_800
        val input = sineStereo(frames, sampleRate = 48_000, freqHz = 1_000.0, ampL = 10_000.0, ampR = -10_000.0)

        val processor = newConfiguredProcessor(sampleRate = 48_000, channels = 2)
        processor.isKaraokeEnabled = true
        processor.guideVocalLevel = 0.0f
        val output = processBuffer(processor, input)

        val ratio = rms(output) / rms(input)
        assertTrue("Expected stereo side ~100% RMS, got $ratio", ratio in 0.95..1.05)
    }

    @Test
    fun fullScaleHotSignalClampsWithoutOverflowWrap() {
        val processor = newConfiguredProcessor(sampleRate = 48_000, channels = 2)
        processor.isKaraokeEnabled = true
        processor.guideVocalLevel = 0.4f

        // Alternating extremes in L/R to drive sideL/sideR + lowL past 32767 if unclamped.
        val input = ShortArray(200) { idx -> if (idx % 2 == 0) 32_767 else -32_768 }
        val output = processBuffer(processor, input)

        for (i in 0 until output.size step 2) {
            assertTrue("Left sample should stay positive when clamped: ${output[i]}", output[i] >= 0)
            assertTrue("Right sample should stay negative when clamped: ${output[i + 1]}", output[i + 1] <= 0)
        }
    }

    private fun newConfiguredProcessor(
        sampleRate: Int,
        channels: Int,
    ): KaraokeAudioProcessor {
        val processor = KaraokeAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT))
        processor.flush()
        return processor
    }

    private fun processBuffer(
        processor: KaraokeAudioProcessor,
        samples: ShortArray,
    ): ShortArray {
        val inBuf = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { inBuf.putShort(it) }
        inBuf.flip()
        processor.queueInput(inBuf)
        val outBuf = processor.output.order(ByteOrder.nativeOrder())
        val out = ShortArray(outBuf.remaining() / 2)
        for (i in out.indices) {
            out[i] = outBuf.short
        }
        return out
    }

    private fun sineStereo(
        frames: Int,
        sampleRate: Int,
        freqHz: Double,
        ampL: Double,
        ampR: Double,
    ): ShortArray =
        ShortArray(frames * 2).also { arr ->
            for (f in 0 until frames) {
                val s = sin(2.0 * PI * freqHz * f / sampleRate)
                arr[f * 2] = (ampL * s).roundToInt().toShort()
                arr[f * 2 + 1] = (ampR * s).roundToInt().toShort()
            }
        }

    private fun rms(
        samples: ShortArray,
        startIndex: Int = 0,
    ): Double {
        var sumSq = 0.0
        val count = samples.size - startIndex
        for (i in startIndex until samples.size) {
            val v = samples[i].toDouble()
            sumSq += v * v
        }
        return sqrt(sumSq / count.coerceAtLeast(1))
    }
}
