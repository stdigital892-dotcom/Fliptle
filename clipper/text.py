"""Word/sentence-level text utilities shared by candidate generation and
boundary snapping.

Everything here operates on the flat word list from ``work/transcript.json``
(``{word, start, end, confidence}``) so the same sentence view is used by
every stage.
"""

from __future__ import annotations

import json
import logging
import re
import unicodedata
from pathlib import Path
from typing import Any, Iterable

_log = logging.getLogger("clip.romanize")

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

# Any Devanagari codepoint, including danda and digits.
_DEVANAGARI_RUN = re.compile(r"[ऀ-ॿ‌‍]+")
# A Devanagari *word*: excludes danda (U+0964), double danda (U+0965) and the
# Devanagari digits (U+0966-096F), which are handled separately so that
# sentence splitting still sees a terminator after romanization.
_DEVA_WORD = re.compile(r"[ऀ-ॣ॰-ॿ‌‍]+")
_DEVA_DIGITS = str.maketrans("०१२३४५६७८९", "0123456789")
_NUKTA = "़"


def norm(token: str) -> str:
    """Lowercase, strip punctuation/diacritics. Empty for pure punctuation."""
    t = unicodedata.normalize("NFKD", token)
    t = "".join(c for c in t if not unicodedata.combining(c))
    return _WORD_RE.sub("", t).lower()


def has_devanagari(text: str) -> bool:
    return bool(_DEVANAGARI_RUN.search(text))


# --------------------------------------------------------------------------
# Romanization: lexicon -> vowel length -> rules
#
# Layer 1 is an editable JSON lexicon of conventional Hinglish spellings and
# always wins. Layer 2 preserves vowel length (आ->aa, ई->ee, ऊ->oo), because
# collapsing them changes the word (kaam/kam, saal/sal). Layer 3 is the
# fallback for anything the lexicon has not seen; every word that reaches it
# is logged so the lexicon can grow.

DEFAULT_LEXICON = Path(__file__).resolve().parent.parent / "assets" / "hinglish_lexicon.json"

# Long vowels doubled rather than collapsed — kaam/kam and saal/sal are
# different words. Applied after schwa deletion so the short inherent 'a' is
# still distinguishable from a real 'ā'.
_LONG_VOWELS = {"ā": "aa", "ī": "ee", "ū": "oo", "ṝ": "ri"}
# ...except word-finally, where the convention is a single letter: accha,
# bada, kya, seekhi, meri — not acchaa or seekhee. This matches how the
# lexicon spells every word with the same ending.
_LONG_FINAL = re.compile(r"ā\b|ī\b|ū\b")
_LONG_FINAL_MAP = {"ā": "a", "ī": "i", "ū": "u"}

# Folded before transliteration, because IAST handles them in ways Hinglish
# does not follow:
#   - candra vowels, used almost entirely for English loanwords (लैपटॉप,
#     प्रॉफिट), which the transliterator passes through untouched;
#   - the retroflex flaps ड़/ढ़, which IAST renders with an r (बड़ा -> "bara")
#     where Hinglish always writes d/dh (bada, padhna, badhna). Both the
#     precomposed and nukta-decomposed spellings are covered.
_PRE_FOLD = {
    "ॉ": "ो", "ऑ": "ओ", "ॅ": "े", "ऍ": "ए",
    "ड़": "ड", "ढ़": "ढ", "ड" + _NUKTA: "ड", "ढ" + _NUKTA: "ढ",
}

# The productive -िए imperative suffix: IAST gives "ie", Hinglish writes
# "iye" (rakhiye, suniye, dekhiye).
_IE_SUFFIX = re.compile(r"ie\b")

# IAST -> the letters Hinglish is actually typed with. Note IAST uses 'c' for
# च and 'ch' for छ, which is the opposite of the Hinglish convention.
_IAST_FIXUP = {
    "ṃ": "n", "ṁ": "n", "ṅ": "n", "ñ": "n", "ṇ": "n",
    "ś": "sh", "ṣ": "sh", "ṛ": "ri", "ḷ": "l",
    "ḥ": "h", "ṭ": "t", "ḍ": "d",
}
_CH = re.compile(r"ch|c")
_JNA = re.compile(r"jñ")
_IAST_VOWELS = "aāiīuūeoṛ"
# Word-final inherent 'a': IAST renders every consonant with it, Hindi drops
# it. tīna -> tīn, śabda -> śabd. Long 'ā' is a real vowel and stays (kyā).
_FINAL_SCHWA = re.compile(r"(?<=[^\W\d_])a\b")


def _lex_key(word: str) -> str:
    return unicodedata.normalize("NFC", word.strip())


