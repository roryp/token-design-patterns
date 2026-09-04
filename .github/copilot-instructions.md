# TokenFlow Lab Project Instructions

## Project Intent

- Maintain an interactive workshop that demonstrates token-efficient agent design with honest measurements against real model calls.
- Treat `README.md` as the source of truth for supported patterns, HTTP behavior, local operation, and Azure deployment. Update it when those contracts change.
- Keep changes focused. Do not edit generated content under `target/`.

## Stack and Structure

- Target Java 21 and Spring Boot 4.1. Spring Boot 4 renamed the starters: use `spring-boot-starter-webmvc` and `spring-boot-starter-webmvc-test`.
- Keep LangChain4j core (`1.19.0`) and Agentic (`1.19.0-beta29`) versions explicit in `pom.xml`. Agentic is experimental and pinned; change it only as a deliberate compatibility upgrade with tests.
- Keep `azure-identity` an explicit dependency. It is optional in the LangChain4j Azure OpenAI module but required by `DefaultAzureCredential`, and remote ACR builds start from an empty dependency cache.
- Put model construction and agent definitions in `agent`, orchestration and measurements in `service`, API records in `domain`, and HTTP concerns in `web`.
- The API surface is `GET /api/patterns`, `GET /api/config`, `POST /api/runs`, and `DELETE /api/cache`. `/api/config` returns `modelsConfigured`, `models`, and a literal `agenticVersion` that must be updated whenever the pinned Agentic version changes.
- The browser client is the dependency-free static application in `src/main/resources/static/index.html`; do not introduce a frontend build system unless the task requires it.

## Agentic Workflow Invariants

- The application has a single execution path against Azure OpenAI. Do not reintroduce a demo, offline, or simulated model mode in production code.
- The eight pattern ids are `router`, `triage`, `compression`, `rag`, `tool-use`, `step-back`, `caching`, and `batching`. `PatternRunner` dispatches on them and `PatternCatalog` supplies the matching topology, so add or rename them in both places.
- Keep each request's workflow state and trace isolated. Agent `outputKey` values must match downstream `AgenticScope` inputs.
- Prefer deterministic Java agents for routing gates, retrieval, arithmetic, and cache lookup when model reasoning is unnecessary.
- Preserve cache-hit behavior: a hit performs zero model calls and reports zero observed tokens.
- Keep batching bounded and suitable only for independent work. It improves throughput; it does not inherently reduce content tokens.

## Metrics and Claims

- Preserve the distinction between observed tokens and projected baselines in code, API fields, UI labels, tests, and documentation. `PatternRunResult.Metrics` names them `observedTokens`, `projectedBaselineTokens`, `avoidedTokens`, and `projectedSavingsPercent`, with `basis` describing how the baseline was derived.
- Never present projected baselines as provider telemetry.
- Never claim automatic token savings for batching. Its projected baseline equals observed usage, so its savings percentage remains zero.
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
