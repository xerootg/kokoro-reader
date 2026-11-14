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
  Python task. Do not use it.

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
