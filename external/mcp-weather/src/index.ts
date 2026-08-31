import {McpServer} from "@modelcontextprotocol/sdk/server/mcp.js";
import {StdioServerTransport} from "@modelcontextprotocol/sdk/server/stdio.js";
import {z} from "zod";
import {handleGetWeather} from "./weather-handler.js";

const server = new McpServer({
    name: "mcp-weather",
    version: "1.0.0",
});

server.registerTool(
    "get-weather",
    {
        description:
            "Get the current weather for a city. Use this tool whenever current temperature or weather conditions are requested.",
        inputSchema: z.object({
            city: z
                .string()
                .describe(
                    "The city name to get weather for, e.g. 'Berlin', 'Munich'"
                ),
        }),
    },
    handleGetWeather,
);

async function main(): Promise<void> {
    const transport = new StdioServerTransport();
    await server.connect(transport);
    process.stderr.write("Weather MCP server running on stdio\n");
}

main().catch((err) => {
    process.stderr.write(`Fatal: ${err}\n`);
    process.exit(1);
});
