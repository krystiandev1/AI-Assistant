package com.example.cdq.countries.tool;

import com.example.cdq.countries.client.RestCountriesClient;
import com.example.cdq.countries.model.CountryInfo;
import com.example.cdq.countries.model.ToolResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class CountryTool {

    private final RestCountriesClient restCountriesClient;

    public CountryTool(RestCountriesClient restCountriesClient) {
        this.restCountriesClient = restCountriesClient;
    }

    @McpTool(
        name = "get_country",
        description = "Retrieves factual information about a country: name, capital city, " +
                      "region, and population. Use this tool for any question about " +
                      "country facts, capitals, or country-specific data. " +
                      "The returned 'capital' field contains the capital city name " +
                      "which can be used directly as input to the get_weather tool."
    )
    public ToolResult<CountryInfo> getCountry(
        @McpToolParam(
            description = "Common English country name, e.g. 'Germany', 'France', 'Japan'",
            required = true
        )
        String countryName
    ) {
        return restCountriesClient.findByName(countryName);
    }
}
