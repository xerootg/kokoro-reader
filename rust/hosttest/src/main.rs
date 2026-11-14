//! kokoro-hosttest <en_dict.tsv> < sentences.txt
//! Prints one phoneme line per input line; unknown words go to stderr.
#[path = "../../src/kokoro_g2p.rs"]
mod kokoro_g2p;
use std::io::BufRead;

fn main() {
    let dict_path = std::env::args().nth(1).expect("usage: kokoro-hosttest <en_dict.tsv>");
    let dict = kokoro_g2p::Dict::load(&dict_path).expect("cannot read dictionary");
    let m = kokoro_g2p::encode_marker("mondɾaɣˈon");
    assert_eq!(kokoro_g2p::decode_marker(&m).as_deref(), Some("mondɾaɣˈon"));
    let mut missing = Vec::new();
    for line in std::io::stdin().lock().lines() {
        let line = line.unwrap();
        println!("{}", kokoro_g2p::phonemize(&dict, &line, &mut missing));
    }
    missing.sort(); missing.dedup();
    eprintln!("unknown words: {} {:?}", missing.len(), &missing[..missing.len().min(40)]);
}
