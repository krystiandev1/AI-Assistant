import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
// Patch 1: dotenv removed — Spring AI STDIO client injects env vars via process.env
const WEATHER_API_KEY = process.env.WEATHER_API_KEY ?? "";
const WEATHER_API_URL = process.env.WEATHER_API_URL ?? "https://api.weatherapi.com/v1/current.json";
const server = new McpServer({
    name: "mcp-weather",
    version: "1.0.0",
});
server.tool("get-weather", 
// Patch 2: accurate description for tool selection
"Get the current weather for a city. Use this tool whenever current temperature or weather conditions are requested.", { city: z.string().describe("The city name to get weather for, e.g. 'Berlin', 'Munich'") }, async ({ city }) => {
    try {
        const url = `${WEATHER_API_URL}?key=${encodeURIComponent(WEATHER_API_KEY)}&q=${encodeURIComponent(city)}`;
        const response = await fetch(url);
        if (!response.ok) {
            // Patch 3: structured error — parseable by McpToolResultDecoder
            return {
                content: [{
                        type: "text",
                        text: JSON.stringify({
                            status: "ERROR",
                            errorCode: "WEATHER_PROVIDER_UNAVAILABLE",
                            message: "Current weather data could not be retrieved.",
                        }),
                    }],
            };
        }
        const data = await response.json();
        // Patch 3: structured success — parseable by McpToolResultDecoder
        return {
            content: [{
                    type: "text",
                    text: JSON.stringify({
                        status: "OK",
                        city: data.location?.name ?? city,
                        temperatureCelsius: data.current?.temp_c ?? null,
                    }),
                }],
        };
    }
    catch (_err) {
        return {
            content: [{
                    type: "text",
                    text: JSON.stringify({
                        status: "ERROR",
                        errorCode: "WEATHER_PROVIDER_UNAVAILABLE",
                        message: "Current weather data could not be retrieved.",
                    }),
                }],
        };
    }
});
async function main() {
    const transport = new StdioServerTransport();
    await server.connect(transport);
    process.stderr.write("Weather MCP server running on stdio\n");
}
main().catch((err) => {
    process.stderr.write(`Fatal: ${err}\n`);
    process.exit(1);
});
