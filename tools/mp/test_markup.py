"""Does the official MediaPipe Kokoro task honour inline phoneme markup?

    tools/mp/.venv/bin/python tools/mp/test_markup.py <kokoro_int8_fp16.litertlm> <out_dir>

Synthesises the same sentence three ways with the same voice and prints, for each, the audio
length and the word timestamps the task reports:

  plain    names as an English reader would type them
  markup   names pinned with [word](/phonemes/), the syntax Kokoro's reference text processor
           (misaki) uses and that LiteRT-LM's english_g2p.h says it preprocesses
  literal  control: the same phonemes without the link syntax, to show what "read out
           literally" looks like

If markup works, its timestamps list the original words (not slashes or symbols) and its
duration is close to the plain version.  Listening is still the real test; WAVs are written
to out_dir.
"""
import sys, os, time
import soundfile as sf
from mediapipe.tasks.python.audio import text_to_speech
from mediapipe.tasks.python.core import base_options

model, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
VOICE = 'am_michael'
CASES = {
    'plain': 'Joe Mondragon and Sheriff Bernabe Montoya drove out to Milagro before the trucha stopped biting.',
    'markup': 'Joe [Mondragón](/mondɾaɣˈon/) and Sheriff [Bernabé](/beɾnaβˈe/) [Montoya](/montˈoʝa/) drove out to '
              '[Milagro](/milˈaɣɾo/) before the [trucha](/tɾˈuʧa/) stopped biting.',
    'literal': 'Joe mondɾaɣˈon and Sheriff beɾnaβˈe montˈoʝa drove out to milˈaɣɾo before the tɾˈuʧa stopped biting.',
}
options = text_to_speech.TextToSpeechOptions(
    base_options=base_options.BaseOptions(model_asset_path=model),
    default_voice_id=VOICE, speaking_rate=1.0)
with text_to_speech.TextToSpeech.create_from_options(options) as tts:
    voices = [v.voice_id for v in tts.list_voices()]
    print('voices:', len(voices), '| am_michael present:', VOICE in voices)
    for name, text in CASES.items():
        t0 = time.time()
        try:
            r = tts.synthesize(text, voice_id=VOICE)
        except Exception as e:
            print(f'{name}: FAILED {type(e).__name__}: {str(e)[:200]}'); continue
        dt = time.time() - t0
        dur = len(r.samples) / r.sample_rate_hz
        sf.write(os.path.join(out, f'markup_test_{name}.wav'), r.samples, r.sample_rate_hz)
        words = [(w.word, round(w.start_sec, 2)) for w in r.word_timestamps]
        print(f'{name}: {dur:.2f}s audio in {dt:.2f}s | {len(words)} timed words')
        print('   ', ' '.join(f'{w}@{t}' for w, t in words))
