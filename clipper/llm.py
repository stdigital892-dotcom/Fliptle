"""Thin LLM wrapper for the one optional model call in the pipeline.

Anthropic is the default; OpenAI is supported as an alternative. Both use the
provider's official SDK and read the key from the environment. If neither key
is set the caller falls back to the offline heuristic.
"""

from __future__ import annotations

import json
import logging
import os
import re
from typing import Any

log = logging.getLogger("clip.llm")

ANTHROPIC_DEFAULT_MODEL = "claude-opus-5"
OPENAI_DEFAULT_MODEL = "gpt-4o"


class LLMUnavailable(RuntimeError):
    """No usable provider (missing key or missing SDK)."""


class LLMRefused(RuntimeError):
    """The model declined the request."""


def detect_provider(prefer: str | None = None) -> str | None:
    """Return 'anthropic' | 'openai' | None based on env keys."""
    have_anthropic = bool(os.environ.get("ANTHROPIC_API_KEY"))
    have_openai = bool(os.environ.get("OPENAI_API_KEY"))
    if prefer == "anthropic":
        return "anthropic" if have_anthropic else None
    if prefer == "openai":
        return "openai" if have_openai else None
    if have_anthropic:
        return "anthropic"
    if have_openai:
        return "openai"
    return None


def complete_json(
    system: str,
    user: str,
    schema: dict[str, Any],
    *,
    provider: str,
    model: str | None = None,
    effort: str | None = None,
    max_tokens: int = 32000,
) -> dict[str, Any]:
    """Run one completion constrained to ``schema`` and return parsed JSON."""
    if provider == "anthropic":
        return _anthropic(system, user, schema, model, effort, max_tokens)
    if provider == "openai":
        return _openai(system, user, schema, model, max_tokens)
    raise LLMUnavailable(f"unknown provider {provider!r}")


# --------------------------------------------------------------------------
# Anthropic


def _anthropic(
    system: str,
    user: str,
    schema: dict[str, Any],
    model: str | None,
    effort: str | None,
    max_tokens: int,
) -> dict[str, Any]:
    try:
        import anthropic
    except ImportError as exc:  # pragma: no cover
        raise LLMUnavailable("pip install anthropic") from exc

    model = model or ANTHROPIC_DEFAULT_MODEL
    client = anthropic.Anthropic()

    output_config: dict[str, Any] = {
        "format": {"type": "json_schema", "schema": schema}
    }
    if effort:
        output_config["effort"] = effort

    kwargs: dict[str, Any] = {
        "model": model,
        "max_tokens": max_tokens,
        # Cache the (stable) instructions; only the transcript varies between
        # chunks of the same video.
        "system": [{
            "type": "text",
            "text": system,
            "cache_control": {"type": "ephemeral"},
        }],
        "messages": [{"role": "user", "content": user}],
        "output_config": output_config,
    }

    log.info("anthropic %s (%d chars of transcript)", model, len(user))
    try:
        msg = _anthropic_stream(client, kwargs)
    except TypeError as exc:
        # Older SDK without output_config: the system prompt already demands
        # bare JSON, so retry unconstrained and parse defensively.
        if "output_config" not in str(exc):
            raise
        log.warning("SDK rejected output_config; retrying without it")
        kwargs.pop("output_config")
        msg = _anthropic_stream(client, kwargs)

    if getattr(msg, "stop_reason", None) == "refusal":
        raise LLMRefused(f"model declined: {getattr(msg, 'stop_details', None)}")
    if getattr(msg, "stop_reason", None) == "max_tokens":
        log.warning("hit max_tokens — candidate list may be truncated")

    text = "".join(
        b.text for b in msg.content if getattr(b, "type", None) == "text"
    )
    usage = getattr(msg, "usage", None)
    if usage is not None:
        log.info(
            "tokens in=%s out=%s cache_read=%s",
            getattr(usage, "input_tokens", "?"),
            getattr(usage, "output_tokens", "?"),
            getattr(usage, "cache_read_input_tokens", "?"),
        )
    return _parse_json(text)


def _anthropic_stream(client: Any, kwargs: dict[str, Any]) -> Any:
    # Streaming keeps a large max_tokens from tripping the SDK HTTP timeout.
    with client.messages.stream(**kwargs) as stream:
        return stream.get_final_message()


# --------------------------------------------------------------------------
# OpenAI


def _openai(
    system: str,
    user: str,
    schema: dict[str, Any],
    model: str | None,
    max_tokens: int,
) -> dict[str, Any]:
    try:
        from openai import OpenAI
    except ImportError as exc:  # pragma: no cover
        raise LLMUnavailable("pip install openai") from exc

    model = model or OPENAI_DEFAULT_MODEL
    client = OpenAI()
    log.info("openai %s (%d chars of transcript)", model, len(user))
    resp = client.chat.completions.create(
        model=model,
        max_completion_tokens=max_tokens,
        messages=[
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        response_format={
            "type": "json_schema",
            "json_schema": {
                "name": "clip_candidates",
                "schema": schema,
                "strict": True,
            },
        },
    )
    choice = resp.choices[0]
    if choice.finish_reason == "length":
        log.warning("hit token limit — candidate list may be truncated")
    return _parse_json(choice.message.content or "")


# --------------------------------------------------------------------------


_FENCE = re.compile(r"```(?:json)?\s*(.*?)```", re.S)


def _parse_json(text: str) -> dict[str, Any]:
    text = text.strip()
    if not text:
        raise ValueError("model returned no text")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    m = _FENCE.search(text)
    if m:
        return json.loads(m.group(1))
    # Last resort: the outermost brace pair.
    start, end = text.find("{"), text.rfind("}")
    if start != -1 and end > start:
        return json.loads(text[start:end + 1])
    raise ValueError(f"could not parse JSON from model output: {text[:300]!r}")
