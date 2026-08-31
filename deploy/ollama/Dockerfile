FROM ollama/ollama:latest

RUN printf '%s\n' \
  '#!/bin/bash' \
  'set -e' \
  'export OLLAMA_HOST="${OLLAMA_HOST:-0.0.0.0:11434}"' \
  'export OLLAMA_MAX_LOADED_MODELS="${OLLAMA_MAX_LOADED_MODELS:-2}"' \
  'export OLLAMA_KEEP_ALIVE="${OLLAMA_KEEP_ALIVE:--1}"' \
  'export OLLAMA_NUM_PARALLEL="${OLLAMA_NUM_PARALLEL:-1}"' \
  'echo "Starting Ollama (host=$OLLAMA_HOST, max_models=$OLLAMA_MAX_LOADED_MODELS, keep_alive=$OLLAMA_KEEP_ALIVE)..."' \
  'ollama serve &' \
  'OLLAMA_PID=$!' \
  'echo "Waiting for Ollama to become ready..."' \
  'until ollama list > /dev/null 2>&1; do' \
  '    sleep 2' \
  'done' \
  'echo "Ollama server is up."' \
  'pull_if_missing() {' \
  '    local model="$1"' \
  '    if ollama list 2>/dev/null | grep -q "${model}"; then' \
  '        echo "Model [${model}] already cached in volume."' \
  '    else' \
  '        echo "Pulling model [${model}] — this may take several minutes..."' \
  '        ollama pull "$model"' \
  '        echo "Model [${model}] ready."' \
  '    fi' \
  '}' \
  'pull_if_missing "qwen3:4b"' \
  'pull_if_missing "qwen3-embedding:0.6b"' \
  'echo "Pre-loading chat model into RAM..."' \
  'ollama run qwen3:4b "Hello" > /dev/null 2>&1' \
  'echo "All models ready. Ollama is fully operational."' \
  'wait $OLLAMA_PID' \
  > /ollama-entrypoint.sh && chmod +x /ollama-entrypoint.sh

EXPOSE 11434
ENTRYPOINT ["/ollama-entrypoint.sh"]
