#!/bin/bash
set -e

if [ -n "$OLLAMA_BASE_URL" ]; then
    echo "Waiting for Ollama at $OLLAMA_BASE_URL ..."
    until curl -sf "$OLLAMA_BASE_URL/api/tags" > /dev/null 2>&1; do
        echo "  Ollama not ready yet, retrying in 5s..."
        sleep 5
    done
    echo "Ollama is ready."
fi

exec java -jar /app/app.jar
