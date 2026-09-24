# TokenFlow Lab

**Eight agent design patterns. Real model calls. Visible trade-offs.**

An interactive Java workshop about when to call a model, which model to choose, and how much context to send. Run a pattern, watch its graph animate, then inspect the answer, token usage, and agent trace.

**[Open the live lab](https://ca-tokenflow-dev-h4anbfs7yreo6.lemonwave-32f00510.eastus2.azurecontainerapps.io/)** · [Operations and API reference](docs/operations.md)

> Every run calls Azure OpenAI and can incur charges; there is no simulated mode. The hosted lab may be paused between workshops.

[![TokenFlow Lab showing all eight patterns, a completed triage flow, observed usage, output, and agent trace](docs/images/tokenflow-lab-desktop.png)](docs/images/tokenflow-lab-desktop.png)

*Screenshots are real Azure runs captured on September 24, 2026; select one to enlarge it. Outputs, usage, and latency vary between runs.*

## Contents

- [Five-minute tour](#five-minute-tour)
  - [What the cache demo shows](#what-the-cache-demo-shows)
- [The eight patterns](#the-eight-patterns)
- [Read the numbers correctly](#read-the-numbers-correctly)
- [Run locally](#run-locally)
  - [Model tiers](#model-tiers)
- [Deploy to Azure](#deploy-to-azure)
- [Before you go on stage](#before-you-go-on-stage)
- [Develop and test](#develop-and-test)
  - [Where to explore](#where-to-explore)
- [Operations and API reference](docs/operations.md) (separate guide)

## Five-minute tour

1. **Triage, fast path.** Keep the sample HTTP 429 question and select **Run Triage**. A Java gate spends zero model tokens, then the small model answers.
2. **Triage, deep path.** Replace the question with this prompt and run it again. The graph now takes the deep path to the large model.

   ```text
   Design a secure distributed multi-region architecture for a payment system, including migration trade-offs.
   ```

3. **Caching.** Open **Cached instructions for this browser session** to see exactly what Terra receives. **Run cache test 1** is a MISS: Azure writes the instructions to its cache. **Run cache test 2** is a HIT: Azure reuses them. **Start over** gives you new instructions, so the next test misses again.
4. **Batching.** Run the three supplied items. Three model calls run concurrently, with no automatic content-token savings.

### What the cache demo shows

HIT and MISS are Azure's own usage numbers for each model call: `cached_tokens` read from its prompt cache and `cache_write_tokens` written to it. The first line of the instructions is unique to your browser session, so test 1 has nothing to reuse; test 2 sends the same instructions and reads them back. The question and answer are never cached, and Terra writes a fresh answer every time.

[![Cache test 2 on Azure: HIT, reusing the 1,773 instruction tokens that test 1 wrote. The dropdown shows the exact session instructions, and the history shows test 1 MISS, then test 2 HIT](docs/images/tokenflow-provider-cache.png)](docs/images/tokenflow-provider-cache.png)

The page reports each result exactly as Azure returns it; if test 2 ever misses, run test 3. The lab uses the Responses API because, in our trials, Azure served the cached instructions to test 2 every time, whereas with Chat Completions test 2 usually missed ([details](docs/operations.md#provider-caching)).

## The eight patterns

Each pattern has a built-in sample, an execution graph, and teaching notes.

| Pattern | Try this | What to look for |
|---|---|---|
| **Router** | Compare a Java debugging question with an architecture question | Only the selected specialist runs |
| **Triage** | Compare a routine question with the deep-path prompt above | A zero-token gate picks the small or large model |
| **Context compression** | Run the incident-analysis sample | A compact working set reaches the larger model |
| **RAG** | Ask how `AgenticScope` shares state | Two relevant local knowledge chunks ground the answer |
| **Tool use** | Run the token-cost calculation | Java does the arithmetic; the model explains it |
| **Step-back planning** | Run the migration-planning sample | A short plan guides the answer; planning itself adds tokens |
| **Caching** | Run cache test 1, then test 2, then **Start over** | The MISS writes the instructions, the HIT reuses them, and every answer is fresh |
| **Batching** | Submit one to six independent items, separated by semicolons or newlines | One model call per item, results in order, bounded concurrency |

Empty batches and batches of more than six items are rejected before any model call; work is never invented or silently dropped.

For a longer session, spend two or three minutes on each pattern and ask: **did the answer stay useful, and what did we actually measure?** The [printable pattern overview](docs/images/tokenflow-patterns-overview.svg) helps the discussion.

## Read the numbers correctly

| UI measurement | What it means |
|---|---|
| **Observed** | Input plus output tokens reported by the provider |
| **Modeled baseline / Projected saving** | A modeled comparison, not a measured before-and-after run or a bill |
| **Prefix reused** | Cached instruction tokens as a share of all input tokens, not a question match or a dollar discount |
| **Cache writes** | Input stored for later reuse, not a hit on that call |
| **Reasoning tokens** | Already included in output tokens; do not add them again |
| **Wall time / Model calls** | Elapsed application time and actual model invocations |

**Caching, batching, and step-back planning claim no single-run token savings.** Cache reads can lower processing cost, batching can raise throughput, and planning can avoid later rework, but a savings percentage alone shows none of those. Missing telemetry is shown as unknown, never as zero.

## Run locally

You need:

- **Java 21+** and Git. Maven is optional; the wrapper is included.
- An **Azure OpenAI resource** with the [model deployments](#model-tiers) below.
- An Azure CLI sign-in with **Cognitive Services OpenAI User** on that resource.

### Windows PowerShell

Replace the endpoint with your resource URL:

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

`sh` runs the included wrapper without an executable file permission.

</details>

Open **[localhost:8080](http://localhost:8080)**, select a pattern, and run its sample. <kbd>Ctrl</kbd>+<kbd>Enter</kbd> (or <kbd>⌘</kbd>+<kbd>Enter</kbd>) also submits a request.

Identity authentication uses `DefaultAzureCredential`: your Azure CLI sign-in locally and managed identity in Azure. Keys and access tokens stay on the server.

### Model tiers

| Tier | Default deployment | Used for |
|---|---|---|
| Small | `gpt-5.6-luna` | Classification, fast responses, compression |
| Medium | `gpt-5.6-terra` | Code answers, caching, batch work |
| Large | `gpt-5.6-sol` | Architecture, deeper reasoning, grounded answers |

LangChain4j Agentic orchestrates the workflows, and the official OpenAI Java SDK sends stateless Responses API requests to Azure. For custom deployment names or API-key authentication, see [configuration](docs/operations.md#configuration).

## Deploy to Azure

**First deployment?** Follow the [Azure setup steps](docs/operations.md#first-deployment) for permissions, model availability, quota, and environment creation. For an environment that is already configured:

```powershell
azd provision --preview
azd up
azd env get-value AZURE_CONTAINER_APP_URL
```

The template runs a non-root Java 21 container on Azure Container Apps with managed identity, external HTTPS, health probes, and scale-to-zero. ACR builds the image remotely, so you don't need Docker locally. The operations guide covers [architecture, lifecycle commands, and troubleshooting](docs/operations.md#azure-architecture).

## Before you go on stage

- Open the deployed URL and run one sample to warm the app and confirm model access.
- Run cache tests 1 and 2 to confirm MISS, then HIT. A HIT is very likely but not guaranteed; if test 2 misses, run test 3.
- Check model quota for your audience size. One batch can make up to six model calls.
- Reload the browser after each deployment. If the app was explicitly stopped, deploying may not restart it; see [start and stop](docs/operations.md#start-and-stop).
- Keep the screenshots above as a clearly labeled fallback in case connectivity fails.
- Use a controlled workshop environment and monitor cost. Scale-to-zero does not remove registry, storage, or monitoring charges.

## Develop and test

```powershell
.\mvnw.cmd clean package
```

Tests use test-scoped stubs, so they need no Azure access or credentials. On macOS/Linux, run `sh ./mvnw clean package`.

**Live tests make paid calls.** The [verification guide](docs/operations.md#verification) covers the API smoke test and the Playwright MCP suites for every pattern, desktop and mobile layouts, routing branches, caching, and input validation.

### Where to explore

| File | Responsibility |
|---|---|
| [PatternAgents.java](src/main/java/com/example/tokenpatterns/agent/PatternAgents.java) | AI prompts and deterministic agents |
| [PatternRunner.java](src/main/java/com/example/tokenpatterns/service/PatternRunner.java) | Workflow composition and measurements |
| [TraceCollector.java](src/main/java/com/example/tokenpatterns/service/TraceCollector.java) | Per-invocation provider usage and trace |
| [OfficialSdkChatModel.java](src/main/java/com/example/tokenpatterns/agent/OfficialSdkChatModel.java) | Typed Responses API requests, cache controls, and provider usage |
| [CacheInstructions.java](src/main/java/com/example/tokenpatterns/agent/CacheInstructions.java) | The exact cached instructions for each browser session |
| [index.html](src/main/resources/static/index.html) | Dependency-free workshop UI |
| [infra/](infra) | Azure resources and deployment configuration |

**Stack:** Java 21 · Spring Boot 4.1.0 · LangChain4j 1.19.0 · Agentic 1.19.0-beta29 · OpenAI Java SDK 4.63.1. Exact versions are pinned in [pom.xml](pom.xml). Agentic is experimental, so keep it pinned and rerun the tests when upgrading.

For HTTP contracts, environment variables, operations, and cleanup, see the **[operations and API reference](docs/operations.md)**.
