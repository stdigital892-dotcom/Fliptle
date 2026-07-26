"""Stage 3 — boundary snapping.

Naive clippers bleed at the edges: they start mid-sentence, end mid-word, and
open on two seconds of dead air. This stage fixes the edges against the word
and sentence grid, then drops anything that still cannot work.
"""

from __future__ import annotations

import bisect
import logging
from pathlib import Path
from typing import Any

from . import text as T
from .util import write_json

log = logging.getLogger("clip.boundaries")

SNAP_WINDOW = 3.0     # how far to look for a sentence edge
MAX_DRIFT = 15.0      # beyond this, treat the timestamp as hallucinated
SILENCE_GAP = 0.4     # dead air at an edge worth trimming
LEAD_IN = 0.10        # breath before the first word
RING_OUT = 0.20       # let the last word finish
HOOK_WINDOW = 2.0     # first N seconds must contain a content word
IDEAL_LEN = 35.0
MAX_OVERLAP = 0.5     # of the shorter clip, before we call it a duplicate


def _snap_start(start: float, starts: list[float]) -> tuple[float, str | None]:
    """Nearest sentence start within SNAP_WINDOW, preferring earlier."""
    lo = bisect.bisect_left(starts, start - SNAP_WINDOW)
    hi = bisect.bisect_right(starts, start + SNAP_WINDOW)
    window = starts[lo:hi]
    if window:
        best = min(window, key=lambda s: (abs(s - start), s > start))
        return best, None
    # Nothing close: fall back to the sentence we are sitting inside, so we
    # still never begin mid-sentence.
    i = bisect.bisect_right(starts, start) - 1
    if i >= 0:
        return starts[i], f"start snapped {start - starts[i]:.1f}s back"
    return starts[0], "start moved to first sentence"


def _snap_end(end: float, ends: list[float]) -> tuple[float, str | None]:
    """Nearest sentence end within SNAP_WINDOW, preferring later."""
    lo = bisect.bisect_left(ends, end - SNAP_WINDOW)
    hi = bisect.bisect_right(ends, end + SNAP_WINDOW)
    window = ends[lo:hi]
    if window:
        best = min(window, key=lambda e: (abs(e - end), e < end))
        return best, None
    i = bisect.bisect_left(ends, end)
    if i < len(ends):
        return ends[i], f"end snapped {ends[i] - end:.1f}s forward"
    return ends[-1], "end moved to last sentence"


def _fit_length(
    start: float,
    end: float,
    ends: list[float],
    min_len: float,
    max_len: float,
) -> tuple[float, str | None]:
    """Pull the end to a sentence boundary that satisfies the length bounds."""
    dur = end - start
    if min_len <= dur <= max_len:
        return end, None

    if dur > max_len:
        i = bisect.bisect_right(ends, start + max_len) - 1
        if i >= 0 and ends[i] - start >= min_len:
            return ends[i], f"trimmed to {ends[i] - start:.1f}s"
        return end, "too long"

    i = bisect.bisect_left(ends, start + min_len)
    if i < len(ends) and ends[i] - start <= max_len:
        return ends[i], f"extended to {ends[i] - start:.1f}s"
    return end, "too short"


def _overlap(a: dict[str, Any], b: dict[str, Any]) -> float:
    lo = max(a["start"], b["start"])
    hi = min(a["end"], b["end"])
    if hi <= lo:
        return 0.0
    shorter = min(a["end"] - a["start"], b["end"] - b["start"])
    return (hi - lo) / shorter if shorter else 0.0


