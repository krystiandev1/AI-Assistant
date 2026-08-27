package com.example.cdq.countries.client;

import com.example.cdq.countries.config.AppProperties;
import com.example.cdq.countries.model.CountryInfo;
import com.example.cdq.countries.model.ToolResult;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class RestCountriesClientTest {

    static WireMockServer wireMock;
    static RestCountriesClient client;

    @BeforeAll
    static void setup() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig()
            .usingFilesUnderClasspath(".")
            .dynamicPort());
        wireMock.start();

        AppProperties props = new AppProperties(
            new AppProperties.Countries("http://localhost:" + wireMock.port(), "test-api-key"),
            new AppProperties.Timeouts(Duration.ofSeconds(5))
        );
        client = new RestCountriesClient(props, WebClient.builder());
    }

    @AfterAll
    static void teardown() {
        wireMock.stop();
    }

    @Test
    void germany_returns_berlin_as_capital() {
        wireMock.stubFor(get(urlPathEqualTo("/countries/v5/names.common/Germany"))
            .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer test-api-key"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .withBodyFile("germany-v5-response.json")));

        ToolResult<CountryInfo> result = client.findByName("Germany");

        assertThat(result.status()).isEqualTo(ToolResult.Status.OK);
        assertThat(result.data()).isNotNull();
        assertThat(result.data().name()).isEqualTo("Germany");
        assertThat(result.data().officialName()).isEqualTo("Federal Republic of Germany");
        assertThat(result.data().capital()).isEqualTo("Berlin");
        assertThat(result.data().region()).isEqualTo("Europe");
        assertThat(result.data().population()).isEqualTo(83_240_525L);
    }

    @Test
    void country_not_found_returns_error_result() {
        wireMock.stubFor(get(urlPathEqualTo("/countries/v5/names.common/Narnia"))
            .willReturn(aResponse().withStatus(404)));

        ToolResult<CountryInfo> result = client.findByName("Narnia");

        assertThat(result.status()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(result.errorCode()).isEqualTo("COUNTRY_NOT_FOUND");
    }

    @Test
    void missing_api_key_returns_error_result() {
        wireMock.stubFor(get(urlPathEqualTo("/countries/v5/names.common/Germany"))
            .withHeader(HttpHeaders.AUTHORIZATION, absent())
            .willReturn(aResponse().withStatus(401)));

        // Our client always sends the Bearer header, so 401 only happens if key is invalid
        // This test verifies the client handles non-404 server errors gracefully
        WireMockServer unauthWireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        unauthWireMock.start();
        unauthWireMock.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(401)));

        AppProperties badProps = new AppProperties(
            new AppProperties.Countries("http://localhost:" + unauthWireMock.port(), "bad-key"),
            new AppProperties.Timeouts(Duration.ofSeconds(5))
        );
        RestCountriesClient badClient = new RestCountriesClient(badProps, WebClient.builder());

        ToolResult<CountryInfo> result = badClient.findByName("Germany");
        // 401 is not NotFound, so it bubbles as COUNTRIES_API_ERROR
        assertThat(result.status()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(result.errorCode()).isEqualTo("COUNTRIES_API_ERROR");

        unauthWireMock.stop();
    }

    @Test
    void timeout_returns_error_result() {
        wireMock.stubFor(get(urlPathEqualTo("/countries/v5/names.common/Slow"))
            .willReturn(aResponse()
                .withStatus(200)
                .withFixedDelay(10_000)));

        AppProperties timeoutProps = new AppProperties(
            new AppProperties.Countries("http://localhost:" + wireMock.port(), "test-api-key"),
            new AppProperties.Timeouts(Duration.ofMillis(200))
        );
        RestCountriesClient timeoutClient = new RestCountriesClient(timeoutProps, WebClient.builder());

        ToolResult<CountryInfo> result = timeoutClient.findByName("Slow");
        assertThat(result.status()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(result.errorCode()).isEqualTo("COUNTRIES_API_ERROR");
    }
}
