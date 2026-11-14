package com.brahmadeo.supertonic.tts

import android.util.Log
import com.brahmadeo.supertonic.tts.utils.AudioCache
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
 *     That includes a sentence end in the middle of the text it is given.
 *     Measured on kokoro_int8_fp16.litertlm, November 2026. So text is cut here first, into
 *     pieces the task will not cut again, and each piece's own silence is trimmed.
 *  3. Gaps. With the task's silence gone, the gap after each piece is chosen here.
 *
 * Output is 24 kHz mono 16-bit PCM, like the task produces.
 */
object KokoroEngine {
    private const val TAG = "KokoroEngine"
    const val SAMPLE_RATE = 24000
    /** Built for the GPU. Measured on a Pixel 10a (Tensor G4): 21 s of speech in 5.5 s, against 41 s on the CPU. */
    const val GPU_MODEL_FILE = "kokoro_int8_gpu_fp16.litertlm"
    /** CPU build: the fallback when the GPU one is missing or fails to load. Slower than real time on that phone. */
    const val MODEL_FILE = "kokoro_int8_fp16.litertlm"
    const val DEFAULT_VOICE = "am_michael"

    /** Every voice in the Kokoro-82M bundle. First letter: a US, b UK, e Spanish, f French, h Hindi, i Italian,
     *  j Japanese, p Portuguese (BR), z Chinese; second letter: f/m. The English front end only reads English well. */
    val ALL_VOICES = listOf(
        "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica", "af_kore", "af_nicole", "af_nova", "af_river",
        "af_sarah", "af_sky", "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael", "am_onyx",
        "am_puck", "am_santa", "bf_alice", "bf_emma", "bf_isabella", "bf_lily", "bm_daniel", "bm_fable", "bm_george",
        "bm_lewis", "ef_dora", "em_alex", "em_santa", "ff_siwis", "hf_alpha", "hf_beta", "hm_omega", "hm_psi",
        "if_sara", "im_nicola", "jf_alpha", "jf_gongitsune", "jf_nezumi", "jf_tebukuro", "jm_kumo", "pf_dora",
        "pm_alex", "pm_santa", "zf_xiaobei", "zf_xiaoni", "zf_xiaoxiao", "zf_xiaoyi", "zm_yunjian", "zm_yunxi",
        "zm_yunxia", "zm_yunyang",
    )

    /** "am_michael" -> "US male · Michael": sorts by accent and sex, then name. */
    fun voiceLabel(id: String): String {
        val lang = mapOf('a' to "US", 'b' to "UK", 'e' to "Spanish", 'f' to "French", 'h' to "Hindi", 'i' to "Italian",
            'j' to "Japanese", 'p' to "Portuguese", 'z' to "Chinese")[id[0]] ?: id.take(1)
        val sex = if (id[1] == 'f') "female" else "male"
        val name = id.substringAfter('_').replaceFirstChar { it.uppercase() }
        return "$lang $sex · $name"
    }

    /** The picker's map for this engine: label -> style file name, in the app's existing convention. */
    fun voiceChoices(): Map<String, String> = ALL_VOICES.associate { voiceLabel(it) to "$it.json" }

    /** Raw characters per piece, markup included. The task force-cuts near 90. */
    private const val PIECE_LIMIT = 80
    private const val PIECE_SOFT_MIN = 45

    /** Long-bucket bundles (tools/export, 512 phoneme tokens) for the direct LiteRT-LM binding. */
    const val LONG_MODEL_GPU = "kokoro_512_gpu.litertlm"
    const val LONG_MODEL_CPU = "kokoro_512_cpu.litertlm"
    /** Raw characters per run on the long-bucket path: 512 tokens is about 380 characters; keep a margin. */
    private const val LONG_PIECE_LIMIT = 340
    private const val NATIVE_CHUNK_CHARS = 400          // the runtime's own limit, set above ours so it never cuts

    private var native = 0L
    private var nativeModel: String? = null
    private var nativeVoice: String? = null
    const val GAP_COMMA_DEFAULT = 0.16f
    const val GAP_CLAUSE_DEFAULT = 0.30f
    const val GAP_SENTENCE_DEFAULT = 0.45f
    const val PREFS = "SupertonicPrefs"
    const val KEY_GAP_COMMA = "kokoro_gap_comma"
    const val KEY_GAP_CLAUSE = "kokoro_gap_clause"
    const val KEY_GAP_SENTENCE = "kokoro_gap_sentence"
    private const val KEY_LAST_RATE = "kokoro_last_rate"
    @Volatile private var appContext: android.content.Context? = null
    /** The speaking rate last asked for. The task takes its rate at load time, so loading at this rate avoids a second load. */
    @Volatile var preferredRate = 1.0f
        private set

