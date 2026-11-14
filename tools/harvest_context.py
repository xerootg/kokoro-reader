"""Learn how common words are pronounced inside sentences, for the on-device table.

build_dict.py looks every word up on its own, where it keeps full stress.  In running speech
misaki weakens or drops the stress on function words ("in", "had", "be", "of").  This script
runs misaki over any plain-text corpus (one paragraph per line) and records, for each word,
the form it most often takes in context.

    python tools/harvest_context.py corpus.txt app/src/main/assets/kokoro/en_context.tsv [min_count]

Output: word<TAB>phonemes<TAB>count, only for words seen at least min_count times (default 5)
whose in-context form is consistent (the majority form covers at least 70% of uses).
Only word->phoneme pairs are written; none of the corpus text is kept.
"""
import sys, re, collections, warnings
warnings.filterwarnings('ignore')
from misaki import en, espeak

corpus, out = sys.argv[1], sys.argv[2]
min_count = int(sys.argv[3]) if len(sys.argv) > 3 else 5
g2p = en.G2P(trf=False, british=False, fallback=espeak.EspeakFallback(british=False))
forms = collections.defaultdict(collections.Counter)
n = 0
for line in open(corpus, encoding='utf8'):
    for sent in re.split(r'(?<=[.!?…])["”\')]*\s+', line.strip()):
        if not re.search(r'[A-Za-z]', sent): continue
        try:
            _, toks = g2p(sent)
        except Exception:
            continue
        for t in toks or []:
            w = (t.text or '').lower()
            if t.phonemes and re.fullmatch(r"[a-z]+(?:'[a-z]+)?", w) and ' ' not in t.phonemes and '❓' not in t.phonemes:
                forms[w][t.phonemes] += 1
        n += 1
        if n % 2000 == 0: print(n, 'sentences', flush=True)
kept = 0
with open(out, 'w', encoding='utf8') as f:
    for w in sorted(forms):
        c = forms[w]; total = sum(c.values()); ph, top = c.most_common(1)[0]
        if total >= min_count and top / total >= 0.7:
            f.write(f'{w}\t{ph}\t{total}\n'); kept += 1
print('sentences', n, '| words seen', len(forms), '| kept', kept)
