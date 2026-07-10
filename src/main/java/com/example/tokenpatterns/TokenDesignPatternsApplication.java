package com.example.tokenpatterns;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TokenDesignPatternsApplication {

    public static void main(String[] args) {
        SpringApplication.run(TokenDesignPatternsApplication.class, args);
    }
}