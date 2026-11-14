#!/usr/bin/env bash
# Repack freshly exported Kokoro models into LiteRT-LM bundles, reusing the voices and text front end
# of Google's stock bundles.
#
#   ./repack.sh <export_dir> <stock_cpu.litertlm> <stock_gpu.litertlm> <out_dir> [tag]
#
# export_dir holds kokoro_acoustic.tflite and kokoro_vocoder.tflite (from export_main). Writes
# <out_dir>/kokoro_<tag>_cpu.litertlm and <out_dir>/kokoro_<tag>_gpu.litertlm; the two differ only by
# the vocoder section's backend_constraint, exactly like the stock pair.
set -euo pipefail
EXP=$(realpath "$1"); CPU=$(realpath "$2"); GPU=$(realpath "$3"); OUT=$(realpath -m "$4"); TAG=${5:-512}
HERE=$(cd "$(dirname "$0")" && pwd); B="$HERE/.venv/bin/litert-lm-builder"
mkdir -p "$OUT"
for kind in cpu gpu; do
  src=$([ $kind = cpu ] && echo "$CPU" || echo "$GPU")
  work="$OUT/unpack_$kind"; rm -rf "$work"
  "$B" unpack --litertlm_file "$src" --output_dir "$work" >/dev/null
  cp "$EXP/kokoro_acoustic.tflite" "$work"/Section1_TFLiteModel_tf_lite_acoustic.tflite
  cp "$EXP/kokoro_vocoder.tflite"  "$work"/Section2_TFLiteModel_tf_lite_vocoder.tflite
  # paths in model.toml are relative to the toml's directory
  (cd "$work" && "$B" toml --path model.toml output --path "$OUT/kokoro_${TAG}_${kind}.litertlm" --weights-in-model-graph true >/dev/null)
  rm -rf "$work"
  ls -la "$OUT/kokoro_${TAG}_${kind}.litertlm" | awk '{print $5, $9}'
done
