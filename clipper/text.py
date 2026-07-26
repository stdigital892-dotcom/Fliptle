"""Word/sentence-level text utilities shared by candidate generation and
boundary snapping.

Everything here operates on the flat word list from ``work/transcript.json``
(``{word, start, end, confidence}``) so the same sentence view is used by
every stage.
"""

from __future__ import annotations

import re
import unicodedata
from typing import Any, Iterable

# Sentence terminators, including the Devanagari danda.
TERMINATORS = ".?!।…"
_TRAILING = "\"')]}»”’ "

# Stopwords for "does the hook contain a content word". English plus the
# Hinglish function words that dominate code-switched speech — a clip that
# opens on "toh phir yeh hai ki" has no hook.
STOPWORDS = {
    # english
    "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "can",
    "did", "do", "does", "for", "from", "get", "got", "had", "has", "have",
    "he", "her", "here", "him", "his", "how", "i", "if", "in", "is", "it",
    "its", "just", "like", "me", "my", "no", "not", "now", "of", "off", "oh",
    "ok", "okay", "on", "one", "or", "our", "out", "own", "say", "she", "so",
    "some", "than", "that", "the", "their", "them", "then", "there", "these",
    "they", "this", "to", "too", "up", "very", "was", "we", "well", "were",
    "what", "when", "where", "which", "who", "why", "will", "with", "would",
    "yeah", "yes", "you", "your",
    # hinglish function words / fillers
    "aa", "aap", "ab", "abhi", "acha", "achha", "agar", "ap", "apna", "apne",
    "aur", "bas", "bhi", "bola", "bolo", "ek", "gaya", "gayi", "h", "hai",
    "hain", "han", "haan", "hi", "ho", "hoga", "hota", "hote", "hu", "hum",
    "hun", "isko", "iska", "iske", "ye", "yeh", "ji", "jo", "ka", "kar",
    "karo", "ke", "ki", "kiya", "ko", "koi", "kuch", "kya", "kyunki", "le",
    "lekin", "liye", "log", "mai", "main", "mat", "me", "mein", "mera",
    "mere", "na", "nahi", "nahin", "ne", "par", "phir", "sab", "se", "si",
    "tha", "the", "thi", "to", "toh", "tum", "us", "uska", "uske", "wo",
    "woh", "yaar", "yani",
}

# Signals a heuristic can see without a model: numbers, absolutes, framing.
PUNCH_WORDS = {
    "best", "worst", "never", "always", "everyone", "nobody", "biggest",
    "smallest", "fastest", "hardest", "easiest", "only", "first", "last",
    "most", "least", "huge", "massive", "insane", "crazy", "secret",
    "mistake", "wrong", "truth", "actually", "literally", "million",
    "billion", "crore", "lakh", "percent", "%", "free", "proof", "why",
    "how", "stop", "start", "problem", "reason", "because", "shocking",
    "nobody's", "hidden", "rule", "law", "fail", "failed", "lost", "won",
}

_NUM_RE = re.compile(r"\d")
_WORD_RE = re.compile(r"[^\w%]+", re.UNICODE)
_DEVANAGARI_RUN = re.compile(r"[ऀ-ॿ‌‍]+")


def norm(token: str) -> str:
    """Lowercase, strip punctuation/diacritics. Empty for pure punctuation."""
    t = unicodedata.normalize("NFKD", token)
    t = "".join(c for c in t if not unicodedata.combining(c))
    return _WORD_RE.sub("", t).lower()


def has_devanagari(text: str) -> bool:
    return bool(_DEVANAGARI_RUN.search(text))


# IAST -> the letters Hinglish is actually typed with.
_IAST_FIXUP = {
    "ṃ": "n", "ṁ": "n", "ṅ": "n", "ñ": "n", "ṇ": "n",
    "ś": "sh", "ṣ": "sh", "ṛ": "ri", "ṝ": "ri", "ḷ": "l",
    "ḥ": "h", "ṭ": "t", "ḍ": "d",
    # IAST renders the danda as a pipe; make it a real terminator so
    # build_sentences still splits on it after romanization.
    "॥": ".", "।": ".", "||": ".", "|": ".",
}
_IAST_VOWELS = "aāiīuūeoṛ"
# Word-final inherent 'a': IAST renders every consonant with it, Hindi drops
# it. tīna -> tīn, śabda -> śabd. Long 'ā' is a real vowel and stays (kyā).
_FINAL_SCHWA = re.compile(r"(?<=[^\W\d_])a\b")


