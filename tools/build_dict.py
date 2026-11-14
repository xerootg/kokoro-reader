"""Build the flat English pronunciation table the on-device Kokoro engine looks words up in.

Kokoro's own text processor (misaki) keeps ~180k base words and derives inflected forms
(wanted, mountains, dogs) in Python at run time.  The phone has no Python, so this script runs
misaki here over a large word list and writes every result out:

    word<TAB>phonemes        (lowercase word; Kokoro phoneme symbols)

Run with a Python that has misaki[en] installed:
    python tools/build_dict.py app/src/main/assets/kokoro/en_dict.tsv [extra_wordlist.txt ...]
Sources: misaki's us_gold/us_silver keys, /usr/share/dict/american-english, any extra lists,
plus regular inflections of each (-s, -es, -ed, -ing, -'s).
"""
import sys, os, re, json, glob, warnings
warnings.filterwarnings('ignore')
import misaki
from misaki import en, espeak

out = sys.argv[1]
d = os.path.join(os.path.dirname(misaki.__file__), 'data')
words = set()
for f in ('us_gold.json', 'us_silver.json'):
    words |= {k for k in json.load(open(os.path.join(d, f), encoding='utf8'))}
for f in ['/usr/share/dict/american-english'] + sys.argv[2:]:
    if os.path.exists(f): words |= {w.strip() for w in open(f, encoding='utf8')}
words = {w for w in words if re.fullmatch(r"[A-Za-z]+(?:'[A-Za-z]+)?", w) and len(w) <= 24}
base = {w.lower() for w in words}
forms = set(base)
for w in base:
    if "'" in w: continue
    forms.add(w + "'s")
    if len(w) > 2:
        forms.add(w + 's'); forms.add(w + 'ed'); forms.add(w + 'ing')
        if w.endswith(('s', 'x', 'z', 'ch', 'sh')): forms.add(w + 'es')
        if w.endswith('e'): forms.add(w + 'd'); forms.add(w[:-1] + 'ing')
        if w.endswith('y') and w[-2] not in 'aeiou': forms.add(w[:-1] + 'ies'); forms.add(w[:-1] + 'ied')
# Only keep generated inflections that a real word list contains or that misaki resolves from
# its dictionary (not by spelling them out through the espeak fallback).
strict = en.G2P(trf=False, british=False, fallback=None)
loose = en.G2P(trf=False, british=False, fallback=espeak.EspeakFallback(british=False))
n = 0; kept = 0
os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, 'w', encoding='utf8') as f:
    for w in sorted(forms):
        n += 1
        try:
            ph, toks = strict(w)
            if not ph or '❓' in ph:
                if w not in base: continue            # invented inflection the dictionary can't resolve
                ph, toks = loose(w)
            ph = ph.strip()
            if ph and '❓' not in ph and ' ' not in ph:
                f.write(f'{w}\t{ph}\n'); kept += 1
        except Exception:
            pass
        if n % 50000 == 0: print(n, 'of', len(forms), 'kept', kept, flush=True)
print('done:', kept, 'entries from', len(forms), 'candidate forms')
