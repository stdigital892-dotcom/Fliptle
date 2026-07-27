"""Stage 1 — transcription.

faster-whisper with word timestamps and VAD, cached on a content hash of the
input file so the same video is never transcribed twice.
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

from . import text as T
from .util import file_hash, read_json, write_json

log = logging.getLogger("clip.transcribe")

CONF_WARN = 0.55   # per-word probability below which we call it shaky
CONF_BAD = 0.35    # ...and below which it is probably wrong


def _pick_device(requested: str | None) -> tuple[str, str, str]:
    """-> (device, compute_type, default_model_size)."""
    if requested in ("cpu", "cuda"):
        device = requested
    else:
        device = "cpu"
        try:
            import ctranslate2

            if ctranslate2.get_cuda_device_count() > 0:
                device = "cuda"
        except Exception as exc:  # pragma: no cover - depends on local build
            log.debug("CUDA probe failed (%s); using CPU", exc)
    if device == "cuda":
        return "cuda", "float16", "large-v3"
    return "cpu", "int8", "base"


def _cache_path(work_dir: Path, digest: str) -> Path:
    return work_dir / "cache" / f"{digest[:16]}.transcript.json"


def transcribe(
    video: str | Path,
    work_dir: Path,
    *,
    model_size: str | None = None,
    device: str | None = None,
    language: str | None = None,
    romanize: bool = False,
    lexicon: str | Path | None = None,
    force: bool = False,
) -> dict[str, Any]:
    """Transcribe ``video``, returning the transcript dict.

    Also writes ``work/transcript.json`` and a hash-keyed cache copy.
    """
    video = Path(video)
    digest = file_hash(video)
    cache = _cache_path(work_dir, digest)
    out = work_dir / "transcript.json"

    if cache.exists() and not force:
        log.info("transcript cache hit (%s)", cache.name)
        data = read_json(cache)
        if romanize and not data.get("romanized"):
            log.info("cached transcript is not romanized; re-romanizing")
            data = _apply_romanize(data, work_dir, lexicon)
            write_json(cache, data)
        write_json(out, data)
        _report_confidence(data)
        return data

    from faster_whisper import WhisperModel  # lazy: heavy import

    dev, compute, default_size = _pick_device(device)
    size = model_size or default_size
    log.info("loading faster-whisper %s on %s (%s)", size, dev, compute)
    model = WhisperModel(size, device=dev, compute_type=compute)

    log.info("transcribing %s ...", video.name)
    segments, info = model.transcribe(
        str(video),
        word_timestamps=True,
        vad_filter=True,
        beam_size=5,
        language=language,
    )

    words: list[dict[str, Any]] = []
    segs: list[dict[str, Any]] = []
    for seg in segments:  # generator — consuming it is what does the work
        seg_words = []
        for w in (seg.words or []):
            entry = {
                "word": w.word,
                "start": round(w.start, 3),
                "end": round(w.end, 3),
                "confidence": round(float(w.probability), 4),
            }
            words.append(entry)
            seg_words.append(entry)
        segs.append({
            "start": round(seg.start, 3),
            "end": round(seg.end, 3),
            "text": seg.text.strip(),
            "n_words": len(seg_words),
        })
        if len(segs) % 25 == 0:
            log.info("  ... %s segments, %.0fs of audio", len(segs), seg.end)

    data: dict[str, Any] = {
        "source": str(video),
        "audio_hash": digest,
        "model": size,
        "device": dev,
        "language": info.language,
        "language_probability": round(float(info.language_probability), 4),
        "duration": round(float(info.duration), 3),
        "romanized": False,
        "segments": segs,
        "words": words,
    }

    if romanize:
        data = _apply_romanize(data, work_dir, lexicon)

    write_json(cache, data)
    write_json(out, data)
    log.info(
        "transcribed %s words / %s segments (lang=%s p=%.2f)",
        len(words), len(segs), data["language"], data["language_probability"],
    )
    _report_confidence(data)
    return data


def _apply_romanize(
    data: dict[str, Any],
    work_dir: Path | None = None,
    lexicon: str | Path | None = None,
) -> dict[str, Any]:
    """Convert Devanagari to Roman in place, keeping the original around.

    Words that miss the lexicon and fall through to the rule layer are
    written to ``work/romanization_misses.json`` in a shape you can paste
    straight into the lexicon after correcting the spellings.
    """
    r = T.Romanizer(lexicon)
    touched = 0
    for w in data["words"]:
        if T.has_devanagari(w["word"]):
            w.setdefault("word_original", w["word"])
            w["word"] = r.text(w["word"], at=w.get("start"))
            touched += 1
    for s in data["segments"]:
        if T.has_devanagari(s["text"]):
            s.setdefault("text_original", s["text"])
            # Segment text repeats the words; don't double-count the misses.
            s["text"] = r.text(s["text"], record=False)

    total = r.n_lexicon_hits + r.n_rule_words
    data["romanized"] = True
    data["romanized_words"] = touched
    data["romanization"] = {
        "lexicon": str(r.path),
        "lexicon_entries": len(r.lexicon),
        "lexicon_hits": r.n_lexicon_hits,
        "rule_words": r.n_rule_words,
        "distinct_misses": len(r.misses),
    }
    log.info(
        "romanized %s tokens: %s lexicon hits, %s fell through to rules "
        "(%s distinct words)",
        touched, r.n_lexicon_hits, r.n_rule_words, len(r.misses),
    )

    if r.misses and work_dir is not None:
        rows = r.miss_report()
        path = work_dir / "romanization_misses.json"
        write_json(path, {
            "_readme": (
                "Words that missed the lexicon and were transliterated by "
                "rule. Check each spelling, then paste the corrected pairs "
                "from 'paste_into_lexicon' into the 'words' object of "
                f"{r.path.name}."
            ),
            "lexicon": str(r.path),
            "counts": {
                "lexicon_hits": r.n_lexicon_hits,
                "rule_words": r.n_rule_words,
                "distinct": len(rows),
            },
            "paste_into_lexicon": {m["word"]: m["rules_gave"] for m in rows},
            "detail": rows,
        })
        pct = 100 * r.n_rule_words / total if total else 0.0
        log.info("--- romanization misses (%.1f%% of Devanagari words) ---", pct)
        for m in rows[:20]:
            log.info(
                '  x%-4d "%s": "%s"   (first at %s)',
                m["count"], m["word"], m["rules_gave"],
                f"{m['first_at']:.1f}s" if m["first_at"] is not None else "?",
            )
        if len(rows) > 20:
            log.info("  ... and %s more", len(rows) - 20)
        log.info("full list: %s", path)
    return data


def _report_confidence(data: dict[str, Any]) -> None:
    """Print where transcription is failing. Deliberately blunt — the point is
    visibility on code-switched audio, not a quality metric."""
    words = data["words"]
    if not words:
        log.warning("transcript has no words")
        return

    confs = [w["confidence"] for w in words]
    buckets = [0] * 5  # 0-.2 .2-.4 .4-.6 .6-.8 .8-1
    for c in confs:
        buckets[min(int(c * 5), 4)] += 1
    total = len(confs)
    mean = sum(confs) / total
    shaky = [w for w in words if w["confidence"] < CONF_WARN]
    bad = [w for w in words if w["confidence"] < CONF_BAD]

    log.info("--- word confidence ---")
    log.info("mean %.3f over %s words", mean, total)
    for i, n in enumerate(buckets):
        lo, hi = i * 0.2, (i + 1) * 0.2
        bar = "#" * int(40 * n / total)
        log.info("  %.1f-%.1f %6d %s", lo, hi, n, bar)
    log.info(
        "below %.2f: %s (%.1f%%)   below %.2f: %s (%.1f%%)",
        CONF_WARN, len(shaky), 100 * len(shaky) / total,
        CONF_BAD, len(bad), 100 * len(bad) / total,
    )
    if data.get("romanized"):
        log.info("romanized words: %s", data.get("romanized_words", 0))
    elif any(T.has_devanagari(w["word"]) for w in words[:5000]):
        log.warning(
            "transcript contains Devanagari; pass --romanize for Roman output"
        )

    if bad:
        log.info("worst 15 words (timestamp / confidence / token):")
        for w in sorted(bad, key=lambda x: x["confidence"])[:15]:
            log.info(
                "  %8.2fs  %.2f  %r",
                w["start"], w["confidence"], w["word"].strip(),
            )