def _strip_nukta(word: str) -> str:
    return unicodedata.normalize("NFC", word).replace(_NUKTA, "")


class Romanizer:
    """Three-layer Devanagari -> Hinglish converter.

    Call :meth:`text` on any string. Words that fall through to the rule layer
    are counted in :attr:`misses` so you can see what the lexicon is missing.
    """

    def __init__(self, lexicon_path: str | Path | None = None) -> None:
        self.path = Path(lexicon_path) if lexicon_path else DEFAULT_LEXICON
        self.lexicon: dict[str, str] = {}
        self.lexicon_nn: dict[str, str] = {}   # nukta-insensitive fallback
        self.misses: dict[str, dict[str, Any]] = {}
        self.n_lexicon_hits = 0
        self.n_rule_words = 0
        self._load()

    def _load(self) -> None:
        if not self.path.exists():
            _log.warning(
                "lexicon %s not found — falling back to rules for every word",
                self.path,
            )
            return
        raw = json.loads(self.path.read_text(encoding="utf-8"))
        words = raw.get("words", raw) if isinstance(raw, dict) else {}
        for k, v in words.items():
            if k.startswith("_") or not isinstance(v, str):
                continue
            key = _lex_key(k)
            self.lexicon[key] = v
            self.lexicon_nn.setdefault(_strip_nukta(key), v)
        _log.info("lexicon: %s entries from %s", len(self.lexicon), self.path)

    # -- layer 3 ----------------------------------------------------------

    def rules(self, word: str) -> str:
        """Transliterate a word the lexicon does not know."""
        from indic_transliteration import sanscript  # lazy: optional dep
        from indic_transliteration.sanscript import transliterate

        for src, dst in _PRE_FOLD.items():
            word = word.replace(src, dst)
        s = transliterate(word, sanscript.DEVANAGARI, sanscript.IAST)
        s = unicodedata.normalize("NFC", s)
        # 3a. word-final schwa deletion (must precede vowel doubling)
        s = _FINAL_SCHWA.sub(
            lambda m: "" if m.string[m.start() - 1] not in _IAST_VOWELS else "a",
            s,
        )
        # 2. vowel length: single word-finally, doubled everywhere else
        s = _LONG_FINAL.sub(lambda m: _LONG_FINAL_MAP[m.group()], s)
        for src, dst in _LONG_VOWELS.items():
            s = s.replace(src, dst)
        # 3b. consonants and suffixes
        s = _IE_SUFFIX.sub("iye", s)
        s = _JNA.sub("gy", s)
        s = _CH.sub(lambda m: "chh" if m.group() == "ch" else "ch", s)
        for src, dst in _IAST_FIXUP.items():
            s = s.replace(src, dst)
        decomposed = unicodedata.normalize("NFKD", s)
        return "".join(c for c in decomposed if not unicodedata.combining(c))

    # -- layers 1 + 3 ------------------------------------------------------

    def word(
        self, token: str, at: float | None = None, record: bool = True
    ) -> str:
        """Romanize one Devanagari word. Lexicon first, rules as fallback.

        Pass ``record=False`` for text that repeats words already counted
        (segment text restates the word list) so the miss counts stay a true
        word frequency.
        """
        key = _lex_key(token)
        hit = self.lexicon.get(key) or self.lexicon_nn.get(_strip_nukta(key))
        if hit is not None:
            if record:
                self.n_lexicon_hits += 1
            return hit

        out = self.rules(token)
        if record:
            self.n_rule_words += 1
            entry = self.misses.get(key)
            if entry is None:
                self.misses[key] = {"count": 1, "rules_gave": out, "first_at": at}
            else:
                entry["count"] += 1
        return out

    def text(
        self, s: str, at: float | None = None, record: bool = True
    ) -> str:
        """Romanize every Devanagari run in ``s``, leaving Latin untouched."""
        if not has_devanagari(s):
            return s
        s = s.translate(_DEVA_DIGITS)
        s = _DEVA_WORD.sub(lambda m: self.word(m.group(0), at, record), s)
        return s.replace("।", ".").replace("॥", ".")

    # -- reporting ---------------------------------------------------------

    def miss_report(self, limit: int | None = None) -> list[dict[str, Any]]:
        rows = [
            {"word": w, **info}
            for w, info in sorted(
                self.misses.items(), key=lambda kv: -kv[1]["count"]
            )
        ]
        return rows[:limit] if limit else rows


_default_romanizer: Romanizer | None = None


def romanize(text: str) -> str:
    """Convenience wrapper around a module-level :class:`Romanizer`."""
    global _default_romanizer
    if _default_romanizer is None:
        _default_romanizer = Romanizer()
    return _default_romanizer.text(text)


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
