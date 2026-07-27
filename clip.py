#!/usr/bin/env python3
"""Turn one long-form video into ranked, ready-to-post vertical shorts.

    python clip.py input.mp4 --n 5 --min-len 20 --max-len 75

Stages 1-3 (transcribe, candidate generation, boundary snapping) are
implemented. Each writes JSON to ``work/`` and can be re-run on its own:

    python clip.py input.mp4 --stage candidates,boundaries
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path
from typing import Any

from clipper import boundaries, candidates, transcribe
from clipper.util import fmt_ts, read_json, setup_logging

log = logging.getLogger("clip")

ALL_STAGES = ["transcribe", "candidates", "boundaries"]
FUTURE_STAGES = ["rank", "reframe", "render"]


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(
        prog="clip.py",
        description="Generate vertical short clips from a long-form video.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    p.add_argument("input", type=Path, help="path to a local .mp4/.mkv")
    p.add_argument("--n", type=int, default=5,
                   help="number of clips to produce (stage 6)")
    p.add_argument("--min-len", type=float, default=20.0,
                   help="minimum clip length in seconds")
    p.add_argument("--max-len", type=float, default=75.0,
                   help="maximum clip length in seconds")
    p.add_argument("--stage", default="all",
                   help=f"comma-separated stages to run: {','.join(ALL_STAGES)}")
    p.add_argument("--work-dir", type=Path, default=Path("work"))
    p.add_argument("--out-dir", type=Path, default=Path("out"))

    g = p.add_argument_group("transcription")
    g.add_argument("--whisper-model", default=None,
                   help="override model size (default: large-v3 on CUDA, base on CPU)")
    g.add_argument("--device", choices=["auto", "cpu", "cuda"], default="auto")
    g.add_argument("--language", default=None,
                   help="force a language code, e.g. hi or en (default: detect)")
    g.add_argument("--romanize", action="store_true",
                   help="transliterate Devanagari output to Roman script")
    g.add_argument("--lexicon", type=Path, default=None,
                   help="override the Hinglish lexicon JSON "
                        "(default: assets/hinglish_lexicon.json)")
    g.add_argument("--force", action="store_true",
                   help="ignore the transcript cache and re-transcribe")

    g = p.add_argument_group("candidate generation")
    g.add_argument("--provider", choices=["anthropic", "openai"], default=None,
                   help="LLM provider (default: whichever API key is set)")
    g.add_argument("--llm-model", default=None, help="override the model id")
    g.add_argument("--llm-effort",
                   choices=["low", "medium", "high", "xhigh", "max"],
                   default=None, help="Anthropic effort level")
    g.add_argument("--no-llm", action="store_true",
                   help="skip the LLM and use the offline heuristic")

    g = p.add_argument_group("rendering (stages 4-6, not yet implemented)")
    g.add_argument("--no-sfx", action="store_true", help="disable sound effects")
    g.add_argument("--no-zoom", action="store_true", help="disable punch-in zooms")

    p.add_argument("-v", "--verbose", action="store_true")
    return p.parse_args(argv)


def resolve_stages(spec: str) -> list[str]:
    if spec == "all":
        return list(ALL_STAGES)
    wanted = [s.strip() for s in spec.split(",") if s.strip()]
    for s in wanted:
        if s in FUTURE_STAGES:
            raise SystemExit(f"stage {s!r} is not implemented yet")
        if s not in ALL_STAGES:
            raise SystemExit(
                f"unknown stage {s!r}; expected one of {', '.join(ALL_STAGES)}"
            )
    return [s for s in ALL_STAGES if s in wanted]


def print_candidates(data: dict[str, Any], n: int) -> None:
    cands = data["candidates"]
    method = data.get("method", "?")
    model = data.get("model")
    print()
    print("=" * 78)
    print(f"  {len(cands)} candidates  (source: {method}"
          f"{' / ' + model if model else ''})")
    print("=" * 78)
    if not cands:
        print("\n  Nothing survived boundary snapping. Check work/candidates.json"
              "\n  -> 'dropped' for why.\n")
        return

    for i, c in enumerate(cands, 1):
        mark = "*" if i <= n else " "
        print(f"\n{mark} [{i:>2}] {c['clip_id']}  "
              f"{fmt_ts(c['start'])} -> {fmt_ts(c['end'])}  "
              f"({c['duration']:.1f}s)")
        bits = []
        if c.get("hook_strength") is not None:
            bits.append(f"hook {float(c['hook_strength']):.2f}")
        bits.append("self-contained" if c.get("self_contained") else "needs context")
        bits.append(f"{c.get('word_rate', 0):.1f} w/s")
        bits.append(f"conf {c.get('mean_confidence', 0):.2f}")
        print(f"      {'  |  '.join(bits)}")
        if c.get("topic"):
            print(f"      topic:    {c['topic']}")
        print(f"      hook:     {c.get('hook_actual') or c.get('hook', '')}")
        if c.get("reason"):
            print(f"      why:      {c['reason']}")
        if c.get("emphasis_words"):
            print(f"      emphasis: {', '.join(c['emphasis_words'])}")
        if c.get("snap_notes"):
            print(f"      snapped:  {'; '.join(c['snap_notes'])}")

    print()
    print("-" * 78)
    print(f"  * = the top {n} by list order. Ranking is stage 4 — this order is")
    print("    chronological, not quality-sorted.")
    print(f"  Full text of every clip: {Path('work') / 'candidates.json'}")
    print("-" * 78)
    print()


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    setup_logging(args.verbose)

    if not args.input.exists():
        log.error("no such file: %s", args.input)
        return 1
    if args.min_len >= args.max_len:
        log.error("--min-len must be below --max-len")
        return 1
    if args.no_sfx or args.no_zoom:
        log.info("--no-sfx/--no-zoom recorded; they apply from stage 6 onward")

    stages = resolve_stages(args.stage)
    work: Path = args.work_dir
    work.mkdir(parents=True, exist_ok=True)
    log.info("stages: %s", ", ".join(stages))

    transcript: dict[str, Any] | None = None
    raw: dict[str, Any] | None = None

    if "transcribe" in stages:
        transcript = transcribe.transcribe(
            args.input, work,
            model_size=args.whisper_model,
            device=None if args.device == "auto" else args.device,
            language=args.language,
            romanize=args.romanize,
            lexicon=args.lexicon,
            force=args.force,
        )

    if "candidates" in stages or "boundaries" in stages:
        if transcript is None:
            path = work / "transcript.json"
            if not path.exists():
                log.error("%s missing — run the transcribe stage first", path)
                return 1
            transcript = read_json(path)

    if "candidates" in stages:
        raw = candidates.generate(
            transcript, args.input, work,
            min_len=args.min_len, max_len=args.max_len,
            provider=args.provider, model=args.llm_model,
            effort=args.llm_effort, use_llm=not args.no_llm,
        )

    if "boundaries" in stages:
        if raw is None:
            path = work / "candidates.raw.json"
            if not path.exists():
                log.error("%s missing — run the candidates stage first", path)
                return 1
            raw = read_json(path)
        refined = boundaries.refine(
            raw, transcript, work,
            min_len=args.min_len, max_len=args.max_len,
        )
        print_candidates(refined, args.n)

    return 0


if __name__ == "__main__":
    sys.exit(main())
