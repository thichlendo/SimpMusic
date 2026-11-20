package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Media3 [AudioProcessor] for real-time Karaoke vocal reduction on PCM 16-bit Stereo streams.
 *
 * Splits each channel with a 1st-order ~220 Hz low-pass filter to preserve bass/kick (`lowL`/`lowR`),
 * removes the center-panned mid/high vocal signal (`centerMid = 0.5f * (midL + midR)`) while keeping
 * stereo accompaniment (`sideL`, `sideR`), and blends back `guideVocalLevel * centerMid`.
 */
@UnstableApi
class KaraokeAudioProcessor(
    private val enabledProvider: (() -> Boolean)? = null,
    private val guideVocalProvider: (() -> Float)? = null,
) : BaseAudioProcessor() {
    @Volatile
    var isKaraokeEnabled: Boolean = false

    @Volatile
    var guideVocalLevel: Float = 0.0f

    private var lpfAlpha = 0f
    private var lowL = 0f
    private var lowR = 0f
    private var wasEnabled = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            inputAudioFormat.channelCount != 2 ||
            inputAudioFormat.sampleRate <= 0
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        lpfAlpha = (1.0 - exp(-2.0 * PI * CUTOFF_HZ / inputAudioFormat.sampleRate)).toFloat().coerceIn(0f, 1f)
        lowL = 0f
        lowR = 0f
        return inputAudioFormat
    }

    // isActive() is intentionally NOT overridden so BaseAudioProcessor keeps this processor active
    // for PCM 16-bit Stereo regardless of runtime toggle state and drops unsupported/mono formats.

    override fun onFlush() {
        lowL = 0f
        lowR = 0f
    }

    override fun onReset() {
        lowL = 0f
        lowR = 0f
        lpfAlpha = 0f
        wasEnabled = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val output = replaceOutputBuffer(remaining)
        val enabled = enabledProvider?.invoke() ?: isKaraokeEnabled
        if (!enabled) {
            if (wasEnabled) {
                lowL = 0f
                lowR = 0f
                wasEnabled = false
            }
            output.put(inputBuffer)
            output.flip()
            return
        }

        wasEnabled = true
        val rawGuide = guideVocalProvider?.invoke() ?: guideVocalLevel
        val guide = if (rawGuide.isNaN()) 0f else rawGuide.coerceIn(0f, MAX_GUIDE_VOCAL_LEVEL)
        val alpha = lpfAlpha

        inputBuffer.order(ByteOrder.nativeOrder())
        while (inputBuffer.remaining() >= STEREO_PCM16_FRAME_BYTES) {
            val l = inputBuffer.short.toFloat()
            val r = inputBuffer.short.toFloat()

            lowL += alpha * (l - lowL)
            lowR += alpha * (r - lowR)

            val midL = l - lowL
            val midR = r - lowR

            val centerMid = 0.5f * (midL + midR)
            val sideL = midL - centerMid
            val sideR = midR - centerMid

            val guideMid = guide * centerMid
            val outL = (lowL + sideL + guideMid).coerceIn(PCM16_MIN, PCM16_MAX).roundToInt().toShort()
            val outR = (lowR + sideR + guideMid).coerceIn(PCM16_MIN, PCM16_MAX).roundToInt().toShort()

            output.putShort(outL)
            output.putShort(outR)
        }
        while (inputBuffer.hasRemaining()) {
            output.put(inputBuffer.get())
        }
        output.flip()
    }

    private companion object {
        const val CUTOFF_HZ = 220.0
        const val MAX_GUIDE_VOCAL_LEVEL = 0.4f
        const val PCM16_MIN = -32_768f
        const val PCM16_MAX = 32_767f
        const val STEREO_PCM16_FRAME_BYTES = 4
    }
}
