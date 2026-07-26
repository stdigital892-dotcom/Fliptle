"""Vertical short-clip generator.

Six discrete stages, each writing JSON into ``work/`` so stages can be
re-run independently:

1. transcribe  -> work/transcript.json
2. candidates  -> work/candidates.raw.json
3. boundaries  -> work/candidates.json
4. ranking     -> work/ranked.json
5. reframe     -> work/reframe/<clip_id>.json
6. render      -> out/<clip_id>.mp4 + out/clips.json

Stages 1-3 are implemented.
"""

__version__ = "0.1.0"
