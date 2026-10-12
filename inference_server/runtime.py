from __future__ import annotations

import asyncio
import logging
import os
import time
from dataclasses import dataclass
from typing import Any, Literal

import httpx


ResponseMode = Literal["text", "document_analysis"]

DOCUMENT_ANALYSIS_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "documentType": {"type": "string"},
        "extractedFields": {"type": "array", "items": {"type": "string"}},
        "summary": {"type": "string"},
        "explanation": {"type": "string"},
        "riskFlags": {"type": "array", "items": {"type": "string"}},
        "recommendedActions": {"type": "array", "items": {"type": "string"}},
    },
    "required": [
        "documentType",
        "extractedFields",
        "summary",
        "explanation",
        "riskFlags",
        "recommendedActions",
    ],
}

DEFAULT_MODEL = "gemma3:4b-it-q4_K_M"
DEFAULT_MUSE_URL = "https://api.meta.ai/v1"
DEFAULT_MUSE_MODEL = "muse-spark-1.1"
BACKENDS = ("ollama", "muse")

DOCUMENT_ANALYSIS_INSTRUCTION = (
    "Reply with one JSON object only, no markdown fences and no commentary. "
    "Use exactly these keys: documentType (string), extractedFields (array of strings), "
    "summary (string), explanation (string), riskFlags (array of strings), "
    "recommendedActions (array of strings)."
)

logger = logging.getLogger(__name__)


class LocalAiRuntimeError(RuntimeError):
    pass


def _json_object(response: httpx.Response, backend_name: str) -> dict[str, Any]:
    """Parse a backend reply, turning malformed bodies into a readable 503."""
    try:
        body = response.json()
    except ValueError as exc:
        raise LocalAiRuntimeError(
            f"{backend_name} returned a response that is not JSON: {response.text[:200]}"
        ) from exc
    if not isinstance(body, dict):
        raise LocalAiRuntimeError(f"{backend_name} returned an unexpected JSON shape")
    return body


@dataclass(frozen=True)
class Settings:
    token: str
    ollama_url: str = "http://127.0.0.1:11434"
    model: str = DEFAULT_MODEL
    context_length: int = 4096
    backend: str = "ollama"
    muse_api_key: str = ""
    muse_url: str = DEFAULT_MUSE_URL
    muse_model: str = DEFAULT_MUSE_MODEL
    muse_reasoning_effort: str = ""
    # Muse bills hidden reasoning against max_tokens, so the app's small caps
    # (220-350) can be used up before any visible text is produced.
    muse_min_output_tokens: int = 2048

    @property
    def active_model(self) -> str:
        return self.muse_model if self.backend == "muse" else self.model

    @classmethod
    def from_environment(cls) -> "Settings":
        backend = os.getenv("AI_BACKEND", "ollama").strip().lower() or "ollama"
        if backend not in BACKENDS:
            raise ValueError(f"AI_BACKEND must be one of {BACKENDS}, not {backend!r}")
        return cls(
            backend=backend,
            muse_api_key=os.getenv("MUSE_API_KEY", "").strip(),
            muse_url=os.getenv("MUSE_BASE_URL", DEFAULT_MUSE_URL).strip().rstrip("/"),
            muse_model=os.getenv("MUSE_MODEL", DEFAULT_MUSE_MODEL).strip(),
            muse_reasoning_effort=os.getenv("MUSE_REASONING_EFFORT", "").strip(),
            muse_min_output_tokens=int(os.getenv("MUSE_MIN_OUTPUT_TOKENS", "2048")),
            token=os.getenv("LOCAL_AI_TOKEN", "").strip(),
            ollama_url=os.getenv("OLLAMA_URL", "http://127.0.0.1:11434").rstrip("/"),
            model=os.getenv("OLLAMA_MODEL", DEFAULT_MODEL).strip(),
            context_length=int(os.getenv("OLLAMA_CONTEXT_LENGTH", "4096")),
        )


