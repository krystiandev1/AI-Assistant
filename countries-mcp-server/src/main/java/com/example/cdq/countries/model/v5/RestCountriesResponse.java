package com.example.cdq.countries.model.v5;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RestCountriesResponse(Data data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(List<CountryObject> objects) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CountryObject(
        Names names,
        List<Capital> capitals,
        String region,
        String subregion,
        long population
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Names(
        String common,
        String official
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Capital(String name) {}
}