def romanize(text: str) -> str:
    """Devanagari -> plain Roman, leaving Latin runs untouched.

    IAST, then Hindi schwa deletion, then strip the remaining diacritics.
    Skipping the schwa step is what makes naive transliteration read as
    Sanskrit (``tina sala`` instead of ``tin saal``). Lossy on purpose — this
    is for reading the transcript, not for captions.
    """
    if not has_devanagari(text):
        return text
    from indic_transliteration import sanscript  # lazy: optional dependency
    from indic_transliteration.sanscript import transliterate

    def _one(m: re.Match[str]) -> str:
        s = transliterate(m.group(0), sanscript.DEVANAGARI, sanscript.IAST)
        s = _FINAL_SCHWA.sub(
            lambda w: "" if w.string[w.start() - 1] not in _IAST_VOWELS else "a",
            s,
        )
        for src, dst in _IAST_FIXUP.items():
            s = s.replace(src, dst)
        decomposed = unicodedata.normalize("NFKD", s)
        return "".join(c for c in decomposed if not unicodedata.combining(c))

    out = _DEVANAGARI_RUN.sub(_one, text)
    return out.replace("।", ".").replace("॥", ".")


def is_content_word(token: str) -> bool:
    """A word that carries meaning — used for the dead-hook check."""
    if _NUM_RE.search(token):
        return True
    t = norm(token)
    return len(t) >= 3 and t not in STOPWORDS


def build_sentences(
    words: list[dict[str, Any]],
    max_gap: float = 0.7,
    max_len: float = 25.0,
) -> list[dict[str, Any]]:
    """Group words into sentences.

    Closes on terminal punctuation, on a pause longer than ``max_gap``, or
    when a run exceeds ``max_len`` (Whisper sometimes emits unpunctuated
    monologue, and an unbounded "sentence" makes snapping useless).
    """
    sentences: list[dict[str, Any]] = []
    cur: list[int] = []

    def flush() -> None:
        if not cur:
            return
        i0, i1 = cur[0], cur[-1]
        sentences.append({
            "start": words[i0]["start"],
            "end": words[i1]["end"],
            "text": " ".join(words[i]["word"].strip() for i in cur).strip(),
            "i0": i0,
            "i1": i1,
        })
        cur.clear()

    for i, w in enumerate(words):
        cur.append(i)
        token = w["word"].strip().rstrip(_TRAILING)
        close = bool(token) and token[-1] in TERMINATORS
        if not close and i + 1 < len(words):
            close = words[i + 1]["start"] - w["end"] > max_gap
        if not close:
            close = w["end"] - words[cur[0]]["start"] > max_len
        if close:
            flush()
    flush()
    return sentences


def words_in(words: list[dict[str, Any]], start: float, end: float) -> list[dict[str, Any]]:
    """Words whose midpoint falls inside [start, end)."""
    out = []
    for w in words:
        mid = (w["start"] + w["end"]) / 2
        if start <= mid < end:
            out.append(w)
        elif mid >= end:
            break
    return out


def keyword_score(tokens: Iterable[str]) -> float:
    """Fraction of tokens that are numbers or punchy framing words."""
    tokens = list(tokens)
    if not tokens:
        return 0.0
    hits = sum(
        1 for t in tokens
        if _NUM_RE.search(t) or norm(t) in PUNCH_WORDS
    )
    return hits / len(tokens)


def salient_words(tokens: Iterable[str], limit: int = 4) -> list[str]:
    """Pick emphasis-word candidates: numbers first, then punch words, then
    the longest content words."""
    seen: set[str] = set()
    numbers, punchy, longest = [], [], []
    for t in tokens:
        n = norm(t)
        if not n or n in seen or not is_content_word(t):
            continue
        seen.add(n)
        if _NUM_RE.search(t):
            numbers.append(t.strip(_TRAILING + ",.").strip())
        elif n in PUNCH_WORDS:
            punchy.append(t.strip(_TRAILING + ",.").strip())
        else:
            longest.append(t.strip(_TRAILING + ",.").strip())
    longest.sort(key=len, reverse=True)
    return (numbers + punchy + longest)[:limit]
