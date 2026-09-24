package com.example.tokenpatterns.agent;

import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.openai.azure.AzureUrlPathMode;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.LogLevel;
import com.openai.credential.BearerTokenCredential;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

import static com.example.tokenpatterns.agent.OfficialSdkChatModel.PromptCacheMode.BYPASS;
import static com.example.tokenpatterns.agent.OfficialSdkChatModel.PromptCacheMode.CACHE_SYSTEM_PREFIX;

@Component
public class ModelCatalog {

    private static final String AZURE_OPENAI_SCOPE = "https://cognitiveservices.azure.com/.default";

    private final String apiKey;
    private final String endpoint;
    private final boolean managedIdentity;
    private final String smallModelName;
    private final String mediumModelName;
    private final String largeModelName;

    private ModelSet models;
    private OpenAIClient client;
    private boolean closed;

    public ModelCatalog(TokenPatternProperties properties) {
        this.apiKey = properties.apiKey() == null ? "" : properties.apiKey().strip();
        this.endpoint = properties.endpoint() == null ? "" : properties.endpoint().strip();
        this.managedIdentity = properties.managedIdentity();
        this.smallModelName = properties.smallModel();
        this.mediumModelName = properties.mediumModel();
        this.largeModelName = properties.largeModel();
    }

    public synchronized ModelSet models() {
        if (closed) {
            throw new IllegalStateException("Azure OpenAI model catalog is closed");
        }
        if (!configured()) {
            throw new IllegalStateException(
                    "Azure OpenAI is not configured: set AZURE_OPENAI_ENDPOINT plus managed identity or OPENAI_API_KEY");
        }
        if (models == null) {
            if (client == null) {
                client = createClient();
            }
            models = new ModelSet(
                    new OfficialSdkChatModel(client, smallModelName, BYPASS),
                    new OfficialSdkChatModel(client, mediumModelName, BYPASS),
                    new OfficialSdkChatModel(client, largeModelName, BYPASS),
                    new OfficialSdkChatModel(client, mediumModelName, CACHE_SYSTEM_PREFIX),
                    "OpenAI: " + smallModelName + " / " + mediumModelName + " / " + largeModelName);
        }
        return models;
    }

    public boolean configured() {
        return !endpoint.isBlank() && (managedIdentity || !apiKey.isBlank());
    }

    public String modelSummary() {
        return smallModelName + " / " + mediumModelName + " / " + largeModelName;
    }

    private OpenAIClient createClient() {
        String baseUrl = azureBaseUrl(endpoint);
        try {
            var builder = OpenAIOkHttpClient.builder()
                    .baseUrl(baseUrl)
                    .azureUrlPathMode(AzureUrlPathMode.UNIFIED)
                    .logLevel(LogLevel.OFF)
                    .maxRetries(2)
                    .timeout(Duration.ofSeconds(60));
            if (managedIdentity) {
                var credential = new DefaultAzureCredentialBuilder().build();
                builder.credential(BearerTokenCredential.create(() -> {
                    try {
                        // Called for each authentication attempt; Azure Identity manages token refresh/caching.
                        var token = credential.getTokenSync(new TokenRequestContext().addScopes(AZURE_OPENAI_SCOPE));
                        if (token == null || token.getToken() == null || token.getToken().isBlank()) {
                            throw new LangChain4jException("Azure OpenAI authentication returned no token");
                        }
                        return token.getToken();
                    } catch (RuntimeException exception) {
                        throw new LangChain4jException("Azure OpenAI authentication failed");
                    }
                }));
            } else {
                builder.apiKey(apiKey);
            }
            return builder.build();
        } catch (RuntimeException exception) {
            throw new LangChain4jException("Azure OpenAI client could not be initialized");
        }
    }

    static String azureBaseUrl(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint.strip());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Azure OpenAI endpoint must be a valid HTTPS URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "Azure OpenAI endpoint must be an HTTPS URL without credentials, query or fragment");
        }
        String path = uri.getRawPath().replaceAll("/+$", "");
        if (!path.isEmpty() && !path.equals("/openai") && !path.equals("/openai/v1")) {
            throw new IllegalArgumentException("Azure OpenAI endpoint must be a resource URL or /openai/v1/ base URL");
        }
        return "https://" + uri.getRawAuthority() + "/openai/v1/";
    }

    @PreDestroy
    public synchronized void close() {
        if (!closed) {
            closed = true;
            if (client != null) {
                try {
                    client.close();
                } catch (RuntimeException exception) {
                    throw new LangChain4jException("Azure OpenAI client could not be closed");
                }
            }
        }
    }

    public record ModelSet(
            ChatModel small,
            ChatModel medium,
            ChatModel large,
            ChatModel cachedMedium,
            String label) {
    }
}
