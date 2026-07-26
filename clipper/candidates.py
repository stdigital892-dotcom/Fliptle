"""Stage 2 — candidate generation.

Primary path: one LLM call over the timestamped transcript asking for 20-40
segments. Fallback path (no API key, or the call fails): sentence-boundary
windows scored on audio-energy variance and keyword density, so the tool still
runs fully offline.
"""

from __future__ import annotations

import array
import logging
import math
import subprocess
from pathlib import Path
from typing import Any

from . import llm
from . import text as T
from .util import have, read_json, write_json

log = logging.getLogger("clip.candidates")

# Roughly 4 chars/token; keep a wide margin below the context window and split
# very long videos into overlapping windows.
MAX_CHUNK_CHARS = 320_000
CHUNK_OVERLAP_S = 120.0

CANDIDATE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "candidates": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "start": {"type": "number"},
                    "end": {"type": "number"},
                    "hook": {"type": "string"},
                    "reason": {"type": "string"},
                    "emphasis_words": {
                        "type": "array",
                        "items": {"type": "string"},
                    },
                    "topic": {"type": "string"},
                    "self_contained": {"type": "boolean"},
                    "hook_strength": {"type": "number"},
                },
                "required": [
                    "start", "end", "hook", "reason", "emphasis_words",
                    "topic", "self_contained", "hook_strength",
                ],
                "additionalProperties": False,
            },
        }
    },
    "required": ["candidates"],
    "additionalProperties": False,
}

SYSTEM_PROMPT = """\
You select short-form vertical video clips from a long-form transcript.

You will be given a transcript as timestamped lines: `[123.4] sentence text`.
The number is the start time of that line in seconds.

Return 20-40 candidate segments. Each must be a moment that works as a
standalone short with no prior context.

What makes a good candidate:
- A complete thought: setup, then payoff. It resolves within the clip.
- A strong claim, number, or concrete stake inside the first 3 seconds.
- A story arc — something happens and lands.
- A question followed by its answer.
- A contrarian or counterintuitive statement.
- An emotional peak: frustration, delight, conviction, a punchline.

Reject:
- Anything that only makes sense if you heard the previous 10 minutes.
- Segments opening on a pronoun or connective referring to unseen material
  ("so that's why...", "and then he said..." with no antecedent).
- Setup with no payoff, or payoff with no setup.
- Pure filler, small talk, admin, or repetition of an earlier point.

Rules:
- Each segment must be between {min_len} and {max_len} seconds long.
- Prefer around 35 seconds.
- Segments must not overlap each other.
- Use timestamps from the transcript; start on a line boundary.
- The transcript may be Hindi/English code-switched (Hinglish). That is fine
  and normal — judge the meaning, not the language mix. Transcription errors
  are expected; do not pick a segment whose key line looks garbled.

Field notes:
- `hook`: the actual opening words of the clip, verbatim from the transcript.
- `reason`: one sentence on why this works as a short.
- `emphasis_words`: 2-5 words spoken in the segment that should be visually
  emphasised in captions. Must appear verbatim in the segment.
- `topic`: 2-5 words.
- `self_contained`: true only if it needs zero prior context.
- `hook_strength`: 0.0-1.0, how strongly the first 3 seconds grab attention.

Respond with JSON only, matching the requested schema. No prose, no markdown.
"""


# --------------------------------------------------------------------------
# transcript -> prompt


def render_transcript(sentences: list[dict[str, Any]]) -> str:
    return "\n".join(f"[{s['start']:.1f}] {s['text']}" for s in sentences)


def _chunk(sentences: list[dict[str, Any]]) -> list[list[dict[str, Any]]]:
    """Split into windows small enough to send, overlapping so a moment on a
    boundary is still visible whole to one of the calls."""
    rendered = render_transcript(sentences)
    if len(rendered) <= MAX_CHUNK_CHARS:
        return [sentences]

    n_chunks = math.ceil(len(rendered) / MAX_CHUNK_CHARS)
    span = sentences[-1]["end"] / n_chunks
    chunks: list[list[dict[str, Any]]] = []
    for i in range(n_chunks):
        lo = max(0.0, i * span - (CHUNK_OVERLAP_S if i else 0.0))
        hi = (i + 1) * span + (CHUNK_OVERLAP_S if i < n_chunks - 1 else 0.0)
        window = [s for s in sentences if lo <= s["start"] < hi]
        if window:
            chunks.append(window)
    log.info("transcript split into %s LLM chunks", len(chunks))
    return chunks


