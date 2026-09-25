package com.example.tokenpatterns.service;

import com.example.tokenpatterns.agent.CacheInstructions;
import com.example.tokenpatterns.agent.ModelCatalog;
import com.example.tokenpatterns.agent.TokenCostRequest;
import com.example.tokenpatterns.agent.ModelCatalog.ModelSet;
import com.example.tokenpatterns.agent.PatternAgents.ArchitectureSpecialist;
import com.example.tokenpatterns.agent.PatternAgents.BatchWorker;
import com.example.tokenpatterns.agent.PatternAgents.BatchWorkflow;
import com.example.tokenpatterns.agent.PatternAgents.CacheableAnswerer;
import com.example.tokenpatterns.agent.PatternAgents.CodeSpecialist;
import com.example.tokenpatterns.agent.PatternAgents.ContextCompressor;
import com.example.tokenpatterns.agent.PatternAgents.DeepReasoningResponder;
import com.example.tokenpatterns.agent.PatternAgents.FastPathResponder;
import com.example.tokenpatterns.agent.PatternAgents.FocusedAnswerer;
import com.example.tokenpatterns.agent.PatternAgents.GroundedGenerator;
import com.example.tokenpatterns.agent.PatternAgents.HeuristicTriage;
import com.example.tokenpatterns.agent.PatternAgents.KnowledgeSpecialist;
import com.example.tokenpatterns.agent.PatternAgents.LocalKnowledgeRetriever;
import com.example.tokenpatterns.agent.PatternAgents.PlanExecutor;
import com.example.tokenpatterns.agent.PatternAgents.RouteClassifier;
import com.example.tokenpatterns.agent.PatternAgents.StepBackPlanner;
import com.example.tokenpatterns.agent.PatternAgents.TokenCostCalculator;
import com.example.tokenpatterns.agent.PatternAgents.ToolGroundedExplainer;
import com.example.tokenpatterns.domain.PatternDefinition;
import com.example.tokenpatterns.domain.PatternRunRequest;
import com.example.tokenpatterns.domain.PatternRunResult;
import com.example.tokenpatterns.domain.PatternRunResult.Metrics;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

@Service
public class PatternRunner {

    private static final String LONG_INCIDENT_CONTEXT = """
            09:02 — The checkout API began returning intermittent HTTP 429 responses. Traffic was 8% below the normal weekday peak.
            09:07 — The client SDK had been upgraded the previous evening. The release changed the default maximum concurrency from 12 to 40.
            09:11 — An engineer restarted two pods. Error rate fell briefly and then returned. CPU, heap, and database connection pools stayed healthy.
            09:18 — The upstream payment service publishes a budget of 60 requests per minute per merchant and returns Retry-After on throttling.
            09:23 — Application logs showed groups of retries occurring at the same millisecond. The SDK retries 429 responses three times with a fixed 100 ms delay.
            09:31 — A dashboard panel counted original requests but excluded retry attempts, so apparent traffic understated calls to the upstream service.
            09:39 — Reducing concurrency to 10 cut 429 responses by 82%. Adding random delay to one canary eliminated synchronized retry spikes.
            09:47 — The team agreed to honor Retry-After, use exponential backoff with jitter, cap in-flight work, and add retry-attempt telemetry.
            """;

    private final PatternCatalog catalog;
    private final ModelCatalog modelCatalog;
    private final CacheInstructions cacheInstructions;
    private final ExecutorService batchExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public PatternRunner(PatternCatalog catalog, ModelCatalog modelCatalog, CacheInstructions cacheInstructions) {
        this.catalog = catalog;
        this.modelCatalog = modelCatalog;
        this.cacheInstructions = cacheInstructions;
    }

