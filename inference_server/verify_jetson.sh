#!/usr/bin/env bash
set -euo pipefail

readonly SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly ENV_FILE="${ENV_FILE:-${SERVER_DIR}/.env}"
readonly OUTPUT_DIR="${SERVER_DIR}/jetson-validation"
mkdir -p "$OUTPUT_DIR"

if [[ ! -r "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE" >&2
  exit 1
fi
OLLAMA_MODEL="$(sed -n 's/^OLLAMA_MODEL=//p' "$ENV_FILE" | tail -n 1)"
: "${OLLAMA_MODEL:=gemma3:4b-it-q4_K_M}"
export OLLAMA_MODEL
AI_BACKEND="$(sed -n 's/^AI_BACKEND=//p' "$ENV_FILE" | tail -n 1 | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
: "${AI_BACKEND:=ollama}"
case "$AI_BACKEND" in
  ollama) ;;
  muse)
    grep -q '^MUSE_API_KEY=.' "$ENV_FILE" || {
      echo "Rejected: AI_BACKEND=muse but MUSE_API_KEY is empty in $ENV_FILE." >&2
      exit 1
    }
    ;;
  *)
    echo "Rejected: AI_BACKEND must be ollama or muse (found '$AI_BACKEND')." >&2
    exit 1
    ;;
esac

# Ollama is validated in both modes: it is the primary backend or the Muse fallback.

curl --fail --silent --show-error http://127.0.0.1:11434/api/chat \
  -H 'Content-Type: application/json' \
  -d "{\"model\":\"${OLLAMA_MODEL}\",\"messages\":[{\"role\":\"user\",\"content\":\"Reply with only ready\"}],\"stream\":false,\"keep_alive\":-1,\"options\":{\"num_ctx\":4096,\"num_predict\":8}}" \
  >"${OUTPUT_DIR}/text-probe.json"
grep -qi 'ready' "${OUTPUT_DIR}/text-probe.json" || {
  echo "Rejected: text probe did not contain the expected ready response." >&2
  exit 1
}

timeout 15 tegrastats --interval 1000 >"${OUTPUT_DIR}/tegrastats.txt" &
stats_pid=$!
sleep 2
processor="$(ollama ps | awk -v model="$OLLAMA_MODEL" '$1 == model {for (i=1; i<=NF; i++) if ($i ~ /GPU/) print $(i-1) " " $i}')"
wait "$stats_pid" || true

if [[ "$processor" != "100% GPU" ]]; then
  echo "Rejected: Ollama did not report 100% GPU placement for $OLLAMA_MODEL (reported: ${processor:-missing})." >&2
  exit 1
fi
if grep -Eq 'RAM (7[0-9]{3}|8[0-9]{3})/' "${OUTPUT_DIR}/tegrastats.txt"; then
  echo "Rejected: validation observed unsafe RAM pressure." >&2
  exit 1
fi
swap_used_mb="$(free -m | awk '/Swap:/ {print $3}')"
if (( swap_used_mb > 512 )); then
  echo "Rejected: validation used ${swap_used_mb}MB of swap." >&2
  exit 1
fi
if grep -Eq '@(8[5-9]|9[0-9]|1[0-9]{2})C' "${OUTPUT_DIR}/tegrastats.txt"; then
  echo "Rejected: validation observed a temperature at or above 85C." >&2
  exit 1
fi

free -h >"${OUTPUT_DIR}/memory.txt"
ollama ps >"${OUTPUT_DIR}/ollama-ps.txt"
curl --fail --silent --show-error http://127.0.0.1:8000/health >"${OUTPUT_DIR}/health.json"
grep -q "\"backend\":\"${AI_BACKEND}\"" "${OUTPUT_DIR}/health.json" || {
  echo "Rejected: gateway /health does not report backend ${AI_BACKEND}; restart metax-gateway after editing .env." >&2
  exit 1
}
echo "Jetson validation passed: $OLLAMA_MODEL is loaded 100% on GPU; gateway backend is ${AI_BACKEND}. Evidence: $OUTPUT_DIR"
if [[ "$AI_BACKEND" == "muse" ]]; then
  echo "Note: Muse is a cloud API. Its reachability is not probed here; check internet access separately."
fi