# --------------------------------------------------------------------------
# LLM path


def generate_llm(
    sentences: list[dict[str, Any]],
    *,
    provider: str,
    model: str | None,
    effort: str | None,
    min_len: float,
    max_len: float,
) -> list[dict[str, Any]]:
    system = SYSTEM_PROMPT.format(min_len=int(min_len), max_len=int(max_len))
    out: list[dict[str, Any]] = []
    for i, chunk in enumerate(_chunk(sentences), 1):
        user = render_transcript(chunk)
        data = llm.complete_json(
            system, user, CANDIDATE_SCHEMA,
            provider=provider, model=model, effort=effort,
        )
        got = data.get("candidates") or []
        log.info("chunk %s -> %s candidates", i, len(got))
        for c in got:
            c["source"] = "llm"
            out.append(c)
    return out


# --------------------------------------------------------------------------
# Heuristic path


def audio_energy(
    video: str | Path,
    work_dir: Path,
    digest: str,
    hop: float = 0.1,
) -> dict[str, Any] | None:
    """Coarse RMS envelope of the audio track, cached per file hash.

    Returns None (with a warning) if ffmpeg is unavailable — the heuristic
    degrades to keyword density only rather than failing outright.
    """
    cache = work_dir / "cache" / f"{digest[:16]}.energy.json"
    if cache.exists():
        return read_json(cache)
    if not have("ffmpeg"):
        log.warning("ffmpeg not found — scoring without audio energy")
        return None

    sr = 16000
    frame = int(sr * hop)
    log.info("extracting audio energy envelope ...")
    proc = subprocess.Popen(
        [
            "ffmpeg", "-v", "error", "-i", str(video),
            "-vn", "-ac", "1", "-ar", str(sr), "-f", "s16le", "-",
        ],
        stdout=subprocess.PIPE,
    )
    rms: list[float] = []
    leftover = array.array("h")
    assert proc.stdout is not None
    while True:
        raw = proc.stdout.read(frame * 2 * 600)
        if not raw:
            break
        buf = array.array("h")
        buf.frombytes(raw[: len(raw) // 2 * 2])
        samples = leftover + buf
        n = len(samples) // frame
        for i in range(n):
            window = samples[i * frame:(i + 1) * frame]
            acc = 0
            for s in window:
                acc += s * s
            rms.append(math.sqrt(acc / frame) / 32768.0)
        leftover = samples[n * frame:]
    proc.wait()
    if proc.returncode != 0:
        log.warning("ffmpeg audio decode failed — scoring without energy")
        return None

    data = {"hop": hop, "rms": [round(v, 5) for v in rms]}
    write_json(cache, data)
    log.info("energy envelope: %s frames", len(rms))
    return data


def _energy_variance(energy: dict[str, Any] | None, start: float, end: float) -> float:
    if not energy:
        return 0.0
    hop = energy["hop"]
    rms = energy["rms"]
    i0, i1 = int(start / hop), min(int(end / hop), len(rms))
    window = rms[i0:i1]
    if len(window) < 4:
        return 0.0
    mean = sum(window) / len(window)
    var = sum((v - mean) ** 2 for v in window) / len(window)
    return math.sqrt(var)


def generate_heuristic(
    sentences: list[dict[str, Any]],
    words: list[dict[str, Any]],
    energy: dict[str, Any] | None,
    *,
    min_len: float,
    max_len: float,
    target: int = 35,
) -> list[dict[str, Any]]:
    """Sentence-anchored windows scored on energy variance + keyword density.

    Deliberately dumb. It exists so the pipeline runs with no API key; the LLM
    path is the one that should pick good moments.
    """
    ideal = 35.0
    scored: list[tuple[float, dict[str, Any]]] = []

    for i, s in enumerate(sentences):
        # Grow the window sentence by sentence until it is long enough.
        for j in range(i, len(sentences)):
            start, end = s["start"], sentences[j]["end"]
            dur = end - start
            if dur < min_len:
                continue
            if dur > max_len:
                break

            toks = [w["word"] for w in words[s["i0"]:sentences[j]["i1"] + 1]]
            if not toks:
                break
            density = len(toks) / dur
            kw = T.keyword_score(toks)
            evar = _energy_variance(energy, start, end)
            len_fit = 1.0 - min(abs(dur - ideal) / ideal, 1.0)
            hook_toks = [
                w["word"] for w in T.words_in(words, start, start + 3.0)
            ]
            hook = T.keyword_score(hook_toks) + (
                0.3 if any(T.is_content_word(t) for t in hook_toks) else 0.0
            )

            score = (
                2.5 * kw
                + 1.5 * min(evar / 0.12, 1.0)
                + 1.0 * min(density / 3.5, 1.0)
                + 1.0 * len_fit
                + 1.0 * min(hook, 1.0)
            )
            text = " ".join(t.strip() for t in toks).strip()
            scored.append((score, {
                "start": round(start, 2),
                "end": round(end, 2),
                "hook": " ".join(t.strip() for t in toks[:12]).strip(),
                "reason": (
                    f"heuristic: keyword density {kw:.2f}, energy variance "
                    f"{evar:.3f}, {density:.1f} words/sec"
                ),
                "emphasis_words": T.salient_words(toks),
                "topic": " ".join(t.strip() for t in toks[:4]).strip(),
                "self_contained": False,   # unknowable without a model
                "hook_strength": round(min(hook, 1.0), 3),
                "heuristic_score": round(score, 4),
                "source": "heuristic",
                "_text": text,
            }))
            break  # one window per anchor sentence

    scored.sort(key=lambda x: -x[0])
    picked: list[dict[str, Any]] = []
    for _, cand in scored:
        if any(_overlap(cand, p) > 0.25 for p in picked):
            continue
        picked.append(cand)
        if len(picked) >= target:
            break
    picked.sort(key=lambda c: c["start"])
    for c in picked:
        c.pop("_text", None)
    log.info("heuristic produced %s candidates", len(picked))
    return picked


def _overlap(a: dict[str, Any], b: dict[str, Any]) -> float:
    """Intersection over the shorter of the two spans."""
    lo = max(a["start"], b["start"])
    hi = min(a["end"], b["end"])
    if hi <= lo:
        return 0.0
    shorter = min(a["end"] - a["start"], b["end"] - b["start"])
    return (hi - lo) / shorter if shorter else 0.0


# --------------------------------------------------------------------------


def generate(
    transcript: dict[str, Any],
    video: str | Path,
    work_dir: Path,
    *,
    min_len: float,
    max_len: float,
    provider: str | None = None,
    model: str | None = None,
    effort: str | None = None,
    use_llm: bool = True,
) -> dict[str, Any]:
    """Run stage 2 and write ``work/candidates.raw.json``."""
    words = transcript["words"]
    sentences = T.build_sentences(words)
    log.info("%s words -> %s sentences", len(words), len(sentences))

    candidates: list[dict[str, Any]] = []
    chosen = llm.detect_provider(provider) if use_llm else None

    if chosen:
        try:
            candidates = generate_llm(
                sentences,
                provider=chosen, model=model, effort=effort,
                min_len=min_len, max_len=max_len,
            )
        except Exception as exc:
            log.error("LLM candidate generation failed (%s)", exc)
            log.warning("falling back to the offline heuristic")
            candidates = []
    elif use_llm:
        log.warning(
            "no ANTHROPIC_API_KEY / OPENAI_API_KEY set — using the offline "
            "heuristic. Expect noticeably worse moment-picking."
        )

    method = "llm" if candidates else "heuristic"
    if not candidates:
        energy = audio_energy(video, work_dir, transcript["audio_hash"])
        candidates = generate_heuristic(
            sentences, words, energy, min_len=min_len, max_len=max_len,
        )

    for i, c in enumerate(candidates):
        c.setdefault("clip_id", f"c{i:03d}")

    data = {
        "audio_hash": transcript["audio_hash"],
        "method": method,
        "provider": chosen if method == "llm" else None,
        "model": (model or (
            llm.ANTHROPIC_DEFAULT_MODEL if chosen == "anthropic"
            else llm.OPENAI_DEFAULT_MODEL
        )) if method == "llm" else None,
        "min_len": min_len,
        "max_len": max_len,
        "n_sentences": len(sentences),
        "candidates": candidates,
    }
    write_json(work_dir / "candidates.raw.json", data)
    log.info("stage 2: %s candidates via %s", len(candidates), method)
    return data
