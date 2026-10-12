# Meta glasses banking assistant — Jetson AI gateway

Document understanding and assistant answers are produced by an AI gateway on an NVIDIA Jetson Orin Nano 8GB. The Android app performs on-device OCR before contacting the Jetson; the Jetson receives text only.

The gateway has two backends, chosen by `AI_BACKEND` in `inference_server/.env`:

| `AI_BACKEND` | Model | Where inference runs | Data leaving the Jetson |
|---|---|---|---|
| `ollama` (default) | `gemma3:4b-it-q4_K_M` | Jetson GPU | None |
| `muse` | `muse-spark-1.1` (Meta Muse API) | Meta cloud, `api.meta.ai` | Every `/chat` prompt |

```text
Meta glasses -> Android phone -> ML Kit OCR
Android phone -> localhost:8000 through adb reverse over direct USB
Jetson Orin Nano -> FastAPI /chat on 127.0.0.1:8000
                 -> AI_BACKEND=ollama: Ollama + Gemma on 127.0.0.1:11434 -> NVIDIA GPU
                 -> AI_BACKEND=muse:   HTTPS to https://api.meta.ai/v1 (Meta Muse)
```

**Muse is a cloud service.** When `AI_BACKEND=muse`, each prompt sent to Meta includes whatever the feature needs: for the RM assistant, the matched customer's name, ID, phone number, profile, account numbers and balances, recent transactions and relationship history; for document scan and Q&A, the full OCR text of the scanned document. Only use Muse where sending that data to Meta is permitted. Face recognition, OCR, speech recognition and text-to-speech run on the phone in both modes, and images never leave the phone.

The active Android contract is authenticated `POST /chat` with `X-Local-Token`. The Android app only ever talks to the Jetson; it holds no cloud API keys. The legacy image `/ground` endpoint is intentionally removed. Neither gateway service listens on the LAN. The gateway does not fail over between backends automatically; switching is a deliberate `.env` change plus a gateway restart, and `GET /health` reports the active `backend` and `model`.

## Start here

1. Complete the physical and flashing prerequisites in [docs/JETSON_LOCAL_SETUP.md](docs/JETSON_LOCAL_SETUP.md).
2. On the Jetson, run `bash inference_server/setup_jetson.sh`.
3. Configure and build the APK on the workstation:

   ```powershell
   .\inference_server\configure_android.ps1 -BaseUrl "http://127.0.0.1:8000" -EnvFile "C:\secure\jetson.env"
   .\gradlew.bat :app:assembleDebug
   ```

4. Follow [docs/DAILY_START_STOP.md](docs/DAILY_START_STOP.md) for normal use and recovery.

The token file is ignored by Git. Never paste `LOCAL_AI_TOKEN` or `MUSE_API_KEY` into logs, documentation, tests, or commits. `OLLAMA_MODEL` remains configurable, but the supported and tested default is `gemma3:4b-it-q4_K_M`. To switch backends, see [Switching between Ollama and Muse](docs/DAILY_START_STOP.md#switching-between-ollama-and-muse).

## Development checks

```bash
python -m pytest inference_server/tests -q
```

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Meta Device Access Toolkit remains pinned to 0.7.0. Face recognition, speech recognition, TTS, customer data, and glasses streaming/capture remain unchanged.

Speech recognition uses Android's system `SpeechRecognizer`. Depending on the phone and its settings, that service may itself process audio in the cloud; this is independent of `AI_BACKEND`.
