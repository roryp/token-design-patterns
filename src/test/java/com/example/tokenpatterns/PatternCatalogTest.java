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

    @Test
    void cachingShowsBothProviderOutcomesButNeverSkipsTheModel() {
        var caching = new PatternCatalog().get("caching");
        assertThat(caching.nodes()).extracting(node -> node.id())
                .containsExactly("input", "cache", "hit", "miss", "model", "output");
        assertThat(caching.edges()).anySatisfy(edge -> {
            assertThat(edge.from()).isEqualTo("hit");
            assertThat(edge.to()).isEqualTo("model");
        }).anySatisfy(edge -> {
            assertThat(edge.from()).isEqualTo("miss");
            assertThat(edge.to()).isEqualTo("model");
        }).anySatisfy(edge -> {
            assertThat(edge.from()).isEqualTo("model");
            assertThat(edge.to()).isEqualTo("cache");
            assertThat(edge.dashed()).isTrue();
        });
        assertThat(caching.edges()).filteredOn(edge -> edge.to().equals("output"))
                .allSatisfy(edge -> assertThat(edge.from()).isEqualTo("model"));
        assertThat(caching.nodeFor("Cache answerer")).isEqualTo("model");
        assertThat(caching.agentNodes()).hasSize(1);
    }
}