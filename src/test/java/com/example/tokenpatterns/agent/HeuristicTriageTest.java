package com.example.tokenpatterns.agent;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicTriageTest {

    @ParameterizedTest(name = "{1}: {0}")
    @CsvSource(delimiter = '|', value = {
            "What does HTTP 429 mean and what should a client do? | SIMPLE",
            "What does the word architecture mean? Answer briefly. | SIMPLE",
            "What is a distributed system? | SIMPLE",
            "Define idempotency in one sentence. | SIMPLE",
            "How do I securely store an API key in an environment variable? | SIMPLE",
            "Why does my Java stream return an empty list after I add a filter? | SIMPLE",
            "Give a short explanation of how retries work. | SIMPLE",
            "Design a secure distributed multi-region architecture for a payment system, including migration trade-offs. | COMPLEX",
            "Recommend an architecture for a multi-region payment service with one trade-off and a safe rollout plan. | COMPLEX",
            "Our multi-region payment API failed over to the secondary region and started double-charging customers. Diagnose the root cause and recommend a fix. | COMPLEX",
            "What is the best architecture for a multi-region payment system with strict consistency, and how should we migrate from our monolith while keeping PCI compliance? | COMPLEX",
            "Should we migrate our distributed job scheduler from cron to a queue? | COMPLEX"
    })
    void escalatesOnlyWhenSeveralComplexitySignalsAgree(String request, String expected) {
        assertThat(PatternAgents.HeuristicTriage.complexity(request)).isEqualTo(expected);
    }
}
