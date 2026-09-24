package com.example.tokenpatterns.agent;

import com.example.tokenpatterns.domain.ModelOutputLimitException;
import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.OpenAIClient;
import com.openai.core.ObjectMappers;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputText;
import com.openai.services.blocking.ResponseService;
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
            {"input_tokens":1000,"output_tokens":100,"total_tokens":1100}
            """;
    // Reasoning deployments return a reasoning item before the answer message.
    private static final String REASONING_ITEM = """
            {"type":"reasoning","id":"rs-offline","summary":[]}""";

    private OpenAIClient client;
    private ResponseService responses;

    @BeforeEach
    void mockSdkBoundary() {
        client = mock(OpenAIClient.class);
        responses = mock(ResponseService.class);
        when(client.responses()).thenReturn(responses);
    }

    @Test
    void placesExplicitCacheBreakpointOnlyOnStableSystemTextAndReusesVersionedKey() {
        when(responses.create(any(ResponseCreateParams.class)))
                .thenReturn(response("first fresh answer"), response("second fresh answer"));
        var model = new OfficialSdkChatModel(client, DEPLOYMENT, CACHE_SYSTEM_PREFIX);

        var first = model.chat(SystemMessage.from(SYSTEM_PREFIX), UserMessage.from("first question"));
        var second = model.chat(SystemMessage.from(SYSTEM_PREFIX), UserMessage.from("different question"));
        assertEquals("first fresh answer", first.aiMessage().text());
        assertEquals("second fresh answer", second.aiMessage().text());

        var captured = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(responses, times(2)).create(captured.capture());
        for (var params : captured.getAllValues()) {
            assertEquals(DEPLOYMENT, params.model().orElseThrow().asString());
            assertEquals(2000L, params.maxOutputTokens().orElseThrow());
            assertFalse(params.store().orElseThrow(), "Responses must not be stored by the service");
            assertTrue(params.temperature().isEmpty(), "Do not introduce a temperature default");
            assertEquals("tokenflow-policy-v1", params.promptCacheKey().orElseThrow());
            assertEquals(ResponseCreateParams.PromptCacheOptions.Mode.EXPLICIT,
                    params.promptCacheOptions().orElseThrow().mode().orElseThrow());
            assertEquals(ResponseCreateParams.PromptCacheOptions.Ttl._30M,
                    params.promptCacheOptions().orElseThrow().ttl().orElseThrow());
            assertEquals(EasyInputMessage.Role.SYSTEM, input(params, 0).role());
            var systemText = systemText(params);
            assertEquals(SYSTEM_PREFIX, systemText.text());
            assertTrue(systemText.promptCacheBreakpoint().isPresent());
            systemText.promptCacheBreakpoint().orElseThrow().validate();

            // Inspect the SDK's serialized typed body, not a handcrafted request or additional properties.
            JsonNode wire = wire(params);
            assertEquals("explicit", wire.at("/prompt_cache_options/mode").asText());
            assertEquals("30m", wire.at("/prompt_cache_options/ttl").asText());
            assertEquals("system", wire.at("/input/0/role").asText());
            assertEquals("input_text", wire.at("/input/0/content/0/type").asText());
            assertEquals("explicit", wire.at("/input/0/content/0/prompt_cache_breakpoint/mode").asText());
            assertFalse(wire.has("prompt_cache_breakpoint"));
            assertFalse(wire.at("/input/0").has("prompt_cache_breakpoint"));
            assertFalse(wire.at("/input/1").has("prompt_cache_breakpoint"));
            assertEquals(1, wire.findValues("prompt_cache_breakpoint").size());
            assertFalse(wire.path("store").asBoolean(true));
            assertEquals(2000, wire.path("max_output_tokens").asInt());
            assertFalse(wire.has("max_completion_tokens"));
            assertFalse(wire.has("messages"));
        }
        assertEquals("first question", userText(captured.getAllValues().get(0), 1));
        assertEquals("different question", userText(captured.getAllValues().get(1), 1));
    }

    @Test
    void bypassIsExplicitAndNeverAddsKeyTtlOrBreakpoint() {
        when(responses.create(any(ResponseCreateParams.class))).thenReturn(response("answer"));
        var model = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS);
        model.chat(SystemMessage.from(SYSTEM_PREFIX), UserMessage.from("question"));

        var params = capturedRequest();
        assertEquals(ResponseCreateParams.PromptCacheOptions.Mode.EXPLICIT,
                params.promptCacheOptions().orElseThrow().mode().orElseThrow());
        assertTrue(params.promptCacheKey().isEmpty());
        assertTrue(params.promptCacheOptions().orElseThrow().ttl().isEmpty());
        assertFalse(params.store().orElseThrow());
        assertEquals(SYSTEM_PREFIX, systemText(params).text());
        assertTrue(wire(params).findValues("prompt_cache_breakpoint").isEmpty());
        assertEquals(ModelProvider.AZURE_OPEN_AI, model.provider());
    }

    @Test
    void mapsTextHistoryAndSupportedCommonParametersWithoutRetainingConversation() {
        when(responses.create(any(ResponseCreateParams.class))).thenReturn(response("answer"));
        var model = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS);
        var request = ChatRequest.builder()
                .messages(SystemMessage.from("instructions"),
                        UserMessage.from("prior question"),
                        AiMessage.from("prior answer"),
                        UserMessage.from(TextContent.from("part one"), TextContent.from("part two")))
                .parameters(ChatRequestParameters.builder().modelName(DEPLOYMENT)
                        .temperature(0.3).topP(0.8).maxOutputTokens(900).responseFormat(ResponseFormat.TEXT)
                        .build())
                .build();

        model.chat(request);
        var params = capturedRequest();
        assertEquals(4, params.input().orElseThrow().asResponse().size());
        assertEquals(EasyInputMessage.Role.USER, input(params, 1).role());
        assertEquals("prior question", userText(params, 1));
        assertEquals(EasyInputMessage.Role.ASSISTANT, input(params, 2).role());
        assertEquals("prior answer", userText(params, 2));
        var parts = input(params, 3).content().asResponseInputMessageContentList();
        assertEquals(List.of("part one", "part two"), parts.stream().map(part -> part.asInputText().text()).toList());
        JsonNode wire = wire(params);
        assertEquals(0.3, wire.path("temperature").asDouble());
        assertEquals(0.8, wire.path("top_p").asDouble());
        assertEquals(900, wire.path("max_output_tokens").asInt());
        assertFalse(wire.has("max_completion_tokens"));
        assertEquals("text", wire.at("/text/format/type").asText());
        assertEquals("message", wire.at("/input/2/type").asText());

        clearInvocations(responses);
        model.chat("fresh question");
        var fresh = capturedRequest();
        assertEquals(1, fresh.input().orElseThrow().asResponse().size());
        assertEquals("fresh question", userText(fresh, 0));
        assertEquals(2000L, fresh.maxOutputTokens().orElseThrow());
        assertTrue(fresh.temperature().isEmpty());
        assertTrue(fresh.text().isEmpty());
    }

    @Test
    void supportsActualAgenticStringAgentsWithSystemAndUserTemplates() {
        when(responses.create(any(ResponseCreateParams.class))).thenReturn(response("answer"));
        var model = new OfficialSdkChatModel(client, DEPLOYMENT, CACHE_SYSTEM_PREFIX);
        var agent = AgenticServices.agentBuilder(TextAgent.class).chatModel(model).build();
        var workflow = AgenticServices.sequenceBuilder().subAgents(agent)
                .name("Offline transport workflow").outputKey("answer").build();

        assertEquals("answer", workflow.invoke(Map.of("question", "render this question")));
        var params = capturedRequest();
        assertEquals(SYSTEM_PREFIX, systemText(params).text());
        assertEquals("Question: render this question", userText(params, 1));
        assertEquals("tokenflow-policy-v1", params.promptCacheKey().orElseThrow());
    }

    public interface TextAgent {
        @Agent(name = "Offline transport contract", outputKey = "answer")
        @dev.langchain4j.service.SystemMessage(SYSTEM_PREFIX)
        @dev.langchain4j.service.UserMessage("Question: {{question}}")
        String answer(@V("question") String question);
    }

    @Test
    void deepTriageBoundsTheRecommendationWithoutIncreasingTheOutputBudget() {
        String question = "Design a secure distributed multi-region architecture for a payment system, "
                + "including migration trade-offs.";
        String answer = "Decision frame: Prioritize a consistent ledger and authenticated regional APIs.\n\n"
                + "Next step: Migrate one shard at a time, trading temporary dual-run cost for a reversible cutover.\n\n"
                + "Validation: Require failover within 60 seconds with zero duplicate ledger postings.";
        when(responses.create(any(ResponseCreateParams.class))).thenReturn(response(answer));
        var model = new OfficialSdkChatModel(client, "gpt-5.6-sol", BYPASS);
        var agent = AgenticServices.agentBuilder(PatternAgents.DeepReasoningResponder.class)
                .chatModel(model).build();
        var workflow = AgenticServices.sequenceBuilder().subAgents(agent)
                .name("Offline deep-triage transport").outputKey("answer").build();

        assertEquals(answer, workflow.invoke(Map.of("request", question)));
        var params = capturedRequest();
        assertEquals("gpt-5.6-sol", params.model().orElseThrow().asString());
        assertEquals(2000L, params.maxOutputTokens().orElseThrow());
        assertTrue(params.temperature().isEmpty());
        assertTrue(params.promptCacheKey().isEmpty());
        String prompt = userText(params, 0);
        assertTrue(prompt.contains("at most 150 words"));
        assertTrue(prompt.contains("Decision frame, Next step, and Validation"));
        assertTrue(prompt.contains("cover the request's key constraints and one trade-off"));
        assertTrue(prompt.contains("give a measurable validation metric"));
        assertTrue(prompt.contains("Do not enumerate alternatives or implementation details."));
        assertTrue(prompt.contains("REQUEST: " + question));
    }

    @Test
    void returnsActualTotalsProviderMetadataAndTypedCacheAndReasoningSubsets() {
        when(responses.create(any(ResponseCreateParams.class))).thenReturn(fixture("""
                {"input_tokens":1000,"output_tokens":100,"total_tokens":1100,
                 "input_tokens_details":{"cached_tokens":800,"cache_write_tokens":100},
                 "output_tokens_details":{"reasoning_tokens":40}}
                """));
        var response = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS).chat(UserMessage.from("question"));

        assertEquals("offline-id", response.id());
        assertEquals("provider-medium-revision", response.modelName());
        assertEquals("answer", response.aiMessage().text());
        assertEquals(FinishReason.STOP, response.finishReason());
        assertEquals(new ProviderTokenUsage(1000, 100, 1100, 800, 100, 40), response.tokenUsage());
    }

    @Test
    void joinsEveryOutputTextPartOfTheSingleAnswerMessage() {
        when(responses.create(any(ResponseCreateParams.class))).thenReturn(responseWith("completed", message("completed",
                "{\"type\":\"output_text\",\"text\":\"first \",\"annotations\":[]},"
                        + "{\"type\":\"output_text\",\"text\":\"second\",\"annotations\":[]}"), TOTALS, ""));
        var response = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS).chat(UserMessage.from("question"));
        assertEquals("first second", response.aiMessage().text());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            ",\"input_tokens_details\":null,\"output_tokens_details\":null",
            ",\"input_tokens_details\":{},\"output_tokens_details\":{}",
            ",\"input_tokens_details\":{\"cached_tokens\":null,\"cache_write_tokens\":null},"
                    + "\"output_tokens_details\":{\"reasoning_tokens\":null}"
    })
    void absentOrNullSubsetCountersRemainUnknown(String details) {
        String usage = TOTALS.strip().replace("}", details + "}");
        when(responses.create(any(ResponseCreateParams.class))).thenReturn(fixture(usage));
        var result = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS).chat(UserMessage.from("question"));
        assertEquals(new ProviderTokenUsage(1000, 100, 1100, null, null, null), result.tokenUsage());
    }

    @Test
    void preservesExplicitZeroIndependentlyFromOtherMissingCounters() {
        when(responses.create(any(ResponseCreateParams.class))).thenReturn(fixture("""
                {"input_tokens":1000,"output_tokens":100,"total_tokens":1100,
                 "input_tokens_details":{"cached_tokens":0},
                 "output_tokens_details":{"reasoning_tokens":0}}
                """));
        var result = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS).chat(UserMessage.from("question"));
        assertEquals(new ProviderTokenUsage(1000, 100, 1100, 0, null, 0), result.tokenUsage());
    }

    @ParameterizedTest
    @MethodSource("malformedUsages")
    void malformedOrMissingTelemetryFailsInsteadOfInventingUsage(String usage, CapturedOutput output) {
        when(responses.create(any(ResponseCreateParams.class))).thenReturn(fixture(usage));
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
                "{\"output_tokens\":100,\"total_tokens\":1100}",
                "{\"input_tokens\":1000,\"output_tokens\":100}",
                "{\"input_tokens\":-1,\"output_tokens\":100,\"total_tokens\":99}",
                "{\"input_tokens\":1000,\"output_tokens\":-1,\"total_tokens\":999}",
                "{\"input_tokens\":1000,\"output_tokens\":100,\"total_tokens\":100}",
                "{\"input_tokens\":2147483648,\"output_tokens\":0,\"total_tokens\":2147483648}",
                "{\"input_tokens\":1.5,\"output_tokens\":100,\"total_tokens\":101}",
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
                withDetails("\"cached_tokens\":0", "\"reasoning_tokens\":\"sensitive-provider-value\""),
                TOTALS.strip().replace("}", ",\"input_tokens_details\":\"sensitive-provider-value\"}"),
                TOTALS.strip().replace("}", ",\"output_tokens_details\":[\"sensitive-provider-value\"]}"));
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void missingRefusedOrInvalidContentIsNotSuccessful(Response response) {
        when(responses.create(any(ResponseCreateParams.class))).thenReturn(response);
        var model = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS);
        var failure = assertThrows(LangChain4jException.class, () -> model.chat("question"));
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("sensitive-provider-value"));
    }

    static Stream<Response> invalidResponses() {
        String answer = message("completed", outputText("answer"));
        return Stream.of(
                null,
                responseWith("completed", "", TOTALS, ""),
                responseWith("completed", REASONING_ITEM, TOTALS, ""),
                responseWith("completed", answer + "," + answer, TOTALS, ""),
                parse(json("completed", answer, TOTALS, "").replace("\"offline-id\"", "\" \"")),
                parse(json("completed", answer, TOTALS, "").replace("\"provider-medium-revision\"", "\"\"")),
                parse(json("completed", answer, TOTALS, "").replace("\"id\":\"offline-id\",", "")),
                responseWith("completed", message("completed", outputText(" \n ")), TOTALS, ""),
                responseWith("completed", message("completed", ""), TOTALS, ""),
                responseWith("completed", message("completed", "{\"type\":\"output_text\",\"annotations\":[]}"), TOTALS, ""),
                responseWith("completed", message("completed",
                        outputText("answer") + ",{\"type\":\"refusal\",\"refusal\":\"sensitive-provider-value\"}"), TOTALS, ""),
                responseWith("completed", message("completed", "{\"type\":\"sensitive-provider-value\"}"), TOTALS, ""),
                responseWith("completed", message("in_progress", outputText("answer")), TOTALS, ""),
                responseWith("completed", answer + ",{\"type\":\"function_call\",\"id\":\"fc-1\",\"call_id\":\"call-1\","
                        + "\"name\":\"lookup\",\"arguments\":\"{}\",\"status\":\"completed\"}", TOTALS, ""),
                responseWith("completed", answer + ",{\"type\":\"sensitive-provider-value\"}", TOTALS, ""),
                responseWith("completed", answer, TOTALS,
                        ",\"error\":{\"code\":\"server_error\",\"message\":\"sensitive-provider-value\"}"),
                responseWith("failed", answer, TOTALS,
                        ",\"error\":{\"code\":\"server_error\",\"message\":\"sensitive-provider-value\"}"),
                responseWith("in_progress", answer, TOTALS, ""),
                responseWith("sensitive-provider-value", answer, TOTALS, ""),
                parse(json("completed", answer, TOTALS, "").replace("\"status\":\"completed\",\"model\"", "\"model\"")),
                responseWith("incomplete", answer, TOTALS, ""),
                responseWith("incomplete", answer, TOTALS, ",\"incomplete_details\":{\"reason\":\"sensitive-provider-value\"}"));
    }

    @ParameterizedTest
    @MethodSource("incompleteReasons")
    void rejectsFilteredAndTruncatedResponsesEvenWithPartialContent(String reason, String content) {
        String output = content == null ? "" : message("incomplete", outputText(content));
        when(responses.create(any(ResponseCreateParams.class))).thenReturn(responseWith("incomplete", output, TOTALS,
                ",\"incomplete_details\":{\"reason\":\"" + reason + "\"}"));
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

    static Stream<Arguments> incompleteReasons() {
        return Stream.of("max_output_tokens", "content_filter").flatMap(reason ->
                Stream.of("partial sensitive-provider-value", "", " \n ", null)
                        .map(content -> Arguments.of(reason, content)));
    }

    @ParameterizedTest
    @MethodSource("unsupportedParameters")
    void explicitlyRejectsUnsupportedParametersBeforeCallingSdk(ChatRequestParameters parameters) {
        var model = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS);
        var request = ChatRequest.builder().messages(UserMessage.from("question")).parameters(parameters).build();
        assertThrows(IllegalArgumentException.class, () -> model.chat(request));
        verifyNoInteractions(responses);
    }

    static Stream<ChatRequestParameters> unsupportedParameters() {
        return Stream.of(
                ChatRequestParameters.builder().topK(4).build(),
                ChatRequestParameters.builder().frequencyPenalty(0.1).build(),
                ChatRequestParameters.builder().presencePenalty(0.2).build(),
                ChatRequestParameters.builder().stopSequences(List.of("END")).build(),
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
    void explicitlyRejectsNonTextNamedAndToolMessagesBeforeCallingSdk(ChatMessage message) {
        var model = new OfficialSdkChatModel(client, DEPLOYMENT, BYPASS);
        assertThrows(IllegalArgumentException.class, () -> model.chat(message));
        verifyNoInteractions(responses);
    }

    static Stream<ChatMessage> unsupportedMessages() {
        return Stream.of(
                UserMessage.from(ImageContent.from("https://offline.invalid/image.png")),
                UserMessage.from("learner", "a named question"),
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
        verifyNoInteractions(responses);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 400, 401, 403, 408, 500, 503})
    void mapsHttpFailuresWithoutRetainingSdkMessagesOrCauses(int status) {
        var sdkFailure = mock(OpenAIServiceException.class);
        when(sdkFailure.statusCode()).thenReturn(status);
        when(sdkFailure.getMessage()).thenReturn("sensitive-provider-value");
        doThrow(sdkFailure).when(responses).create(any(ResponseCreateParams.class));

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
            doThrow(sdkFailure).when(responses).create(any(ResponseCreateParams.class));
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
        var captured = new ConcurrentLinkedQueue<ResponseCreateParams>();
        when(responses.create(any(ResponseCreateParams.class))).thenAnswer(invocation -> {
            ResponseCreateParams params = invocation.getArgument(0);
            captured.add(params);
            String question = userText(params, 1);
            int number = Integer.parseInt(question);
            return parse(json("completed", message("completed", outputText("answer " + question)),
                    "{\"input_tokens\":%d,\"output_tokens\":2,\"total_tokens\":%d}".formatted(number + 10, number + 12), "")
                    .replace("\"offline-id\"", "\"id-" + question + "\""));
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
            assertEquals(2, params.input().orElseThrow().asResponse().size());
            int number = Integer.parseInt(userText(params, 1));
            assertEquals(number % 2 == 0, params.promptCacheKey().isPresent());
            assertEquals(number % 2 == 0, systemText(params).promptCacheBreakpoint().isPresent());
        }
        verify(client, never()).close();
    }

    private ResponseCreateParams capturedRequest() {
        var captor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(responses).create(captor.capture());
        return captor.getValue();
    }

    private static EasyInputMessage input(ResponseCreateParams params, int index) {
        return params.input().orElseThrow().asResponse().get(index).asEasyInputMessage();
    }

    private static String userText(ResponseCreateParams params, int index) {
        return input(params, index).content().asTextInput();
    }

    private static ResponseInputText systemText(ResponseCreateParams params) {
        var content = input(params, 0).content().asResponseInputMessageContentList();
        assertEquals(1, content.size());
        return content.getFirst().asInputText();
    }

    private static JsonNode wire(ResponseCreateParams params) {
        return ObjectMappers.jsonMapper().valueToTree(params._body());
    }

    private static Response response(String text) {
        return responseWith("completed", REASONING_ITEM + "," + message("completed", outputText(text)), TOTALS, "");
    }

    private static Response fixture(String usage) {
        return responseWith("completed", REASONING_ITEM + "," + message("completed", outputText("answer")), usage, "");
    }

    private static String outputText(String text) {
        return "{\"type\":\"output_text\",\"text\":\"%s\",\"annotations\":[]}".formatted(text.replace("\n", "\\n"));
    }

    private static String message(String status, String content) {
        return """
                {"type":"message","id":"msg-offline","role":"assistant","status":"%s","content":[%s]}"""
                .formatted(status, content);
    }

    private static String withDetails(String input, String output) {
        return """
                {"input_tokens":1000,"output_tokens":100,"total_tokens":1100,
                 "input_tokens_details":{%s},"output_tokens_details":{%s}}
                """.formatted(input, output);
    }

    private static String json(String status, String output, String usage, String extra) {
        return """
                {"id":"offline-id","object":"response","created_at":0,"status":"%s","model":"provider-medium-revision",
                 "output":[%s],"usage":%s%s}
                """.formatted(status, output, usage, extra);
    }

    private static Response responseWith(String status, String output, String usage, String extra) {
        return parse(json(status, output, usage, extra));
    }

    private static Response parse(String json) {
        try {
            return ObjectMappers.jsonMapper().readValue(json, Response.class);
        } catch (Exception exception) {
            throw new AssertionError("Could not read offline response fixture", exception);
        }
    }
}
