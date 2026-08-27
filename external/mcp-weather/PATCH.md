# Weather MCP Fork

Upstream: https://github.com/semdin/mcp-weather
Upstream commit: [pin after first `git clone` — run `git -C external/mcp-weather log --oneline -1` to record]

## Patch 1 — Portable environment configuration

Problem: Original code imported `dotenv` with a hardcoded Windows-specific `.env` path,
breaking non-Windows usage and making the server unsuitable for STDIO process launch by
Spring AI (which injects env vars via the child process environment, not a file).

Change: Removed `dotenv` import and `dotenv.config()` call entirely. All configuration
now read from `process.env`, which Spring AI's STDIO client populates automatically.

## Patch 2 — Tool description

Problem: Original description was vague and did not communicate expected use to the model.

Change: Updated to: "Get the current weather for a city. Use this tool whenever current
temperature or weather conditions are requested."

## Patch 3 — Structured error and success responses

Problem: Errors returned as plain text, unparseable by McpToolResultDecoder for evidence.
Success response was not consistently structured.

Change: Both success and error paths return JSON inside the content array:
- Success: `{ "status": "OK", "city": "...", "temperatureCelsius": 18.2 }`
- Error:   `{ "status": "ERROR", "errorCode": "WEATHER_PROVIDER_UNAVAILABLE", "message": "..." }`

Note: The tool name remains `get-weather` (upstream convention). Spring AI normalizes
MCP tool names for model compatibility — the model sees `get_weather` (underscore).
Do not rename the tool in this server.
