"""Exercise a Kokoro LiteRT bundle with the official MediaPipe task: how long a run can it take?

    tools/mp/.venv/bin/python tools/mp/test_bundle.py <bundle.litertlm> <out_dir>

For texts of growing length it reports the audio length, the number of long (>= 0.45 s) silences
inside the audio (each one is a cut the runtime made), the word count the task timed, and the wall
time. A bundle with a 128-token bucket cuts anything past ~90 characters; a 512-token bundle should
speak a whole paragraph with no internal cut. WAVs are written so the result can be heard.
"""
import sys, os, time, numpy as np, soundfile as sf
from mediapipe.tasks.python.audio import text_to_speech
from mediapipe.tasks.python.core import base_options

model, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
S1 = 'The water was already running in the ditch.'
S2 = ('When the sheriff finally drove out to the beanfield that morning, the water was already running in the ditch, '
      'and nobody in town could say for certain who had opened the headgate the night before.')
S3 = (S2 + ' He stood at the edge of the field for a long while, hat in hand, looking at the neat rows and the wet earth, '
      'and then he got back into the truck without a word and drove slowly back toward town, past the church and the '
      'shuttered bar, while the first light came over the mesa.')
S4 = S3 + ' ' + S2 + ' ' + S3
CASES = [('short', S1), ('sentence', S2), ('paragraph', S3), ('long', S4)]

def silences(w, sr, th=0.012, minlen=0.45):
    q = np.abs(w) < th; idx = np.where(~q)[0]
    if not len(idx): return []
    a, b = idx[0], idx[-1]; out = []; i = a
    while i <= b:
        if q[i]:
            j = i
            while j <= b and q[j]: j += 1
            if (j - i) / sr >= minlen: out.append(round((j - i) / sr, 2))
            i = j
        else: i += 1
    return out

o = text_to_speech.TextToSpeechOptions(base_options=base_options.BaseOptions(model_asset_path=model), default_voice_id='am_michael', speaking_rate=1.0)
t = time.time()
with text_to_speech.TextToSpeech.create_from_options(o) as tts:
    print(f'loaded in {time.time() - t:.1f}s, voices {len(tts.list_voices())}')
    for name, text in CASES:
        t = time.time()
        try:
            r = tts.synthesize(text, voice_id='am_michael')
        except Exception as e:
            print(f'{name} ({len(text)} chars): FAILED {type(e).__name__}: {str(e)[:200]}'); continue
        dt = time.time() - t; w = np.asarray(r.samples)
        sf.write(os.path.join(out, f'{name}.wav'), w, r.sample_rate_hz)
        print(f'{name} ({len(text)} chars): {len(w) / r.sample_rate_hz:.2f}s audio in {dt:.2f}s, '
              f'internal cuts {len(silences(w, r.sample_rate_hz))} {silences(w, r.sample_rate_hz)}, words {len(r.word_timestamps)}')