    public PatternRunResult run(PatternRunRequest request) {
        PatternDefinition definition = catalog.get(request.patternId());
        List<String> batchItems = "batching".equals(definition.id()) ? splitBatch(request.input()) : List.of();
        if ("tool-use".equals(definition.id())) {
            // The calculator reads the same request; an unreadable one is rejected before any model is initialized.
            TokenCostRequest.parse(request.input());
        }
        String instructions = "caching".equals(definition.id()) ? cacheInstructions.forSession(request.cacheSession()) : null;
        TraceCollector trace = new TraceCollector(definition);
        ModelSet models = trace.instrument(modelCatalog.models());
        long startedAt = System.nanoTime();

        RunOutcome outcome = switch (definition.id()) {
            case "router" -> runRouter(request.input(), models, trace);
            case "triage" -> runTriage(request.input(), models, trace);
            case "compression" -> runCompression(request.input(), models, trace);
            case "rag" -> runRag(request.input(), models, trace);
            case "tool-use" -> runToolUse(request.input(), models, trace);
            case "step-back" -> runStepBack(request.input(), models, trace);
            case "caching" -> runCaching(request.input(), instructions, request.providerCacheEnabled(), models, trace);
            case "batching" -> runBatching(batchItems, models, trace);
            default -> throw new IllegalArgumentException("Unsupported pattern: " + definition.id());
        };

        long durationMs = Math.max(1, (System.nanoTime() - startedAt) / 1_000_000);
        int observedTokens = trace.observedTokens();
        int baselineTokens = projectedBaseline(definition.id(), observedTokens, outcome, request.input());
        int avoidedTokens = Math.max(0, baselineTokens - observedTokens);
        int savingsPercent = baselineTokens == 0 ? 0 : (int) Math.round(avoidedTokens * 100.0 / baselineTokens);
        boolean cacheEnabled = "caching".equals(definition.id()) && request.providerCacheEnabled();
        String cacheStatus = trace.cacheStatus(cacheEnabled);

        Metrics metrics = new Metrics(
                baselineTokens,
                observedTokens,
                avoidedTokens,
                savingsPercent,
                trace.modelCalls(),
                trace.events().size(),
                durationMs,
                outcome.concurrency(),
                trace.inputTokens(),
                trace.outputTokens(),
                trace.cachedInputTokens(),
                trace.cacheWriteTokens(),
                trace.reasoningTokens(),
                cacheEnabled,
                cacheStatus,
                measurementBasis(definition.id()));

        return new PatternRunResult(
                UUID.randomUUID().toString(),
                definition.id(),
                outcome.output(),
                metrics,
                trace.events(),
                sanitizeScope(outcome.scope()),
                takeaways(definition.id(), outcome, trace, cacheStatus),
                Instant.now());
    }

    @PreDestroy
    void shutdown() {
        batchExecutor.shutdownNow();
    }

    private RunOutcome runRouter(String request, ModelSet models, TraceCollector trace) {
        RouteClassifier classifier = AgenticServices.agentBuilder(RouteClassifier.class)
                .chatModel(models.small())
                .build();
        CodeSpecialist code = AgenticServices.agentBuilder(CodeSpecialist.class)
                .chatModel(models.medium())
                .build();
        KnowledgeSpecialist knowledge = AgenticServices.agentBuilder(KnowledgeSpecialist.class)
                .chatModel(models.small())
                .build();
        ArchitectureSpecialist architecture = AgenticServices.agentBuilder(ArchitectureSpecialist.class)
                .chatModel(models.large())
                .build();

        UntypedAgent routes = AgenticServices.conditionalBuilder()
                .subAgents(scope -> "CODE".equals(scope.readState("route", "")), code)
                .subAgents(scope -> "KNOWLEDGE".equals(scope.readState("route", "")), knowledge)
                .subAgents(scope -> "ARCHITECTURE".equals(scope.readState("route", "")), architecture)
                .name("Model route")
                .build();

        UntypedAgent workflow = AgenticServices.sequenceBuilder()
                .subAgents(classifier, routes)
                .name("Router workflow")
                .outputKey("answer")
                .listener(trace)
                .build();
        return invoke(workflow, Map.of("request", request));
    }

    private RunOutcome runTriage(String request, ModelSet models, TraceCollector trace) {
        FastPathResponder fast = AgenticServices.agentBuilder(FastPathResponder.class)
                .chatModel(models.small())
                .build();
        DeepReasoningResponder deep = AgenticServices.agentBuilder(DeepReasoningResponder.class)
                .chatModel(models.large())
                .build();

        UntypedAgent paths = AgenticServices.conditionalBuilder()
                .subAgents(scope -> "SIMPLE".equals(scope.readState("complexity", "")), fast)
                .subAgents(scope -> "COMPLEX".equals(scope.readState("complexity", "")), deep)
                .name("Triage decision")
                .build();

        UntypedAgent workflow = AgenticServices.sequenceBuilder()
                .subAgents(new HeuristicTriage(trace), paths)
                .name("Triage workflow")
                .outputKey("answer")
                .listener(trace)
                .build();
        return invoke(workflow, Map.of("request", request));
    }

    private RunOutcome runCompression(String request, ModelSet models, TraceCollector trace) {
        ContextCompressor compressor = AgenticServices.agentBuilder(ContextCompressor.class)
                .chatModel(models.small())
                .build();
        FocusedAnswerer answerer = AgenticServices.agentBuilder(FocusedAnswerer.class)
                .chatModel(models.large())
                .build();

        UntypedAgent workflow = AgenticServices.sequenceBuilder()
                .subAgents(compressor, answerer)
                .name("Compression workflow")
                .outputKey("answer")
                .listener(trace)
                .build();
        return invoke(workflow, Map.of("request", request, "context", LONG_INCIDENT_CONTEXT));
    }

