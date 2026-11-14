#!/usr/bin/env bash
# Run Kokoro Bench in a headless Android emulator (x86_64, CPU only).
# This proves the app's plumbing - install, model load, synthesis, logging, audio pull - before a
# phone session.  Emulator timings say nothing about a phone's speed.
#
#   ./run_emulator.sh <kokoro_int8_fp16.litertlm> <out_dir>
#
# Needs: sdkmanager packages "emulator" and "system-images;android-36;google_apis;x86_64", KVM access.
set -euo pipefail
MODEL=$1; OUT=$2
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk} ANDROID_SDK_ROOT=${ANDROID_HOME:-$HOME/Android/Sdk}
AVD=kokorobench; PKG=dev.milagro.kokorobench; PORT=5584; SERIAL=emulator-$PORT
ADB="adb -s $SERIAL"                       # always name the emulator: a real phone may be plugged in too
AVDM=$(ls -d "$ANDROID_HOME"/cmdline-tools/latest*/bin/avdmanager | tail -1)
APK=$(dirname "$0")/app/build/outputs/apk/debug/app-debug.apk
mkdir -p "$OUT"

if ! "$ANDROID_HOME/emulator/emulator" -list-avds | grep -qx "$AVD"; then
  echo no | "$AVDM" create avd -n "$AVD" -k "system-images;android-36;google_apis;x86_64" -d pixel_7 >/dev/null
  # room for two 295 MB models plus caches, and enough memory for the CPU model (~0.9 GB peak)
  printf 'disk.dataPartition.size=6G\nhw.ramSize=6144\nhw.cpu.ncore=4\n' >> "$HOME/.android/avd/$AVD.avd/config.ini"
fi
"$ANDROID_HOME/emulator/emulator" -avd "$AVD" -port $PORT -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect >"$OUT/emulator.log" 2>&1 &
EMU=$!
trap '$ADB emu kill >/dev/null 2>&1 || kill $EMU 2>/dev/null || true' EXIT
$ADB wait-for-device
for i in $(seq 1 120); do
  [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 2
done
echo "booted: Android $($ADB shell getprop ro.build.version.release | tr -d '\r'), $($ADB shell getprop ro.product.cpu.abi | tr -d '\r')"
$ADB install -r "$APK" | tail -1
$ADB push "$MODEL" /data/local/tmp/ | tail -1
$ADB shell "run-as $PKG sh -c 'mkdir -p files && cp /data/local/tmp/$(basename "$MODEL") files/'"
$ADB logcat -c
$ADB shell am start -n $PKG/.MainActivity -e model "$(basename "$MODEL")" -e delegate cpu -e voice am_michael --ei runs 2 | tail -1
for i in $(seq 1 150); do
  $ADB logcat -d -s KOKOROBENCH 2>/dev/null | grep -q ' DONE ' && break; sleep 4
done
$ADB logcat -d -s KOKOROBENCH | sed -n 's/.*KOKOROBENCH: //p' | tee "$OUT/bench.log"
for f in bench_cpu.wav markup_cpu.wav; do
  $ADB exec-out run-as $PKG cat files/$f > "$OUT/emu_$f" 2>/dev/null || true
done
ls -la "$OUT"/emu_*.wav 2>/dev/null | awk '{print $5, $9}'
