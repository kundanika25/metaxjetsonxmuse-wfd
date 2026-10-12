# Jetson daily operation

## Start

1. Power the actively cooled Jetson and wait for boot.
2. If working headlessly, connect the Windows laptop to the Jetson over the direct Ethernet/ICS link and SSH into the Jetson.
3. Restart Ollama, compact memory, and warm the model:

   ```bash
   sudo systemctl restart ollama
   sleep 5

   sudo sync
   sudo sh -c 'echo 3 > /proc/sys/vm/drop_caches'
   sudo sh -c 'echo 1 > /proc/sys/vm/compact_memory'

   curl --fail --show-error --silent http://127.0.0.1:11434/api/chat \
     -H 'Content-Type: application/json' \
     -d '{"model":"gemma3:4b-it-q4_K_M","messages":[{"role":"user","content":"Reply with only: ready"}],"stream":false,"keep_alive":-1,"options":{"num_ctx":4096,"num_predict":8}}'

   ollama ps
   ```

   `ollama ps` must show `gemma3:4b-it-q4_K_M`, `100% GPU`, context `4096`, and `Forever`.

   If the warmup fails once with `cudaMalloc` or `out of memory`, repeat this exact restart/compact/warmup block once. Do not switch to q4 KV unless it fails after two clean tries.

4. Start the app services:

   ```bash
   sudo systemctl restart metax-gateway metax-adb-reverse
   ```

5. Connect the unlocked, USB-debug-enabled phone directly to a Jetson USB host port with a data-capable cable.
6. Accept the Jetson RSA prompt if Android asks.
7. Verify readiness:

   ```bash
   systemctl is-active ollama metax-gateway metax-adb-reverse
   curl --fail --show-error --silent http://127.0.0.1:8000/health
   adb reverse --list
   ollama ps
   ```

`/health` must report `ready` and the configured model. `ollama ps` must report `100% GPU`. The reverse list must contain `tcp:8000 tcp:8000`. Open `http://127.0.0.1:8000/health` on the phone, then use the Android app.

The Windows laptop is only needed for headless administration, APK work, or provisioning. It is not in the runtime inference path. Do not use USB tethering or expose the loopback services on the LAN.

## Required Ollama memory settings

The Jetson 8 GB deployment keeps Ollama Flash Attention and q8 KV cache enabled for the Gemma 3 text-only pipeline. This was the reliable setting for loading Gemma at 4096 context on the device. Confirm the active service environment after rebuilds:

```bash
sudo systemctl show ollama --property=Environment --no-pager
```

Expected values inside `Environment=`:

```ini
Environment="OLLAMA_FLASH_ATTENTION=1"
Environment="OLLAMA_KV_CACHE_TYPE=q8_0"
Environment="OLLAMA_CONTEXT_LENGTH=4096"
```

If `ollama ps` is empty and Ollama logs show `cudaMalloc failed` or `out of memory`, restore these settings, run `sudo systemctl daemon-reload`, restart Ollama, clear/compact memory, and warm the model again. This can fail once even with the correct q8 settings; repeat the startup block once before changing anything.

Only if q8 fails after two clean startup attempts, temporarily switch the drop-in to:

```bash
sudo tee /etc/systemd/system/ollama.service.d/zz-metax-memory.conf >/dev/null <<'EOF'
[Service]
Environment="OLLAMA_FLASH_ATTENTION=1"
Environment="OLLAMA_KV_CACHE_TYPE=q4_0"
Environment="OLLAMA_CONTEXT_LENGTH=4096"
EOF
sudo systemctl daemon-reload
```

Then restart Ollama, compact memory, and warm the model again. q4 is a reliability fallback, not the preferred daily setting. To return to q8, remove `zz-metax-memory.conf`, run `sudo systemctl daemon-reload`, and restart Ollama; the installed `metax-local.conf` already sets q8. systemd applies drop-in files in alphabetical order and the last one wins, which is why the fallback name starts with `zz-`: a name such as `90-...` sorts before `metax-local.conf` and is silently overridden. Confirm the active value with `sudo systemctl show ollama --property=Environment --no-pager`.

## Cable and authorization recovery

The ADB service automatically handles ordinary disconnects. If it does not recover:

```bash
journalctl -u metax-adb-reverse -n 50 --no-pager
adb devices -l
```

Disconnect extra phones. For `unauthorized`, unlock the intended phone and approve the prompt. For `offline`, reconnect the data cable. Do not replace the loopback binds with LAN addresses.

## Service and model recovery

