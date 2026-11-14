package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Cache of synthesised speech, one file per piece of text, so nothing is rendered twice.
 *
 * Key: the exact text sent to the engine (pronunciation markup included), the voice, the speaking rate and
 * the model file. Pause gaps are not part of the key: they are added at playback, so the pause sliders never
 * invalidate anything, while a lexicon change does (for the pieces it touches), as it should.
 *
 * Value: raw 24 kHz 16-bit mono PCM, trimmed of leading and trailing silence (48 KB per second of speech).
 *
 * Size is capped by the `kokoro_cache_mb` preference (default 1024 MB, about 6 hours of speech); when the cap
 * is exceeded the least recently used files go first. A read touches the file's modification time so it counts
 * as recent. Everything lives in the app's cache directory, so Android may also clear it when space runs low.
 */
object AudioCache {
    private const val TAG = "AudioCache"
    const val PREFS = "SupertonicPrefs"
    const val KEY_CACHE_MB = "kokoro_cache_mb"
    const val DEFAULT_MB = 1024
    private const val DIR = "tts_cache"

    @Volatile private var dir: File? = null
    @Volatile var limitBytes: Long = DEFAULT_MB * 1024L * 1024L
        private set
    @Volatile private var usedBytes: Long = -1
    @Volatile var hits = 0L; private set
    @Volatile var misses = 0L; private set

    fun init(context: Context) {
        val c = context.applicationContext
        dir = File(c.cacheDir, DIR).apply { mkdirs() }
        limitBytes = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CACHE_MB, DEFAULT_MB) * 1024L * 1024L
    }

    fun setLimitMb(context: Context, mb: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_CACHE_MB, mb).apply()
        limitBytes = mb * 1024L * 1024L
        Thread { evict() }.start()
    }

    fun key(model: String, voice: String, rate: Float, piece: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("$model|$voice|${"%.3f".format(java.util.Locale.US, rate)}|".toByteArray())
        md.update(piece.toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** The cached PCM for this key, or null. */
    fun get(key: String): ByteArray? {
        val f = File(dir ?: return null, "$key.pcm")
        if (!f.isFile) { misses++; return null }
        return try {
            val b = f.readBytes()
            f.setLastModified(System.currentTimeMillis())
            hits++; b
        } catch (e: Exception) { misses++; null }
    }

    fun put(key: String, pcm: ByteArray) {
        val d = dir ?: return
        try {
            val tmp = File(d, "$key.tmp"); tmp.writeBytes(pcm)
            val f = File(d, "$key.pcm"); tmp.renameTo(f)
            if (usedBytes >= 0) usedBytes += pcm.size
            if (usedBytes < 0 || usedBytes > limitBytes) evict()
        } catch (e: Exception) { Log.w(TAG, "Could not cache a piece: ${e.message}") }
    }

    /** Bytes in use, counted from disk the first time and kept current after that. */
    fun usedBytes(): Long {
        if (usedBytes < 0) usedBytes = (dir?.listFiles() ?: emptyArray()).filter { it.name.endsWith(".pcm") }.sumOf { it.length() }
        return usedBytes
    }

    fun clear() {
        dir?.listFiles()?.forEach { it.delete() }
        usedBytes = 0
    }

    /** Delete least recently used files until the cache is under its limit. */
    @Synchronized
    private fun evict() {
        val d = dir ?: return
        val files = (d.listFiles() ?: return).filter { it.name.endsWith(".pcm") }
        var total = files.sumOf { it.length() }
        if (total <= limitBytes) { usedBytes = total; return }
        var removed = 0
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= limitBytes * 0.9) break          // leave headroom so this does not run on every write
            total -= f.length(); if (f.delete()) removed++
        }
        usedBytes = total
        Log.i(TAG, "evicted $removed files, ${total / 1048576} MB in use")
    }
}