    /** Silence, in seconds, after a comma / after a semicolon, colon or dash / between sentences. Set from the sliders. */
    @Volatile var gapComma = GAP_COMMA_DEFAULT
    @Volatile var gapClause = GAP_CLAUSE_DEFAULT
    @Volatile var gapSentence = GAP_SENTENCE_DEFAULT

    /** Read the saved gaps. Called wherever the app loads its lexicon, so the speech service has them too. */
    fun loadGaps(context: android.content.Context) {
        val p = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        gapComma = p.getFloat(KEY_GAP_COMMA, GAP_COMMA_DEFAULT)
        gapClause = p.getFloat(KEY_GAP_CLAUSE, GAP_CLAUSE_DEFAULT)
        gapSentence = p.getFloat(KEY_GAP_SENTENCE, GAP_SENTENCE_DEFAULT)
        if (appContext == null) { appContext = context.applicationContext; preferredRate = p.getFloat(KEY_LAST_RATE, 1.0f); AudioCache.init(context) }
    }
    private const val TRIM_THRESHOLD = 0.012f
    private const val TRIM_KEEP_S = 0.04f

    private var tts: TextToSpeech? = null
    private var loadedModel: String? = null
    private var loadedRate = 1.0f
    private var voices: Set<String> = ALL_VOICES.toSet()
    /** True when the MediaPipe task was not created at initialize() because the direct path will serve; it is made on demand. */
    @Volatile private var taskDeferred = false

    /** The app keeps each model version in `<files>/<version>/onnx`; the Kokoro bundle sits beside that folder. */
    fun modelFileFor(onnxDir: String): File {
        val dir = File(onnxDir).parentFile ?: File(onnxDir)
        val gpu = File(dir, GPU_MODEL_FILE)
        return if (gpu.exists() && gpu.length() > 0 && !gpuFailed) gpu else File(dir, MODEL_FILE)
    }

    /** Set when the GPU build would not load, so the CPU build is used for the rest of this run. */
    @Volatile private var gpuFailed = false

    fun isKokoroModelDir(onnxDir: String): Boolean = modelFileFor(onnxDir).let { it.exists() && it.length() > 0 }

    @Synchronized
    fun isInitialized(onnxDir: String): Boolean = (tts != null || taskDeferred) && loadedModel == modelFileFor(onnxDir).absolutePath

    @Synchronized
    fun initialize(onnxDir: String, speakingRate: Float = preferredRate): Boolean {
        val model = modelFileFor(onnxDir)
        if ((tts != null || taskDeferred) && loadedModel == model.absolutePath && abs(loadedRate - speakingRate) < 0.01f) return true
        release()
        if (abs(speakingRate - 1.0f) < 0.01f && KokoroNative.available && longModelFor(model.absolutePath) != null) {
            // The direct path will do the work; the MediaPipe task (2 s, ~200 MB) is only made if ever needed.
            loadedModel = model.absolutePath; loadedRate = speakingRate; taskDeferred = true
            Log.i(TAG, "Kokoro: direct path available, MediaPipe task deferred")
            return true
        }
        return createTask(model, speakingRate, onnxDir)
    }