    private RunOutcome runRag(String request, ModelSet models, TraceCollector trace) {
        GroundedGenerator generator = AgenticServices.agentBuilder(GroundedGenerator.class)
                .chatModel(models.medium())
                .build();

        UntypedAgent workflow = AgenticServices.sequenceBuilder()
                .subAgents(new LocalKnowledgeRetriever(trace), generator)
                .name("RAG workflow")
                .outputKey("answer")
                .listener(trace)
                .build();
        return invoke(workflow, Map.of("request", request));
    }

    private RunOutcome runToolUse(String request, ModelSet models, TraceCollector trace) {
        ToolGroundedExplainer explainer = AgenticServices.agentBuilder(ToolGroundedExplainer.class)
                .chatModel(models.small())
                .build();

        UntypedAgent workflow = AgenticServices.sequenceBuilder()
                .subAgents(new TokenCostCalculator(trace), explainer)
                .name("Tool workflow")
                .outputKey("answer")
                .listener(trace)
                .build();
        return invoke(workflow, Map.of("request", request));
    }

    private RunOutcome runStepBack(String request, ModelSet models, TraceCollector trace) {
        StepBackPlanner planner = AgenticServices.agentBuilder(StepBackPlanner.class)
                .chatModel(models.small())
                .build();
        PlanExecutor executor = AgenticServices.agentBuilder(PlanExecutor.class)
                .chatModel(models.large())
                .build();

        UntypedAgent workflow = AgenticServices.sequenceBuilder()
                .subAgents(planner, executor)
                .name("Step-back workflow")
                .outputKey("answer")
                .listener(trace)
                .build();
        return invoke(workflow, Map.of("request", request));
    }

    private RunOutcome runCaching(String request, String instructions, boolean cacheEnabled,
                                  ModelSet models, TraceCollector trace) {
        CacheableAnswerer answerer = AgenticServices.agentBuilder(CacheableAnswerer.class)
                .chatModel(cacheEnabled ? models.cachedMedium() : models.medium())
                .systemMessageProvider(ignored -> instructions)
                .build();

        UntypedAgent workflow = AgenticServices.sequenceBuilder()
                .subAgents(answerer)
                .name("Caching workflow")
                .outputKey("answer")
                .listener(trace)
                .build();

        return invoke(workflow, Map.of("request", request));
    }

    private RunOutcome runBatching(List<String> items, ModelSet models, TraceCollector trace) {
        BatchWorker worker = AgenticServices.agentBuilder(BatchWorker.class)
                .chatModel(models.medium())
                .build();
        BatchWorkflow workflow = AgenticServices.parallelMapperBuilder(BatchWorkflow.class)
                .subAgents(worker)
                .itemsProvider("items")
                .executor(batchExecutor)
                .name("Batch mapper")
                .listener(trace)
                .build();

        List<String> answers = workflow.process(items);
        String output = IntStream.range(0, answers.size())
                .mapToObj(index -> (index + 1) + ". " + answers.get(index))
                .collect(java.util.stream.Collectors.joining("\n"));
        return new RunOutcome(output, Map.of("items", items, "answers", answers), items.size(), items.size());
    }

    private static RunOutcome invoke(UntypedAgent workflow, Map<String, Object> input) {
        ResultWithAgenticScope<String> result = workflow.invokeWithAgenticScope(input);
        return new RunOutcome(result.result(), result.agenticScope().state(), 1, 1);
    }

