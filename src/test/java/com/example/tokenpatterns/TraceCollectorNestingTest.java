package com.example.tokenpatterns;

import com.example.tokenpatterns.domain.PatternDefinition;
import com.example.tokenpatterns.agent.ProviderTokenUsage;
import com.example.tokenpatterns.domain.PatternRunResult.TraceEvent;
import com.example.tokenpatterns.service.PatternCatalog;
import com.example.tokenpatterns.service.TraceCollector;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
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

    @Test
    void sumsProviderSubsetsWithoutSubtractingOrCountingThemTwice() {
        TraceCollector collector = new TraceCollector(new PatternCatalog().get("router"));
        recordUsage(collector, "Code specialist", new ProviderTokenUsage(2000, 50, 2050, 1200, 0, 20));
        recordUsage(collector, "Knowledge specialist", new ProviderTokenUsage(2100, 80, 2180, 0, 1600, 30));

        assertThat(collector.observedTokens()).isEqualTo(4230);
        assertThat(collector.inputTokens()).isEqualTo(4100);
        assertThat(collector.outputTokens()).isEqualTo(130);
        assertThat(collector.cachedInputTokens()).isEqualTo(1200);
        assertThat(collector.cacheWriteTokens()).isEqualTo(1600);
        assertThat(collector.reasoningTokens()).isEqualTo(50);
        assertThat(collector.events().getFirst().cachedInputTokens()).isEqualTo(1200);
        assertThat(collector.events().getLast().cacheWriteTokens()).isEqualTo(1600);
    }

    @Test
    void oneMissingCounterMakesOnlyThatAggregateUnknown() {
        TraceCollector collector = new TraceCollector(new PatternCatalog().get("router"));
        recordUsage(collector, "Code specialist", new ProviderTokenUsage(2000, 50, 2050, 1200, 0, 20));
        recordUsage(collector, "Knowledge specialist", new ProviderTokenUsage(2100, 80, 2180, 0, null, null));

        assertThat(collector.observedTokens()).isEqualTo(4230);
        assertThat(collector.cachedInputTokens()).isEqualTo(1200);
        assertThat(collector.cacheWriteTokens()).isNull();
        assertThat(collector.reasoningTokens()).isNull();
        assertThat(collector.cacheStatus(true)).isEqualTo("hit");
    }

    private static void recordUsage(TraceCollector collector, String name, TokenUsage usage) {
        AgentInstance agent = agent(name);
        ChatModel model = collector.instrument(new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("Measured answer"))
                        .modelName("fixture-model")
                        .tokenUsage(usage)
                        .build();
            }
        });
        collector.beforeAgentInvocation(new AgentRequest(null, agent, Map.of()));
        model.chat("Fixture request");
        collector.afterAgentInvocation(new AgentResponse(null, agent, Map.of(), "Measured answer", null, null));
    }
}
