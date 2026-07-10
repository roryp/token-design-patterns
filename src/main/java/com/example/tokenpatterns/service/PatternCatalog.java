package com.example.tokenpatterns.service;

import com.example.tokenpatterns.domain.PatternDefinition;
import com.example.tokenpatterns.domain.PatternDefinition.FlowEdge;
import com.example.tokenpatterns.domain.PatternDefinition.FlowNode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PatternCatalog {

    private final Map<String, PatternDefinition> patterns;

    public PatternCatalog() {
        Map<String, PatternDefinition> definitions = new LinkedHashMap<>();
        add(definitions, router());
        add(definitions, triage());
        add(definitions, compression());
        add(definitions, rag());
        add(definitions, toolUse());
        add(definitions, stepBack());
        add(definitions, caching());
        add(definitions, batching());
        patterns = Map.copyOf(definitions);
    }

    public List<PatternDefinition> all() {
        return patterns.values().stream()
                .sorted(java.util.Comparator.comparingInt(PatternDefinition::order))
                .toList();
    }

    public PatternDefinition get(String id) {
        PatternDefinition definition = patterns.get(id);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown token design pattern: " + id);
        }
        return definition;
    }

    private static void add(Map<String, PatternDefinition> definitions, PatternDefinition definition) {
        definitions.put(definition.id(), definition);
    }

    private static PatternDefinition router() {
        return new PatternDefinition(
                "router", 1, "Router", "route", "60–80% projected",
                "Match each request to the least expensive capable path.",
                "A small classifier chooses a narrow specialist instead of sending every request to the largest model.",
                "LangChain4j sequence + conditional workflows share the selected route through AgenticScope.",
                "Mixed workloads with clear domains or capability tiers.",
                "Routing errors can cost more than model savings. Keep a fallback and measure misroutes.",
                "Large-model calls avoided",
                "Why does my Java stream return an empty list after I add a filter?",
                List.of(
                        n("input", "Request", "developer prompt", "user", 7, 50),
                        n("router", "Route", "small classifier", "orchestrator", 28, 50),
                        n("small", "Knowledge", "small model", "model-small", 58, 18),
                        n("medium", "Code", "medium model", "model-medium", 58, 50),
                        n("large", "Architecture", "large model", "model-large", 58, 82),
                        n("output", "Answer", "selected path", "output", 90, 50)),
                List.of(
                        e("input", "router", "classify"),
                        e("router", "small", "knowledge"),
                        e("router", "medium", "code"),
                        e("router", "large", "architecture"),
                        e("small", "output", ""),
                        e("medium", "output", ""),
                        e("large", "output", "")),
                Map.of(
                        "Route classifier", "router",
                        "Knowledge specialist", "small",
                        "Code specialist", "medium",
                        "Architecture specialist", "large"));
    }

    private static PatternDefinition triage() {
        return new PatternDefinition(
                "triage", 2, "Triage", "filter", "50–70% projected",
                "Escalate only the requests that earn deeper reasoning.",
                "A deterministic complexity gate sends routine questions to a fast path and reserves the large model for ambiguous work.",
                "A non-AI @Agent writes complexity into AgenticScope; a conditional workflow activates one responder.",
                "Support queues, incident intake, and question-answering with a long tail of easy requests.",
                "Complexity is not the same as prompt length. Test the gate against real escalations.",
                "Escalation rate",
                "What does HTTP 429 mean and what should a client do?",
                List.of(
                        n("input", "Request", "incoming work", "user", 7, 50),
                        n("triage", "Triage", "zero-token gate", "orchestrator", 30, 50),
                        n("fast", "Fast path", "small model", "model-small", 62, 25),
                        n("deep", "Deep path", "large model", "model-large", 62, 75),
                        n("output", "Answer", "one response", "output", 91, 50)),
                List.of(
                        e("input", "triage", "inspect"),
                        e("triage", "fast", "simple"),
                        e("triage", "deep", "complex"),
                        e("fast", "output", ""),
                        e("deep", "output", "")),
                Map.of(
                        "Triage gate", "triage",
                        "Fast-path responder", "fast",
                        "Deep reasoning responder", "deep"));
    }

    private static PatternDefinition compression() {
        return new PatternDefinition(
                "compression", 3, "Context compression", "compress", "30–60% projected",
                "Turn a growing transcript into a compact working set.",
                "A compressor retains decisions, constraints, and unresolved facts before a larger model performs the final reasoning.",
                "A sequential workflow writes compactContext once and passes that smaller state to the focused answerer.",
                "Long sessions, incident timelines, research notes, and repeated follow-up questions.",
                "Summaries can erase decisive details. Preserve source references and refresh after material changes.",
                "Context tokens per turn",
                "Based on the incident history, what is the most likely cause of the repeated 429 responses?",
                List.of(
                        n("context", "Long context", "repeated history", "data", 7, 50),
                        n("compressor", "Compress", "small model", "model-small", 31, 50),
                        n("summary", "Working set", "durable facts", "data", 55, 50),
                        n("reasoner", "Reason", "large model", "model-large", 77, 50),
                        n("output", "Answer", "focused result", "output", 94, 50)),
                List.of(
                        e("context", "compressor", "distill"),
                        e("compressor", "summary", "write state"),
                        e("summary", "reasoner", "small context"),
                        e("reasoner", "output", "")),
                Map.of(
                        "Context compressor", "compressor",
                        "Focused answerer", "reasoner"));
    }

    private static PatternDefinition rag() {
        return new PatternDefinition(
                "rag", 4, "Retrieval augmented generation", "search", "40–60% projected",
                "Retrieve the few facts that can answer the question.",
                "A non-AI retriever selects relevant chunks from the local knowledge base before generation.",
                "A sequential workflow stores retrieved context in AgenticScope and grounds the generator on that state.",
                "Large documentation sets, policies, runbooks, and product knowledge.",
                "Retrieval quality is the ceiling. Track recall, citations, chunk size, and empty-result behavior.",
                "Retrieved-to-corpus ratio",
                "How does AgenticScope share state between agents?",
                List.of(
                        n("input", "Question", "developer query", "user", 6, 50),
                        n("retriever", "Retrieve", "lexical search", "orchestrator", 28, 50),
                        n("corpus", "Knowledge", "top 2 chunks", "data", 51, 50),
                        n("generator", "Generate", "grounded model", "model-large", 75, 50),
                        n("output", "Answer", "from evidence", "output", 94, 50)),
                List.of(
                        e("input", "retriever", "query"),
                        d("corpus", "retriever", "search"),
                        e("retriever", "corpus", "select"),
                        e("corpus", "generator", "context"),
                        e("generator", "output", "")),
                Map.of(
                        "Knowledge retriever", "retriever",
                        "Grounded generator", "generator"));
    }

    private static PatternDefinition toolUse() {
        return new PatternDefinition(
                "tool-use", 5, "Tool use", "tool", "30–50% projected",
                "Use deterministic computation where language reasoning adds no value.",
                "A non-AI cost calculator performs exact arithmetic; a model only explains the verified result.",
                "LangChain4j treats the calculator as a first-class non-AI @Agent inside a sequence workflow.",
                "Math, lookups, database operations, APIs, validation, and side-effecting work.",
                "Tools need typed inputs, timeouts, authorization, and clear failure behavior.",
                "Reasoning tokens replaced",
                "Estimate monthly cost for 50M input tokens and 10M output tokens at $0.15/$0.60 per million.",
                List.of(
                        n("input", "Request", "cost question", "user", 7, 50),
                        n("tool", "Calculator", "deterministic", "tool", 35, 50),
                        n("explainer", "Explain", "small model", "model-small", 67, 50),
                        n("output", "Answer", "auditable", "output", 93, 50)),
                List.of(
                        e("input", "tool", "typed values"),
                        e("tool", "explainer", "verified result"),
                        e("explainer", "output", "")),
                Map.of(
                        "Cost calculator", "tool",
                        "Tool-grounded explainer", "explainer"));
    }

    private static PatternDefinition stepBack() {
        return new PatternDefinition(
                "step-back", 6, "Step-back planning", "plan", "20–40% projected",
                "Spend a few planning tokens to avoid expensive wandering.",
                "A planner resolves goals, constraints, and sequencing before a second agent writes the recommendation.",
                "A sequential Agentic workflow shares a compact plan instead of repeatedly rediscovering the problem frame.",
                "Architecture, migrations, multi-step changes, and tasks with costly retries.",
                "For trivial work the planning call is overhead. Gate this pattern by complexity.",
                "Rework and retry rate",
                "Design a safe migration from a monolith to event-driven services without a big-bang rewrite.",
                List.of(
                        n("input", "Goal", "complex task", "user", 7, 50),
                        n("planner", "Step back", "small planner", "model-small", 32, 50),
                        n("plan", "Plan", "constraints + steps", "plan", 56, 50),
                        n("executor", "Execute", "focused model", "model-large", 78, 50),
                        n("output", "Answer", "less rework", "output", 95, 50)),
                List.of(
                        e("input", "planner", "frame"),
                        e("planner", "plan", "commit"),
                        e("plan", "executor", "guide"),
                        e("executor", "output", "")),
                Map.of(
                        "Step-back planner", "planner",
                        "Plan executor", "executor"));
    }

    private static PatternDefinition caching() {
        return new PatternDefinition(
                "caching", 7, "Caching", "cache", "50–90% typical",
                "The cheapest repeated model call is the one you never make.",
                "An exact normalized-key cache answers stable repeat requests and invokes the model only on a miss.",
                "A cache lookup non-AI agent writes answer; a conditional workflow activates the model only for the miss sentinel.",
                "Stable definitions, deterministic transforms, shared prefixes, and repeated support questions.",
                "Define freshness, tenancy, privacy, invalidation, and whether stochastic output is safe to reuse.",
                "Cache hit rate",
                "What is idempotency and why does it matter for retries?",
                List.of(
                        n("input", "Request", "normalized key", "user", 7, 50),
                        n("cache", "Cache", "exact lookup", "cache", 35, 50),
                        n("model", "Generate", "on miss only", "model-medium", 66, 75),
                        n("output", "Answer", "hit or fill", "output", 92, 50)),
                List.of(
                        e("input", "cache", "lookup"),
                        e("cache", "output", "hit"),
                        d("cache", "model", "miss"),
                        e("model", "output", "answer"),
                        d("model", "cache", "store")),
                Map.of(
                        "Cache lookup", "cache",
                        "Cache answerer", "model"));
    }

    private static PatternDefinition batching() {
        return new PatternDefinition(
                "batching", 8, "Batching", "batch", "Throughput first",
                "Fan independent work out concurrently and amortize orchestration overhead.",
                "A parallel mapper applies one stateless agent to each semicolon-separated item and aggregates the results.",
                "LangChain4j parallelMapperBuilder coordinates the fan-out and preserves a single top-level execution trace.",
                "Bulk classification, extraction, evaluation, and independent document processing.",
                "Parallelism lowers wall time, not content tokens by itself. Bound concurrency and respect provider rate limits.",
                "Wall time and throughput",
                "Explain router patterns; Explain context compression; Explain caching",
                List.of(
                        n("items", "Items", "3 independent tasks", "user", 7, 50),
                        n("mapper", "Mapper", "fan out", "batch", 31, 50),
                        n("workers", "Workers", "parallel models", "model-medium", 62, 50),
                        n("output", "Results", "ordered list", "output", 92, 50)),
                List.of(
                        e("items", "mapper", "collection"),
                        e("mapper", "workers", "× N"),
                        e("workers", "output", "aggregate")),
                Map.of(
                        "Batch mapper", "mapper",
                        "Batch worker", "workers"));
    }

    private static FlowNode n(String id, String label, String caption, String kind, int x, int y) {
        return new FlowNode(id, label, caption, kind, x, y);
    }

    private static FlowEdge e(String from, String to, String label) {
        return new FlowEdge(from, to, label, false);
    }

    private static FlowEdge d(String from, String to, String label) {
        return new FlowEdge(from, to, label, true);
    }
}