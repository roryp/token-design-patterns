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
    void cachingIsOneChainWhereEveryTestReachesTheModel() {
        var caching = new PatternCatalog().get("caching");
        assertThat(caching.nodes()).extracting(node -> node.id())
                .containsExactly("instructions", "cache", "model", "output");
        assertThat(caching.edges()).extracting(edge -> edge.from() + "->" + edge.to())
                .containsExactly("instructions->cache", "cache->model", "model->output");
        assertThat(caching.edges()).noneSatisfy(edge -> assertThat(edge.dashed()).isTrue());
        assertThat(caching.nodeFor("Cache answerer")).isEqualTo("model");
        assertThat(caching.agentNodes()).hasSize(1);
    }
}