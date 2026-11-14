# Kokoro Bench

Throwaway Android app that times Google's MediaPipe Kokoro text-to-speech task on a real device
(or the emulator) and writes the audio it produced. It is not part of the reader app.

## Build

    JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk ./gradlew --no-daemon :app:assembleDebug

## Run

    PKG=dev.milagro.kokorobench
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb push kokoro_int8_fp16.litertlm /data/local/tmp/          # CPU model, from litert-community/Kokoro-82M
    adb push kokoro_int8_gpu_fp16.litertlm /data/local/tmp/      # GPU model
    adb shell "run-as $PKG sh -c 'mkdir -p files && cp /data/local/tmp/*.litertlm files/'"
    adb logcat -c
    adb shell am start -n $PKG/.MainActivity -e model kokoro_int8_fp16.litertlm -e delegate cpu -e voice am_michael --ei runs 3
    adb logcat -d -s KOKOROBENCH                                  # START / LOADED / RUN / MARKUP / MEM / DONE lines
    adb exec-out run-as $PKG cat files/bench_cpu.wav > bench_cpu.wav

Use `-e model kokoro_int8_gpu_fp16.litertlm -e delegate gpu` for the GPU run. RTF is synthesis
time divided by audio length; below 1.0 is faster than real time.
