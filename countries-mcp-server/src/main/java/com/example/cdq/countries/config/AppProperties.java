package com.example.cdq.countries.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app")
public record AppProperties(
    Countries countries,
    Timeouts  timeouts
) {
    public record Countries(
        String baseUrl,
        String apiKey
    ) {}

    public record Timeouts(
        Duration countries
    ) {
        public Timeouts {
            if (countries == null) countries = Duration.ofSeconds(5);
        }
    }
}
