# Pair actual Meta glasses with this app

Use this when moving from MockDeviceKit to real Meta/Ray-Ban Meta glasses.

You do **not** install this APK onto the glasses. Install this app only on the Android phone.

"Companion app" means the **Meta AI app** on the phone. On some older setups it may still be called Meta View.

## 1. Pair the glasses in the Meta AI app

1. Charge the glasses and case.
2. Turn on Bluetooth on the Android phone.
3. Open the **Meta AI** app.
4. Sign in.
5. Go to the glasses/devices section.
6. Tap the option to add or pair glasses.
7. Keep the glasses near the phone.
8. Accept Bluetooth/pairing prompts.
9. If Meta AI asks for a glasses firmware update, install it.
10. Confirm Meta AI shows the glasses as connected.

## 2. Start Jetson services

SSH into the Jetson:

```powershell
ssh user@ubuntu.local
```

If Wi-Fi is down, use the direct Ethernet/ICS link and the Jetson's `192.168.137.x` address.

Run:

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
sudo systemctl start metax-gateway metax-adb-reverse
```

Gemma is warmed even though the demo uses Muse, so it is ready as the backup.

If the warmup fails once with `cudaMalloc` or `out of memory`, repeat the exact same block once.

Do not switch to q4 KV unless this fails after two clean tries.

Check:

```bash
systemctl is-active ollama metax-gateway metax-adb-reverse
curl --fail --show-error --silent http://127.0.0.1:8000/health
ollama ps
```

Expected:

```text
active
active
active
```

Health should say `ready` and `"backend":"muse"` (or `ollama` if you are on the backup), and `ollama ps` should show `100% GPU`, context `4096`, and `Forever`.

## 3. Connect the phone to the Jetson

1. Connect the phone directly to a Jetson USB 3.0 port.
2. Use a data-capable USB cable.
3. Unlock the phone.
4. If USB debugging asks for permission, tap **Allow**.
5. If the Jetson RSA prompt appears, tap **Allow**.

On the Jetson, run:

```bash
adb devices -l
adb reverse --list
```

Expected:

- one phone listed as `device`;
- reverse rule shows `tcp:8000 tcp:8000`.

If reverse is missing:

```bash
sudo systemctl restart metax-adb-reverse
sleep 8
adb reverse --list
```

## 4. Check phone-to-Jetson health

On the Android phone, open Chrome.

Go to:

```text
http://127.0.0.1:8000/health
```

Expected: JSON showing `ready`.

Do not continue until this works.

## 5. Disable MockDeviceKit

In this app:

1. Open the debug/bug button.
2. Open MockDeviceKit.
3. Tap **Disable MockDeviceKit**.
4. Force close and reopen the app if needed.

## 6. Connect this app to the real glasses

1. Open this app on the phone.
2. Tap **Connect my glasses**.
3. Allow requested permissions:
   - Nearby devices / Bluetooth;
   - Camera;
   - Microphone;
   - Meta/DAT permission prompts.
4. If Meta AI opens, complete the prompt there.
5. Return to this app.
6. Wait for the connected/non-streaming screen.

## 7. If update buttons appear

If the app shows a firmware update:

1. Tap the firmware update button.
2. Complete the update in Meta AI.
3. Return to this app.

If the app shows **Update app on glasses**:

1. Tap **Update app on glasses**.
2. Complete the Meta/DAT update in Meta AI.
3. Return to this app.
4. Retry streaming.

This is not sideloading your APK. It is Meta's own glasses-side DAT update.

## 8. Start streaming

1. Tap **Start streaming**.
2. Allow camera permission if prompted.
3. Wait for the live preview.
4. Say **"Meta scan"** (or tap the document scan button) with the document in view.
5. Say **"Meta question"**, then ask about the document, to confirm OCR, analysis and Q&A work.
6. End the document session, look at a sample customer, and say **"Meta question"** to test the customer assistant. The customer card appears once the face matches and clears about 5 seconds after they leave view.

The stream keeps running with the phone screen off; a "WF Meta is streaming" notification shows while it does. Warnings such as glasses running hot, low battery or a timeout appear in the app but do not end the session.

## 9. If something fails

### Phone cannot reach health URL

Fix Jetson/ADB reverse:

```bash
adb devices -l
adb reverse --list
sudo systemctl restart metax-adb-reverse
```

### App cannot find glasses

1. Open Meta AI.
2. Confirm glasses are connected there.
3. Disable MockDeviceKit.
4. Reopen this app.
5. Tap **Connect my glasses** again.

### Session ends on its own

The session now ends only on a critical stream error, the glasses hinge closing, permissions being revoked, or a thermal emergency. If it still ends, keep the glasses cool and charged, keep the phone near the glasses, and avoid running a 2.4 GHz phone hotspot at the same time. Capture logs with:

```bash
adb logcat -G 16M && adb logcat -c
adb logcat | grep -E "CameraAccess:|DAT:"
```

### Voice command not recognised

Speak "Meta scan" or "Meta question" clearly after the status reads "Say 'Meta scan' or 'Meta question'". The status line shows what was heard (`Heard: ...`).

### Local AI request failed

Check Jetson:

```bash
systemctl is-active ollama metax-gateway metax-adb-reverse
curl --fail --show-error --silent http://127.0.0.1:8000/health
ollama ps
```

If `/health` shows `"backend":"muse"`, the Jetson also needs internet access and a valid `MUSE_API_KEY`. `Cannot reach the Muse API: [Errno -3] Temporary failure in name resolution` means the Jetson has no internet or DNS; see [Wi-Fi recovery](DAILY_START_STOP.md#wi-fi-recovery). `finish_reason=length` means `MUSE_MIN_OUTPUT_TOKENS` is too low. To fall back to local Gemma, follow [Switching between Ollama and Muse](DAILY_START_STOP.md#switching-between-ollama-and-muse).

### Scan works but OCR is bad

Retake the photo with:

- better lighting;
- less blur;
- the full document in frame;
- less glare.

## Final checklist

- [ ] Glasses connected in Meta AI.
- [ ] MockDeviceKit disabled.
- [ ] Phone connected to Jetson by USB.
- [ ] `adb devices -l` shows one authorized phone.
- [ ] `adb reverse --list` shows `tcp:8000 tcp:8000`.
- [ ] Phone Chrome opens `http://127.0.0.1:8000/health`.
- [ ] App **Connect my glasses** succeeds.
- [ ] App **Start streaming** succeeds.
- [ ] Jetson has internet (`getent hosts api.meta.ai`) and `/health` shows the expected backend.
- [ ] "Meta scan" and "Meta question" are recognised.
- [ ] Document scan succeeds.
- [ ] Customer assistant answers for a recognised sample customer.
