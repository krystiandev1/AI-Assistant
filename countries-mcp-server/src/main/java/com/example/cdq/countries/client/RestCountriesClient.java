package com.example.cdq.countries.client;

import com.example.cdq.countries.config.AppProperties;
import com.example.cdq.countries.model.CountryInfo;
import com.example.cdq.countries.model.ToolResult;
import com.example.cdq.countries.model.v5.RestCountriesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Optional;

@Component
public class RestCountriesClient {

    private static final Logger log = LoggerFactory.getLogger(RestCountriesClient.class);

    private static final String RESPONSE_FIELDS =
        "names.common,names.official,capitals,region,subregion,population";

    private final WebClient webClient;
    private final Duration timeout;

    public RestCountriesClient(AppProperties props, WebClient.Builder webClientBuilder) {
        this.timeout = props.timeouts().countries();
        this.webClient = webClientBuilder
            .baseUrl(props.countries().baseUrl())
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.countries().apiKey())
            .build();
    }

    public ToolResult<CountryInfo> findByName(String countryName) {
        log.debug("Calling REST Countries v5 for: {}", countryName);
        try {
            RestCountriesResponse response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/countries/v5/names.common/{name}")
                    .queryParam("response_fields", RESPONSE_FIELDS)
                    .build(countryName))
                .retrieve()
                .bodyToMono(RestCountriesResponse.class)
                .timeout(timeout)
                .block();

            return map(response);
        } catch (WebClientResponseException.NotFound e) {
            log.debug("Country not found: {}", countryName);
            return ToolResult.error("COUNTRY_NOT_FOUND",
                "No country found with name: " + countryName);
        } catch (Exception e) {
            log.error("REST Countries call failed for: {}", countryName, e);
            return ToolResult.error("COUNTRIES_API_ERROR",
                "Failed to retrieve country data: " + e.getMessage());
        }
    }

    private ToolResult<CountryInfo> map(RestCountriesResponse response) {
        if (response == null
                || response.data() == null
                || response.data().objects() == null
                || response.data().objects().isEmpty()) {
            return ToolResult.error("COUNTRY_NOT_FOUND", "No data returned");
        }

        RestCountriesResponse.CountryObject obj = response.data().objects().get(0);

        String capital = Optional.ofNullable(obj.capitals())
            .filter(caps -> !caps.isEmpty())
            .map(caps -> caps.get(0).name())
            .orElse("Unknown");

        String commonName = Optional.ofNullable(obj.names())
            .map(RestCountriesResponse.Names::common)
            .orElse("Unknown");

        String officialName = Optional.ofNullable(obj.names())
            .map(RestCountriesResponse.Names::official)
            .orElse(commonName);

        return ToolResult.ok(new CountryInfo(
            commonName,
            officialName,
            capital,
            Optional.ofNullable(obj.region()).orElse("Unknown"),
            Optional.ofNullable(obj.subregion()).orElse("Unknown"),
            obj.population()
        ));
    }
}
