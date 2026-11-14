package dev.milagro.kokorobench

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.speech.tts.TextToSpeech as SystemTts
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Start with:
 *   adb shell am start -n dev.milagro.kokorobench/.MainActivity \
 *       -e model kokoro_int8_fp16.litertlm -e delegate cpu -e voice am_michael --ei runs 3
 * The model file must already be in the app's files directory (see bench/README.md).
 * Every result line is logged with the tag KOKOROBENCH and mirrored on screen.
 */
class MainActivity : Activity() {
    private lateinit var view: TextView
    private val tag = "KOKOROBENCH"

    // Original test text, about 18 seconds of speech.  Not taken from any book.
    private val paragraph = "The ditch rider walked the bank before sunrise, checking each headgate by hand. " +
        "Water had been promised to the lower fields for three days, and nobody downstream believed it would come. " +
        "By the time the sun cleared the ridge, a thin brown stream was already finding its way between the rows."
    private val markup = "Joe [Mondragón](/mondɾaɣˈon/) and Sheriff [Bernabé](/beɾnaβˈe/) [Montoya](/montˈoʝa/) " +
        "drove out to [Milagro](/milˈaɣɾo/) before the [trucha](/tɾˈuʧa/) stopped biting."

    // Original test text for the "tts" mode: lexicon names, a semicolon and a quoted line.
    private val engineTest = "Joe Mondragón and Sheriff Bernabé Montoya drove out to Milagro before the trucha stopped biting; " +
        "nobody in the plaza looked up. \"Que no,\" said Amarante Córdova, \"that water belongs to the acequia.\""

    /**
     * Speak through Android's standard text-to-speech API using a named engine package, and save the
     * result to tts_out.wav.  This exercises another app's speech service end to end.
     *   adb shell am start -n dev.milagro.kokorobench/.MainActivity -e mode tts -e engine <package>
     */
    private fun systemTts(engine: String, text: String) {
        say("TTS-START engine=$engine chars=${text.length}")
        var tts: SystemTts? = null
        val t0 = System.nanoTime()
        tts = SystemTts(this, { status ->
            if (status != SystemTts.SUCCESS) { say("DONE error=tts-init-status-$status"); return@SystemTts }
            val t = tts ?: return@SystemTts
            say("TTS-READY ms=${(System.nanoTime() - t0) / 1_000_000} defaultEngine=${t.defaultEngine} engines=${t.engines.joinToString { it.name }}")
            t.setLanguage(Locale.US)
            val out = File(filesDir, "tts_out.wav")
            val start = System.nanoTime()
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { say("TTS-SPEAKING") }
                override fun onDone(id: String?) {
                    say("TTS-FILE bytes=${out.length()} synth_ms=${(System.nanoTime() - start) / 1_000_000}")
                    say("DONE ok"); t.shutdown()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { say("DONE error=tts-utterance"); t.shutdown() }
                override fun onError(id: String?, code: Int) { say("DONE error=tts-utterance-$code"); t.shutdown() }
            })
            val r = t.synthesizeToFile(text, Bundle(), out, "bench")
            if (r != SystemTts.SUCCESS) say("DONE error=synthesizeToFile-$r")
        }, engine.ifEmpty { null })
    }

    private fun say(s: String) {
        Log.i(tag, s)
        runOnUiThread { view.append(s + "\n") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply { setPadding(32, 96, 32, 32); textSize = 13f }
        setContentView(ScrollView(this).apply { addView(view) })
        val model = intent.getStringExtra("model") ?: "kokoro_int8_fp16.litertlm"
        val delegate = (intent.getStringExtra("delegate") ?: "cpu").lowercase()
        val voice = intent.getStringExtra("voice") ?: "am_michael"
        val runs = intent.getIntExtra("runs", 3)
        if (intent.getStringExtra("mode") == "tts") {
            systemTts(intent.getStringExtra("engine") ?: "", intent.getStringExtra("text") ?: engineTest)
        } else {
            Thread { bench(model, delegate, voice, runs) }.start()
        }
    }

    private fun bench(model: String, delegate: String, voice: String, runs: Int) {
        val file = File(filesDir, model)
        say("START model=$model delegate=$delegate voice=$voice runs=$runs exists=${file.exists()} size=${file.length()}")
        if (!file.exists()) { say("DONE error=model-missing path=${file.absolutePath}"); return }
        try {
            val t0 = System.nanoTime()
            val options = TextToSpeechOptions(
                baseOptions = BaseOptions.builder()
                    .setModelAssetPath(file.absolutePath)
                    .setDelegate(if (delegate == "gpu") Delegate.GPU else Delegate.CPU)
                    .build(),
                defaultVoiceId = voice,
                speakingRate = 1.0f,
                cacheDirectory = cacheDir.absolutePath,
            )
            val tts = TextToSpeech.create(options)
            say("LOADED ms=${(System.nanoTime() - t0) / 1_000_000}")
            for (i in 0..runs) {                       // run 0 is the warm-up
                val t = System.nanoTime()
                val r = tts.synthesize(paragraph, SynthesisOptions(voiceId = voice))
                val ms = (System.nanoTime() - t) / 1_000_000
                val audio = r.samples.size.toFloat() / r.sampleRateHz
                say("RUN i=$i ${if (i == 0) "warmup" else "timed"} synth_ms=$ms audio_s=${"%.2f".format(audio)} rtf=${"%.3f".format(ms / 1000f / audio)}")
                if (i == runs) writeWav(File(filesDir, "bench_${delegate}.wav"), r.samples, r.sampleRateHz)
            }
            val t = System.nanoTime()
            val m = tts.synthesize(markup, SynthesisOptions(voiceId = voice))
            say("MARKUP synth_ms=${(System.nanoTime() - t) / 1_000_000} audio_s=${"%.2f".format(m.samples.size.toFloat() / m.sampleRateHz)}")
            writeWav(File(filesDir, "markup_${delegate}.wav"), m.samples, m.sampleRateHz)
            val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            say("MEM total_pss_mb=${mem.totalPss / 1024} native_heap_mb=${Debug.getNativeHeapAllocatedSize() / 1048576}")
            tts.close()
            say("DONE ok")
        } catch (e: Throwable) {
            say("DONE error=${e.javaClass.simpleName}: ${e.message?.take(300)}")
            Log.e(tag, "failed", e)
        }
    }

    private fun writeWav(f: File, samples: FloatArray, rate: Int) {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.capacity())
        RandomAccessFile(f, "rw").use { it.setLength(0); it.write(h.array()); it.write(data.array()) }
    }
}
