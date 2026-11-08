# Jetson gateway architecture

```text
Meta glasses
  -> Bluetooth / Meta Device Access Toolkit 0.7.0
Android phone (foreground streaming service + wake lock)
  -> voice commands "Meta scan" / "Meta question"
  -> on-device face recognition (ML Kit + MobileFaceNet)
  -> capturePhoto()
  -> bundled ML Kit Latin OCR
  -> localhost:8000 through adb reverse over direct USB
Jetson Orin Nano 8GB
  -> FastAPI /chat on 127.0.0.1:8000
  -> AI_BACKEND=muse (demo default): HTTPS over USB Wi-Fi to https://api.meta.ai/v1/chat/completions (Meta Muse, cloud)
  -> AI_BACKEND=ollama (backup):     Ollama + gemma3:4b-it-q4_K_M on 127.0.0.1:11434 -> NVIDIA GPU
```

The workstation is not a runtime component. FastAPI exposes unauthenticated `GET /health` and token-authenticated `POST /chat`. A single `asyncio.Lock` serializes every model request. The legacy image `/ground` endpoint is intentionally removed.

## Backends

`AI_BACKEND` in `inference_server/.env` selects one backend when the gateway starts. The demo runs `muse` with Gemma kept warm as the backup; the code and setup default is `ollama`. There is no automatic failover; switching requires editing `.env` and restarting `metax-gateway`. `GET /health` reports `backend` and `model`.

- **`ollama` (backup; code default):** Gemma runs on the Jetson GPU. No prompt data leaves the Jetson. Ollama enforces the document-analysis JSON schema.
- **`muse` (demo default):** prompts are sent over the internet to Meta's Muse API using `MUSE_API_KEY`. Document analysis asks for the JSON shape through a system instruction rather than an enforced schema; the Android app tolerates code fences around the JSON. Muse counts hidden reasoning against `max_tokens`, so the gateway raises every request to at least `MUSE_MIN_OUTPUT_TOKENS` (code default 2048; this deployment uses 8192 with `MUSE_REASONING_EFFORT=low`). If Muse still stops early, the gateway returns `Muse returned no visible text (finish_reason=...)`; network failures return `Cannot reach the Muse API: ...`. In Muse mode `/health` only checks that a key is configured; it does not contact the API, so an offline Jetson still reports `ready`.

### What Muse receives

Every `/chat` prompt goes to Meta in full:

- **RM assistant:** the matched customer's ID, name, phone number, profile, last visit, account types, numbers, balances and statuses, the six most recent transactions, the four most recent history notes, up to ten conversation turns, and the spoken question.
- **Document scan and Q&A:** the complete OCR text of the scanned document, up to eight prior Q&A turns, and the spoken question.

Images, face embeddings, and audio stay on the phone in both modes. Use Muse only where sending this data to Meta is permitted.

`metax-gateway.service` uses `Wants=ollama.service` rather than `Requires=`, so the gateway keeps serving Muse if Ollama stops or is stopped to free memory.

## Phone app session handling

- **Streaming service:** starting a stream starts a foreground service with a partial wake lock (60-minute safety limit); stopping the stream stops it. The glasses session survives the phone screen turning off.
- **Stream errors:** `StreamErrorPolicy` treats only `CRITICAL_STREAM_ERROR`, `HINGE_CLOSED`, `PERMISSIONS_DENIED` and `THERMAL_EMERGENCY` as terminal. Other errors (thermal hot, battery low, peak power, timeout, transient stream errors) are logged or shown, and the stream continues; if the glasses really stop, the stream reaches `CLOSED` and the app returns to device selection.
- **Customer presence:** `CustomerPresencePolicy` searches for a face every 1 s and rechecks a matched customer every 2 s. The match is kept through brief misses and cleared after 5 s without recognition; a different known customer replaces it at once.
- **Customer isolation:** the assistant starts with no customer selected. Changing customer clears the previous question, answer and conversation, stops listening, and drops any answer still being generated for the previous customer.
- **Voice commands:** one `SpeechRecognizer` listener handles both "Meta scan" and "Meta question", choosing the closest phrase by edit distance. Audio is not retained.

## Document path

Android captures the high-resolution glasses photo, applies the existing HEIC/EXIF handling, and runs bundled on-device ML Kit OCR. The resulting OCR transcription is stored as the document session text and sent to Jetson `/chat` for document analysis.

Document analysis, follow-up Q&A, and RM/customer Q&A continue through `/chat` with their existing token limits. Follow-up Q&A still sends the complete original OCR transcription, up to eight prior Q&A turns, and the current question. Document-specific facts must come from the OCR text; general banking context must be labelled as such.

The Jetson does not receive document images, perform Pillow preprocessing, or run a vision model. OCR quality depends on focus, glare, framing, document language, and ML Kit Latin-script support. If OCR text is blank or too short, the Android scan fails locally before calling the Jetson.

## Network

The phone reaches the Jetson only through `adb reverse` over USB. The Jetson reaches the internet (needed for Muse) through a 2.4 GHz USB Wi-Fi adapter (RTL8188FTV, `rtl8188fu` DKMS driver); see [Jetson network access](JETSON_LOCAL_SETUP.md#jetson-network-access-usb-wi-fi). The laptop is used only for SSH administration and APK builds.

## Isolation and recovery

Ollama and Uvicorn are loopback-only systemd services. The ADB manager waits for exactly one authorized physical phone and recreates `adb reverse tcp:8000 tcp:8000` whenever necessary. The ignored `.env` file, which holds `LOCAL_AI_TOKEN` and `MUSE_API_KEY`, is readable only by its owner.

With `AI_BACKEND=ollama`, readiness requires the configured text model to exist. Provisioning proves text operation, `100% GPU` placement, acceptable memory, and no thermal failure in both modes, because Ollama is the fallback when Muse is active. A CPU fallback is a deployment error.
