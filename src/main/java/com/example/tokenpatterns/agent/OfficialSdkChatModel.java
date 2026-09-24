package com.example.tokenpatterns.agent;

import com.example.tokenpatterns.domain.ModelOutputLimitException;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonField;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.ResponseFormatText;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseCreateParams.PromptCacheOptions;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.ResponseUsage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The lab's stateless text transport over the Responses API. Client ownership belongs to {@link ModelCatalog};
 * every invocation builds its own immutable SDK request and asks the service not to store it.
 * Azure reliably serves a prefix written by one Responses request to the next request with the same
 * prompt cache key, which Chat Completions requests spread across replicas did not.
 */
public final class OfficialSdkChatModel implements ChatModel {

    public enum PromptCacheMode {
        BYPASS,
        CACHE_SYSTEM_PREFIX
    }

    private static final String PROMPT_CACHE_KEY = "tokenflow-policy-v1";
    private static final int MAX_OUTPUT_TOKENS = 2000;
    private static final Logger LOGGER = LoggerFactory.getLogger(OfficialSdkChatModel.class);

    private final OpenAIClient client;
    private final String deployment;
    private final PromptCacheMode cacheMode;
    private final ChatRequestParameters defaults;

    public OfficialSdkChatModel(OpenAIClient client, String deployment, PromptCacheMode cacheMode) {
        this.client = Objects.requireNonNull(client, "client");
        if (deployment == null || deployment.isBlank()) {
            throw new IllegalArgumentException("Azure OpenAI deployment must not be blank");
        }
        this.deployment = deployment;
        this.cacheMode = Objects.requireNonNull(cacheMode, "cacheMode");
        this.defaults = ChatRequestParameters.builder()
                .modelName(deployment)
                // The budget includes reasoning tokens.
                .maxOutputTokens(MAX_OUTPUT_TOKENS)
                .build();
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return defaults;
    }

    @Override
    public ModelProvider provider() {
        return ModelProvider.AZURE_OPEN_AI;
    }

