# TokenFlow Lab workspace instructions

- [x] Verify workspace instructions
- [x] Clarify project requirements
- [x] Scaffold the Maven project
- [x] Implement the Agentic pattern lab
- [x] Confirm no additional extensions are required
- [x] Compile and test the project
- [x] Create and run the test task
- [x] Launch and visually validate the application
- [x] Complete project documentation

## Project conventions

- Target Java 21 and Spring Boot 4.1.
- Pin LangChain4j Agentic because the module is experimental.
- Keep demo mode credential-free and deterministic.
- Preserve the distinction between observed tokens and projected baselines.
- Do not claim token savings for batching; emphasize throughput and bounded concurrency.
- Keep secrets server-side and configure live models through environment variables.
- Use managed identity for Azure OpenAI and ACR; do not add deployment keys to Bicep or Container Apps.
- Map the current GPT-5.6 deployments as Luna/Small, Terra/Medium, and Sol/Large.
- Run `mvnw.cmd test` after backend or workflow changes.
