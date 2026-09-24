package com.example.tokenpatterns.agent;

import com.example.tokenpatterns.domain.ModelOutputLimitException;
import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.OpenAIClient;
import com.openai.core.ObjectMappers;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.completions.CompletionUsage;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.example.tokenpatterns.agent.OfficialSdkChatModel.PromptCacheMode.BYPASS;
import static com.example.tokenpatterns.agent.OfficialSdkChatModel.PromptCacheMode.CACHE_SYSTEM_PREFIX;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class OfficialSdkChatModelTest {

    private static final String DEPLOYMENT = "gpt-5.6-terra";
    private static final String SYSTEM_PREFIX = "Stable, versioned policy shared by the cache experiment.";
    private static final String TOTALS = """
            {"prompt_tokens":1000,"completion_tokens":100,"total_tokens":1100}
            """;

    private OpenAIClient client;
    private ChatCompletionService completions;

    @BeforeEach
    void mockSdkBoundary() {
        client = mock(OpenAIClient.class);
        var chat = mock(ChatService.class);
        completions = mock(ChatCompletionService.class);
        when(client.chat()).thenReturn(chat);
        when(chat.completions()).thenReturn(completions);
    }

    @Test
    void placesExplicitCacheBreakpointOnlyOnStableSystemTextAndReusesVersionedKey() {
        when(completions.create(any(ChatCompletionCreateParams.class)))
                .thenReturn(response("first fresh answer"), response("second fresh answer"));
        var model = new OfficialSdkChatModel(client, DEPLOYMENT, CACHE_SYSTEM_PREFIX);

        var first = model.chat(SystemMessage.from(SYSTEM_PREFIX), UserMessage.from("first question"));
        var second = model.chat(SystemMessage.from(SYSTEM_PREFIX), UserMessage.from("different question"));
        assertEquals("first fresh answer", first.aiMessage().text());
        assertEquals("second fresh answer", second.aiMessage().text());

        var captured = ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        verify(completions, times(2)).create(captured.capture());
        for (var params : captured.getAllValues()) {
            assertEquals(DEPLOYMENT, params.model().asString());
            assertEquals(2000L, params.maxCompletionTokens().orElseThrow());
            assertTrue(params.temperature().isEmpty(), "Do not introduce a temperature default");
            assertEquals("tokenflow-policy-v1", params.promptCacheKey().orElseThrow());
            assertEquals(ChatCompletionCreateParams.PromptCacheOptions.Mode.EXPLICIT,
                    params.promptCacheOptions().orElseThrow().mode().orElseThrow());
            assertEquals(ChatCompletionCreateParams.PromptCacheOptions.Ttl._30M,
                    params.promptCacheOptions().orElseThrow().ttl().orElseThrow());
            var systemText = params.messages().getFirst().asSystem().content().asArrayOfContentParts().getFirst();
            assertEquals(SYSTEM_PREFIX, systemText.text());
            assertTrue(systemText.promptCacheBreakpoint().isPresent());
            systemText.promptCacheBreakpoint().orElseThrow().validate();

            // Inspect the SDK's serialized typed body, not a handcrafted request or additional properties.
            JsonNode wire = ObjectMappers.jsonMapper().valueToTree(params._body());
            assertEquals("explicit", wire.at("/prompt_cache_options/mode").asText());
            assertEquals("30m", wire.at("/prompt_cache_options/ttl").asText());
            assertEquals("explicit", wire.at("/messages/0/content/0/prompt_cache_breakpoint/mode").asText());
            assertFalse(wire.has("prompt_cache_breakpoint"));
            assertFalse(wire.at("/messages/0").has("prompt_cache_breakpoint"));
            assertFalse(wire.at("/messages/1").has("prompt_cache_breakpoint"));
            assertEquals(1, wire.findValues("prompt_cache_breakpoint").size());
        }
        assertEquals("first question", captured.getAllValues().get(0).messages().get(1).asUser().content().asText());
        assertEquals("different question", captured.getAllValues().get(1).messages().get(1).asUser().content().asText());
    }

    @Test
    void bypassIsExplicitAndNeverAddsKeyTtlOrBreakpoint() {
        when(completions.create(any(ChatCompletionCreateParams.class))).thenReturn(response("answer"));
        var model = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS);
        model.chat(SystemMessage.from(SYSTEM_PREFIX), UserMessage.from("question"));

        var params = capturedRequest();
        assertEquals(ChatCompletionCreateParams.PromptCacheOptions.Mode.EXPLICIT,
                params.promptCacheOptions().orElseThrow().mode().orElseThrow());
        assertTrue(params.promptCacheKey().isEmpty());
        assertTrue(params.promptCacheOptions().orElseThrow().ttl().isEmpty());
        JsonNode wire = ObjectMappers.jsonMapper().valueToTree(params._body());
        assertTrue(wire.findValues("prompt_cache_breakpoint").isEmpty());
        assertEquals(ModelProvider.AZURE_OPEN_AI, model.provider());
    }

    @Test
    void mapsTextHistoryNamesAndExplicitCommonParametersWithoutRetainingConversation() {
        when(completions.create(any(ChatCompletionCreateParams.class))).thenReturn(response("answer"));
        var model = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS);
        var request = ChatRequest.builder()
                .messages(SystemMessage.from("instructions"),
                        UserMessage.from("learner", "prior question"),
                        AiMessage.from("prior answer"),
                        UserMessage.from(TextContent.from("part one"), TextContent.from("part two")))
                .parameters(ChatRequestParameters.builder().modelName(DEPLOYMENT)
                        .temperature(0.3).topP(0.8).frequencyPenalty(0.1).presencePenalty(0.2)
                        .maxOutputTokens(900).stopSequences(List.of("END")).responseFormat(ResponseFormat.TEXT)
                        .build())
                .build();

        model.chat(request);
        var params = capturedRequest();
        assertEquals(4, params.messages().size());
        assertEquals("learner", params.messages().get(1).asUser().name().orElseThrow());
        assertEquals("prior question", params.messages().get(1).asUser().content().asText());
        assertEquals("prior answer", params.messages().get(2).asAssistant().content().orElseThrow().asText());
        var parts = params.messages().get(3).asUser().content().asArrayOfContentParts();
        assertEquals(List.of("part one", "part two"), parts.stream().map(part -> part.asText().text()).toList());
        JsonNode wire = ObjectMappers.jsonMapper().valueToTree(params._body());
        assertEquals(0.3, wire.path("temperature").asDouble());
        assertEquals(0.8, wire.path("top_p").asDouble());
        assertEquals(0.1, wire.path("frequency_penalty").asDouble());
        assertEquals(0.2, wire.path("presence_penalty").asDouble());
        assertEquals(900, wire.path("max_completion_tokens").asInt());
        assertFalse(wire.has("max_tokens"));
        assertEquals("END", wire.at("/stop/0").asText());
        assertEquals("text", wire.at("/response_format/type").asText());

        clearInvocations(completions);
        model.chat("fresh question");
        var fresh = capturedRequest();
        assertEquals(1, fresh.messages().size());
        assertEquals("fresh question", fresh.messages().getFirst().asUser().content().asText());
        assertEquals(2000L, fresh.maxCompletionTokens().orElseThrow());
        assertTrue(fresh.temperature().isEmpty());
        assertTrue(fresh.stop().isEmpty());
    }

    @Test
    void supportsActualAgenticStringAgentsWithSystemAndUserTemplates() {
        when(completions.create(any(ChatCompletionCreateParams.class))).thenReturn(response("answer"));
        var model = new OfficialSdkChatModel(client, DEPLOYMENT, CACHE_SYSTEM_PREFIX);
        var agent = AgenticServices.agentBuilder(TextAgent.class).chatModel(model).build();
        var workflow = AgenticServices.sequenceBuilder().subAgents(agent)
                .name("Offline transport workflow").outputKey("answer").build();

        assertEquals("answer", workflow.invoke(Map.of("question", "render this question")));
        var params = capturedRequest();
        assertEquals(SYSTEM_PREFIX,
                params.messages().getFirst().asSystem().content().asArrayOfContentParts().getFirst().text());
        assertEquals("Question: render this question", params.messages().get(1).asUser().content().asText());
        assertEquals("tokenflow-policy-v1", params.promptCacheKey().orElseThrow());
    }

    public interface TextAgent {
        @Agent(name = "Offline transport contract", outputKey = "answer")
        @dev.langchain4j.service.SystemMessage(SYSTEM_PREFIX)
        @dev.langchain4j.service.UserMessage("Question: {{question}}")
        String answer(@V("question") String question);
    }

    @Test
    void deepTriageBoundsTheRecommendationWithoutIncreasingTheCompletionBudget() {
        String question = "Design a secure distributed multi-region architecture for a payment system, "
                + "including migration trade-offs.";
        String answer = "Decision frame: Prioritize a consistent ledger and authenticated regional APIs.\n\n"
                + "Next step: Migrate one shard at a time, trading temporary dual-run cost for a reversible cutover.\n\n"
                + "Validation: Require failover within 60 seconds with zero duplicate ledger postings.";
        when(completions.create(any(ChatCompletionCreateParams.class))).thenReturn(response(answer));
        var model = new OfficialSdkChatModel(client, "gpt-5.6-sol", BYPASS);
        var agent = AgenticServices.agentBuilder(PatternAgents.DeepReasoningResponder.class)
                .chatModel(model).build();
        var workflow = AgenticServices.sequenceBuilder().subAgents(agent)
                .name("Offline deep-triage transport").outputKey("answer").build();

        assertEquals(answer, workflow.invoke(Map.of("request", question)));
        var params = capturedRequest();
        assertEquals("gpt-5.6-sol", params.model().asString());
        assertEquals(2000L, params.maxCompletionTokens().orElseThrow());
        assertTrue(params.temperature().isEmpty());
        assertTrue(params.promptCacheKey().isEmpty());
        String prompt = params.messages().getFirst().asUser().content().asText();
        assertTrue(prompt.contains("at most 150 words"));
        assertTrue(prompt.contains("Decision frame, Next step, and Validation"));
        assertTrue(prompt.contains("cover the request's key constraints and one trade-off"));
        assertTrue(prompt.contains("give a measurable validation metric"));
        assertTrue(prompt.contains("Do not enumerate alternatives or implementation details."));
        assertTrue(prompt.contains("REQUEST: " + question));
    }

    @Test
    void returnsActualTotalsProviderMetadataAndTypedCacheAndReasoningSubsets() {
        var fixture = fixture("""
                {"prompt_tokens":1000,"completion_tokens":100,"total_tokens":1100,
                 "prompt_tokens_details":{"cached_tokens":800,"cache_write_tokens":100},
                 "completion_tokens_details":{"reasoning_tokens":40}}
                """);
        when(completions.create(any(ChatCompletionCreateParams.class))).thenReturn(fixture);
        var response = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS).chat(UserMessage.from("question"));

        assertEquals("offline-id", response.id());
        assertEquals("provider-medium-revision", response.modelName());
        assertEquals("answer", response.aiMessage().text());
        assertEquals(FinishReason.STOP, response.finishReason());
        assertEquals(new ProviderTokenUsage(1000, 100, 1100, 800, 100, 40), response.tokenUsage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            ",\"prompt_tokens_details\":null,\"completion_tokens_details\":null",
            ",\"prompt_tokens_details\":{},\"completion_tokens_details\":{}",
            ",\"prompt_tokens_details\":{\"cached_tokens\":null,\"cache_write_tokens\":null},"
                    + "\"completion_tokens_details\":{\"reasoning_tokens\":null}"
    })
    void absentOrNullSubsetCountersRemainUnknown(String details) {
        String usage = TOTALS.strip().replace("}", details + "}");
        when(completions.create(any(ChatCompletionCreateParams.class))).thenReturn(fixture(usage));
        var result = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS).chat(UserMessage.from("question"));
        assertEquals(new ProviderTokenUsage(1000, 100, 1100, null, null, null), result.tokenUsage());
    }

    @Test
    void preservesExplicitZeroIndependentlyFromOtherMissingCounters() {
        when(completions.create(any(ChatCompletionCreateParams.class))).thenReturn(fixture("""
                {"prompt_tokens":1000,"completion_tokens":100,"total_tokens":1100,
                 "prompt_tokens_details":{"cached_tokens":0},
                 "completion_tokens_details":{"reasoning_tokens":0}}
                """));
        var result = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS).chat(UserMessage.from("question"));
        assertEquals(new ProviderTokenUsage(1000, 100, 1100, 0, null, 0), result.tokenUsage());
    }

    @ParameterizedTest
    @MethodSource("malformedUsages")
    void malformedOrMissingTelemetryFailsInsteadOfInventingUsage(String usage, CapturedOutput output) {
        when(completions.create(any(ChatCompletionCreateParams.class))).thenReturn(fixture(usage));
        var model = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS);
        var failure = assertThrows(LangChain4jException.class, () -> model.chat("question"));
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("sensitive-provider-value"));
        assertFalse(output.getAll().contains("sensitive-provider-value"));
    }

    static Stream<String> malformedUsages() {
        return Stream.of(
                "null",
                "{}",
                "{\"completion_tokens\":100,\"total_tokens\":1100}",
                "{\"prompt_tokens\":1000,\"completion_tokens\":100}",
                "{\"prompt_tokens\":-1,\"completion_tokens\":100,\"total_tokens\":99}",
                "{\"prompt_tokens\":1000,\"completion_tokens\":-1,\"total_tokens\":999}",
                "{\"prompt_tokens\":1000,\"completion_tokens\":100,\"total_tokens\":100}",
                "{\"prompt_tokens\":2147483648,\"completion_tokens\":0,\"total_tokens\":2147483648}",
                "{\"prompt_tokens\":1.5,\"completion_tokens\":100,\"total_tokens\":101}",
                withDetails("\"cached_tokens\":-1", "\"reasoning_tokens\":0"),
                withDetails("\"cache_write_tokens\":-1", "\"reasoning_tokens\":0"),
                withDetails("\"cached_tokens\":800,\"cache_write_tokens\":201", "\"reasoning_tokens\":0"),
                withDetails("\"cache_write_tokens\":1001", "\"reasoning_tokens\":0"),
                withDetails("\"cached_tokens\":1001", "\"reasoning_tokens\":0"),
                withDetails("\"cached_tokens\":0", "\"reasoning_tokens\":101"),
                withDetails("\"cached_tokens\":0", "\"reasoning_tokens\":-1"),
                withDetails("\"cached_tokens\":2147483648", "\"reasoning_tokens\":0"),
                withDetails("\"cached_tokens\":\"sensitive-provider-value\"", "\"reasoning_tokens\":0"),
                withDetails("\"cache_write_tokens\":\"sensitive-provider-value\"", "\"reasoning_tokens\":0"),
                withDetails("\"cached_tokens\":0", "\"reasoning_tokens\":\"sensitive-provider-value\""));
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void missingRefusedOrInvalidContentIsNotSuccessful(ChatCompletion response) {
        when(completions.create(any(ChatCompletionCreateParams.class))).thenReturn(response);
        var model = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS);
        var failure = assertThrows(LangChain4jException.class, () -> model.chat("question"));
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("sensitive-provider-value"));
    }

    static Stream<ChatCompletion> invalidResponses() {
        var response = response("answer");
        var choice = response.choices().getFirst();
        return Stream.of(
                null,
                response.toBuilder().choices(List.of()).build(),
                response.toBuilder().addChoice(choice).build(),
                response.toBuilder().id(" ").build(),
                response.toBuilder().model("").build(),
                response.toBuilder().choices(List.of(choice.toBuilder().index(1).build())).build(),
                response(" \n "),
                response.toBuilder().choices(List.of(choice.toBuilder()
                        .message(ChatCompletionMessage.builder()
                                .content(Optional.empty()).refusal(Optional.empty()).build()).build())).build(),
                response.toBuilder().choices(List.of(choice.toBuilder()
                        .message(ChatCompletionMessage.builder().content("answer")
                                .refusal("sensitive-provider-value").build()).build())).build(),
                response.toBuilder().choices(List.of(choice.toBuilder()
                        .finishReason(ChatCompletion.Choice.FinishReason.TOOL_CALLS).build())).build(),
                response.toBuilder().choices(List.of(choice.toBuilder()
                        .finishReason(ChatCompletion.Choice.FinishReason.of("sensitive-provider-value")).build())).build());
    }

    @ParameterizedTest
    @MethodSource("incompleteFinishReasons")
    void rejectsFilteredAndTruncatedResponsesEvenWithPartialContent(String reason, String content) {
        var response = response(content);
        response = response.toBuilder().choices(List.of(response.choices().getFirst().toBuilder()
                .finishReason(ChatCompletion.Choice.FinishReason.of(reason)).build())).build();
        when(completions.create(any(ChatCompletionCreateParams.class))).thenReturn(response);
        var model = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS);

        var failure = assertThrows(LangChain4jException.class, () -> model.chat("question"));
        if ("content_filter".equals(reason)) {
            assertInstanceOf(ContentFilteredException.class, failure);
            assertEquals("Azure OpenAI blocked the response with its content filter", failure.getMessage());
        } else {
            assertInstanceOf(ModelOutputLimitException.class, failure);
            assertEquals("Azure OpenAI response was truncated because the completion token limit was reached",
                    failure.getMessage());
        }
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("sensitive-provider-value"));
    }

    static Stream<Arguments> incompleteFinishReasons() {
        return Stream.of("length", "content_filter").flatMap(reason ->
                Stream.of("partial sensitive-provider-value", "", " \n ", null)
                        .map(content -> Arguments.of(reason, content)));
    }

    @ParameterizedTest
    @MethodSource("unsupportedParameters")
    void explicitlyRejectsUnsupportedParametersBeforeCallingSdk(ChatRequestParameters parameters) {
        var model = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS);
        var request = ChatRequest.builder().messages(UserMessage.from("question")).parameters(parameters).build();
        assertThrows(IllegalArgumentException.class, () -> model.chat(request));
        verifyNoInteractions(completions);
    }

    static Stream<ChatRequestParameters> unsupportedParameters() {
        return Stream.of(
                ChatRequestParameters.builder().topK(4).build(),
                ChatRequestParameters.builder().responseFormat(ResponseFormat.JSON).build(),
                ChatRequestParameters.builder().toolChoice(ToolChoice.REQUIRED).build(),
                ChatRequestParameters.builder().toolSpecifications(
                        ToolSpecification.builder().name("lookup").description("unsupported tool").build()).build(),
                ChatRequestParameters.builder().modelName("another-deployment").build(),
                ChatRequestParameters.builder().maxOutputTokens(0).build(),
                mock(ChatRequestParameters.class));
    }

    @ParameterizedTest
    @MethodSource("unsupportedMessages")
    void explicitlyRejectsNonTextAndToolMessagesBeforeCallingSdk(ChatMessage message) {
        var model = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS);
        assertThrows(IllegalArgumentException.class, () -> model.chat(message));
        verifyNoInteractions(completions);
    }

    static Stream<ChatMessage> unsupportedMessages() {
        return Stream.of(
                UserMessage.from(ImageContent.from("https://offline.invalid/image.png")),
                UserMessage.builder().contents(List.of(TextContent.from("text")))
                        .attributes(Map.of("unsupported", true)).build(),
                AiMessage.from(ToolExecutionRequest.builder().id("call-1").name("lookup").arguments("{}").build()),
                AiMessage.builder().text("answer").thinking("private reasoning").build(),
                AiMessage.builder().text("answer").attributes(Map.of("unsupported", true)).build(),
                ToolExecutionResultMessage.from("call-1", "lookup", "result"));
    }

    @Test
    void cachedModeRequiresExactlyOneLeadingSystemPrefix() {
        var model = new OfficialSdkChatModel(client, DEPLOYMENT, CACHE_SYSTEM_PREFIX);
        assertThrows(IllegalArgumentException.class, () -> model.chat(UserMessage.from("question")));
        assertThrows(IllegalArgumentException.class,
                () -> model.chat(UserMessage.from("question"), SystemMessage.from(SYSTEM_PREFIX)));
        assertThrows(IllegalArgumentException.class, () -> model.chat(
                SystemMessage.from(SYSTEM_PREFIX), SystemMessage.from("more instructions"), UserMessage.from("question")));
        verifyNoInteractions(completions);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 400, 401, 403, 408, 500, 503})
    void mapsHttpFailuresWithoutRetainingSdkMessagesOrCauses(int status) {
        var sdkFailure = mock(OpenAIServiceException.class);
        when(sdkFailure.statusCode()).thenReturn(status);
        when(sdkFailure.getMessage()).thenReturn("sensitive-provider-value");
        doThrow(sdkFailure).when(completions).create(any(ChatCompletionCreateParams.class));

        var model = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS);
        var failure = assertThrows(LangChain4jException.class, () -> model.chat("question"));
        if (status == 429) {
            assertInstanceOf(RateLimitException.class, failure);
        } else {
            assertEquals(status, assertInstanceOf(HttpException.class, failure).statusCode());
        }
        assertFalse(failure.getMessage().contains("sensitive-provider-value"));
        assertNull(failure.getCause());
    }

    @Test
    void mapsConnectionTimeoutParsingAndCredentialFailuresToSanitizedProviderFailures(CapturedOutput output) {
        var model = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS);
        for (RuntimeException sdkFailure : List.of(new OpenAIException("sensitive-provider-value"),
                new IllegalStateException("sensitive-provider-value"))) {
            doThrow(sdkFailure).when(completions).create(any(ChatCompletionCreateParams.class));
            var failure = assertThrows(LangChain4jException.class, () -> model.chat("question"));
            assertEquals("Azure OpenAI request could not be completed", failure.getMessage());
            assertNull(failure.getCause());
        }
        assertTrue(output.getAll().contains("errorType=OpenAIException"));
        assertTrue(output.getAll().contains("errorType=IllegalStateException"));
        assertFalse(output.getAll().contains("sensitive-provider-value"));
    }

    @Test
    void concurrentCachedAndBypassRequestsKeepMessagesOptionsResponsesAndUsageIsolated() throws Exception {
        var captured = new ConcurrentLinkedQueue<ChatCompletionCreateParams>();
        when(completions.create(any(ChatCompletionCreateParams.class))).thenAnswer(invocation -> {
            ChatCompletionCreateParams params = invocation.getArgument(0);
            captured.add(params);
            String question = params.messages().get(1).asUser().content().asText();
            int number = Integer.parseInt(question);
            return response("answer " + question).toBuilder().id("id-" + question)
                    .usage(CompletionUsage.builder().promptTokens(number + 10L)
                            .completionTokens(2).totalTokens(number + 12L).build()).build();
        });
        var cached = new OfficialSdkChatModel(client, DEPLOYMENT, CACHE_SYSTEM_PREFIX);
        var bypass = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS);
        List<Callable<ChatResponse>> tasks = IntStream.range(0, 32)
                .<Callable<ChatResponse>>mapToObj(number -> () -> (number % 2 == 0 ? cached : bypass)
                        .chat(SystemMessage.from(SYSTEM_PREFIX), UserMessage.from(Integer.toString(number))))
                .toList();

        try (var executor = Executors.newFixedThreadPool(4)) {
            var results = executor.invokeAll(tasks, 10, TimeUnit.SECONDS);
            for (int number = 0; number < results.size(); number++) {
                var result = results.get(number).get();
                assertEquals("answer " + number, result.aiMessage().text());
                assertEquals("id-" + number, result.id());
                assertEquals(number + 12, result.tokenUsage().totalTokenCount());
            }
        }
        assertEquals(32, captured.size());
        for (var params : captured) {
            assertEquals(2, params.messages().size());
            int number = Integer.parseInt(params.messages().get(1).asUser().content().asText());
            assertEquals(number % 2 == 0, params.promptCacheKey().isPresent());
            assertEquals(number % 2 == 0, params.messages().getFirst().asSystem().content()
                    .asArrayOfContentParts().getFirst().promptCacheBreakpoint().isPresent());
        }
        verify(client, never()).close();
    }

    private ChatCompletionCreateParams capturedRequest() {
        var captor = ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        verify(completions).create(captor.capture());
        return captor.getValue();
    }

    private static ChatCompletion response(String text) {
        return ChatCompletion.builder().id("offline-id").model("provider-medium-revision").created(0)
                .addChoice(ChatCompletion.Choice.builder().index(0).finishReason(ChatCompletion.Choice.FinishReason.STOP)
                        .logprobs(Optional.empty())
                        .message(ChatCompletionMessage.builder()
                                .content(Optional.ofNullable(text)).refusal(Optional.empty()).build()).build())
                .usage(CompletionUsage.builder().promptTokens(1000).completionTokens(100).totalTokens(1100).build())
                .build();
    }

    private static String withDetails(String prompt, String completion) {
        return """
                {"prompt_tokens":1000,"completion_tokens":100,"total_tokens":1100,
                 "prompt_tokens_details":{%s},"completion_tokens_details":{%s}}
                """.formatted(prompt, completion);
    }

    private static ChatCompletion fixture(String usage) {
        try {
            return ObjectMappers.jsonMapper().readValue("""
                    {"id":"offline-id","object":"chat.completion","created":0,"model":"provider-medium-revision",
                     "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"answer"}}],
                     "usage":%s}
                    """.formatted(usage), ChatCompletion.class);
        } catch (Exception exception) {
            throw new AssertionError("Could not read offline response fixture", exception);
        }
    }
}
