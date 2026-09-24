# TokenFlow Lab

**Eight agent design patterns. Real model calls. Visible trade-offs.**

An interactive Java workshop for learning when to call a model, which model to choose, and how much context to send. Run a pattern, follow its animated graph, and inspect the answer, token usage, and agent trace.

**[Open the live lab](https://ca-tokenflow-dev-h4anbfs7yreo6.lemonwave-32f00510.eastus2.azurecontainerapps.io/)** · [Five-minute tour](#five-minute-tour) · [Run locally](#run-locally) · [Deploy to Azure](#deploy-to-azure) · [Operations and API reference](docs/operations.md)

> Every run uses Azure OpenAI and can incur charges. There is no simulated production mode. The hosted lab may be paused outside workshop sessions.

[![TokenFlow Lab showing all eight patterns, a completed triage flow, observed usage, output, and agent trace](docs/images/tokenflow-lab-desktop.png)](docs/images/tokenflow-lab-desktop.png)

*A real Azure run, captured September 24, 2026. Click either screenshot to enlarge it. Outputs, usage, and latency vary between runs; these are examples, not benchmarks.*

## Five-minute tour

1. **Start with Triage.** Keep the sample HTTP 429 question and select **Run Triage**. Follow the fast path: the Java gate uses zero model tokens, then the small model answers.
2. **Trigger escalation.** Replace the question with the prompt below. The graph should select the deep path instead.
3. **Try Caching.** Open **View the shared system instructions**, then ask about HTTP 429 and a Java `NullPointerException`. Both can HIT: Azure reuses the same instructions, not your question. Each answer is generated afresh. A hit is not guaranteed.
4. **Bypass the cache.** Uncheck the option and run again. This bypasses cache reads and writes; it does not clear Azure's cache.
5. **Try Batching.** Run the three supplied items. Notice three model calls and concurrent work, not automatic content-token savings.

Prompt for the deep-triage step:

```text
Design a secure distributed multi-region architecture for a payment system, including migration trade-offs.
```

The important distinction in the cache view: **HIT reuses the fixed system instructions, not the textbox or an old answer. Even random text can legitimately share that prefix. Both branches still call Terra.**

[![Real provider cache hit: 1,723 input tokens reused, with the HIT path flowing through Terra to a fresh answer](docs/images/tokenflow-provider-cache.png)](docs/images/tokenflow-provider-cache.png)

The dashed return path lights only when the provider reports cache writes. The animation replays the completed receipt; it is not a live stream of Azure's internal operations. Bypass and missing telemetry are shown explicitly rather than guessed.

## The eight patterns

All patterns have a built-in sample, an execution graph, and teaching notes.

| Pattern | Try this | What to look for |
|---|---|---|
| **Router** | Compare a Java debugging question with an architecture question | Only the selected specialist runs |
| **Triage** | Compare a routine question with the complex prompt above | A zero-token gate selects the small or large model |
| **Context compression** | Run the incident-analysis sample | A compact working set reaches the larger model |
| **RAG** | Ask how `AgenticScope` shares state | Two relevant local knowledge chunks ground the answer |
| **Tool use** | Run the token-cost calculation | Java does the arithmetic; the model explains it |
| **Step-back planning** | Run the migration-planning sample | A short plan guides execution; planning itself adds tokens |
| **Caching** | Change the question, inspect the shared instructions, then disable caching | Instruction-prefix reads and writes, not question matching; a fresh answer every time |
| **Batching** | Submit one to six independent items | One model call per item, ordered results, bounded concurrency |

Batch items can be separated by semicolons or newlines. Empty batches and more than six items are rejected before model initialization; work is never invented or silently dropped.

For a longer session, spend 2–3 minutes on each pattern, then ask: **Did the answer stay useful, and what did we actually measure?** The [printable pattern overview](docs/images/tokenflow-patterns-overview.svg) is useful for discussion.

## Read the numbers correctly

| UI measurement | What it means |
|---|---|
| **Observed** | Actual input plus output tokens reported by the provider |
| **Modeled baseline / Projected saving** | A modeled comparison, not a measured before-and-after run or a provider bill |
| **Prefix reused** | Cached instruction-prefix tokens divided by all input tokens, not a question match or dollar discount |
| **Cache writes** | Input stored for possible later reuse, not a cache hit on that call |
| **Reasoning tokens** | A subset already included in output tokens, not an extra amount to add |
| **Wall time / Model calls** | Elapsed application time and observed model invocations |

**Caching, batching, and step-back planning claim zero single-run content-token avoidance.** Cache reads can reduce processing cost; batching can improve throughput; planning may avoid later rework. None of those benefits is established by a savings percentage alone. Missing optional telemetry stays unknown, not zero.

## Run locally

You need:

- **Java 21+** and Git. Maven is optional; the wrapper is included.
- An **Azure OpenAI resource** with the deployments listed below.
- Azure CLI sign-in and **Cognitive Services OpenAI User** access to that resource.

### Windows PowerShell

Replace the endpoint with your own resource URL:

```powershell
git clone https://github.com/roryp/token-design-patterns.git
Set-Location token-design-patterns
az login

$env:AZURE_OPENAI_ENDPOINT = "https://your-resource.openai.azure.com/"
$env:AZURE_OPENAI_USE_MANAGED_IDENTITY = "true"
.\mvnw.cmd spring-boot:run
```

<details>
<summary>macOS / Linux</summary>

```bash
git clone https://github.com/roryp/token-design-patterns.git
cd token-design-patterns
az login

export AZURE_OPENAI_ENDPOINT="https://your-resource.openai.azure.com/"
export AZURE_OPENAI_USE_MANAGED_IDENTITY="true"
sh ./mvnw spring-boot:run
```

`sh` runs the included wrapper without requiring an executable file permission.

</details>

Open **[localhost:8080](http://localhost:8080)**. Select a pattern and run its sample. <kbd>Ctrl</kbd>+<kbd>Enter</kbd> or <kbd>⌘</kbd>+<kbd>Enter</kbd> also submits a request.

The identity option uses `DefaultAzureCredential`: Azure CLI credentials locally, managed identity in Azure. Keys and access tokens remain server-side.

### Model tiers

| Tier | Default deployment | Used for |
|---|---|---|
| Small | `gpt-5.6-luna` | Classification, fast responses, compression |
| Medium | `gpt-5.6-terra` | Code answers, caching, batch work |
| Large | `gpt-5.6-sol` | Architecture, deeper reasoning, grounded answers |

For custom deployment names or API-key authentication, see [configuration](docs/operations.md#configuration). LangChain4j Agentic orchestrates the workflows; the official OpenAI Java SDK handles Azure `/openai/v1/` requests and provider usage.

## Deploy to Azure

**First deployment?** Follow the [Azure setup steps](docs/operations.md#first-deployment) for permissions, model availability, quota, and environment creation.

For an already configured azd environment:

```powershell
azd provision --preview
azd up
azd env get-value AZURE_CONTAINER_APP_URL
```

The template uses managed identity, external HTTPS, a non-root Java 21 container, health probes, and scale-to-zero. Builds run remotely in ACR, so local Docker is not required. [Architecture, lifecycle commands, and troubleshooting](docs/operations.md#azure-architecture) are in the operations guide.

## Before you go on stage

- Open the deployed URL and run one sample to warm the app and verify model access.
- Run the cache sample before the session, but **do not promise a hit** during the talk.
- Check model quota for the expected audience. One batch can make up to six model calls.
- Refresh the browser after deploying changes. If the app was explicitly stopped, deployment alone may not restart it; see [start and stop](docs/operations.md#start-and-stop).
- Keep these screenshots as a clearly labeled backup if connectivity fails.
- Use a controlled workshop environment and monitor cost. Scale-to-zero does not remove registry, storage, or monitoring charges.

## Develop and test

From the repository root:

```powershell
.\mvnw.cmd clean package
node --test .\scripts\cache-flow.test.mjs
```

Java tests use test-scoped stubs and need no Azure access or model credentials. The animation tests use Node 18+ with no package installation or frontend build. On macOS/Linux, use `sh ./mvnw clean package`.

**Live tests make paid calls.** The [verification guide](docs/operations.md#verification) covers the API smoke test and the Playwright MCP suites for all eight patterns, desktop/mobile layouts, routing branches, cache behavior, and input validation.

### Where to explore

| File | Responsibility |
|---|---|
| [PatternAgents.java](src/main/java/com/example/tokenpatterns/agent/PatternAgents.java) | AI prompts and deterministic agents |
| [PatternRunner.java](src/main/java/com/example/tokenpatterns/service/PatternRunner.java) | Workflow composition and measurements |
| [TraceCollector.java](src/main/java/com/example/tokenpatterns/service/TraceCollector.java) | Per-invocation provider usage and trace |
| [OfficialSdkChatModel.java](src/main/java/com/example/tokenpatterns/agent/OfficialSdkChatModel.java) | Typed SDK requests, cache controls, and response usage |
| [index.html](src/main/resources/static/index.html) | Dependency-free workshop UI |
| [infra/](infra) | Azure resources and deployment configuration |

**Stack:** Java 21 · Spring Boot 4.1.0 · LangChain4j 1.19.0 · Agentic 1.19.0-beta29 · OpenAI Java SDK 4.63.1. Exact pins are in [pom.xml](pom.xml). Agentic is experimental; keep it pinned and rerun the tests when upgrading.

For HTTP contracts, environment variables, operations, and cleanup, continue to the **[operations and API reference](docs/operations.md)**.
