"""Shared helpers: hashing, ffmpeg shell-outs, JSON IO, logging."""

from __future__ import annotations

import hashlib
import json
import logging
import os
import shutil
import subprocess
from pathlib import Path
from typing import Any

log = logging.getLogger("clip")

# How much of the file to feed the hash. Full-file sha256 on a 4 GB video costs
# ~20s of pure IO every run; head+tail+size is enough to key a transcript cache.
_HASH_SAMPLE = 8 * 1024 * 1024


def setup_logging(verbose: bool = False) -> None:
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(levelname)s %(message)s",
    )


def file_hash(path: str | Path) -> str:
    """Sampled content hash: size + first 8MB + last 8MB."""
    path = Path(path)
    size = path.stat().st_size
    h = hashlib.sha256()
    h.update(str(size).encode())
    with path.open("rb") as fh:
        h.update(fh.read(_HASH_SAMPLE))
        if size > _HASH_SAMPLE * 2:
            fh.seek(-_HASH_SAMPLE, os.SEEK_END)
            h.update(fh.read(_HASH_SAMPLE))
    return h.hexdigest()


def have(binary: str) -> bool:
    return shutil.which(binary) is not None


def require_ffmpeg() -> None:
    missing = [b for b in ("ffmpeg", "ffprobe") if not have(b)]
    if missing:
        raise RuntimeError(
            f"{', '.join(missing)} not found on PATH. Install ffmpeg and retry."
        )


def probe_duration(path: str | Path) -> float:
    """Container duration in seconds via ffprobe."""
    out = subprocess.run(
        [
            "ffprobe", "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=nw=1:nk=1",
            str(path),
        ],
        capture_output=True, text=True, check=True,
    ).stdout.strip()
    return float(out)


def read_json(path: str | Path) -> Any:
    with Path(path).open(encoding="utf-8") as fh:
        return json.load(fh)


def write_json(path: str | Path, data: Any) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2)
    tmp.replace(path)


def fmt_ts(seconds: float) -> str:
    """Seconds -> ``m:ss.s`` (or ``h:mm:ss.s`` past an hour)."""
    if seconds < 0:
        seconds = 0.0
    h, rem = divmod(seconds, 3600)
    m, s = divmod(rem, 60)
    if h:
        return f"{int(h)}:{int(m):02d}:{s:04.1f}"
    return f"{int(m)}:{s:04.1f}"