class OllamaClient:
    def __init__(
        self,
        settings: Settings,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.settings = settings
        self.client = httpx.AsyncClient(
            base_url=settings.ollama_url,
            timeout=httpx.Timeout(connect=5.0, read=120.0, write=30.0, pool=5.0),
            trust_env=False,
            transport=transport,
        )

    async def close(self) -> None:
        await self.client.aclose()

    async def is_ready(self) -> tuple[bool, str | None]:
        try:
            response = await self.client.get("/api/tags")
            response.raise_for_status()
            models = {item.get("name", "") for item in response.json().get("models", [])}
            is_present = any(
                name == self.settings.model or name.startswith(f"{self.settings.model}:")
                for name in models
            )
            if not is_present:
                return False, f"Model {self.settings.model} is not installed"
            return True, None
        except Exception as exc:  # Health reports the reason instead of failing the process.
            return False, str(exc)

    async def chat(
        self,
        prompt: str,
        response_mode: ResponseMode,
        max_tokens: int,
    ) -> str:
        payload: dict[str, Any] = {
            "model": self.settings.model,
            "messages": [{"role": "user", "content": prompt}],
            "stream": False,
            "keep_alive": -1,
            "options": {
                "num_ctx": self.settings.context_length,
                "num_predict": max_tokens,
                "temperature": 0.2,
                "top_p": 0.9,
            },
        }
        if response_mode == "document_analysis":
            payload["format"] = DOCUMENT_ANALYSIS_SCHEMA

        try:
            response = await self.client.post("/api/chat", json=payload)
            response.raise_for_status()
            message = _json_object(response, "Ollama").get("message")
            content = message.get("content") if isinstance(message, dict) else None
            text = (content if isinstance(content, str) else "").strip()
        except httpx.HTTPStatusError as exc:
            detail = exc.response.text[:500]
            raise LocalAiRuntimeError(
                f"Ollama returned HTTP {exc.response.status_code}: {detail}"
            ) from exc
        except httpx.HTTPError as exc:
            raise LocalAiRuntimeError(f"Cannot reach local Ollama: {exc}") from exc

        return text


class MuseClient:
    """Cloud backend: Meta Model API through its OpenAI-compatible chat endpoint."""

    def __init__(
        self,
        settings: Settings,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.settings = settings
        self.client = httpx.AsyncClient(
            base_url=settings.muse_url,
            timeout=httpx.Timeout(connect=10.0, read=80.0, write=30.0, pool=5.0),
            transport=transport,
        )

    async def close(self) -> None:
        await self.client.aclose()

    async def is_ready(self) -> tuple[bool, str | None]:
        # No network probe: health must stay free and must not spend API credit.
        if not self.settings.muse_api_key:
            return False, "MUSE_API_KEY is not configured"
        return True, None

    async def chat(
        self,
        prompt: str,
        response_mode: ResponseMode,
        max_tokens: int,
    ) -> str:
        if not self.settings.muse_api_key:
            raise LocalAiRuntimeError("MUSE_API_KEY is not configured")
        messages: list[dict[str, str]] = []
        if response_mode == "document_analysis":
            messages.append({"role": "system", "content": DOCUMENT_ANALYSIS_INSTRUCTION})
        messages.append({"role": "user", "content": prompt})
        payload: dict[str, Any] = {
            "model": self.settings.muse_model,
            "messages": messages,
            "max_tokens": max(max_tokens, self.settings.muse_min_output_tokens),
            "temperature": 0.2,
            "stream": False,
        }
        if self.settings.muse_reasoning_effort:
            payload["reasoning_effort"] = self.settings.muse_reasoning_effort

        try:
            response = await self.client.post(
                "/chat/completions",
                json=payload,
                headers={"Authorization": f"Bearer {self.settings.muse_api_key}"},
            )
            response.raise_for_status()
            choices = _json_object(response, "Muse").get("choices")
            choice = choices[0] if isinstance(choices, list) and choices else {}
            if not isinstance(choice, dict):
                choice = {}
            message = choice.get("message")
            content = message.get("content") if isinstance(message, dict) else None
            text = (content if isinstance(content, str) else "").strip()
            if not text:
                reason = choice.get("finish_reason") or "unknown"
                raise LocalAiRuntimeError(
                    f"Muse returned no visible text (finish_reason={reason}). "
                    "If the reason is 'length', raise MUSE_MIN_OUTPUT_TOKENS."
                )
        except httpx.HTTPStatusError as exc:
            detail = exc.response.text[:500]
            raise LocalAiRuntimeError(
                f"Muse returned HTTP {exc.response.status_code}: {detail}"
            ) from exc
        except httpx.HTTPError as exc:
            raise LocalAiRuntimeError(f"Cannot reach the Muse API: {exc}") from exc

        return text


class LocalAiRuntime:
    def __init__(
        self,
        settings: Settings,
        ollama: OllamaClient | MuseClient | None = None,
    ) -> None:
        self.settings = settings
        # Attribute keeps its historical name; it holds whichever backend is active.
        if ollama is not None:
            self.ollama = ollama
        elif settings.backend == "muse":
            self.ollama = MuseClient(settings)
        else:
            self.ollama = OllamaClient(settings)
        self.model_lock = asyncio.Lock()

    async def start(self) -> None:
        return None

    async def close(self) -> None:
        await self.ollama.close()

    async def health(self) -> dict[str, Any]:
        ollama_ready, ollama_error = await self.ollama.is_ready()
        return {
            "status": "ready" if ollama_ready else "degraded",
            "gateway": "ready",
            "chat": "ready" if ollama_ready else "unavailable",
            "backend": self.settings.backend,
            "model": self.settings.active_model,
            "ollamaError": ollama_error,
        }

    async def chat(
        self, prompt: str, response_mode: ResponseMode, max_tokens: int
    ) -> tuple[str, int]:
        started = time.perf_counter()
        async with self.model_lock:
            text = await self.ollama.chat(prompt, response_mode, max_tokens)
        if not text:
            raise LocalAiRuntimeError("The model returned an empty response")
        return text, round((time.perf_counter() - started) * 1000)