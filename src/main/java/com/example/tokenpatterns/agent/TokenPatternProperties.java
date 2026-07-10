package com.example.tokenpatterns.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("token-patterns.openai")
public record TokenPatternProperties(
        String apiKey,
        String endpoint,
        boolean managedIdentity,
        String smallModel,
        String mediumModel,
        String largeModel) {
}