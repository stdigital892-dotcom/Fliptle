# Vertical clip generator

Takes one long-form video file on disk and produces ranked, ready-to-post
vertical shorts with captions, punch-in zooms and sound effects.

Local files only — pass a path to an `.mp4`/`.mkv`. No downloading, no
accounts, no server. The only network call in the whole pipeline is one
optional LLM request for candidate selection, and it is skippable.

## Status

| Stage | What it does | Output | State |
|---|---|---|---|
| 1 | Transcribe (faster-whisper, word timestamps, VAD) | `work/transcript.json` | done |
| 2 | Candidate generation (LLM, or offline heuristic) | `work/candidates.raw.json` | done |
| 3 | Boundary snapping and filtering | `work/candidates.json` | done |
| 4 | Ranking | `work/ranked.json` | not started |
| 5 | Face-tracked reframe | `work/reframe/<id>.json` | not started |
| 6 | Render | `out/<id>.mp4`, `out/clips.json` | not started |

## Install

```bash
pip install -r requirements.txt
# ffmpeg and ffprobe must be on PATH
```

Optional, and each independently so:

- `indic-transliteration` — only for `--romanize`.
- `anthropic` (or `openai`) plus `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` —
  only for LLM candidate selection.

## Use

```bash
python clip.py input.mp4 --n 5 --min-len 20 --max-len 75
```

Right now that runs stages 1-3 and prints the candidate list to the terminal.
Nothing is rendered yet.

Re-run one stage without redoing the others:

```bash
python clip.py input.mp4 --stage transcribe
python clip.py input.mp4 --stage candidates,boundaries
```

Transcription is cached on a content hash of the input file, so the same
video is never transcribed twice — even across runs and across changes to
every other flag. `--force` overrides that.

### Flags that matter

| Flag | Meaning |
|---|---|
| `--n` | how many clips to render (stage 6); today it just marks the first N in the printout |
| `--min-len` / `--max-len` | clip length bounds in seconds |
| `--stage` | comma-separated stages to run; default all implemented |
| `--romanize` | transliterate Devanagari transcript output to Roman script |
| `--lexicon` | use a different Hinglish lexicon JSON |
| `--language` | force a Whisper language code instead of detecting |
| `--whisper-model` | override the model size |
| `--no-llm` | skip the model, use the offline heuristic |
| `--provider` / `--llm-model` / `--llm-effort` | LLM overrides |
| `--force` | ignore the transcript cache |

## Hindi/English code-switching

Whisper degrades on mid-sentence code-switching, and it often emits
Devanagari where the audience reads Roman script. Two things help you see how
bad it is on your own footage:

**`--romanize`** converts Devanagari to Roman in three layers, in order:

1. **Lexicon** — `assets/hinglish_lexicon.json`, ~1250 of the most frequent
   Hindi words mapped to their conventional Hinglish spelling
   (यह→`yeh`, वह→`woh`, हमने→`humne`, नहीं→`nahi`, क्यों→`kyun`). A lexicon
   hit beats every rule. It also carries the English-in-Devanagari loanwords
   that dominate this kind of content (बिजनेस→`business`, प्रॉफिट→`profit`),
   which rules would otherwise mangle into `bijanes` and `prophit`.
2. **Vowel length** — long vowels are doubled, never collapsed:
   आ→`aa`, ई→`ee`, ऊ→`oo`. काम→`kaam`, बात→`baat`, साल→`saal`. Collapsing
   changes the word. The one exception is word-finally, where the convention
   is a single letter (`accha`, `bada`, `seekhi`) — that matches how every
   lexicon entry with the same ending is spelled.
3. **Rules** — word-final schwa deletion plus consonant fixups, as the
   fallback for anything the lexicon has not seen. This is what turns
   `paryaavaran sanrakshan atyant aavashyak` out of unseen literary Hindi
   rather than Sanskrit-flavoured mush.

The original token is always kept alongside as `word_original`.

**Growing the lexicon.** Every word that falls through to layer 3 is logged.
After a run you get the top misses in the terminal and the full list in
`work/romanization_misses.json`:

```json
"paste_into_lexicon": {
  "पर्यावरण": "paryaavaran",
  "छोड़ी": "chhodi"
}
```

Correct any spellings you disagree with and paste the block into the `words`
object of `assets/hinglish_lexicon.json`. That file is the source of truth —
edit it freely, it is never regenerated. Keys are NFC-normalised on load and
matched with and without nukta, so both spellings of ज़/ज find the same
entry. `--lexicon path.json` points at a different file.

Known limitation: medial schwa is not deleted, so an unseen word like हमने
would come out `hamane` rather than `humne` — which is exactly why the
frequent ones live in the lexicon instead.

**Per-word confidence** is persisted for every word and summarised after
every transcribe run: a histogram, the fraction below 0.55 and 0.35, and the
15 worst words with timestamps. Jump to those timestamps in the source video
and you will see exactly where it is failing. On code-switched audio expect
the confidence floor to sit noticeably lower than on monolingual audio, with
the worst words clustered at the switch points.

`mean_confidence` also rides along on every candidate in
`work/candidates.json`, so you can spot a clip whose text is a mess before
bothering to watch it.

## What stage 3 actually does

This is where naive clippers bleed. For every candidate:

- Snap `start` back to the nearest sentence start within 3s. Never begins
  mid-sentence — if nothing is in range it falls back to the start of the
  containing sentence.
- Snap `end` forward to the nearest sentence end within 3s.
- Trim leading/trailing dead air over 400ms using the word gaps.
- Drop anything whose first 2 seconds contain no content word. A clip opening
  on "so, uh, toh phir" is dead on arrival.
- Drop anything a model hallucinated: if snapping would have to drag a
  timestamp more than 15s to reach the sentence grid, the timestamp is wrong
  and relocating it would produce a plausible-looking clip of the wrong
  moment.
- Fit to the length bounds by moving `end` to another sentence boundary; drop
  if that is impossible.
- Drop emphasis words that do not literally occur in the clip — captions can
  only highlight words that were actually said.
- Dedupe overlaps above 50%, strongest hook wins.

Everything dropped is kept in `work/candidates.json` under `dropped` with a
reason, so you can tell "the model picked badly" apart from "stage 3 is too
aggressive".

## Files

```
clip.py                  CLI entrypoint
clipper/transcribe.py    stage 1
clipper/candidates.py    stage 2 (LLM prompt + offline heuristic + energy envelope)
clipper/boundaries.py    stage 3
clipper/llm.py           provider wrapper (Anthropic / OpenAI)
clipper/text.py          sentence splitting, romanizer, content-word tests
clipper/util.py          hashing, ffprobe, JSON IO
assets/hinglish_lexicon.json   editable Devanagari -> Hinglish lexicon
work/                    intermediate JSON (gitignored)
out/                     final clips + clips.json (gitignored)
```
