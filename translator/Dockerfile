FROM ghcr.io/ggml-org/llama.cpp:server

WORKDIR /app

# Copy entrypoint script
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

ENV PORT=8080 \
    THREADS=1 \
    CTX_SIZE=256 \
    SLOTS=1 \
    MODEL_FILENAME=qwen2.5-0.5b-instruct-q4_k_m.gguf \
    MODEL_URL=https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf

VOLUME ["/models"]

EXPOSE 8080

ENTRYPOINT ["/app/entrypoint.sh"]

