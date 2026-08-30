import { describe, it, expect, vi, beforeEach } from "vitest";
import { handleGetWeather } from "./weather-handler.js";

function mockFetch(ok: boolean, body?: unknown): void {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue({
      ok,
      json: async () => body,
    }),
  );
}

describe("handleGetWeather", () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  it("success → status OK with temperatureCelsius", async () => {
    mockFetch(true, { location: { name: "Berlin" }, current: { temp_c: 18.5 } });

    const result = await handleGetWeather({ city: "Berlin" });
    const parsed = JSON.parse(result.content[0].text);

    expect(parsed.status).toBe("OK");
    expect(parsed.city).toBe("Berlin");
    expect(parsed.temperatureCelsius).toBe(18.5);
  });

  it("success → temperatureCelsius is null when temp_c absent", async () => {
    mockFetch(true, { location: { name: "Berlin" } });

    const result = await handleGetWeather({ city: "Berlin" });
    const parsed = JSON.parse(result.content[0].text);

    expect(parsed.status).toBe("OK");
    expect(parsed.temperatureCelsius).toBeNull();
  });

  it("success → city falls back to input param when location.name absent", async () => {
    mockFetch(true, { current: { temp_c: 10.0 } });

    const result = await handleGetWeather({ city: "Warsaw" });
    const parsed = JSON.parse(result.content[0].text);

    expect(parsed.status).toBe("OK");
    expect(parsed.city).toBe("Warsaw");
  });

  it("HTTP error → status ERROR with WEATHER_PROVIDER_UNAVAILABLE", async () => {
    mockFetch(false);

    const result = await handleGetWeather({ city: "Berlin" });
    const parsed = JSON.parse(result.content[0].text);

    expect(parsed.status).toBe("ERROR");
    expect(parsed.errorCode).toBe("WEATHER_PROVIDER_UNAVAILABLE");
  });

  it("network exception → status ERROR with WEATHER_PROVIDER_UNAVAILABLE", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("Network failure")));

    const result = await handleGetWeather({ city: "Berlin" });
    const parsed = JSON.parse(result.content[0].text);

    expect(parsed.status).toBe("ERROR");
    expect(parsed.errorCode).toBe("WEATHER_PROVIDER_UNAVAILABLE");
  });
});
