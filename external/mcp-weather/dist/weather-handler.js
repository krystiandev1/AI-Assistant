const WEATHER_API_KEY = process.env.WEATHER_API_KEY ?? "";
const WEATHER_API_URL = process.env.WEATHER_API_URL ?? "https://api.weatherapi.com/v1/current.json";
export async function handleGetWeather({ city }) {
    try {
        const url = `${WEATHER_API_URL}?key=${encodeURIComponent(WEATHER_API_KEY)}&q=${encodeURIComponent(city)}`;
        const response = await fetch(url);
        if (!response.ok) {
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
}
