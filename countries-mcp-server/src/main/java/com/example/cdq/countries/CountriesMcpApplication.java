package com.example.cdq.countries;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CountriesMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(CountriesMcpApplication.class, args);
    }
}
