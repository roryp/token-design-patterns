# TokenFlow Lab Project Instructions

## Project Intent

- Maintain an interactive workshop that demonstrates token-efficient agent design with honest measurements against real model calls.
- Treat `README.md` as the source of truth for supported patterns, HTTP behavior, local operation, and Azure deployment. Update it when those contracts change.
- Keep changes focused. Do not edit generated content under `target/`.

## Stack and Structure

- Target Java 21 and Spring Boot 4.1. Spring Boot 4 renamed the starters: use `spring-boot-starter-webmvc` and `spring-boot-starter-webmvc-test`.
- Keep LangChain4j core (`1.19.0`) and Agentic (`1.19.0-beta29`) versions explicit in `pom.xml`. Agentic is experimental and pinned; change it only as a deliberate compatibility upgrade with tests.
- Keep `azure-identity` an explicit dependency for the official OpenAI Java SDK's `DefaultAzureCredential` bearer-token integration; remote ACR builds start from an empty dependency cache.
- Put model construction and agent definitions in `agent`, orchestration and measurements in `service`, API records in `domain`, and HTTP concerns in `web`.
- The API surface is `GET /api/patterns`, `GET /api/config`, `GET /api/cache-policy`, and `POST /api/runs`. The policy endpoint exposes only the public workshop instructions, optionally with a validated `?session=` line; never put secrets or private data in that policy. The retired `DELETE /api/cache` returns `410 Gone`, never a claim that Azure's service-managed cache was cleared. `/api/config` returns `modelsConfigured`, `models`, and a literal `agenticVersion` that must be updated whenever the pinned Agentic version changes.
- The browser client is the dependency-free static application in `src/main/resources/static/index.html`; do not introduce a frontend build system unless the task requires it.

## Agentic Workflow Invariants

- The application has a single execution path against Azure OpenAI. Do not reintroduce a demo, offline, or simulated model mode in production code.
- The eight pattern ids are `router`, `triage`, `compression`, `rag`, `tool-use`, `step-back`, `caching`, and `batching`. `PatternRunner` dispatches on them and `PatternCatalog` supplies the matching topology, so add or rename them in both places.
- Keep each request's workflow state and trace isolated. Agent `outputKey` values must match downstream `AgenticScope` inputs.
- Prefer deterministic Java agents for routing gates, retrieval, and arithmetic when model reasoning is unnecessary.
- Model calls go through the official OpenAI Java SDK's Responses API with `store: false`. Keep it: on Azure GlobalStandard GPT-5.6, Responses requests reliably read a prefix written by the previous request, while Chat Completions requests usually did not.
- Caching is provider prompt caching, not a local response map: every run calls the model, even on a hit. Only the cached system instructions carry a breakpoint; the fixed question and answer are never cached. `CacheInstructions` puts a validated per-browser-session line first so test 1 is a genuine MISS that writes the prefix and test 2 can HIT; the UI has no prompt box for caching and shows the exact instructions sent. Use typed SDK controls and provider usage; missing telemetry stays unknown, and bypass must not pretend to clear the cache.
- Keep batching bounded and suitable only for independent work. It improves throughput; it does not inherently reduce content tokens.

## Metrics and Claims

- Preserve the distinction between observed tokens and projected baselines in code, API fields, UI labels, tests, and documentation. `PatternRunResult.Metrics` names them `observedTokens`, `projectedBaselineTokens`, `avoidedTokens`, and `projectedSavingsPercent`, with `basis` describing how the baseline was derived.
- Never present projected baselines as provider telemetry.
- Never claim automatic content-token savings for batching or provider prompt caching. Their projected baselines equal observed usage, so savings percentages remain zero. Cached reads/writes are subsets of input; reasoning is a subset of output.
- When changing a pattern, validate task quality as well as token count, latency, cache behavior, or concurrency as applicable.

## Models and Security

- Map GPT-5.6 deployments as Luna/Small (`gpt-5.6-luna`), Terra/Medium (`gpt-5.6-terra`), and Sol/Large (`gpt-5.6-sol`).
- Configure Azure OpenAI through environment variables and keep all credentials server-side. Never expose keys through static assets, API responses, logs, Bicep outputs, or source control.
- Use managed identity for Azure OpenAI and ACR in Azure. Do not add deployment keys or enable ACR admin credentials.
- Tests must not require Azure, network access, or model credentials. They use the test-scoped `StubChatModel` through `StubModelConfiguration`.

## Build and Validation

- On Windows, run `./mvnw.cmd test` after backend, API, model, metric, or workflow changes. On macOS or Linux, run `./mvnw test`.
- Add or update focused tests for behavior changes. Keep the full test covering all patterns credential-free.
- For UI changes, run `./mvnw.cmd spring-boot:run` and verify the affected workflow at desktop and mobile widths.
- For API changes, verify validation, error behavior, and serialization through controller tests.
- Update `README.md` when user-facing behavior, configuration, model mapping, operational commands, or deployment architecture changes.

## Azure Operations

- Use available Azure best-practice and diagnostic tools before changing infrastructure or operating deployed resources.
- Do not provision, deploy, activate/deactivate revisions, or delete resources unless the user explicitly asks. Preview infrastructure changes before applying them.
- Derive resource names and endpoints from the selected `azd` environment instead of hard-coding a development resource name.
- Preserve external HTTPS ingress on port 8080, the `/api/config` health probe, remote ACR builds, non-root container execution, and least-privilege role assignments.
- Treat `azd down`, resource deletion, and environment replacement as destructive operations that require explicit confirmation.
