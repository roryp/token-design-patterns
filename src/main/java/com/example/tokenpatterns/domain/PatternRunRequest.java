package com.example.tokenpatterns.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PatternRunRequest(
        @NotBlank String patternId,
        @NotBlank @Size(max = 12_000) String input,
        Boolean cacheEnabled,
        @Pattern(regexp = PatternRunRequest.CACHE_SESSION_PATTERN, message = "must be a lowercase version 4 UUID")
        String cacheSession) {

    public static final String CACHE_SESSION_PATTERN =
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

    public PatternRunRequest(String patternId, String input) {
        this(patternId, input, null, null);
    }

    public PatternRunRequest(String patternId, String input, Boolean cacheEnabled) {
        this(patternId, input, cacheEnabled, null);
    }

    public boolean providerCacheEnabled() {
        return cacheEnabled == null || cacheEnabled;
    }
}