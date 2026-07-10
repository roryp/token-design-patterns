package com.example.tokenpatterns.agent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class PatternAgents {

    public static final String CACHE_MISS = "__TOKEN_PATTERN_CACHE_MISS__";

    @FunctionalInterface
    public interface NonAiObserver {
        NonAiObserver NOOP = (agentName, input, output, durationMs) -> {
        };

        void completed(String agentName, Object input, Object output, long durationMs);
    }

    private PatternAgents() {
    }

    public interface RouteClassifier {

        @UserMessage("""
                [ROUTE_CLASSIFIER]
                You are a low-cost router. Select the narrowest specialist for the request.
                Return exactly one label: CODE, KNOWLEDGE, or ARCHITECTURE.
                REQUEST: {{request}}
                """)
        @Agent(name = "Route classifier", description = "Routes a request to the smallest suitable specialist", outputKey = "route")
        String route(@V("request") String request);
    }

    public interface CodeSpecialist {

        @UserMessage("""
                [CODE_SPECIALIST]
                Give a concise, actionable developer answer. Prefer one diagnostic path over a broad essay.
                REQUEST: {{request}}
                """)
        @Agent(name = "Code specialist", description = "Handles implementation and debugging requests", outputKey = "answer")
        String answer(@V("request") String request);
    }

    public interface KnowledgeSpecialist {

        @UserMessage("""
                [KNOWLEDGE_SPECIALIST]
                Explain the requested developer concept precisely in no more than four sentences.
                REQUEST: {{request}}
                """)
        @Agent(name = "Knowledge specialist", description = "Handles focused conceptual questions", outputKey = "answer")
        String answer(@V("request") String request);
    }

    public interface ArchitectureSpecialist {

        @UserMessage("""
                [ARCHITECTURE_SPECIALIST]
                Give an architecture recommendation with one trade-off, one rollout step, and one validation metric.
                REQUEST: {{request}}
                """)
        @Agent(name = "Architecture specialist", description = "Handles high-complexity design decisions", outputKey = "answer")
        String answer(@V("request") String request);
    }

    public static final class HeuristicTriage {

        private final NonAiObserver observer;

        public HeuristicTriage() {
            this(NonAiObserver.NOOP);
        }

        public HeuristicTriage(NonAiObserver observer) {
            this.observer = observer;
        }

        @Agent(name = "Triage gate", description = "Classifies complexity without spending model tokens", outputKey = "complexity")
        public String classify(@V("request") String request) {
            long startedAt = System.nanoTime();
            String normalized = request.toLowerCase(Locale.ROOT);
            boolean complex = request.length() > 180
                    || List.of("architecture", "migration", "distributed", "security", "trade-off", "multi-region")
                    .stream()
                    .anyMatch(normalized::contains);
            String result = complex ? "COMPLEX" : "SIMPLE";
            observer.completed("Triage gate", request, result, elapsedMillis(startedAt));
            return result;
        }
    }

    public interface FastPathResponder {

        @UserMessage("""
                [FAST_PATH]
                Answer this simple developer question directly in at most two sentences.
                REQUEST: {{request}}
                """)
        @Agent(name = "Fast-path responder", description = "Answers routine questions with a small model", outputKey = "answer")
        String answer(@V("request") String request);
    }

    public interface DeepReasoningResponder {

        @UserMessage("""
                [DEEP_REASONING]
                Analyze the request carefully. State the decision frame, recommended next step, and validation criteria.
                REQUEST: {{request}}
                """)
        @Agent(name = "Deep reasoning responder", description = "Escalates only complex work to a larger model", outputKey = "answer")
        String answer(@V("request") String request);
    }

    public interface ContextCompressor {

        @UserMessage("""
                [CONTEXT_COMPRESSOR]
                Compress the context into durable facts only: signal, constraint, likely cause, and next actions.
                Do not retain greetings, repetition, or dead ends.
                CONTEXT: {{context}}
                REQUEST: {{request}}
                """)
        @Agent(name = "Context compressor", description = "Distills long context before expensive reasoning", outputKey = "compactContext")
        String compress(@V("context") String context, @V("request") String request);
    }

    public interface FocusedAnswerer {

        @UserMessage("""
                [FOCUSED_ANSWER]
                Answer only from the compact context. Call out uncertainty rather than inventing details.
                COMPACT_CONTEXT: {{compactContext}}
                REQUEST: {{request}}
                """)
        @Agent(name = "Focused answerer", description = "Reasons over the compacted working set", outputKey = "answer")
        String answer(@V("compactContext") String compactContext, @V("request") String request);
    }

    public static final class LocalKnowledgeRetriever {

        private static final List<KnowledgeChunk> KNOWLEDGE = List.of(
                new KnowledgeChunk("AgenticScope", "AgenticScope is shared state for one agentic execution. Agent outputs are written under keys and downstream agent arguments read those keys."),
                new KnowledgeChunk("Conditional workflow", "A conditional workflow activates subagents with predicates over AgenticScope. It is suitable for routing and escalation."),
                new KnowledgeChunk("Parallel mapper", "Parallel mapper invokes the same stateless subagent once per collection item and aggregates the outputs into a list."),
                new KnowledgeChunk("Observability", "AgentListener observes requests, responses, errors, and tool execution. AgentMonitor retains an invocation tree with duration and token usage."),
                new KnowledgeChunk("Context engineering", "Agentic systems can provide selected or summarized prior-agent context instead of sending every interaction to every model."));

        private final NonAiObserver observer;

        public LocalKnowledgeRetriever() {
            this(NonAiObserver.NOOP);
        }

        public LocalKnowledgeRetriever(NonAiObserver observer) {
            this.observer = observer;
        }

        @Agent(name = "Knowledge retriever", description = "Selects only relevant local knowledge chunks", outputKey = "context")
        public String retrieve(@V("request") String request) {
            long startedAt = System.nanoTime();
            Set<String> queryTerms = terms(request);
            String result = KNOWLEDGE.stream()
                    .sorted(Comparator.comparingInt((KnowledgeChunk chunk) -> score(chunk, queryTerms)).reversed())
                    .limit(2)
                    .map(chunk -> chunk.title() + ": " + chunk.text())
                    .collect(Collectors.joining("\n"));
            observer.completed("Knowledge retriever", request, result, elapsedMillis(startedAt));
            return result;
        }

        public static String fullCorpus() {
            return KNOWLEDGE.stream()
                    .map(chunk -> chunk.title() + ": " + chunk.text())
                    .collect(Collectors.joining("\n"));
        }

        private static int score(KnowledgeChunk chunk, Set<String> queryTerms) {
            Set<String> chunkTerms = terms(chunk.title() + " " + chunk.text());
            int score = 0;
            for (String term : queryTerms) {
                if (chunkTerms.contains(term)) {
                    score++;
                }
            }
            return score;
        }

        private static Set<String> terms(String text) {
            return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                    .filter(word -> word.length() > 3)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        private record KnowledgeChunk(String title, String text) {
        }
    }

    public interface GroundedGenerator {

        @UserMessage("""
                [RAG_ANSWER]
                Answer the request using only the retrieved context. Keep the answer concise and developer-focused.
                RETRIEVED_CONTEXT: {{context}}
                REQUEST: {{request}}
                """)
        @Agent(name = "Grounded generator", description = "Generates from the retrieved working set", outputKey = "answer")
        String answer(@V("context") String context, @V("request") String request);
    }

    public static final class TokenCostCalculator {

        private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([mMkK]?)");

        private final NonAiObserver observer;

        public TokenCostCalculator() {
            this(NonAiObserver.NOOP);
        }

        public TokenCostCalculator(NonAiObserver observer) {
            this.observer = observer;
        }

        @Agent(name = "Cost calculator", description = "Performs token-cost arithmetic deterministically", outputKey = "toolResult")
        public String calculate(@V("request") String request) {
            long startedAt = System.nanoTime();
            List<Double> values = new ArrayList<>();
            Matcher matcher = NUMBER.matcher(request);
            while (matcher.find()) {
                double value = Double.parseDouble(matcher.group(1));
                String suffix = matcher.group(2).toLowerCase(Locale.ROOT);
                if ("k".equals(suffix)) {
                    value /= 1_000.0;
                }
                values.add(value);
            }

            double inputMillions = value(values, 0, 50.0);
            double outputMillions = value(values, 1, 10.0);
            double inputPrice = value(values, 2, 0.15);
            double outputPrice = value(values, 3, 0.60);
            double total = inputMillions * inputPrice + outputMillions * outputPrice;

                String result = "%.2fM input × $%.2f/M + %.2fM output × $%.2f/M = $%.2f".formatted(
                    inputMillions, inputPrice, outputMillions, outputPrice, total);
                observer.completed("Cost calculator", request, result, elapsedMillis(startedAt));
                return result;
        }

        private static double value(List<Double> values, int index, double fallback) {
            return index < values.size() ? values.get(index) : fallback;
        }
    }

    public interface ToolGroundedExplainer {

        @UserMessage("""
                [TOOL_EXPLAINER]
                Explain the deterministic result in no more than two sentences. Do not redo or alter the arithmetic.
                TOOL_RESULT: {{toolResult}}
                REQUEST: {{request}}
                """)
        @Agent(name = "Tool-grounded explainer", description = "Explains a deterministic tool result", outputKey = "answer")
        String answer(@V("toolResult") String toolResult, @V("request") String request);
    }

    public interface StepBackPlanner {

        @UserMessage("""
                [STEP_BACK_PLANNER]
                Step back from implementation details. Produce exactly three short planning steps covering constraints, sequencing, and validation.
                REQUEST: {{request}}
                """)
        @Agent(name = "Step-back planner", description = "Creates a compact plan before expensive execution", outputKey = "plan")
        String plan(@V("request") String request);
    }

    public interface PlanExecutor {

        @UserMessage("""
                [PLAN_EXECUTOR]
                Execute the plan as a concise recommendation. Do not reopen options that the plan already resolved.
                PLAN: {{plan}}
                REQUEST: {{request}}
                """)
        @Agent(name = "Plan executor", description = "Produces the final answer within the plan", outputKey = "answer")
        String execute(@V("plan") String plan, @V("request") String request);
    }

    public static final class CacheLookup {

        private final ConcurrentMap<String, String> cache;
        private final NonAiObserver observer;

        public CacheLookup(ConcurrentMap<String, String> cache) {
            this(cache, NonAiObserver.NOOP);
        }

        public CacheLookup(ConcurrentMap<String, String> cache, NonAiObserver observer) {
            this.cache = cache;
            this.observer = observer;
        }

        @Agent(name = "Cache lookup", description = "Returns an existing response without a model call", outputKey = "answer")
        public String lookup(@V("request") String request) {
            long startedAt = System.nanoTime();
            String result = cache.getOrDefault(cacheKey(request), CACHE_MISS);
            observer.completed("Cache lookup", request, CACHE_MISS.equals(result) ? "MISS" : "HIT", elapsedMillis(startedAt));
            return result;
        }
    }

    public interface CacheableAnswerer {

        @UserMessage("""
                [CACHEABLE_ANSWER]
                Give a stable, concise definition suitable for exact caching.
                REQUEST: {{request}}
                """)
        @Agent(name = "Cache answerer", description = "Generates only when the cache misses", outputKey = "answer")
        String answer(@V("request") String request);
    }

    public interface BatchWorker {

        @UserMessage("""
                [BATCH_ITEM]
                Answer this independent item in one concise sentence.
                REQUEST: {{item}}
                """)
        @Agent(name = "Batch worker", description = "Processes one independent item", outputKey = "batchAnswer")
        String answer(@V("item") String item);
    }

    public interface BatchWorkflow extends AgentInstance {

        @Agent(name = "Batch mapper", description = "Fans a collection out over stateless workers")
        List<String> process(@V("items") List<String> items);
    }

    public static String cacheKey(String request) {
        return request.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }
}