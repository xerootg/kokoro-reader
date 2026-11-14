# Kokoro Reader: engineering notes

What was learned while turning the Supertonic TTS reader into a Kokoro-based audiobook reader
(November 2026). Numbers are measurements on a Pixel 10a (Tensor G4, Android 17) unless stated.

## Engine

- Speech comes from Google's official Kokoro-82M MediaPipe text-to-speech task
  (`com.google.mediapipe:tasks-audio`, model `litert-community/Kokoro-82M`, Apache-2.0).
  `KokoroEngine.kt` wraps it; the app's English model slot `v1` holds the `.litertlm` bundle.
- **GPU needs manifest declarations.** On Android 12+ an app can only load the vendor's OpenCL
  library if `AndroidManifest.xml` declares it (`<uses-native-library android:name="libOpenCL.so"
  android:required="false"/>`, plus the Pixel and Edge TPU libraries). Without them the task logs
  "OpenCL not supported on this platform. Using OpenGL instead" and runs 2-3x slower, mostly on the
  CPU. With them: 21 s of speech in 5.5 s on the GPU (about 3.7x faster than real time, 580 MB);
  the CPU bundle takes 41 s (2x slower than real time, 890 MB).
- **Cost is per run, not per character.** Each model run costs about 1 s regardless of how much text
  it holds (5.6 s of speech in 1.06 s, 2.8 s in 0.99 s). A whole-paragraph call and a stream of the
  same paragraph cost the same; streaming only lowers time to first audio.
- **The stock bundle is compiled for 128 phoneme tokens per run** (`phoneme_ids: [1, 128]`, about 90
  characters of English). Longer text is cut by the runtime at `. ? ! ;`, at double quotes, and after
  about 90 characters, and it keeps roughly a second of silence at every cut. Each chunk is spoken
  as a complete utterance, which breaks the cadence inside long sentences. The limit is
  `BUCKET_SIZE = 128` in Google's open-source exporter; see `tools/export/README.md` for the
  re-export with a 512-token bucket.
- The runtime's English front end honours inline `[word](/phonemes/)` markup (misaki syntax), which
  is how the pronunciation lexicon is applied. It does not rewrite the Spanish tap `ɾ` the way
  desktop misaki does.
- `target_bucket` in the task options selects a fixed bucket; values other than 0 hung the desktop
  Python task on the stock bundle. It does not change the chunking.
- **The ~90-character chunk is set by the runtime, not the model.** A re-exported bundle with a
  512-token acoustic model (`tools/export`) loads and runs (each run ~3.5x slower, since every call
  pads to 512 tokens), but the runtime still cut the text at the same places. In LiteRT-LM,
  `ReviseTextChunkConfigForKokoro` sets `Text2SpeechTextChunkConfig.max_buffer_size` (main: at most
  120 characters), and nothing in the MediaPipe Java/Python/Kotlin API or in `litertlm-android` can
  change it; only the C++ `Text2SpeechSessionConfig` can. Whole-sentence runs therefore need the C++
  runtime built with a raised limit: `tools/LiteRT-LM` (fork `xerootg/LiteRT-LM`, branch
  `kokoro-reader`) adds `--max_chunk_chars` / `--sentence_delimiters_only` to the runner, a
  `KOKORO_SLICES` log in the acoustic stage, and `omni/text2speech/jni` (a JNI the app loads as
  `KokoroNative`). Verified on the desktop: a 187-token sentence is 2 acoustic runs with the stock
  bundle and 1 with the 512 bundle; a 268-token sentence 3 vs 1. The short silences that remain are
  the model's own comma pauses. A 512-token run costs about twice a 128-token run.
- Building LiteRT-LM here: Bazel 7.6.1 via bazelisk, `clang` on PATH (a wrapper around the NDK's
  clang works for host builds), `--copt=-DHWY_COMPILE_ONLY_EMU128` on x86 (Highway's AVX-512 code
  does not compile with clang 21), and the LFS prebuilts under `prebuilt/` fetched from
  `media.githubusercontent.com` (the repo uses Git LFS; without git-lfs the files are pointers).
  Android: `--config=android_arm64` with `ANDROID_NDK_HOME` set (x86_64 for the emulator the same
  way, plus the Highway flag). Strip with the NDK's `llvm-strip --strip-unneeded` (51 MB -> 25 MB).
