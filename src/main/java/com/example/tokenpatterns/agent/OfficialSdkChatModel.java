package com.example.tokenpatterns.agent;

import com.example.tokenpatterns.domain.ModelOutputLimitException;
import com.openai.client.OpenAIClient;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.ResponseFormatText;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionCreateParams.PromptCacheOptions;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.openai.models.completions.CompletionUsage;
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

import java.util.List;
import java.util.Objects;

/**
 * The lab's stateless text transport. Client ownership belongs to {@link ModelCatalog};
 * every invocation builds its own immutable SDK request.
 */
public final class OfficialSdkChatModel implements ChatModel {

    public enum PromptCacheMode {
        BYPASS,
        CACHE_SYSTEM_PREFIX
    }

    private static final String PROMPT_CACHE_KEY = "tokenflow-policy-v1";
    private static final int MAX_COMPLETION_TOKENS = 2000;
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
                // The budget includes reasoning tokens, just as in the previous transport.
                .maxOutputTokens(MAX_COMPLETION_TOKENS)
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
        ChatCompletionCreateParams params = toSdkRequest(request);
        ChatCompletion completion;
        try {
            completion = client.chat().completions().create(params);
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
        return fromSdkResponse(completion);
    }

    private ChatCompletionCreateParams toSdkRequest(ChatRequest request) {
        ChatRequestParameters parameters = request.parameters();
        validateParameters(parameters);
        var options = PromptCacheOptions.builder().mode(PromptCacheOptions.Mode.EXPLICIT);
        var builder = ChatCompletionCreateParams.builder()
                .model(deployment)
                .maxCompletionTokens(parameters.maxOutputTokens() == null
                        ? MAX_COMPLETION_TOKENS : parameters.maxOutputTokens());
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
        for (ChatMessage message : request.messages()) {
            if (message instanceof SystemMessage system) {
                var text = ChatCompletionContentPartText.builder().text(system.text());
                if (caching) {
                    text.promptCacheBreakpoint(
                            ChatCompletionContentPartText.PromptCacheBreakpoint.builder().build());
                }
                builder.addMessage(ChatCompletionSystemMessageParam.builder()
                        .content(ChatCompletionSystemMessageParam.Content.ofArrayOfContentParts(
                                List.of(text.build())))
                        .build());
            } else if (message instanceof UserMessage user) {
                if (!user.attributes().isEmpty()
                        || user.contents().stream().anyMatch(content -> !(content instanceof TextContent))) {
                    throw new IllegalArgumentException("Official SDK transport supports only text user messages");
                }
                var userBuilder = ChatCompletionUserMessageParam.builder();
                if (user.hasSingleText()) {
                    userBuilder.content(user.singleText());
                } else {
                    userBuilder.content(ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(
                            user.contents().stream()
                                    .map(content -> ChatCompletionContentPart.ofText(
                                            ChatCompletionContentPartText.builder()
                                                    .text(((TextContent) content).text()).build()))
                                    .toList()));
                }
                if (user.name() != null) {
                    userBuilder.name(user.name());
                }
                builder.addMessage(userBuilder.build());
            } else if (message instanceof AiMessage assistant) {
                if (assistant.hasToolExecutionRequests() || !assistant.images().isEmpty()
                        || assistant.thinking() != null || !assistant.attributes().isEmpty()
                        || assistant.text() == null || assistant.text().isBlank()) {
                    throw new IllegalArgumentException("Official SDK transport supports only text assistant messages");
                }
                builder.addMessage(ChatCompletionAssistantMessageParam.builder()
                        .content(assistant.text()).build());
            } else {
                throw new IllegalArgumentException(
                        "Official SDK transport supports only system, user and assistant text messages");
            }
        }
        if (parameters.temperature() != null) {
            builder.temperature(parameters.temperature());
        }
        if (parameters.topP() != null) {
            builder.topP(parameters.topP());
        }
        if (parameters.frequencyPenalty() != null) {
            builder.frequencyPenalty(parameters.frequencyPenalty());
        }
        if (parameters.presencePenalty() != null) {
            builder.presencePenalty(parameters.presencePenalty());
        }
        if (parameters.stopSequences() != null && !parameters.stopSequences().isEmpty()) {
            builder.stopOfStrings(parameters.stopSequences());
        }
        if (parameters.responseFormat() != null) {
            builder.responseFormat(ResponseFormatText.builder().build());
        }
        return builder.build();
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

    private static ChatResponse fromSdkResponse(ChatCompletion completion) {
        try {
            if (completion == null) {
                throw new LangChain4jException("Azure OpenAI returned no response");
            }
            completion.validate();
            if (completion.id().isBlank() || completion.model().isBlank()
                    || completion.choices().size() != 1 || completion.choices().getFirst().index() != 0) {
                throw new LangChain4jException("Azure OpenAI returned invalid response metadata");
            }
            var choice = completion.choices().getFirst();
            FinishReason finishReason = switch (choice.finishReason().value()) {
                case STOP -> FinishReason.STOP;
                case LENGTH -> throw new ModelOutputLimitException();
                case CONTENT_FILTER -> throw new ContentFilteredException(
                        "Azure OpenAI blocked the response with its content filter");
                default -> throw new LangChain4jException("Azure OpenAI returned an unsupported finish reason");
            };
            var message = choice.message();
            if (message.refusal().isPresent()) {
                throw new LangChain4jException("Azure OpenAI refused the request");
            }
            if (message.audio().isPresent() || message.functionCall().isPresent()
                    || message.toolCalls().filter(calls -> !calls.isEmpty()).isPresent()) {
                throw new LangChain4jException("Azure OpenAI returned unsupported non-text content");
            }
            String text = message.content().filter(content -> !content.isBlank())
                    .orElseThrow(() -> new LangChain4jException("Azure OpenAI returned empty text content"));
            CompletionUsage usage = completion.usage()
                    .orElseThrow(() -> new LangChain4jException("Azure OpenAI returned no token usage"));
            var promptDetails = usage.promptTokensDetails();
            var outputDetails = usage.completionTokensDetails();
            var tokenUsage = new ProviderTokenUsage(
                    Math.toIntExact(usage.promptTokens()),
                    Math.toIntExact(usage.completionTokens()),
                    Math.toIntExact(usage.totalTokens()),
                    promptDetails.flatMap(CompletionUsage.PromptTokensDetails::cachedTokens)
                            .map(Math::toIntExact).orElse(null),
                    promptDetails.flatMap(CompletionUsage.PromptTokensDetails::cacheWriteTokens)
                            .map(Math::toIntExact).orElse(null),
                    outputDetails.flatMap(CompletionUsage.CompletionTokensDetails::reasoningTokens)
                            .map(Math::toIntExact).orElse(null));
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .id(completion.id())
                    .modelName(completion.model())
                    .finishReason(finishReason)
                    .tokenUsage(tokenUsage)
                    .build();
        } catch (LangChain4jException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.warn("Azure OpenAI response validation failed: errorType={}", exception.getClass().getSimpleName());
            throw new LangChain4jException("Azure OpenAI returned invalid response data or token usage");
        }
    }
}
