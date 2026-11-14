//! Text -> Kokoro phonemes, on device.
//!
//! Kokoro's reference text processor (misaki) is Python and cannot run on Android.  This module
//! does the part of its job that matters for narration with data instead of code:
//!
//!   * `en_dict.tsv` (built on a desktop by tools/build_dict.py) maps ~900k lowercase English word
//!     forms to Kokoro phoneme strings.  The file is sorted, so it is binary-searched in place and
//!     never expanded into a hash map.
//!   * A few function words change with what follows them ("the apple" / "the pear").
//!   * A lexicon entry can pin exact phonemes for a word or phrase.  The app's lexicon runs before
//!     this code and its text normaliser would mangle phoneme symbols, so pinned phonemes travel
//!     as an ordinary-looking word: `zqk` + the phonemes' UTF-8 bytes as letters a..p + `kqz`.
//!
//! No JNI, no ONNX: this file is also compiled into a small host program for testing.

use std::cmp::Ordering;

pub const MARK_START: &str = "zqk";
pub const MARK_END: &str = "kqz";

pub struct Dict {
    data: Vec<u8>,
    /// In-sentence forms of common words (en_context.tsv, from tools/harvest_context.py).
    /// Function words lose their stress in running speech; this small table wins over `data`.
    context: std::collections::HashMap<String, String>,
}

impl Dict {
    pub fn load(path: &str) -> std::io::Result<Self> {
        Ok(Dict { data: std::fs::read(path)?, context: Default::default() })
    }

    /// Optional: load "word\tphonemes[\tcount]" lines learned from running text.
    pub fn load_context(&mut self, path: &str) -> std::io::Result<usize> {
        for line in std::fs::read_to_string(path)?.lines() {
            let mut it = line.split('\t');
            if let (Some(w), Some(p)) = (it.next(), it.next()) {
                self.context.insert(w.to_string(), p.to_string());
            }
        }
        Ok(self.context.len())
    }

    /// Binary search over "word\tphonemes\n" lines sorted by word (byte order).
    pub fn lookup(&self, word: &str) -> Option<&str> {
        if let Some(p) = self.context.get(word) { return Some(p.as_str()); }
        let d = &self.data;
        let key = word.as_bytes();
        let (mut lo, mut hi) = (0usize, d.len());
        while lo < hi {
            let mid = (lo + hi) / 2;
            // back up to the start of the line containing `mid`
            let mut s = mid;
            while s > lo && d[s - 1] != b'\n' { s -= 1; }
            let mut e = s;
            while e < d.len() && d[e] != b'\n' { e += 1; }
            let line = &d[s..e];
            let tab = line.iter().position(|&b| b == b'\t').unwrap_or(line.len());
            match line[..tab].cmp(key) {
                Ordering::Equal => return std::str::from_utf8(&line[(tab + 1).min(line.len())..]).ok(),
                Ordering::Less => lo = e + 1,
                Ordering::Greater => hi = s,
            }
        }
        None
    }
}

/// Encode a phoneme string as a letters-only word that survives text normalisation.
pub fn encode_marker(phonemes: &str) -> String {
    let mut out = String::from(MARK_START);
    for b in phonemes.as_bytes() {
        out.push((b'a' + (b >> 4)) as char);
        out.push((b'a' + (b & 15)) as char);
    }
    out.push_str(MARK_END);
    out
}

pub fn decode_marker(word: &str) -> Option<String> {
    let w = word.to_ascii_lowercase();
    let body = w.strip_prefix(MARK_START)?.strip_suffix(MARK_END)?;
    if body.len() % 2 != 0 { return None; }
    let b = body.as_bytes();
    let mut bytes = Vec::with_capacity(b.len() / 2);
    for pair in b.chunks(2) {
        if !(b'a'..=b'p').contains(&pair[0]) || !(b'a'..=b'p').contains(&pair[1]) { return None; }
        bytes.push(((pair[0] - b'a') << 4) | (pair[1] - b'a'));
    }
    String::from_utf8(bytes).ok()
}

const VOWEL_START: &str = "AIOWYaeiouæɑɒɔəɛɜɪʊʌᵊᵻɐ";

fn starts_with_vowel(ph: &str) -> bool {
    ph.chars().find(|c| *c != 'ˈ' && *c != 'ˌ').map_or(false, |c| VOWEL_START.contains(c))
}