- Verified in the emulator (2026-11-14): the app loads `libkokoro_tts_jni.so`, creates the 512-token
  engine in about 1.2 s, and speaks each sentence of the test text as one piece (197 and 124
  characters). When the direct path is available the MediaPipe task is not created at all unless the
  speed is changed from 1.0, which saves 2 s and about 200 MB.

## Text handling in the app (`KokoroEngine.pieces`)

- Text is cut into pieces of at most 80 raw characters (markup counts) at clause punctuation, so the
  runtime never cuts again and its one-second pauses never appear. Semicolons and dashes become
  commas inside a piece; quotation marks are removed (they are not spoken and cost a pause).
- A forced cut (no punctuation in reach) backs up to the last word a phrase can start with
  ("about why Joe Mondragón did it"), never between a first and last name. Measured silence at such
  a cut fell from 0.37 s to 0.13 s.
- Gaps after each piece are the app's own: comma 0.16 s, semicolon/colon/dash 0.30 s, sentence
  0.45 s by default, all adjustable live from the playback screen's tune panel. Each piece is
  trimmed of the model's leading and trailing silence first (threshold 0.012, 40 ms kept).
- Speaking rate is a property of the loaded task, so a speed change reloads the model (about 4 s on
  the GPU). The app remembers the last rate and loads at that rate from the start.

## Playback

- Pieces stream to the player as they are made; playback starts after two pieces and the producer
  runs up to 24 pieces ahead. Before this the player waited for three whole sentences.
- `AudioCache` stores each piece's trimmed PCM under a key of text + voice + rate + model file, LRU
  under a size cap (Playback Settings, default 1 GB, about 6 hours). Gaps are added at playback so
  the pause sliders never invalidate the cache; a lexicon change does, for the pieces it touches.
  A cached sentence replays in milliseconds.
- Playback is a foreground media service with a MediaSession, so it continues with the screen off and
  has notification and headset controls (inherited from Supertonic).
- The system text-to-speech service streams the same pieces to other apps, and lists all 54 Kokoro
  voices. Only the US and UK voices read English well; the others apply their accent to English text.
- **The 2026-11-13 lock-up.** The phone's ANR record (dumpsys dropbox, 21:16) shows the UI thread
  blocked in `KokoroEngine.isInitialized`, waiting for the engine's object lock, which a worker held
  inside `synthesizeStreaming`; that worker was parked in the player's piece callback (`runBlocking
  { channel.send }`, look-ahead full). The lock was held for a whole sentence including the callback,
  so the UI froze for as long as playback took to drain the look-ahead, and the user force-stopped
  the app from Settings at 22:06. Now `prepare()` runs under the lock, each piece's native call takes
  the lock on its own (re-checking the engine was not released or re-made for another voice), the
  callback runs unlocked, and `isInitialized` reads volatile fields without locking. The phone was
  also swapping hard that night (Home Assistant OOM-ing, lmkd killing Teams and Gboard every few
  minutes, the reader killed for LOW_MEMORY at 03:04 at 442 MB PSS); the reader's footprint with the
  512 GPU bundle is about 950 MB PSS, half of it GPU buffers.
- **16 KB page size.** Android 16 shows a "not 16 KB compatible" dialog for debuggable apps whose
  native libraries have 4 KB LOAD segments. `libkokoro_tts_jni.so` is linked with
  `-Wl,-z,max-page-size=16384` since release `kokoro-jni-v2`; the APK stores libraries uncompressed
  (`useLegacyPackaging = false`) so the zip alignment check can see them. The Pixel 10a itself runs
  4 KB pages, so the old build worked; 16 KB-page devices would have refused to load the library.

## Pronunciation lexicon

- Rules whose replacement is written `/phonemes/` are pinned as inline markup. Multi-word terms need
  one phoneme group per word. Possessives and plurals need their own rules (whole-word matching).
- Pronunciations were settled by blind listening tests: the same word rendered with several candidate
  phoneme sets, in a shuffled order announced by spoken numbers, so the listener is not swayed by
  labels. Example outcome: "Milagro" takes the "father" vowel (ɑ) while the other Spanish names keep
  the plain Spanish a.

## Test tooling

- `bench/` (Kokoro Bench) times the task directly (`-e delegate cpu|gpu`), drives the reader's TTS
  service (`-e mode tts -e engine <package>`), and tests the library's streaming API (`-e mode
  stream`). It writes `bench_log.txt` in its files directory because logcat over a flaky USB link
  drops lines. `bench/run_emulator.sh` and `bench/run_emulator_fork.sh` run it headless.
- The Pixel has a work profile: always install with `adb install --user 0`, or the app lands in both
  profiles.
