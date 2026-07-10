package com.example.tokenpatterns.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * A deterministic model that keeps the workshop runnable without credentials.
 * The surrounding orchestration is the real LangChain4j Agentic runtime; only
 * the model responses are locally generated.
 */
public final class DemoChatModel implements ChatModel {

    private final String modelName;
    private final long latencyMs;

    public DemoChatModel(String modelName, long latencyMs) {
        this.modelName = modelName;
        this.latencyMs = latencyMs;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        String prompt = request.messages().stream()
                .map(DemoChatModel::textOf)
                .collect(Collectors.joining("\n"));

        pause();
        String response = respond(prompt);
        int inputTokens = estimateTokens(prompt);
        int outputTokens = estimateTokens(response);

        return ChatResponse.builder()
                .aiMessage(AiMessage.from(response))
                .modelName(modelName)
                .tokenUsage(new TokenUsage(inputTokens, outputTokens))
                .finishReason(FinishReason.STOP)
                .build();
    }

    private String respond(String prompt) {
        String normalized = prompt.toLowerCase(Locale.ROOT);
        String request = extractRequest(prompt);

        if (normalized.contains("[route_classifier]")) {
            if (containsAny(request, "java", "code", "class", "method", "exception", "bug", "compile")) {
                return "CODE";
            }
            if (containsAny(request, "architecture", "design", "migrate", "scale", "distributed", "trade-off")) {
                return "ARCHITECTURE";
            }
            return "KNOWLEDGE";
        }
        if (normalized.contains("[triage_classifier]")) {
            return request.length() > 180
                    || containsAny(request, "architecture", "migration", "distributed", "security", "trade-off")
                    ? "COMPLEX"
                    : "SIMPLE";
        }
        if (normalized.contains("[context_compressor]")) {
            return "Compressed context — Signal: retries and concurrency increased together. "
                    + "Constraint: the upstream service enforces a fixed request budget. "
                    + "Likely cause: synchronized retries amplify throttling. "
                    + "Useful facts: cap concurrency, honor Retry-After, add exponential backoff with jitter, and measure retry volume.";
        }
        if (normalized.contains("[step_back_planner]")) {
            return "1. Establish the goal, constraints, and measurable success criteria.\n"
                    + "2. Separate reversible experiments from irreversible architecture decisions.\n"
                    + "3. Deliver the smallest observable slice, validate it, then expand.";
        }
        if (normalized.contains("[code_specialist]")) {
            return "Start at the first boundary where the value can become empty: inspect the source collection, "
                    + "log each filter predicate, and verify that the stream is not consumed earlier. Add a focused test "
                    + "for the failing input before changing the pipeline.";
        }
        if (normalized.contains("[architecture_specialist]")) {
            return "Use an incremental architecture decision: define the service boundary, add observability, and route a small "
                    + "percentage of traffic through it first. Keep the old path as a rollback option and compare latency, failure rate, "
                    + "and operating cost before expanding.";
        }
        if (normalized.contains("[knowledge_specialist]")) {
            return "Treat the concept as a constraint rather than a slogan: identify what state is shared, who owns it, and how failure "
                    + "is recovered. Then validate the behavior with one small example and provider telemetry.";
        }
        if (normalized.contains("[fast_path]")) {
            return "HTTP 429 means the caller exceeded a rate or quota limit. Honor Retry-After, use jittered backoff, and reduce concurrency.";
        }
        if (normalized.contains("[deep_reasoning]")) {
            return "Frame the problem across correctness, operability, and cost. Start with a reversible pilot, instrument the current baseline, "
                    + "then compare alternatives against explicit latency, reliability, and ownership constraints.";
        }
        if (normalized.contains("[focused_answer]")) {
            return "The compressed evidence points to retry amplification rather than raw demand. Cap in-flight work, honor Retry-After, "
                    + "use exponential backoff with jitter, and alert on the ratio of retries to successful requests.";
        }
        if (normalized.contains("[rag_answer]")) {
            return "LangChain4j's AgenticScope is the shared state for one agentic execution. Agents write outputs under keys and downstream "
                    + "agents read those keys as inputs, which keeps orchestration explicit without replaying the entire conversation to every model.";
        }
        if (normalized.contains("[tool_explainer]")) {
            String toolResult = extractSection(prompt, "TOOL_RESULT:");
            return "The deterministic calculator produced: " + toolResult
                    + " Keep arithmetic outside the model so the answer is auditable, repeatable, and token-light.";
        }
        if (normalized.contains("[plan_executor]")) {
            return "Run a strangler-style migration: instrument the monolith first, extract one low-coupling capability behind a stable contract, "
                    + "shadow or canary traffic, and expand only after the new path meets the agreed reliability and cost thresholds.";
        }
        if (normalized.contains("[cacheable_answer]")) {
            return "Idempotency means repeating the same operation has the same externally visible effect as performing it once. "
                    + "Use an idempotency key plus durable result storage when retries can repeat side effects.";
        }
        if (normalized.contains("[batch_item]")) {
            return "Concise result: " + compact(request, 150)
                    + " — define the boundary, measure the baseline, and validate the optimization with traces.";
        }
        return "Use the smallest capable model and the least context necessary, then verify quality and token usage with production traces.";
    }

    private static String textOf(ChatMessage message) {
        if (message instanceof UserMessage userMessage && userMessage.hasSingleText()) {
            return userMessage.singleText();
        }
        if (message instanceof SystemMessage systemMessage) {
            return systemMessage.text();
        }
        if (message instanceof ToolExecutionResultMessage toolResult) {
            return toolResult.text();
        }
        return message.toString();
    }

    private static String extractRequest(String prompt) {
        String extracted = extractSection(prompt, "REQUEST:");
        return extracted.isBlank() ? compact(prompt, 500) : extracted;
    }

    private static String extractSection(String prompt, String marker) {
        int start = prompt.lastIndexOf(marker);
        if (start < 0) {
            return "";
        }
        String section = prompt.substring(start + marker.length()).strip();
        int nextMarker = section.indexOf("\n[");
        if (nextMarker >= 0) {
            section = section.substring(0, nextMarker);
        }
        return compact(section, 420);
    }

    private static boolean containsAny(String value, String... needles) {
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (normalized.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String compact(String value, int maxLength) {
        String compacted = value.replaceAll("\\s+", " ").strip();
        return compacted.length() <= maxLength ? compacted : compacted.substring(0, maxLength - 1) + "…";
    }

    private static int estimateTokens(String text) {
        return Math.max(1, (int) Math.ceil(text.codePointCount(0, text.length()) / 4.0));
    }

    private void pause() {
        try {
            Thread.sleep(latencyMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Demo model invocation was interrupted", exception);
        }
    }
}