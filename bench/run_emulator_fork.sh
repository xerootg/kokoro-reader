#!/usr/bin/env bash
# End-to-end test of the reader app fork in a headless emulator: install it, place the Kokoro model
# and a lexicon inside it, then speak through its text-to-speech service from Kokoro Bench.
#
#   ./run_emulator_fork.sh <kokoro_int8_fp16.litertlm> <lexicon.json> <out_dir>
set -euo pipefail
MODEL=$1; LEX=$2; OUT=$3
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk} ANDROID_SDK_ROOT=${ANDROID_HOME:-$HOME/Android/Sdk}
AVD=kokorobench; PORT=5584; SERIAL=emulator-$PORT; ADB="adb -s $SERIAL"
BENCH=dev.milagro.kokorobench; FORK=io.github.xerootg.kokororeader
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"
"$ANDROID_HOME/emulator/emulator" -avd "$AVD" -port $PORT -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect >"$OUT/emulator.log" 2>&1 &
EMU=$!
trap '$ADB emu kill >/dev/null 2>&1 || kill $EMU 2>/dev/null || true' EXIT
$ADB wait-for-device
for i in $(seq 1 120); do [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 2; done
echo "booted: Android $($ADB shell getprop ro.build.version.release | tr -d '\r')"
$ADB install -r "$HERE/../app/build/outputs/apk/debug/app-debug.apk" | tail -1
$ADB install -r "$HERE/app/build/outputs/apk/debug/app-debug.apk" | tail -1
$ADB push "$MODEL" /data/local/tmp/kokoro_int8_fp16.litertlm | tail -1
$ADB push "$LEX" /data/local/tmp/user_lexicon.json | tail -1
$ADB shell "run-as $FORK sh -c 'mkdir -p files/v1 && cp /data/local/tmp/kokoro_int8_fp16.litertlm files/v1/ && touch files/v1/.ready && cp /data/local/tmp/user_lexicon.json files/user_lexicon.json && ls -la files files/v1'" | tr -d '\r' | tail -8
$ADB logcat -c
$ADB shell am start -n $BENCH/.MainActivity -e mode tts -e engine $FORK | tail -1
for i in $(seq 1 120); do $ADB logcat -d -s KOKOROBENCH 2>/dev/null | grep -q ' DONE ' && break; sleep 3; done
echo "--- test app"; $ADB logcat -d -s KOKOROBENCH | sed -n 's/.*KOKOROBENCH: //p' | tee "$OUT/fork_bench.log"
echo "--- fork"; $ADB logcat -d -s SupertonicTTS KokoroEngine AssetManager | sed -n 's/^[^ ]* [^ ]* *[0-9]* *[0-9]* \([A-Z] [A-Za-z]*\) *: /\1: /p' | cut -c1-220 | tail -25 | tee "$OUT/fork_app.log"
echo "--- crashes"; $ADB logcat -d -b crash | tail -15 | cut -c1-220
$ADB exec-out run-as $BENCH cat files/tts_out.wav > "$OUT/fork_tts_out.wav" 2>/dev/null || true
ls -la "$OUT/fork_tts_out.wav" | awk '{print $5, $9}'
