package com.example.tokenpatterns.agent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class PatternAgents {

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

        private static final List<Pattern> DOMAIN_TERMS = Stream.of(
                        "architect", "migrat", "distributed", "secur", "trade-?offs?", "multi-region",
                        "complian", "failover", "scalab", "incident")
                .map(term -> Pattern.compile("\\b" + term, Pattern.CASE_INSENSITIVE))
                .toList();
        private static final Pattern DESIGN_INTENT = Pattern.compile(
                "\\b(?:design|recommend|plan|compare|evaluate|strateg|roadmap|migrat|trade-?off|diagnos|root cause|analy[sz])",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern DEFINITION = Pattern.compile(
                "^\\s*(?:what\\s+(?:does|do|is|are)\\b|what's\\b|define\\b|meaning\\s+of\\b|explain\\s+the\\s+(?:term|word)\\b)",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern BRIEF = Pattern.compile(
                "\\b(?:briefly|in\\s+(?:one|a)\\s+(?:sentence|line|word)|one-line|short\\s+answer)\\b",
                Pattern.CASE_INSENSITIVE);

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
            String result = complexity(request);
            observer.completed("Triage gate", request, result, elapsedMillis(startedAt));
            return result;
        }

        /** One keyword is not enough: escalation needs several signals, and short definitions stay simple. */
        static String complexity(String request) {
            long domainTerms = DOMAIN_TERMS.stream().filter(term -> term.matcher(request).find()).count();
            int score = (int) Math.min(2, domainTerms);
            if (DESIGN_INTENT.matcher(request).find()) {
                score++;
            }
            if (request.length() > 180) {
                score++;
            }
            if (request.length() > 400) {
                score++;
            }
            if (request.length() <= 100 && (DEFINITION.matcher(request).find() || BRIEF.matcher(request).find())) {
                score -= 2;
            }
            return score >= 2 ? "COMPLEX" : "SIMPLE";
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
                Provide a concise triage recommendation, not a complete solution.
                Use at most 150 words, with one short paragraph each for Decision frame, Next step, and Validation.
                Choose one recommended approach; cover the request's key constraints and one trade-off, and give a measurable validation metric.
                Do not enumerate alternatives or implementation details.
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

        /** Written to the retrieved context when nothing in the knowledge base matches the request. */
        public static final String NO_RELEVANT_KNOWLEDGE = "No relevant knowledge was found for this request.";

        private static final List<KnowledgeChunk> KNOWLEDGE = List.of(
                new KnowledgeChunk("AgenticScope", "AgenticScope is shared state for one agentic execution. Agent outputs are written under keys and downstream agent arguments read those keys."),
                new KnowledgeChunk("Sequential workflow (sequenceBuilder)", "A sequential workflow invokes subagents one after another; each subagent reads earlier outputs from AgenticScope."),
                new KnowledgeChunk("Conditional workflow (conditionalBuilder)", "A conditional workflow activates subagents with predicates over AgenticScope. It is suitable for routing and escalation."),
                new KnowledgeChunk("Parallel workflow (parallelBuilder)", "A parallel workflow invokes different, independent subagents concurrently and combines their outputs."),
                new KnowledgeChunk("Parallel mapper (parallelMapperBuilder)", "A parallel mapper invokes the same stateless subagent concurrently, once per collection item, for independent tasks, and aggregates the outputs into a list."),
                new KnowledgeChunk("Observability", "AgentListener observes requests, responses, errors, and tool execution. AgentMonitor retains an invocation tree with duration and token usage."),
                new KnowledgeChunk("Context engineering", "Agentic systems can provide selected or summarized prior-agent context instead of sending every interaction to every model."));
        private static final Set<String> STOP_WORDS = Set.of(
                "about", "and", "are", "can", "does", "for", "from", "have", "how", "into", "its", "only", "should",
                "that", "the", "their", "then", "there", "this", "those", "through", "was", "what", "when", "where",
                "which", "who", "why", "will", "with", "would", "you", "your");
        // Terms that appear in fewer chunks carry more weight, so a shared word like "workflow" cannot outrank "parallel".
        private static final Map<String, Double> WEIGHTS = weights();

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
            String result = retrieveContext(request);
            observer.completed("Knowledge retriever", request, result, elapsedMillis(startedAt));
            return result;
        }

        /** Up to two chunks that share weighted terms with the request, best first, or a note that none do. */
        static String retrieveContext(String request) {
            Set<String> queryTerms = terms(request);
            String context = KNOWLEDGE.stream()
                    .map(chunk -> Map.entry(chunk, score(chunk, queryTerms)))
                    .filter(scored -> scored.getValue() > 0)
                    .sorted(Map.Entry.<KnowledgeChunk, Double>comparingByValue().reversed())
                    .limit(2)
                    .map(scored -> scored.getKey().title() + ": " + scored.getKey().text())
                    .collect(Collectors.joining("\n"));
            return context.isEmpty() ? NO_RELEVANT_KNOWLEDGE : context;
        }

        public static String fullCorpus() {
            return KNOWLEDGE.stream()
                    .map(chunk -> chunk.title() + ": " + chunk.text())
                    .collect(Collectors.joining("\n"));
        }

        private static double score(KnowledgeChunk chunk, Set<String> queryTerms) {
            Set<String> titleTerms = terms(chunk.title());
            Set<String> textTerms = terms(chunk.text());
            double score = 0;
            for (String term : queryTerms) {
                double weight = WEIGHTS.getOrDefault(term, 0.0);
                if (titleTerms.contains(term)) {
                    score += 2 * weight;
                } else if (textTerms.contains(term)) {
                    score += weight;
                }
            }
            return score;
        }

        private static Map<String, Double> weights() {
            Map<String, Integer> chunksWithTerm = new HashMap<>();
            for (KnowledgeChunk chunk : KNOWLEDGE) {
                terms(chunk.title() + " " + chunk.text()).forEach(term -> chunksWithTerm.merge(term, 1, Integer::sum));
            }
            Map<String, Double> weights = new HashMap<>();
            chunksWithTerm.forEach((term, count) -> weights.put(term, 1 + Math.log((double) KNOWLEDGE.size() / count)));
            return Map.copyOf(weights);
        }

        /** Splits identifiers such as parallelMapperBuilder, drops common words, and folds simple plurals. */
        private static Set<String> terms(String text) {
            return Arrays.stream(text.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                    .filter(word -> word.length() >= 3 && !STOP_WORDS.contains(word))
                    .map(LocalKnowledgeRetriever::singular)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        private static String singular(String word) {
            if (word.length() > 4 && word.endsWith("ies")) {
                return word.substring(0, word.length() - 3) + "y";
            }
            if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us")) {
                return word.substring(0, word.length() - 1);
            }
            return word;
        }

        private record KnowledgeChunk(String title, String text) {
        }
    }

    public interface GroundedGenerator {

        @UserMessage("""
                [RAG_ANSWER]
                Answer the request using only the retrieved context. If the context does not state what the request asks, say so plainly instead of inferring it. Keep the answer concise and developer-focused.
                RETRIEVED_CONTEXT: {{context}}
                REQUEST: {{request}}
                """)
        @Agent(name = "Grounded generator", description = "Generates from the retrieved working set", outputKey = "answer")
        String answer(@V("context") String context, @V("request") String request);
    }

    public static final class TokenCostCalculator {

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
            String result = TokenCostRequest.parse(request).describe();
            observer.completed("Cost calculator", request, result, elapsedMillis(startedAt));
            return result;
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
                Execute the plan as a concise recommendation of at most 200 words.
                Give each plan step one short paragraph. Do not reopen options that the plan already resolved.
                PLAN: {{plan}}
                REQUEST: {{request}}
                """)
        @Agent(name = "Plan executor", description = "Produces the final answer within the plan", outputKey = "answer")
        String execute(@V("plan") String plan, @V("request") String request);
    }

    public interface CacheableAnswerer {

        @UserMessage("""
                [CACHEABLE_ANSWER]
                REQUEST: {{request}}
                """)
        @Agent(name = "Cache answerer", description = "Generates a fresh answer with a reusable provider-cached prefix", outputKey = "answer")
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

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }
}