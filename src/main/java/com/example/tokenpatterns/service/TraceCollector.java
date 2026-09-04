package com.example.tokenpatterns.service;

import com.example.tokenpatterns.agent.ModelCatalog.ModelSet;
import com.example.tokenpatterns.agent.PatternAgents.NonAiObserver;
import com.example.tokenpatterns.domain.PatternDefinition;
import com.example.tokenpatterns.domain.PatternRunResult.TraceEvent;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class TraceCollector implements AgentListener, NonAiObserver {

    private static final Set<String> NON_AI_AGENTS = Set.of(
            "Triage gate", "Knowledge retriever", "Cost calculator", "Cache lookup");

    private final PatternDefinition definition;
    private final AtomicInteger sequence = new AtomicInteger();
    private final List<TraceEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
    private final ThreadLocal<Deque<PendingSpan>> spans = ThreadLocal.withInitial(ArrayDeque::new);

    public TraceCollector(PatternDefinition definition) {
        this.definition = definition;
    }

    /** Wraps each tier so usage is read from the model call itself, not from scope state shared by parallel agents. */
    public ModelSet instrument(ModelSet models) {
        return new ModelSet(
                instrument(models.small()),
                instrument(models.medium()),
                instrument(models.large()),
                models.label());
    }

    public ChatModel instrument(ChatModel delegate) {
        return new TracingChatModel(delegate, this);
    }

    @Override
    public void beforeAgentInvocation(AgentRequest request) {
        spans.get().push(new PendingSpan(
                sequence.incrementAndGet(),
                request.agentName(),
                System.nanoTime(),
                preview(request.inputs())));
    }

    @Override
    public void afterAgentInvocation(AgentResponse response) {
        PendingSpan span = pop(response.agentName());
        ChatResponse chatResponse = span.modelResponse().get();
        TokenUsage usage = chatResponse == null ? null : chatResponse.tokenUsage();
        int inputTokens = usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
        int outputTokens = usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
        String model = chatResponse == null || chatResponse.modelName() == null
                ? ""
                : chatResponse.modelName();

        events.add(new TraceEvent(
                span.sequence(),
                response.agentName(),
                definition.nodeFor(response.agentName()),
                kind(response.agentName(), chatResponse != null),
                model,
                elapsedMillis(span.startedAtNanos()),
                inputTokens,
                outputTokens,
                span.inputPreview(),
                preview(response.output()),
                "completed"));
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError error) {
        PendingSpan span = pop(error.agentName());
        events.add(new TraceEvent(
                span.sequence(),
                error.agentName(),
                definition.nodeFor(error.agentName()),
                kind(error.agentName(), false),
                "",
                elapsedMillis(span.startedAtNanos()),
                0,
                0,
                span.inputPreview(),
                preview(error.error().getMessage()),
                "failed"));
    }

    @Override
    public boolean inheritedBySubagents() {
        return true;
    }

    public List<TraceEvent> events() {
        synchronized (events) {
            return events.stream()
                    .sorted(Comparator.comparingInt(TraceEvent::sequence))
                    .toList();
        }
    }

    public int observedTokens() {
        return events().stream().mapToInt(event -> event.inputTokens() + event.outputTokens()).sum();
    }

    public int modelCalls() {
        return (int) events().stream().filter(event -> "model".equals(event.kind())).count();
    }

    public int outputTokensFor(String agentName) {
        return events().stream()
                .filter(event -> agentName.equals(event.agent()))
                .mapToInt(TraceEvent::outputTokens)
                .sum();
    }

    @Override
    public void completed(String agentName, Object input, Object output, long durationMs) {
        events.add(new TraceEvent(
                sequence.incrementAndGet(),
                agentName,
                definition.nodeFor(agentName),
                "non-ai",
                "",
                durationMs,
                0,
                0,
                preview(input),
                preview(output),
                "completed"));
    }

    private PendingSpan pop(String agentName) {
        Deque<PendingSpan> stack = spans.get();
        PendingSpan span = stack.isEmpty()
                ? new PendingSpan(sequence.incrementAndGet(), agentName, System.nanoTime(), "")
                : stack.pop();
        if (stack.isEmpty()) {
            spans.remove();
        }
        return span;
    }

    private static String kind(String agentName, boolean hasModelResponse) {
        if (hasModelResponse) {
            return "model";
        }
        if (NON_AI_AGENTS.contains(agentName)) {
            return "non-ai";
        }
        return "workflow";
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }

    /** Binds usage to the agent currently executing on this thread, so a nested agent cannot claim its parent's call. */
    private ChatResponse record(ChatResponse response) {
        PendingSpan current = spans.get().peek();
        if (current != null) {
            current.modelResponse().set(response);
        }
        return response;
    }

    private static String preview(Object value) {
        if (value == null) {
            return "";
        }
        String text;
        if (value instanceof Map<?, ?> map) {
            text = map.entrySet().stream()
                    .limit(4)
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(", "));
        } else {
            text = String.valueOf(value);
        }
        String compact = text.replaceAll("\\s+", " ").strip();
        return compact.length() <= 180 ? compact : compact.substring(0, 179) + "…";
    }

    private record PendingSpan(int sequence, String agentName, long startedAtNanos, String inputPreview,
                               AtomicReference<ChatResponse> modelResponse) {

        PendingSpan(int sequence, String agentName, long startedAtNanos, String inputPreview) {
            this(sequence, agentName, startedAtNanos, inputPreview, new AtomicReference<>());
        }
    }

    private record TracingChatModel(ChatModel delegate, TraceCollector collector) implements ChatModel {

        @Override
        public ChatResponse chat(ChatRequest request) {
            return collector.record(delegate.chat(request));
        }

        @Override
        public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
            return collector.record(delegate.chat(request, options));
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            return collector.record(delegate.doChat(request));
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return delegate.defaultRequestParameters();
        }

        @Override
        public List<ChatModelListener> listeners() {
            return delegate.listeners();
        }

        @Override
        public ModelProvider provider() {
            return delegate.provider();
        }

        @Override
        public Set<Capability> supportedCapabilities() {
            return delegate.supportedCapabilities();
        }
    }
}