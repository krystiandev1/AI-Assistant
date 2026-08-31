#!/bin/bash
set -e

# Bind on all interfaces so Railway private network can reach us
export OLLAMA_HOST="${OLLAMA_HOST:-0.0.0.0:11434}"

echo "Starting Ollama (host: $OLLAMA_HOST)..."
ollama serve &
OLLAMA_PID=$!

echo "Waiting for Ollama to become ready..."
until curl -sf "http://localhost:11434/api/tags" > /dev/null 2>&1; do
    sleep 2
done
echo "Ollama server is up."

pull_if_missing() {
    local model="$1"
    if ollama list | grep -q "^${model}"; then
        echo "Model '$model' already present (cached)."
    else
        echo "Pulling model '$model'..."
        ollama pull "$model"
        echo "Model '$model' ready."
    fi
}

pull_if_missing "qwen3:4b"
pull_if_missing "qwen3-embedding:0.6b"

echo "All models ready. Ollama is fully operational."
wait $OLLAMA_PID