def refine(
    raw: dict[str, Any],
    transcript: dict[str, Any],
    work_dir: Path,
    *,
    min_len: float,
    max_len: float,
) -> dict[str, Any]:
    """Snap, trim, filter and dedupe; write ``work/candidates.json``."""
    words = transcript["words"]
    sentences = T.build_sentences(words)
    starts = [s["start"] for s in sentences]
    ends = [s["end"] for s in sentences]

    kept: list[dict[str, Any]] = []
    dropped: list[dict[str, Any]] = []

    for cand in raw["candidates"]:
        c = dict(cand)
        notes: list[str] = []
        orig = (float(c["start"]), float(c["end"]))
        start, end = orig

        if end <= start:
            dropped.append({**c, "drop_reason": "end before start"})
            continue

        start, note = _snap_start(start, starts)
        if note:
            notes.append(note)
        end, note = _snap_end(end, ends)
        if note:
            notes.append(note)

        if end <= start:
            dropped.append({**c, "drop_reason": "collapsed while snapping"})
            continue

        # A model can hallucinate a timestamp. Snapping would happily drag it
        # onto a real sentence and produce a plausible-looking clip of the
        # wrong moment, so refuse rather than relocate.
        drift = max(abs(start - orig[0]), abs(end - orig[1]))
        if drift > MAX_DRIFT:
            dropped.append({
                **c,
                "drop_reason": f"timestamp off the sentence grid by {drift:.0f}s",
            })
            continue

        # Trim dead air using the word gaps at each edge.
        span = T.words_in(words, start, end)
        if not span:
            dropped.append({**c, "drop_reason": "no words in span"})
            continue
        if span[0]["start"] - start > SILENCE_GAP:
            gap = span[0]["start"] - start
            start = span[0]["start"] - LEAD_IN
            notes.append(f"trimmed {gap:.1f}s of leading silence")
        if end - span[-1]["end"] > SILENCE_GAP:
            gap = end - span[-1]["end"]
            end = span[-1]["end"] + RING_OUT
            notes.append(f"trimmed {gap:.1f}s of trailing silence")

        end, note = _fit_length(start, end, ends, min_len, max_len)
        if note in ("too long", "too short"):
            dropped.append({
                **c,
                "start": round(start, 2), "end": round(end, 2),
                "drop_reason": f"{note} ({end - start:.1f}s)",
            })
            continue
        if note:
            notes.append(note)

        # A dead hook kills the clip: the first couple of seconds must say
        # something, not just "so, uh, the thing is".
        hook_words = T.words_in(words, start, start + HOOK_WINDOW)
        if not any(T.is_content_word(w["word"]) for w in hook_words):
            dropped.append({
                **c,
                "start": round(start, 2), "end": round(end, 2),
                "drop_reason": "dead hook (no content word in first "
                               f"{HOOK_WINDOW:g}s)",
            })
            continue

        span = T.words_in(words, start, end)
        tokens = [w["word"] for w in span]
        confs = [w["confidence"] for w in span]

        c.update({
            "start": round(start, 2),
            "end": round(end, 2),
            "duration": round(end - start, 2),
            "requested": [round(orig[0], 2), round(orig[1], 2)],
            "snap_notes": notes,
            "hook_actual": " ".join(t.strip() for t in tokens[:12]).strip(),
            "text": " ".join(t.strip() for t in tokens).strip(),
            "n_words": len(span),
            "word_rate": round(len(span) / (end - start), 2),
            "mean_confidence": round(sum(confs) / len(confs), 3) if confs else 0.0,
        })
        c["emphasis_words"] = _verify_emphasis(c.get("emphasis_words") or [], tokens)
        kept.append(c)

    kept = _dedupe(kept, dropped)
    kept.sort(key=lambda c: c["start"])
    for i, c in enumerate(kept):
        c["clip_id"] = f"c{i:03d}"

    data = {
        **{k: v for k, v in raw.items() if k != "candidates"},
        "min_len": min_len,
        "max_len": max_len,
        "n_in": len(raw["candidates"]),
        "n_kept": len(kept),
        "candidates": kept,
        "dropped": dropped,
    }
    write_json(work_dir / "candidates.json", data)

    log.info("stage 3: %s in -> %s kept, %s dropped",
             len(raw["candidates"]), len(kept), len(dropped))
    if dropped:
        reasons: dict[str, int] = {}
        for d in dropped:
            key = d["drop_reason"].split("(")[0].strip()
            reasons[key] = reasons.get(key, 0) + 1
        for reason, n in sorted(reasons.items(), key=lambda x: -x[1]):
            log.info("  dropped %2d: %s", n, reason)
    return data


def _verify_emphasis(requested: list[str], tokens: list[str]) -> list[str]:
    """Keep only emphasis words that actually occur in the clip — the model
    sometimes paraphrases, and captions can only highlight real words."""
    present = {T.norm(t) for t in tokens}
    return [w for w in requested if T.norm(w) in present]


def _dedupe(
    kept: list[dict[str, Any]],
    dropped: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Greedy: strongest hook wins, near-ideal length breaks ties."""
    ordered = sorted(
        kept,
        key=lambda c: (
            -float(c.get("hook_strength") or 0.0),
            abs(c["duration"] - IDEAL_LEN),
        ),
    )
    out: list[dict[str, Any]] = []
    for c in ordered:
        clash = next((p for p in out if _overlap(c, p) > MAX_OVERLAP), None)
        if clash is not None:
            dropped.append({
                **c,
                "drop_reason": f"overlaps {clash['start']:.1f}-{clash['end']:.1f}s",
            })
            continue
        out.append(c)
    return out