    @Override
    public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
        // Validate before LangChain4j merges defaults, which could erase custom parameters.
        validateParameters(request.parameters());
        return ChatModel.super.chat(request, options);
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        ResponseCreateParams params = toSdkRequest(request);
        Response response;
        try {
            response = client.responses().create(params);
        } catch (OpenAIServiceException exception) {
            // Do not retain SDK messages, bodies or causes: they can contain credentials/prompts.
            if (exception.statusCode() == 429) {
                throw new RateLimitException("Azure OpenAI rate limit reached");
            }
            throw new HttpException(exception.statusCode(),
                    "Azure OpenAI request failed (HTTP " + exception.statusCode() + ")");
        } catch (RuntimeException exception) {
            LOGGER.warn("Azure OpenAI request failed: errorType={}", exception.getClass().getSimpleName());
            throw new LangChain4jException("Azure OpenAI request could not be completed");
        }
        return fromSdkResponse(response);
    }

    private ResponseCreateParams toSdkRequest(ChatRequest request) {
        ChatRequestParameters parameters = request.parameters();
        validateParameters(parameters);
        var options = PromptCacheOptions.builder().mode(PromptCacheOptions.Mode.EXPLICIT);
        var builder = ResponseCreateParams.builder()
                .model(deployment)
                .store(false)
                .maxOutputTokens(parameters.maxOutputTokens() == null
                        ? MAX_OUTPUT_TOKENS : parameters.maxOutputTokens());
        boolean caching = cacheMode == PromptCacheMode.CACHE_SYSTEM_PREFIX;
        if (caching) {
            if (!(request.messages().getFirst() instanceof SystemMessage)
                    || request.messages().stream().filter(SystemMessage.class::isInstance).count() != 1) {
                throw new IllegalArgumentException(
                        "Provider prompt caching requires exactly one leading system message");
            }
            options.ttl(PromptCacheOptions.Ttl._30M);
            builder.promptCacheKey(PROMPT_CACHE_KEY);
        }
        // EXPLICIT without any breakpoint bypasses the provider cache, rather than using implicit caching.
        builder.promptCacheOptions(options.build());
        List<ResponseInputItem> input = new ArrayList<>();
        for (ChatMessage message : request.messages()) {
            if (message instanceof SystemMessage system) {
                var text = ResponseInputText.builder().text(system.text());
                if (caching) {
                    text.promptCacheBreakpoint(ResponseInputText.PromptCacheBreakpoint.builder().build());
                }
                input.add(message(EasyInputMessage.Role.SYSTEM,
                        EasyInputMessage.Content.ofResponseInputMessageContentList(
                                List.of(ResponseInputContent.ofInputText(text.build())))));
            } else if (message instanceof UserMessage user) {
                if (user.name() != null || !user.attributes().isEmpty()
                        || user.contents().stream().anyMatch(content -> !(content instanceof TextContent))) {
                    throw new IllegalArgumentException(
                            "Official SDK transport supports only unnamed text user messages");
                }
                input.add(message(EasyInputMessage.Role.USER, user.hasSingleText()
                        ? EasyInputMessage.Content.ofTextInput(user.singleText())
                        : EasyInputMessage.Content.ofResponseInputMessageContentList(user.contents().stream()
                                .map(content -> ResponseInputContent.ofInputText(ResponseInputText.builder()
                                        .text(((TextContent) content).text()).build()))
                                .toList())));
            } else if (message instanceof AiMessage assistant) {
                if (assistant.hasToolExecutionRequests() || !assistant.images().isEmpty()
                        || assistant.thinking() != null || !assistant.attributes().isEmpty()
                        || assistant.text() == null || assistant.text().isBlank()) {
                    throw new IllegalArgumentException("Official SDK transport supports only text assistant messages");
                }
                input.add(message(EasyInputMessage.Role.ASSISTANT,
                        EasyInputMessage.Content.ofTextInput(assistant.text())));
            } else {
                throw new IllegalArgumentException(
                        "Official SDK transport supports only system, user and assistant text messages");
            }
        }
        builder.inputOfResponse(input);
        if (parameters.temperature() != null) {
            builder.temperature(parameters.temperature());
        }
        if (parameters.topP() != null) {
            builder.topP(parameters.topP());
        }
        if (parameters.responseFormat() != null) {
            builder.text(ResponseTextConfig.builder().format(ResponseFormatText.builder().build()).build());
        }
        return builder.build();
    }

    private static ResponseInputItem message(EasyInputMessage.Role role, EasyInputMessage.Content content) {
        return ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                .type(EasyInputMessage.Type.MESSAGE).role(role).content(content).build());
    }

    private void validateParameters(ChatRequestParameters parameters) {
        if (parameters.getClass() != DefaultChatRequestParameters.class) {
            throw new IllegalArgumentException("Official SDK transport does not support custom request parameters");
        }
        if (parameters.modelName() != null && !deployment.equals(parameters.modelName())) {
            throw new IllegalArgumentException("Official SDK transport does not support deployment overrides");
        }
        if (parameters.topK() != null) {
            throw new IllegalArgumentException("Official SDK transport does not support topK");
        }
        if (parameters.frequencyPenalty() != null || parameters.presencePenalty() != null
                || (parameters.stopSequences() != null && !parameters.stopSequences().isEmpty())) {
            throw new IllegalArgumentException(
                    "The Responses API does not support frequency or presence penalties or stop sequences");
        }
        if ((parameters.toolSpecifications() != null && !parameters.toolSpecifications().isEmpty())
                || parameters.toolChoice() != null) {
            throw new IllegalArgumentException("Official SDK transport does not support model tool calls");
        }
        if (parameters.responseFormat() != null
                && (parameters.responseFormat().type() != ResponseFormatType.TEXT
                || parameters.responseFormat().jsonSchema() != null)) {
            throw new IllegalArgumentException("Official SDK transport does not support structured response formats");
        }
        if (parameters.maxOutputTokens() != null && parameters.maxOutputTokens() <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
    }

    private static ChatResponse fromSdkResponse(Response response) {
        try {
            if (response == null) {
                throw new LangChain4jException("Azure OpenAI returned no response");
            }
            ResponseStatus status = response.status()
                    .orElseThrow(() -> new LangChain4jException("Azure OpenAI returned no response status"));
            if (ResponseStatus.INCOMPLETE.equals(status)) {
                var reason = response.incompleteDetails().flatMap(Response.IncompleteDetails::reason).orElse(null);
                if (Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS.equals(reason)) {
                    throw new ModelOutputLimitException();
                }
                if (Response.IncompleteDetails.Reason.CONTENT_FILTER.equals(reason)) {
                    throw new ContentFilteredException("Azure OpenAI blocked the response with its content filter");
                }
                throw new LangChain4jException("Azure OpenAI returned an incomplete response");
            }
            if (!ResponseStatus.COMPLETED.equals(status) || response.error().isPresent()) {
                throw new LangChain4jException("Azure OpenAI did not complete the response");
            }
            String model = modelName(response.model());
            if (response.id().isBlank() || model.isBlank()) {
                throw new LangChain4jException("Azure OpenAI returned invalid response metadata");
            }
            String text = outputText(response.output());
            ResponseUsage usage = response.usage()
                    .orElseThrow(() -> new LangChain4jException("Azure OpenAI returned no token usage"));
            var inputDetails = reported(usage._inputTokensDetails());
            var outputDetails = reported(usage._outputTokensDetails());
            var tokenUsage = new ProviderTokenUsage(
                    Math.toIntExact(usage.inputTokens()),
                    Math.toIntExact(usage.outputTokens()),
                    Math.toIntExact(usage.totalTokens()),
                    inputDetails.flatMap(details -> count(details._cachedTokens())).orElse(null),
                    inputDetails.flatMap(details -> count(details._cacheWriteTokens())).orElse(null),
                    outputDetails.flatMap(details -> count(details._reasoningTokens())).orElse(null));
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .id(response.id())
                    .modelName(model)
                    .finishReason(FinishReason.STOP)
                    .tokenUsage(tokenUsage)
                    .build();
        } catch (LangChain4jException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.warn("Azure OpenAI response validation failed: errorType={}", exception.getClass().getSimpleName());
            throw new LangChain4jException("Azure OpenAI returned invalid response data or token usage");
        }
    }

    /** Reasoning items are skipped; the answer must be exactly one completed message of output text. */
    private static String outputText(List<ResponseOutputItem> output) {
        ResponseOutputMessage message = null;
        for (ResponseOutputItem item : output) {
            if (item.isReasoning()) {
                continue;
            }
            if (!item.isMessage()) {
                throw new LangChain4jException("Azure OpenAI returned unsupported non-text content");
            }
            if (message != null) {
                throw new LangChain4jException("Azure OpenAI returned invalid response metadata");
            }
            message = item.asMessage();
        }
        if (message == null || !ResponseOutputMessage.Status.COMPLETED.equals(message.status())) {
            throw new LangChain4jException("Azure OpenAI returned empty text content");
        }
        StringBuilder text = new StringBuilder();
        for (var content : message.content()) {
            if (content.isRefusal()) {
                throw new LangChain4jException("Azure OpenAI refused the request");
            }
            if (!content.isOutputText()) {
                throw new LangChain4jException("Azure OpenAI returned unsupported non-text content");
            }
            text.append(content.asOutputText().text());
        }
        if (text.toString().isBlank()) {
            throw new LangChain4jException("Azure OpenAI returned empty text content");
        }
        return text.toString();
    }

    private static String modelName(ResponsesModel model) {
        if (model.isString()) {
            return model.asString();
        }
        if (model.isChat()) {
            return model.asChat().asString();
        }
        if (model.isOnly()) {
            return model.asOnly().asString();
        }
        throw new LangChain4jException("Azure OpenAI returned invalid response metadata");
    }

    /** A missing or null provider value is unknown; a present value of the wrong type is invalid. */
    private static <T> Optional<T> reported(JsonField<T> field) {
        if (field.isMissing() || field.isNull()) {
            return Optional.empty();
        }
        return Optional.of(field.asKnown().orElseThrow(
                () -> new IllegalStateException("Azure OpenAI returned a malformed usage counter")));
    }

    private static Optional<Integer> count(JsonField<Long> field) {
        return reported(field).map(Math::toIntExact);
    }
}
