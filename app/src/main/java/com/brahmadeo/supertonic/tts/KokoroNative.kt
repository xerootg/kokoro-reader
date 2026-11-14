package com.brahmadeo.supertonic.tts

import android.util.Log

/**
 * Direct binding to LiteRT-LM's Kokoro text-to-speech (tools/LiteRT-LM/omni/text2speech/jni), used
 * instead of the MediaPipe task when its library is packaged and a long-bucket model is installed.
 *
 * The point of it: the MediaPipe task pins the text chunk at about 100 characters, so every longer
 * sentence is spoken as several utterances. Through this binding the chunk size is ours to set, and
 * with a model exported for 512 phoneme tokens a whole sentence or paragraph is one run.
 *
 * Not available through this path yet: speaking rate (the model has no speed input; MediaPipe
 * time-stretches in its wrapper), so the engine falls back to the MediaPipe task when the user's
 * speed is not 1.0.
 */
object KokoroNative {
    private const val TAG = "KokoroNative"
    const val BACKEND_CPU = 0
    const val BACKEND_GPU = 1

    val available: Boolean by lazy {
        try { System.loadLibrary("kokoro_tts_jni"); true }
        catch (e: Throwable) { Log.i(TAG, "libkokoro_tts_jni not packaged: ${e.message}"); false }
    }

    @JvmStatic external fun nativeCreate(
        modelPath: String, backend: Int, cacheDir: String, language: String, voice: String,
        maxChunkChars: Int, sentenceDelimitersOnly: Boolean, numThreads: Int
    ): Long
    @JvmStatic external fun nativeSynthesize(handle: Long, text: String): FloatArray?
    @JvmStatic external fun nativeSampleRate(handle: Long): Int
    @JvmStatic external fun nativeLastError(handle: Long): String
    @JvmStatic external fun nativeDestroy(handle: Long)
}
