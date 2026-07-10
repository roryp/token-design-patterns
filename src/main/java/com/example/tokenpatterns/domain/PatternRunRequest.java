package com.example.tokenpatterns.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PatternRunRequest(
        @NotBlank String patternId,
        @NotBlank @Size(max = 12_000) String input,
        String mode) {

    public String normalizedMode() {
        return "live".equalsIgnoreCase(mode) ? "live" : "demo";
    }
}