```bash
sudo systemctl restart ollama
sleep 5
sudo sync
sudo sh -c 'echo 3 > /proc/sys/vm/drop_caches'
sudo sh -c 'echo 1 > /proc/sys/vm/compact_memory'

curl --fail --show-error --silent http://127.0.0.1:11434/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"model":"gemma3:4b-it-q4_K_M","messages":[{"role":"user","content":"Reply with only: ready"}],"stream":false,"keep_alive":-1,"options":{"num_ctx":4096,"num_predict":8}}'

ollama ps
sudo systemctl restart metax-gateway metax-adb-reverse
```

If recovery fails twice, inspect logs:

```bash
journalctl -u ollama -u metax-gateway -u metax-adb-reverse -n 120 --no-pager
bash inference_server/verify_jetson.sh
```

To intentionally change models, edit only ignored `inference_server/.env`, run `ollama pull <model>`, restart the gateway, and repeat the full device acceptance suite. The supported default is `gemma3:4b-it-q4_K_M`.

## Switching between Ollama and Muse

The gateway uses one backend at a time, set by `AI_BACKEND` in `inference_server/.env`. Muse sends every prompt, including customer records and document OCR text, to Meta's cloud API; see [What Muse receives](ARCHITECTURE_OVERVIEW.md#what-muse-receives). Keep Ollama warmed even while Muse is active so the emergency switch is instant.

`.env` must already contain an `AI_BACKEND=` line and, for Muse, a non-empty `MUSE_API_KEY=` line; `setup_jetson.sh` adds both lines if missing. The `sed` commands below only replace an existing line.

Check that the Jetson can reach Muse (any HTTP status code means it is reachable; `000` means no connection):

```bash
curl -sS -m 5 -o /dev/null -w "%{http_code}\n" https://api.meta.ai/v1
```

Switch to Muse:

```bash
cd ~/metaxjetson/inference_server
sed -i 's/^AI_BACKEND=.*/AI_BACKEND=muse/' .env
sudo systemctl restart metax-gateway metax-adb-reverse
sleep 3
curl -s http://127.0.0.1:8000/health; echo
```

`/health` must show `"backend":"muse"`. In Muse mode `/health` does not contact the API, so test end to end:

```bash
TOKEN=$(sed -n 's/^LOCAL_AI_TOKEN=//p' .env)
curl -sS http://127.0.0.1:8000/chat \
  -H "X-Local-Token: $TOKEN" -H "Content-Type: application/json" \
  -d '{"prompt":"Reply with only: ready","maxTokens":50}'; echo
```

Switch back to local Ollama (also the emergency fallback if Muse or the internet fails):

```bash
cd ~/metaxjetson/inference_server
sed -i 's/^AI_BACKEND=.*/AI_BACKEND=ollama/' .env
sudo systemctl restart metax-gateway
sleep 3
curl -s http://127.0.0.1:8000/health; echo
```

`/health` must show `"backend":"ollama"` and `"status":"ready"`. If the gateway does not answer at all, check `journalctl -u metax-gateway -n 30 --no-pager`; an invalid `AI_BACKEND` value stops it from starting.

## Stop and offline use

```bash
sudo systemctl stop metax-adb-reverse metax-gateway ollama
```

Normal power-off is `sudo poweroff`; wait until SSH disconnects and the Jetson finishes shutting down before unplugging power. With `AI_BACKEND=ollama` you may disconnect networking after provisioning; all inference stays on the Jetson. `AI_BACKEND=muse` needs internet access on the Jetson.

## Next-day resume checklist

1. Power the Jetson and wait about two minutes.
2. If using the direct Windows Ethernet link, confirm Windows Ethernet is `192.168.137.1`, find the Jetson in `arp -a`, then SSH to its `192.168.137.x` address.
3. Run the Ollama restart, memory compact, and warmup block from the **Start** section.
4. Confirm `ollama ps` shows `100% GPU`, context `4096`, and `Forever`.
5. Connect the phone to a Jetson USB host port, authorize USB debugging if prompted, restart `metax-adb-reverse`, and confirm `adb reverse --list`.
6. Launch the Android app and test document scan, document Q&A, and customer assistant. Document transcription runs on-device in Android through bundled ML Kit OCR; the Jetson receives text only. If you want Muse for the session, switch now (see [Switching between Ollama and Muse](#switching-between-ollama-and-muse)) and confirm `/health` reports `"backend":"muse"`.

## Rollback

Keep the previous repository revision and `.env` backup locally. To roll back code, restore that known revision without touching face assets or the secret, rerun `bash inference_server/setup_jetson.sh`, and repeat validation. Never roll back by exposing either service or changing Meta DAT from 0.7.0. Switching to Muse is a backend choice, not a rollback step.
