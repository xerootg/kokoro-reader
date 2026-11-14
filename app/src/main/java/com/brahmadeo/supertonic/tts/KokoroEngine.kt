package com.brahmadeo.supertonic.tts

import android.util.Log
import com.brahmadeo.supertonic.tts.utils.LexiconManager
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * Kokoro-82M through Google's MediaPipe text-to-speech task, as an alternative engine behind
 * [SupertonicTTS]. The task brings its own text front end (normalisation and text-to-phoneme),
 * so this class only has to do three things the task does not:
 *
 *  1. Pronunciation. A lexicon rule whose replacement is written between slashes, e.g.
 *     `Mondragón -> /mondɾaɣˈon/`, is turned into the task's inline markup
 *     `[Mondragón](/mondɾaɣˈon/)`, which pins those exact Kokoro phonemes.
 *  2. Pauses. The task keeps about a second of silence wherever it cuts text, and it cuts at
 *     `. ? ! ;`, at double quotation marks and after roughly 90 raw characters (markup counts).
 *     Measured on kokoro_int8_fp16.litertlm, November 2026. So text is cut here first, into
 *     pieces the task will not cut again, and each piece's own silence is trimmed.
 *  3. Gaps. With the task's silence gone, the gap after each piece is chosen here.
 *
 * Output is 24 kHz mono 16-bit PCM, like the task produces.
 */
object KokoroEngine {
    private const val TAG = "KokoroEngine"
    const val SAMPLE_RATE = 24000
    const val MODEL_FILE = "kokoro_int8_fp16.litertlm"
    const val DEFAULT_VOICE = "am_michael"

    /** Raw characters per piece, markup included. The task force-cuts near 90. */
    private const val PIECE_LIMIT = 80
    private const val PIECE_SOFT_MIN = 45
    private const val GAP_COMMA_S = 0.16f
    private const val GAP_CLAUSE_S = 0.30f
    private const val GAP_FORCED_S = 0.05f
    private const val TRIM_THRESHOLD = 0.012f
    private const val TRIM_KEEP_S = 0.04f

    private var tts: TextToSpeech? = null
    private var loadedModel: String? = null
    private var loadedRate = 1.0f
    private var voices: Set<String> = emptySet()

    /** The app keeps each model version in `<files>/<version>/onnx`; the Kokoro bundle sits beside that folder. */
    fun modelFileFor(onnxDir: String): File = File(File(onnxDir).parentFile ?: File(onnxDir), MODEL_FILE)

    fun isKokoroModelDir(onnxDir: String): Boolean = modelFileFor(onnxDir).let { it.exists() && it.length() > 0 }

    @Synchronized
    fun isInitialized(onnxDir: String): Boolean = tts != null && loadedModel == modelFileFor(onnxDir).absolutePath

    @Synchronized
    fun initialize(onnxDir: String, speakingRate: Float = 1.0f): Boolean {
        val model = modelFileFor(onnxDir)
        if (tts != null && loadedModel == model.absolutePath && abs(loadedRate - speakingRate) < 0.01f) return true
        release()
        return try {
            val cache = File(model.parentFile, "cache").apply { mkdirs() }
            val options = TextToSpeechOptions(
                baseOptions = BaseOptions.builder()
                    .setModelAssetPath(model.absolutePath)
                    .setDelegate(Delegate.CPU)
                    .build(),
                defaultVoiceId = DEFAULT_VOICE,
                speakingRate = speakingRate,
                cacheDirectory = cache.absolutePath,
            )
            val created = TextToSpeech.create(options)
            voices = created.listVoices().map { it.voiceId }.toSet()
            tts = created
            loadedModel = model.absolutePath
            loadedRate = speakingRate
            Log.i(TAG, "Kokoro ready: ${model.name}, ${voices.size} voices, rate $speakingRate")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Kokoro initialisation failed: ${e.message}", e)
            tts = null; loadedModel = null
            false
        }
    }

    @Synchronized
    fun release() {
        try { tts?.close() } catch (_: Throwable) {}
        tts = null
        loadedModel = null
    }

    fun voiceIds(): Set<String> = voices

    /** The app passes a voice as a path to `<voice>.json` (optionally `a;b;alpha` for a mix, which Kokoro ignores). */
    fun voiceFromStylePath(stylePath: String): String {
        val name = File(stylePath.substringBefore(';')).name.removeSuffix(".json")
        return if (name in voices) name else DEFAULT_VOICE
    }

