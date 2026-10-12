import asyncio
import json
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from inference_server.app import create_app
from inference_server.runtime import (
    DOCUMENT_ANALYSIS_INSTRUCTION,
    LocalAiRuntime,
    LocalAiRuntimeError,
    MuseClient,
    OllamaClient,
    Settings,
)


ROOT = Path(__file__).resolve().parents[2]


def muse_settings(**overrides):
    values = {"token": "secret", "backend": "muse", "muse_api_key": "muse-key"}
    values.update(overrides)
    return Settings(**values)


def run_muse(handler, settings=None, prompt="hello", mode="text", max_tokens=220):
    async def run():
        client = MuseClient(settings or muse_settings(), transport=httpx.MockTransport(handler))
        try:
            return await client.chat(prompt, mode, max_tokens)
        finally:
            await client.close()

    return asyncio.run(run())


def completion(content, finish_reason="stop"):
    return {"choices": [{"message": {"content": content}, "finish_reason": finish_reason}]}


def test_backend_defaults_to_ollama_and_rejects_unknown_values(monkeypatch):
    monkeypatch.delenv("AI_BACKEND", raising=False)
    assert Settings.from_environment().backend == "ollama"

    monkeypatch.setenv("AI_BACKEND", " Muse ")
    settings = Settings.from_environment()
    assert settings.backend == "muse"
    assert settings.active_model == "muse-spark-1.1"

    monkeypatch.setenv("AI_BACKEND", "mus")
    with pytest.raises(ValueError, match="AI_BACKEND"):
        Settings.from_environment()


def test_runtime_selects_client_for_backend():
    assert isinstance(LocalAiRuntime(Settings(token="t")).ollama, OllamaClient)
    assert isinstance(LocalAiRuntime(muse_settings()).ollama, MuseClient)


def test_muse_request_shape_auth_and_token_floor():
    captured = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["auth"] = request.headers.get("Authorization")
        captured["body"] = json.loads(request.content)
        return httpx.Response(200, json=completion("  Muse answer  "))

    assert run_muse(handler, max_tokens=220) == "Muse answer"
    assert captured["url"] == "https://api.meta.ai/v1/chat/completions"
    assert captured["auth"] == "Bearer muse-key"
    body = captured["body"]
    assert body["model"] == "muse-spark-1.1"
    assert body["messages"] == [{"role": "user", "content": "hello"}]
    assert body["max_tokens"] == 2048
    assert body["stream"] is False
    assert "reasoning_effort" not in body


def test_muse_keeps_larger_caps_and_sends_reasoning_effort():
    captured = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        captured.update(json.loads(request.content))
        return httpx.Response(200, json=completion("ok"))

    settings = muse_settings(muse_min_output_tokens=256, muse_reasoning_effort="low")
    run_muse(handler, settings=settings, max_tokens=1000)
    assert captured["max_tokens"] == 1000
    assert captured["reasoning_effort"] == "low"


def test_muse_document_analysis_adds_json_instruction():
    captured = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        captured.update(json.loads(request.content))
        return httpx.Response(200, json=completion('{"summary": "ok"}'))

    run_muse(handler, prompt="OCR text", mode="document_analysis")
    assert captured["messages"] == [
        {"role": "system", "content": DOCUMENT_ANALYSIS_INSTRUCTION},
        {"role": "user", "content": "OCR text"},
    ]
    assert "format" not in captured


def test_muse_without_key_fails_without_network_call():
    async def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("Muse must not be called without a key")

    with pytest.raises(LocalAiRuntimeError, match="MUSE_API_KEY"):
        run_muse(handler, settings=muse_settings(muse_api_key=""))


def test_muse_health_never_calls_the_api():
    async def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("health must not spend Muse credit")

    async def run(settings):
        client = MuseClient(settings, transport=httpx.MockTransport(handler))
        try:
            return await client.is_ready()
        finally:
            await client.close()

    assert asyncio.run(run(muse_settings())) == (True, None)
    ready, error = asyncio.run(run(muse_settings(muse_api_key="")))
    assert not ready and "MUSE_API_KEY" in error


@pytest.mark.parametrize(
    "body, expected",
    [
        (completion("", "length"), "finish_reason=length"),
        ({"choices": []}, "finish_reason=unknown"),
        ({"choices": [{"message": None}]}, "finish_reason=unknown"),
    ],
)
def test_muse_empty_output_reports_finish_reason(body, expected):
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=body)

    with pytest.raises(LocalAiRuntimeError, match=expected):
        run_muse(handler)


def test_muse_http_and_network_errors_become_runtime_errors():
    async def http_error(request: httpx.Request) -> httpx.Response:
        return httpx.Response(429, text="rate limited")

    with pytest.raises(LocalAiRuntimeError, match="HTTP 429: rate limited"):
        run_muse(http_error)

    async def offline(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("no route to host")

    with pytest.raises(LocalAiRuntimeError, match="Cannot reach the Muse API"):
        run_muse(offline)


@pytest.mark.parametrize(
    "response",
    [
        httpx.Response(200, text="<html>gateway error</html>"),
        httpx.Response(200, json=["not", "an", "object"]),
    ],
)
def test_malformed_replies_from_either_backend_become_runtime_errors(response):
    async def handler(request: httpx.Request) -> httpx.Response:
        return response

    with pytest.raises(LocalAiRuntimeError, match="Muse returned"):
        run_muse(handler)

    async def run_ollama():
        client = OllamaClient(Settings(token="t"), transport=httpx.MockTransport(handler))
        try:
            return await client.chat("hello", "text", 8)
        finally:
            await client.close()

    with pytest.raises(LocalAiRuntimeError, match="Ollama returned"):
        asyncio.run(run_ollama())


def test_malformed_backend_reply_reaches_phone_as_503():
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text="not json")

    settings = muse_settings()
    runtime = LocalAiRuntime(
        settings, ollama=MuseClient(settings, transport=httpx.MockTransport(handler))
    )
    app = create_app(runtime=runtime, token="secret", load_models_on_start=False)
    with TestClient(app) as client:
        response = client.post(
            "/chat", headers={"X-Local-Token": "secret"}, json={"prompt": "hello"}
        )
        health = client.get("/health").json()
    assert response.status_code == 503
    assert "not JSON" in response.json()["detail"]
    assert health["backend"] == "muse"
    assert health["model"] == "muse-spark-1.1"


def test_gateway_does_not_hard_require_ollama():
    gateway = (ROOT / "inference_server" / "systemd" / "metax-gateway.service").read_text(
        encoding="utf-8"
    )
    assert "Requires=ollama.service" not in gateway
    assert "Wants=network-online.target ollama.service" in gateway


def test_setup_files_carry_backend_switch_lines():
    example = (ROOT / "inference_server" / ".env.example").read_text(encoding="utf-8")
    setup = (ROOT / "inference_server" / "setup_jetson.sh").read_text(encoding="utf-8")
    verify = (ROOT / "inference_server" / "verify_jetson.sh").read_text(encoding="utf-8")
    assert "AI_BACKEND=ollama" in example and "MUSE_API_KEY=" in example
    assert "grep -q '^AI_BACKEND=' \"$ENV_FILE\" || printf 'AI_BACKEND=ollama\\n'" in setup
    assert "AI_BACKEND=ollama" in setup
    assert "MUSE_API_KEY" in verify and '\\"backend\\":\\"${AI_BACKEND}\\"' in verify


def test_readme_discloses_muse_cloud_data_flow():
    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    assert "Muse is a cloud service" in readme
    assert "api.meta.ai" in readme