/// Words whose pronunciation depends on the next word.  (word, before a vowel, otherwise)
const CONTEXT: &[(&str, &str, &str)] = &[
    ("the", "ði", "ðə"),
    ("to", "tʊ", "tə"),
    ("a", "ɐ", "ɐ"),
    ("an", "ɐn", "ɐn"),
];

#[derive(Debug)]
enum Tok { Word(String), Punct(String) }

fn tokenize(text: &str) -> Vec<Tok> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let cs: Vec<char> = text.chars().collect();
    for (i, &c) in cs.iter().enumerate() {
        let apostrophe = (c == '\'' || c == '’') && !cur.is_empty()
            && cs.get(i + 1).map_or(false, |n| n.is_alphabetic());
        if c.is_alphanumeric() || apostrophe {
            cur.push(if c == '’' { '\'' } else { c });
        } else {
            if !cur.is_empty() { out.push(Tok::Word(std::mem::take(&mut cur))); }
            if !c.is_whitespace() { out.push(Tok::Punct(c.to_string())); }
        }
    }
    if !cur.is_empty() { out.push(Tok::Word(cur)); }
    out
}

fn word_phonemes(dict: &Dict, word: &str, missing: &mut Vec<String>) -> String {
    if let Some(p) = decode_marker(word) { return p; }
    let w = word.to_lowercase();
    if let Some(p) = dict.lookup(&w) { return p.to_string(); }
    // possessive / plural of a known word
    for (suffix, voiced, unvoiced) in [("'s", "z", "s"), ("s", "z", "s")] {
        if let Some(stem) = w.strip_suffix(suffix) {
            if let Some(p) = dict.lookup(stem) {
                let last = p.chars().last().unwrap_or('a');
                let tail = if "szʃʒʧʤ".contains(last) { "ᵻz" } else if "ptkfθ".contains(last) { unvoiced } else { voiced };
                return format!("{}{}", p, tail);
            }
        }
    }
    // hyphen-free compounds and unknowns: spell nothing, report it, say the letters
    missing.push(w.clone());
    w.chars().filter_map(|c| dict.lookup(&c.to_string()).map(|s| s.to_string())).collect::<Vec<_>>().join(" ")
}

/// Convert one sentence to a Kokoro phoneme string.  Unknown words are appended to `missing`.
pub fn phonemize(dict: &Dict, text: &str, missing: &mut Vec<String>) -> String {
    let toks = tokenize(text);
    let mut ph: Vec<Option<String>> = toks.iter().map(|t| match t {
        Tok::Word(w) => Some(word_phonemes(dict, w, missing)),
        Tok::Punct(_) => None,
    }).collect();
    // context-dependent function words
    for i in 0..toks.len() {
        if let Tok::Word(w) = &toks[i] {
            let lw = w.to_lowercase();
            if let Some((_, before_vowel, otherwise)) = CONTEXT.iter().find(|(k, _, _)| *k == lw) {
                let next = (i + 1..toks.len()).find_map(|j| ph[j].as_ref());
                let v = next.map_or(false, |p| starts_with_vowel(p));
                ph[i] = Some((if v { before_vowel } else { otherwise }).to_string());
            }
        }
    }
    let mut out = String::new();
    for (i, t) in toks.iter().enumerate() {
        match t {
            Tok::Word(_) => {
                if !out.is_empty() && !out.ends_with(|c: char| "“(\"".contains(c)) && !out.ends_with(' ') { out.push(' '); }
                out.push_str(ph[i].as_deref().unwrap_or(""));
            }
            Tok::Punct(p) => {
                let c = p.chars().next().unwrap();
                if "“(".contains(c) || c == '—' {
                    if !out.is_empty() && !out.ends_with(' ') { out.push(' '); }
                    out.push(c);
                    if c == '—' { out.push(' '); }
                } else if ".,!?;:…”)".contains(c) {
                    out.push(c);
                } else if c == '"' {
                    // straight quote: opening if at start or after a space
                    if out.is_empty() || out.ends_with(' ') { out.push('“'); } else { out.push('”'); }
                } else if c == '-' {
                    out.push(' ');
                }
            }
        }
    }
    out.trim().to_string()
}
