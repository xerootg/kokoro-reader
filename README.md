# Kokoro Reader

An Android audiobook reader that narrates EPUBs on the phone with the [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M)
voice model, running locally through Google's LiteRT build of the model
([litert-community/Kokoro-82M](https://huggingface.co/litert-community/Kokoro-82M), MediaPipe text-to-speech task).
No server, no account; the model runs on the phone's GPU.

It is a fork of [Supertonic TTS for Android](https://github.com/DevGitPit/supertonic-android) (GPL-3.0),
which supplied the EPUB reader, the playback service, the pronunciation-lexicon screens and the system
text-to-speech integration. The Supertonic engine was replaced with Kokoro.

## What it does

- Reads EPUBs aloud, chapter by chapter, with a sentence list you can jump around in. Playback runs as a
  foreground media service: screen off, notification and headset controls.
- 54 Kokoro voices (US and UK English read English properly; the rest apply their accent).
- Pronunciation lexicon with exact phoneme pinning: a rule whose replacement is written `/phonemes/`
  becomes inline `[word](/phonemes/)` markup for the engine. Import rules as JSON from the Lexicon screen.
- Tune panel on the playback screen: voice, speed and the three pause lengths (comma, clause, sentence),
  applied to the sentence being read as soon as a slider is released.
- Streams audio to the player a clause at a time, so reading starts after a second or two.
- Speech cache: every synthesised clause is kept (default cap 1 GB, least recently used evicted), so
  rewinds and re-reads are instant.
- Acts as a system text-to-speech engine for other apps, with the same voices and streaming.

## Building

Requirements: JDK 17 or 21 (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew ...` if the default JDK is a newer runtime without a compiler), Android SDK platform 37 with NDK r29, Rust (stable) with the
`aarch64-linux-android` target, and `cargo vendor` run once in `rust/`.

    cd rust && cargo vendor vendor && cd ..
    printf 'sdk.dir=%s\nrust.targets=arm64\n' "$ANDROID_HOME" > local.properties
    ./gradlew assembleDebug

Install to one profile only on a phone with a work profile: `adb install --user 0 -r app/build/outputs/apk/debug/app-debug.apk`.

The Kokoro model is downloaded by the app on first use (about 295 MB). To place it by hand, copy
`kokoro_int8_gpu_fp16.litertlm` (or the CPU file) into the app's `files/v1/` directory;
`bench/phone_install.sh` does that over adb, together with the lexicon and a book.

**Whole sentences per run.** Google's bundle is compiled for 128 phoneme tokens (about 90
characters), and its runtime cuts longer text into chunks spoken as separate utterances, which
spoils the cadence inside long sentences. The fix has two parts, both optional: a model re-exported
with a 512-token bucket (`tools/export`, placed as `files/v1/kokoro_512_gpu.litertlm`) and
`libkokoro_tts_jni.so`, a direct binding to LiteRT-LM's C++ engine with the chunk size as a
parameter (built by CI of the `tools/LiteRT-LM` fork; the app build fetches it by URL and SHA-256
from `gradle.properties`). With both present the engine speaks each sentence in one run; without
either it uses the MediaPipe task. Speed on the direct path is a WSOLA time-stretch.

CI (`.github/workflows/release.yml`) builds and signs an arm64 release APK on every push to `main` and on
`v*` tags; tags also publish a GitHub release. Signing uses the repository secrets `KEYSTORE_BASE64`,
`KEY_ALIAS`, `KEYSTORE_PASSWORD` and `KEY_PASSWORD`.

## Repository map

- `app/` the reader. `KokoroEngine.kt` wraps the MediaPipe task: lexicon markup, cutting text into
  pieces the engine will not cut again, gaps, trimming, streaming and the cache.
- `bench/` Kokoro Bench, a small app that times the engine on a device, drives the reader's TTS service,
  and tests the library's streaming API. `bench/run_emulator*.sh` run it headless.
- `tools/export/` re-exporting the Kokoro LiteRT bundle with a longer phoneme bucket, using a patched
  fork of Google's exporter (submodule). See its README.
- `docs/NOTES.md` what was measured and learned: GPU setup, engine limits, pauses, cache, test method.
- `rust/` the original Supertonic engine, still built for the non-English model slots.

## License

GPL-3.0, as upstream. Kokoro-82M and Google's LiteRT bundle of it are Apache-2.0.
