#!/usr/bin/env bash
# Install the reader on a phone (personal profile only) and place the models and lexicon inside it.
#
#   ./phone_install.sh <serial> <app-debug.apk|signed.apk> <stock_gpu.litertlm> <stock_cpu.litertlm> [long_gpu.litertlm] [lexicon.json] [book.epub]
#
# The long-bucket bundle (tools/export/bundles/kokoro_512_gpu.litertlm) enables the whole-sentence
# path when the APK carries libkokoro_tts_jni.so. Files go through /data/local/tmp and `run-as`,
# so the app does not need to download anything. Always installs with --user 0: a work profile
# would otherwise get a copy too.
set -euo pipefail
SERIAL=$1; APK=$2; GPU=$3; CPU=$4; LONG=${5:-}; LEX=${6:-}; BOOK=${7:-}
PKG=io.github.xerootg.kokororeader
A="adb -s $SERIAL"
$A install --user 0 -r "$APK" | tail -1
echo "work-profile copies: $($A shell pm list packages --user 10 2>/dev/null | grep -c "$PKG" || true) (should be 0)"
push() { $A push "$1" "/data/local/tmp/$2" | tail -1; }
push "$GPU" kokoro_int8_gpu_fp16.litertlm
push "$CPU" kokoro_int8_fp16.litertlm
[ -n "$LONG" ] && push "$LONG" kokoro_512_gpu.litertlm
[ -n "$LEX" ] && push "$LEX" user_lexicon.json
[ -n "$BOOK" ] && push "$BOOK" kr-book.epub
$A shell "run-as $PKG sh -c '
  mkdir -p files/v1 files/ebooks
  for f in kokoro_int8_gpu_fp16.litertlm kokoro_int8_fp16.litertlm kokoro_512_gpu.litertlm; do
    [ -f /data/local/tmp/\$f ] && cp /data/local/tmp/\$f files/v1/\$f
  done
  touch files/v1/.ready
  [ -f /data/local/tmp/user_lexicon.json ] && cp /data/local/tmp/user_lexicon.json files/user_lexicon.json
  [ -f /data/local/tmp/kr-book.epub ] && cp /data/local/tmp/kr-book.epub \"files/ebooks/$(basename "${BOOK:-book.epub}")\"
  ls -la files/v1 | grep -v total'" | tr -d '\r'
$A shell rm -f /data/local/tmp/kr-book.epub /data/local/tmp/user_lexicon.json
$A shell am force-stop --user 0 $PKG
$A shell monkey --pct-syskeys 0 -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
echo "installed and opened; watch: adb -s $SERIAL logcat -s KokoroEngine KokoroNative"