    @Synchronized
    private fun createTask(model: File, speakingRate: Float, onnxDir: String): Boolean {
        val t0 = android.os.SystemClock.elapsedRealtime()
        return try {
            val cache = File(model.parentFile, "cache").apply { mkdirs() }
            val options = TextToSpeechOptions(
                baseOptions = BaseOptions.builder()
                    .setModelAssetPath(model.absolutePath)
                    .setDelegate(if (model.name == GPU_MODEL_FILE) Delegate.GPU else Delegate.CPU)
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
            taskDeferred = false
            Log.i(TAG, "Kokoro ready: ${model.name} delegate=${if (model.name == GPU_MODEL_FILE) "GPU" else "CPU"}, ${voices.size} voices, rate $speakingRate, " +
                "load ${android.os.SystemClock.elapsedRealtime() - t0} ms, native heap ${android.os.Debug.getNativeHeapAllocatedSize() / 1048576} MB")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Kokoro initialisation failed for ${model.name}: ${e.message}", e)
            tts = null; loadedModel = null
            if (model.name == GPU_MODEL_FILE && !gpuFailed) {
                gpuFailed = true                       // try once more with the CPU build, if it is there
                if (isKokoroModelDir(onnxDir)) return initialize(onnxDir, speakingRate)
            }
            false
        }
    }

    @Synchronized
    fun release() {
        try { tts?.close() } catch (_: Throwable) {}
        tts = null
        loadedModel = null
        taskDeferred = false
        releaseNative()
    }

    private fun releaseNative() {
        if (native != 0L) { try { KokoroNative.nativeDestroy(native) } catch (_: Throwable) {} }
        native = 0L; nativeModel = null; nativeVoice = null
    }

    /** The long-bucket bundle beside the stock one, GPU build first, or null. */
    fun longModelFor(modelPath: String): File? {
        val dir = File(modelPath).parentFile ?: return null
        return listOf(LONG_MODEL_GPU, LONG_MODEL_CPU).map { File(dir, it) }.firstOrNull { it.exists() && it.length() > 0 }
    }

    /** True when whole sentences can go through the direct binding: library packaged, long model present, speed 1.0. */
    fun longPathAvailable(modelPath: String?, speed: Float): Boolean =
        modelPath != null && abs(speed - 1.0f) < 0.01f && KokoroNative.available && longModelFor(modelPath) != null

    /** Create (or re-create for a new voice) the direct engine. Returns false if it could not be made. */
    @Synchronized
    private fun ensureNative(model: File, voice: String): Boolean {
        if (native != 0L && nativeModel == model.absolutePath && nativeVoice == voice) return true
        releaseNative()
        val cache = File(model.parentFile, "cache_native").apply { mkdirs() }
        val backend = if (model.name == LONG_MODEL_GPU) KokoroNative.BACKEND_GPU else KokoroNative.BACKEND_CPU
        val t0 = android.os.SystemClock.elapsedRealtime()
        native = try {
            KokoroNative.nativeCreate(model.absolutePath, backend, cache.absolutePath, "en-US", voice, NATIVE_CHUNK_CHARS, true, 4)
        } catch (e: Throwable) { Log.e(TAG, "native create threw: ${e.message}"); 0L }
        if (native == 0L) { Log.e(TAG, "direct engine failed for ${model.name}; using the MediaPipe task"); return false }
        nativeModel = model.absolutePath; nativeVoice = voice
        Log.i(TAG, "direct engine ready: ${model.name} backend=$backend voice=$voice load ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        return true
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
        val out = ArrayList<ByteArray>()
        synthesizeStreaming(text, stylePath, speed) { out.add(it) }
        if (out.isEmpty()) return null
        val all = ByteArray(out.sumOf { it.size }); var at = 0
        for (b in out) { System.arraycopy(b, 0, all, at, b.size); at += b.size }
        return all
    }

    /**
     * Like [synthesize], but each piece (about one clause, 1-4 s of speech) goes to [onPiece] as soon as the
     * engine has made it, with its gap appended, so playback can start after the first piece instead of the
     * whole text. Returns the number of pieces delivered.
     */
    @Synchronized
    fun synthesizeStreaming(text: String, stylePath: String, speed: Float, onPiece: (ByteArray) -> Unit): Int {
        val modelPath = loadedModel ?: return 0
        // The task takes its speaking rate when it is created, so a new rate means a new task.
        if (abs(speed - preferredRate) >= 0.01f) {
            preferredRate = speed
            appContext?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)?.edit()?.putFloat(KEY_LAST_RATE, speed)?.apply()
        }
        if (abs(speed - loadedRate) >= 0.01f) {
            val onnxDir = File(File(modelPath).parentFile, "onnx").absolutePath
            if (!initialize(onnxDir, speed)) return 0
        }
        val voice = voiceFromStylePath(stylePath)
        val marked = applyPhonemeLexicon(text)
        // Whole sentences per run when the direct binding and a long-bucket model are available.
        val longModel = if (longPathAvailable(modelPath, speed)) longModelFor(modelPath) else null
        val direct = longModel != null && ensureNative(longModel, voice)
        val engine = if (direct) null else (tts ?: run {
            // The task was deferred in favour of the direct path; make it now (speed changed, or the direct engine failed).
            val onnxDir = File(File(modelPath).parentFile, "onnx").absolutePath
            if (!createTask(File(modelPath), loadedRate, onnxDir)) return 0
            tts ?: return 0
        })
        val cut = if (direct) pieces(marked, LONG_PIECE_LIMIT, clauseCuts = false) else pieces(marked)
        // Counts only, never the text: enough to confirm the lexicon and the cutter are doing their job.
        Log.i(TAG, "synth voice=$voice chars=${text.length} lexiconRules=${LexiconManager.phonemeRules().size} " +
            "pinned=${markupSpan.findAll(marked).count()} pieces=${cut.size} longestPiece=${cut.maxOfOrNull { it.first.length } ?: 0}")
        var total = 0; var delivered = 0; var cached = 0
        val started = android.os.SystemClock.elapsedRealtime()
        val modelName = if (direct) longModel!!.name else File(modelPath).name
        for ((piece, gap) in cut) {
            val key = AudioCache.key(modelName, voice, loadedRate, piece)
            var speech: ByteArray? = AudioCache.get(key)          // trimmed PCM without the gap
            if (speech != null) cached++ else {
                val tp = android.os.SystemClock.elapsedRealtime()
                val samples = try {
                    if (direct) KokoroNative.nativeSynthesize(native, piece)
                        ?: throw IllegalStateException(KokoroNative.nativeLastError(native))
                    else engine!!.synthesize(piece, SynthesisOptions(voiceId = voice)).samples
                } catch (e: Throwable) {
                    Log.e(TAG, "Synthesis failed for a piece of ${piece.length} characters: ${e.message}")
                    continue
                }
                Log.i(TAG, "piece chars=${piece.length} ms=${android.os.SystemClock.elapsedRealtime() - tp} audio=${"%.2f".format(samples.size / SAMPLE_RATE.toFloat())} s")
                val trimmed = trim(samples)
                if (trimmed.isEmpty()) continue
                val b = ByteBuffer.allocate(trimmed.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (s in trimmed) b.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                speech = b.array()
                AudioCache.put(key, speech)
            }
            val gapBytes = (gap * SAMPLE_RATE).toInt().coerceAtLeast(0) * 2
            val out = if (gapBytes == 0) speech else speech.copyOf(speech.size + gapBytes)   // zeros = silence
            total += out.size / 2; delivered++
            onPiece(out)
        }
        val ms = android.os.SystemClock.elapsedRealtime() - started
        Log.i(TAG, "synth done in $ms ms for ${"%.1f".format(total / SAMPLE_RATE.toFloat())} s of audio, $delivered pieces, $cached from cache")
        return delivered
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
    private fun clauseGap(c: Char?): Float? = when (c) { ',' -> gapComma; ';', ':', '—' -> gapClause; else -> null }
    private val abbreviations = setOf("mr.", "mrs.", "ms.", "dr.", "st.", "sr.", "jr.", "prof.", "gen.", "col.", "capt.",
        "sgt.", "lt.", "rev.", "hon.", "no.", "vs.", "etc.", "e.g.", "i.e.", "u.s.")

    /** True if this token ends a sentence: the caller may pass several short sentences in one call. */
    private fun endsSentence(token: String, next: String): Boolean {
        val bare = token.trimEnd('"', '”', '’', '\'', ')')
        val last = bare.lastOrNull() ?: return false
        if (last != '.' && last != '?' && last != '!' && last != '…') return false
        if (next.isEmpty()) return false                       // the end of the text is handled by the final flush
        if (last == '.' && (bare.lowercase() in abbreviations || bare.length == 2)) return false   // "Mr.", "J."
        val first = next.trimStart('"', '“', '‘', '\'', '(', '[').firstOrNull() ?: return false
        return first.isUpperCase() || first.isDigit() || last != '.'
    }

    private val phraseStarters = ("about above across after against along although among and around as at because before " +
        "behind below beside between but by down during except for from how if in into like near of off on onto or out " +
        "over since so than that through to toward under until up upon what when where whether which while who whose " +
        "why with without").split(" ").toSet()

    /** Cut text into pieces the task will not cut again, each with the gap (seconds) to put after it. */
    fun pieces(marked: String, limit: Int = PIECE_LIMIT, clauseCuts: Boolean = true): List<Pair<String, Float>> {
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
            val gap = clauseGap(last)
            // A sentence end inside the text: cut here ourselves, or the task cuts and keeps a second of silence.
            if (endsSentence(tok, next)) flush(gapSentence)
            else if (gap != null && clauseCuts && (length >= PIECE_SOFT_MIN || length + next.length + 1 > limit)) flush(gap)
            else if (length + next.length + 1 > limit) {
                // Forced cut between words. Not wherever the limit falls (that split "Joe | Mondragón" and was
                // heard as a long gap): back up to the last word a phrase can start with and carry the rest on.
                fun lenTo(q: Int) = cur.take(q).sumOf { it.length } + q - 1
                var j = (1 until cur.size).lastOrNull { cur[it].lowercase() in phraseStarters && lenTo(it) >= 30 } ?: -1
                while (j > 1 && cur[j - 1].lowercase() in phraseStarters && lenTo(j - 1) >= 30) j--
                if (j < 0) {                                  // no such word: at least keep a run of names together
                    j = cur.size
                    fun namey(t: String) = t.isNotEmpty() && (t[0].isUpperCase() || t[0] == '[')
                    if (namey(next)) while (j > 1 && namey(cur[j - 1])) j--
                    if (j <= 1) j = cur.size
                }
                val rest = cur.subList(j, cur.size).toList()
                while (cur.size > j) cur.removeAt(cur.size - 1)
                flush(0f)
                cur.addAll(rest)
            }
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
