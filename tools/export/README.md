# Re-exporting Kokoro for LiteRT with a longer phoneme bucket

Google's `litert-community/Kokoro-82M` bundle is compiled for a fixed **128 phoneme tokens** per
run (about 90 characters of English), so its engine cuts every longer sentence into chunks and
speaks each chunk as its own utterance. The limit is one constant in the open-source exporter:
`BUCKET_SIZE` in `litert_torch/generative/export_hf/model_ext/kokoro_tts/kokoro_tts.py`.

Setup (everything here is git-ignored except this file and any scripts):

    uv venv .venv --python 3.12
    uv pip install --python .venv/bin/python litert-torch litert-quantizer litert-lm-builder huggingface_hub
    git clone --depth 1 https://github.com/google-ai-edge/LiteRT-Torch.git     # main has the Kokoro extension; the 0.10.0 wheel does not
    sed -i 's/^BUCKET_SIZE = 128$/BUCKET_SIZE = 512/' LiteRT-Torch/litert_torch/generative/export_hf/model_ext/kokoro_tts/kokoro_tts.py
    .venv/bin/python -c "from huggingface_hub import snapshot_download as s; s('hexgrad/Kokoro-82M', local_dir='hf/Kokoro-82M', allow_patterns=['*.pth','*.json','*.md','voices/*.pt'])"

Export (CPU, several GB of RAM, tens of minutes):

    PYTHONPATH=LiteRT-Torch .venv/bin/python -m litert_torch.generative.export_hf.export_main \
        --model=hf/Kokoro-82M --output_dir=out512 --task=text_to_speech \
        --quantization_recipe=dynamic_fc_weight_only_conv_wi8_afp32

Repack: unpack the stock bundle (`litert-lm-builder unpack --litertlm_file kokoro_int8_fp16.litertlm --output_dir unpack/cpu`),
copy `out512/kokoro_acoustic.tflite` over `Section1_*` and `out512/kokoro_vocoder.tflite` over `Section2_*`,
then `litert-lm-builder toml --path unpack/cpu/model.toml output --path kokoro_512.litertlm`. The GPU bundle
differs only by `backend_constraint = "gpu"` on the vocoder section. Voices and the text front end are reused.
Voice packs have 510 rows, so 510 tokens is the most a run can use.
