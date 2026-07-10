package com.example.tokenpatterns.agent;

import com.azure.identity.DefaultAzureCredentialBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.azure.AzureOpenAiChatModel;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ModelCatalog {

    private final String apiKey;
    private final String endpoint;
    private final boolean managedIdentity;
    private final String smallModelName;
    private final String mediumModelName;
    private final String largeModelName;
    private final ModelSet demoModels = new ModelSet(
            new DemoChatModel("demo-small", 35),
            new DemoChatModel("demo-medium", 55),
            new DemoChatModel("demo-large", 85),
            "Deterministic workshop models",
            false);

    private volatile ModelSet liveModels;

    public ModelCatalog(TokenPatternProperties properties) {
        this.apiKey = properties.apiKey() == null ? "" : properties.apiKey().strip();
        this.endpoint = properties.endpoint() == null ? "" : properties.endpoint().strip();
        this.managedIdentity = properties.managedIdentity();
        this.smallModelName = properties.smallModel();
        this.mediumModelName = properties.mediumModel();
        this.largeModelName = properties.largeModel();
    }

    public ModelSet modelsFor(String mode) {
        if (!"live".equalsIgnoreCase(mode)) {
            return demoModels;
        }
        if (!liveEnabled()) {
            throw new IllegalStateException(
                    "Live mode requires AZURE_OPENAI_ENDPOINT plus managed identity or OPENAI_API_KEY");
        }
        ModelSet current = liveModels;
        if (current == null) {
            synchronized (this) {
                current = liveModels;
                if (current == null) {
                    current = new ModelSet(
                            createOpenAiModel(smallModelName),
                            createOpenAiModel(mediumModelName),
                            createOpenAiModel(largeModelName),
                            "OpenAI: " + smallModelName + " / " + mediumModelName + " / " + largeModelName,
                            true);
                    liveModels = current;
                }
            }
        }
        return current;
    }

    public boolean liveEnabled() {
        return !endpoint.isBlank() && (managedIdentity || !apiKey.isBlank());
    }

    public String liveModelSummary() {
        return smallModelName + " / " + mediumModelName + " / " + largeModelName;
    }

    private ChatModel createOpenAiModel(String modelName) {
        var builder = AzureOpenAiChatModel.builder()
                .endpoint(endpoint)
                .deploymentName(modelName)
                .maxCompletionTokens(700)
                .maxRetries(2)
                .timeout(Duration.ofSeconds(60));
        if (managedIdentity) {
            builder.tokenCredential(new DefaultAzureCredentialBuilder().build());
        } else {
            builder.apiKey(apiKey);
        }
        return builder.build();
    }

    public record ModelSet(
            ChatModel small,
            ChatModel medium,
            ChatModel large,
            String label,
            boolean live) {
    }
}