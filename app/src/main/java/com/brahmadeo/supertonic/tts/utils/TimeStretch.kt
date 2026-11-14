package com.brahmadeo.supertonic.tts.utils

import kotlin.math.min

/**
 * Changes the speed of speech without changing its pitch (WSOLA: waveform-similarity overlap-add).
 *
 * Used on the direct LiteRT-LM path, where the engine has no speaking-rate control. Speech is
 * synthesised and cached at normal speed; the stretch is applied at playback, so a speed change never
 * invalidates the cache. Cost: a few milliseconds per second of audio.
 *
 * rate > 1 speeds up (shorter output), rate < 1 slows down. Mono float samples in, same out.
 */
object TimeStretch {
    fun stretch(input: FloatArray, rate: Float, sampleRate: Int): FloatArray {
        if (rate <= 0f || kotlin.math.abs(rate - 1f) < 0.005f || input.size < sampleRate / 10) return input
        val frame = sampleRate * 24 / 1000           // 24 ms analysis frame
        val hopOut = frame / 2                        // 50% overlap in the output
        val hopIn = (hopOut * rate).toInt()           // how far the input advances per output hop
        val search = sampleRate * 6 / 1000            // +-6 ms search for the best-matching waveform
        val outLen = (input.size / rate).toInt() + frame
        val out = FloatArray(outLen)
        val norm = FloatArray(outLen)
        val window = FloatArray(frame) { 0.5f - 0.5f * kotlin.math.cos(2.0 * Math.PI * it / (frame - 1)).toFloat() }

        var inPos = 0; var outPos = 0
        // The first frame is copied as is; later frames are chosen near the nominal input position so
        // that their start lines up with the tail of what was already written.
        var prevEnd = -1                              // input index that follows the previously used frame's hop
        while (outPos + frame <= outLen && inPos + frame <= input.size) {
            var best = inPos
            if (prevEnd >= 0) {
                var bestScore = Float.NEGATIVE_INFINITY
                val lo = maxOf(0, inPos - search); val hi = min(input.size - frame, inPos + search)
                var cand = lo
                while (cand <= hi) {
                    // similarity between the candidate's first half-frame and the natural continuation
                    var score = 0f
                    var i = 0
                    while (i < hopOut) { score += input[cand + i] * input[min(input.size - 1, prevEnd + i)]; i += 2 }
                    if (score > bestScore) { bestScore = score; best = cand }
                    cand += 4
                }
            }
            for (i in 0 until frame) {
                out[outPos + i] += input[best + i] * window[i]
                norm[outPos + i] += window[i]
            }
            prevEnd = best + hopOut
            outPos += hopOut
            inPos += hopIn
        }
        val result = FloatArray(outPos + hopOut.coerceAtMost(outLen - outPos))
        for (i in result.indices) result[i] = if (norm[i] > 1e-3f) out[i] / norm[i] else out[i]
        return result
    }
}