    /**
     * Synthesise one sentence (or any short text). Returns 16-bit little-endian PCM at [SAMPLE_RATE],
     * or null if the engine is not ready or produced nothing.
     */
    @Synchronized
    fun synthesize(text: String, stylePath: String, speed: Float): ByteArray? {
        val modelPath = loadedModel ?: return null
        // The task takes its speaking rate when it is created, so a new rate means a new task.
        if (abs(speed - loadedRate) >= 0.01f) {
            val onnxDir = File(File(modelPath).parentFile, "onnx").absolutePath
            if (!initialize(onnxDir, speed)) return null
        }
        val engine = tts ?: return null
        val voice = voiceFromStylePath(stylePath)
        val marked = applyPhonemeLexicon(text)
        val out = ArrayList<FloatArray>()
        var total = 0
        for ((piece, gap) in pieces(marked)) {
            val samples = try {
                engine.synthesize(piece, SynthesisOptions(voiceId = voice)).samples
            } catch (e: Throwable) {
                Log.e(TAG, "Synthesis failed for a piece of ${piece.length} characters: ${e.message}")
                continue
            }
            val trimmed = trim(samples)
            if (trimmed.isEmpty()) continue
            out.add(trimmed); total += trimmed.size
            val gapSamples = (gap * SAMPLE_RATE).toInt()
            if (gapSamples > 0) { out.add(FloatArray(gapSamples)); total += gapSamples }
        }
        if (total == 0) return null
        val pcm = ByteBuffer.allocate(total * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (chunk in out) for (s in chunk) pcm.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return pcm.array()
    }

    // --- pronunciation -------------------------------------------------------------------------

    private val markupSpan = Regex("""\[[^\]]*]\(/[^/]*/\)""")

    /**
     * Wrap lexicon terms that carry phonemes as inline markup. Multi-word terms need one phoneme
     * group per word (`que no -> /ke no/`); a rule whose counts do not match is skipped.
     */
    fun applyPhonemeLexicon(text: String): String {
        val rules = LexiconManager.phonemeRules()
        if (rules.isEmpty()) return text
        var result = text
        // Longest terms first, so a phrase wins over the words inside it.
        for (rule in rules.sortedByDescending { it.term.length }) {
            val phonemeGroups = rule.replacement.trim('/').trim().split(Regex("\\s+"))
            val flags = if (rule.ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
            val pattern = try {
                if (rule.isRegex) Pattern.compile(rule.term, flags)
                else Pattern.compile("(?<![\\p{L}\\p{N}])${Pattern.quote(rule.term)}(?![\\p{L}\\p{N}])", flags)
            } catch (e: Exception) { continue }
            val protectedRanges = markupSpan.findAll(result).map { it.range }.toList()
            val m = pattern.matcher(result)
            val sb = StringBuilder(); var last = 0
            while (m.find()) {
                if (protectedRanges.any { m.start() <= it.last && m.end() - 1 >= it.first }) continue   // already marked up
                val words = m.group().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (words.size != phonemeGroups.size) continue
                sb.append(result, last, m.start())
                sb.append(words.indices.joinToString(" ") { i -> "[${words[i].trim(',')}](/${phonemeGroups[i]}/)" + if (words[i].endsWith(",")) "," else "" })
                last = m.end()
            }
            if (last > 0) { sb.append(result, last, result.length); result = sb.toString() }
        }
        return result
    }

    // --- pieces and gaps -----------------------------------------------------------------------

    private val tokenPattern = Regex("""\[[^\]]*]\(/[^/]*/\)\S*|\S+""")
    private val clauseGap = mapOf(',' to GAP_COMMA_S, ';' to GAP_CLAUSE_S, ':' to GAP_CLAUSE_S, '—' to GAP_CLAUSE_S)

    /** Cut text into pieces the task will not cut again, each with the gap (seconds) to put after it. */
    fun pieces(marked: String): List<Pair<String, Float>> {
        val tokens = tokenPattern.findAll(marked).map { it.value }.toList()
        val out = ArrayList<Pair<String, Float>>()
        val cur = ArrayList<String>()
        fun flush(gap: Float) {
            if (cur.isEmpty()) return
            var t = cur.joinToString(" ")
            t = t.replace(Regex("\\s*—\\s*"), ", ")          // no cut at an inner dash
            t = t.replace(';', ',')                           // a semicolon costs about a second
            t = t.replace(Regex("[\"“”]"), "")                // so do quotation marks, and they are not spoken
            t = t.replace(Regex(",\\s*,"), ",").trim(' ', ',')
            if (t.any { it.isLetterOrDigit() }) out.add(t to gap)
            cur.clear()
        }
        for ((k, tok) in tokens.withIndex()) {
            cur.add(tok)
            val length = cur.sumOf { it.length } + cur.size - 1
            val last = if (tok == "—") '—' else tok.trimEnd('"', '”', '’', '\'', ')').lastOrNull()
            val next = tokens.getOrNull(k + 1) ?: ""
            val gap = clauseGap[last]
            if (gap != null && (length >= PIECE_SOFT_MIN || length + next.length + 1 > PIECE_LIMIT)) flush(gap)
            else if (length + next.length + 1 > PIECE_LIMIT) flush(GAP_FORCED_S)
        }
        flush(0f)
        if (out.isNotEmpty()) out[out.size - 1] = out.last().first to 0f
        return out
    }

    /** Remove the silence the model puts around an utterance, keeping a short margin. */
    private fun trim(w: FloatArray): FloatArray {
        var first = -1; var lastIdx = -1
        for (i in w.indices) if (abs(w[i]) > TRIM_THRESHOLD) { if (first < 0) first = i; lastIdx = i }
        if (first < 0) return w
        val keep = (TRIM_KEEP_S * SAMPLE_RATE).toInt()
        val a = maxOf(0, first - keep); val b = minOf(w.size, lastIdx + keep)
        return w.copyOfRange(a, b)
    }
}
