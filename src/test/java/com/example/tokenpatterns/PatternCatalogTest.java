package com.example.tokenpatterns;

import com.example.tokenpatterns.service.PatternCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PatternCatalogTest {

    @Test
    void exposesEightPatternsInPresentationOrder() {
        PatternCatalog catalog = new PatternCatalog();

        assertThat(catalog.all())
                .extracting(pattern -> pattern.id())
                .containsExactly(
                        "router",
                        "triage",
                        "compression",
                        "rag",
                        "tool-use",
                        "step-back",
                        "caching",
                        "batching");
        assertThat(catalog.all()).allSatisfy(pattern -> {
            assertThat(pattern.nodes()).isNotEmpty();
            assertThat(pattern.edges()).isNotEmpty();
            assertThat(pattern.sampleInput()).isNotBlank();
        });
    }
}