    private static List<String> splitBatch(String request) {
        List<String> items = java.util.Arrays.stream(request.split("[;\\n]+"))
                .map(String::strip)
                .filter(item -> !item.isBlank())
                .toList();
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Enter at least one non-empty batch item, separated by semicolons or newlines.");
        }
        if (items.size() > 6) {
            throw new IllegalArgumentException("A batch accepts at most six items. Split the request into smaller batches; no items were processed.");
        }
        return items;
    }

    private static int projectedBaseline(String patternId, int observed, RunOutcome outcome, String input) {
        int minimum = Math.max(estimateTokens(input) + estimateTokens(outcome.output()) + 60, observed);
        return switch (patternId) {
            case "router" -> Math.max(minimum, (int) Math.ceil(observed * 2.5));
            case "triage" -> Math.max(minimum, (int) Math.ceil(observed * 2.0));
            case "compression" -> Math.max(minimum, observed + estimateTokens(LONG_INCIDENT_CONTEXT) * 2);
            case "rag" -> Math.max(minimum, observed + estimateTokens(LocalKnowledgeRetriever.fullCorpus()) * 2);
            case "tool-use" -> Math.max(minimum, (int) Math.ceil(observed * 1.55));
            case "step-back" -> observed;
            case "caching" -> observed;
            case "batching" -> observed;
            default -> minimum;
        };
    }

    private static String measurementBasis(String patternId) {
        if ("batching".equals(patternId)) {
            return "Observed model tokens are unchanged; batching is measured primarily by wall time and throughput.";
        }
        if ("step-back".equals(patternId)) {
            return "Planning adds tokens to this turn; step-back pays back across avoided retries and rework, which one run cannot measure.";
        }
        if ("caching".equals(patternId)) {
            return "Azure reports reused and saved tokens as part of the input tokens. Every test calls the model and generates a new answer, so no token saving is claimed.";
        }
        return "Observed run tokens versus a modeled monolithic large-model baseline. Validate the projection with provider telemetry.";
    }

    private static List<String> takeaways(String patternId, RunOutcome outcome, TraceCollector trace, String cacheStatus) {
        return switch (patternId) {
            case "router" -> List.of(
                    "Only one specialist path was activated.",
                    "The route key moved through AgenticScope instead of being repeated in application glue.",
                    "Track misroutes alongside token savings.");
            case "triage" -> List.of(
                    "The complexity gate used zero model tokens.",
                    "Simple and complex requests follow different cost envelopes.",
                    "Calibrate escalation rules with real production samples.");
            case "compression" -> List.of(
                    "The expensive answerer saw compactContext, not the full incident transcript.",
                    "Compression pays back when the working set is reused across turns.",
                    "Retain source links for details that summaries may omit.");
            case "rag" -> List.of(
                    "Only two knowledge chunks were supplied to generation.",
                    "Retrieval is deterministic and costs no model tokens in this sample.",
                    "Evaluate retrieval recall separately from answer quality.");
            case "tool-use" -> List.of(
                    "Arithmetic happened in Java, not probabilistic language generation.",
                    "The model explained a verified result instead of recreating it.",
                    "Add authorization and timeouts before tools perform side effects.");
            case "step-back" -> List.of(
                    "The plan cost %d output tokens and framed a %d token answer.".formatted(
                            trace.outputTokensFor("Step-back planner"),
                            trace.outputTokensFor("Plan executor")),
                    "No token saving is claimed for a single turn; planning is an investment against retries.",
                    "Measure rework avoided, retry rate, and completion rate across turns.");
            case "caching" -> List.of(
                    switch (cacheStatus) {
                        case "hit" -> trace.cacheWriteTokens() == null
                                ? "Azure reused %s instruction tokens from its cache; it did not report cache writes, so they are unknown."
                                        .formatted(count(trace.cachedInputTokens()))
                                : trace.cacheWriteTokens() == 0
                                        ? "Azure reused %s instruction tokens from its cache instead of processing them again."
                                                .formatted(count(trace.cachedInputTokens()))
                                        : "Azure reused %s instruction tokens from its cache and saved %s more."
                                                .formatted(count(trace.cachedInputTokens()), count(trace.cacheWriteTokens()));
                        case "miss-written" -> "Azure had nothing cached, so it processed the instructions in full and saved %s tokens to its cache."
                                .formatted(count(trace.cacheWriteTokens()));
                        case "miss" -> "Azure reported no cache reads or writes for this call.";
                        case "bypassed" -> "Caching was off for this request, so Azure neither read nor saved the instructions.";
                        default -> "Azure did not report cache usage, so the result is unknown rather than a MISS.";
                    },
                    "Terra generated a new answer. Only the instructions can come from the cache, never the question or the answer.",
                    "Reused tokens still count as input tokens, billed at a lower rate. Saving to the cache can add a charge.");
            case "batching" -> List.of(
                    outcome.concurrency() + " independent items were dispatched through the parallel mapper.",
                    "Concurrency improves elapsed time but does not automatically reduce content tokens.",
                    "Bound fan-out to provider rate and concurrency limits.");
            default -> List.of();
        };
    }

    private static Map<String, Object> sanitizeScope(Map<String, Object> scope) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        scope.forEach((key, value) -> {
            if (value instanceof String text && text.length() > 700) {
                sanitized.put(key, text.substring(0, 699) + "…");
            } else {
                sanitized.put(key, value);
            }
        });
        return Map.copyOf(sanitized);
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.codePointCount(0, text.length()) / 4.0));
    }

    // Grouped the way the UI groups numbers, so "1,772" never reads as two numbers.
    private static String count(Integer tokens) {
        return String.format(Locale.US, "%,d", tokens);
    }

    private record RunOutcome(
            String output,
            Map<String, Object> scope,
            int concurrency,
            int itemCount) {
    }
}