package com.example.tokenpatterns;

import com.example.tokenpatterns.domain.PatternDefinition;
import com.example.tokenpatterns.domain.PatternRunResult.TraceEvent;
import com.example.tokenpatterns.service.PatternCatalog;
import com.example.tokenpatterns.service.TraceCollector;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the span-scoped usage capture: an agent that calls a model and then delegates must keep its own tokens.
 */
class TraceCollectorNestingTest {

    private static AgentInstance agent(String name) {
        AgentInstance instance = mock(AgentInstance.class);
        when(instance.name()).thenReturn(name);
        return instance;
    }

    @Test
    void nestedAgentDoesNotConsumeItsParentsModelUsage() {
        PatternDefinition definition = new PatternCatalog().get("router");
        TraceCollector collector = new TraceCollector(definition);
        ChatModel model = collector.instrument(new StubChatModel("stub-medium"));

        AgentInstance outer = agent("Code specialist");
        AgentInstance inner = agent("Knowledge specialist");

        collector.beforeAgentInvocation(new AgentRequest(null, outer, Map.of()));
        model.chat("[CODE_SPECIALIST] outer call");

        collector.beforeAgentInvocation(new AgentRequest(null, inner, Map.of()));
        model.chat("[KNOWLEDGE_SPECIALIST] inner call with a considerably longer prompt so usage differs");
        collector.afterAgentInvocation(new AgentResponse(null, inner, Map.of(), "inner", null, null));

        collector.afterAgentInvocation(new AgentResponse(null, outer, Map.of(), "outer", null, null));

        Map<String, TraceEvent> byAgent = collector.events().stream()
                .collect(java.util.stream.Collectors.toMap(TraceEvent::agent, event -> event));

        assertThat(byAgent).containsOnlyKeys("Code specialist", "Knowledge specialist");
        assertThat(byAgent.values()).allSatisfy(event -> {
            assertThat(event.kind()).isEqualTo("model");
            assertThat(event.inputTokens() + event.outputTokens()).isPositive();
        });
        assertThat(byAgent.get("Code specialist").inputTokens())
                .isNotEqualTo(byAgent.get("Knowledge specialist").inputTokens());
        assertThat(collector.modelCalls()).isEqualTo(2);
    }
}
