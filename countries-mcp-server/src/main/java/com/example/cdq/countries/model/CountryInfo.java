package com.example.cdq.countries.model;

public record CountryInfo(
    String name,
    String officialName,
    String capital,
    String region,
    String subregion,
    long   population
) {}
