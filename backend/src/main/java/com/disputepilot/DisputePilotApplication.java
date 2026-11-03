package com.disputepilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DisputePilotApplication {
    public static void main(String[] args) {
        SpringApplication.run(DisputePilotApplication.class, args);
    }
}
