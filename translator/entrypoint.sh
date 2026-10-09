#!/bin/sh
set -e

MODELS_DIR="/models"
MODEL_FILENAME="${MODEL_FILENAME:-qwen2.5-0.5b-instruct-q4_k_m.gguf}"
MODEL_PATH="${MODELS_DIR}/${MODEL_FILENAME}"
MODEL_URL="${MODEL_URL:-https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf}"

mkdir -p "$MODELS_DIR"

if [ ! -f "$MODEL_PATH" ]; then
    echo "[translator] Model file not found at ${MODEL_PATH}."
    echo "[translator] Downloading from ${MODEL_URL}..."
    TEMP_FILE="${MODEL_PATH}.tmp"
    curl -fL --retry 3 --retry-delay 2 "$MODEL_URL" -o "$TEMP_FILE"
    mv "$TEMP_FILE" "$MODEL_PATH"
    echo "[translator] Model successfully downloaded to ${MODEL_PATH}."
else
    echo "[translator] Found existing model at ${MODEL_PATH}."
fi

PORT="${PORT:-8080}"
THREADS="${THREADS:-1}"
CTX_SIZE="${CTX_SIZE:-256}"
SLOTS="${SLOTS:-1}"

echo "[translator] Starting llama-server on port ${PORT} (ctx: ${CTX_SIZE}, threads: ${THREADS}, slots: ${SLOTS})..."

exec /app/llama-server \
    -m "$MODEL_PATH" \
    --host 0.0.0.0 \
    --port "$PORT" \
    -c "$CTX_SIZE" \
    -t "$THREADS" \
    --parallel "$SLOTS" \
    --context-shift \
    "$@"
