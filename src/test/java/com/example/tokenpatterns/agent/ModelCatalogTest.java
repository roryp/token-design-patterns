package com.example.tokenpatterns.agent;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.openai.azure.AzureUrlPathMode;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.LogLevel;
import com.openai.core.ObjectMappers;
import com.openai.credential.BearerTokenCredential;
import com.openai.credential.Credential;
import com.openai.errors.OpenAIException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.services.blocking.ResponseService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.LangChain4jException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModelCatalogTest {

    private static final String ENDPOINT = "https://offline.openai.azure.com";

    @Test
    void lazilyCreatesOneClientAndFourCorrectlyWiredModelsThenClosesItOnce() {
        try (var sdk = new MockSdk()) {
            var catalog = new ModelCatalog(properties("  test-api-key  ", ENDPOINT + "/", false));
            assertTrue(catalog.configured());
            assertEquals("gpt-5.6-luna / gpt-5.6-terra / gpt-5.6-sol", catalog.modelSummary());
            sdk.factory.verifyNoInteractions();

            var models = catalog.models();
            assertSame(models, catalog.models());
            assertNotSame(models.medium(), models.cachedMedium());
            assertEquals(models.medium().defaultRequestParameters(), models.cachedMedium().defaultRequestParameters());
            assertEquals("gpt-5.6-luna", models.small().defaultRequestParameters().modelName());
            assertEquals("gpt-5.6-terra", models.medium().defaultRequestParameters().modelName());
            assertEquals("gpt-5.6-sol", models.large().defaultRequestParameters().modelName());
            assertFalse(models.label().contains("test-api-key"));

            for (var model : List.of(models.small(), models.medium(), models.large(), models.cachedMedium())) {
                model.chat(SystemMessage.from("Stable policy prefix"), UserMessage.from("question"));
            }
            var requests = ArgumentCaptor.forClass(ResponseCreateParams.class);
            verify(sdk.responses, times(4)).create(requests.capture());
            assertEquals(List.of("gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.6-sol", "gpt-5.6-terra"),
                    requests.getAllValues().stream().map(params -> params.model().orElseThrow().asString()).toList());
            for (int index = 0; index < requests.getAllValues().size(); index++) {
                var params = requests.getAllValues().get(index);
                assertEquals(ResponseCreateParams.PromptCacheOptions.Mode.EXPLICIT,
                        params.promptCacheOptions().orElseThrow().mode().orElseThrow());
                assertEquals(index == 3, params.promptCacheKey().isPresent());
                assertEquals(index == 3, params.input().orElseThrow().asResponse().getFirst().asEasyInputMessage()
                        .content().asResponseInputMessageContentList().getFirst().asInputText()
                        .promptCacheBreakpoint().isPresent());
                assertEquals(2000L, params.maxOutputTokens().orElseThrow());
                assertFalse(params.store().orElseThrow());
                assertTrue(params.temperature().isEmpty());
            }
            sdk.factory.verify(OpenAIOkHttpClient::builder, times(1));
            verify(sdk.builder).baseUrl(ENDPOINT + "/openai/v1/");
            verify(sdk.builder).azureUrlPathMode(AzureUrlPathMode.UNIFIED);
            verify(sdk.builder).maxRetries(2);
            verify(sdk.builder).timeout(Duration.ofSeconds(60));
            verify(sdk.builder).logLevel(LogLevel.OFF);
            verify(sdk.builder).apiKey("test-api-key");
            verify(sdk.builder, never()).credential(any());
            verify(sdk.builder, times(1)).build();

            catalog.close();
            catalog.close();
            verify(sdk.client, times(1)).close();
            assertThrows(IllegalStateException.class, catalog::models);
        }
    }

    @Test
    void managedIdentityUsesARefreshingScopedSupplierAndTakesPrecedenceOverApiKey() {
        var azureCredential = mock(DefaultAzureCredential.class);
        var expires = OffsetDateTime.parse("2030-01-01T00:00:00Z");
        when(azureCredential.getTokenSync(any(TokenRequestContext.class)))
                .thenReturn(new AccessToken("offline-token-one", expires), new AccessToken("offline-token-two", expires))
                .thenThrow(new IllegalStateException("sensitive-credential-value"))
                .thenReturn(null, new AccessToken("", expires));
        var tokenSupplier = new AtomicReference<Supplier<String>>();
        var bearerCredential = mock(Credential.class);

        try (var sdk = new MockSdk();
             var azure = mockConstruction(DefaultAzureCredentialBuilder.class,
                     (builder, context) -> when(builder.build()).thenReturn(azureCredential));
             var bearer = mockStatic(BearerTokenCredential.class)) {
            bearer.when(() -> BearerTokenCredential.create(org.mockito.ArgumentMatchers.<Supplier<String>>any()))
                    .thenAnswer(invocation -> {
                        tokenSupplier.set(invocation.getArgument(0));
                        return bearerCredential;
                    });
            var catalog = new ModelCatalog(properties("unused-test-key", ENDPOINT, true));
            assertTrue(catalog.configured());
            assertTrue(azure.constructed().isEmpty(), "Authentication must remain lazy");
            catalog.models();
            verifyNoInteractions(azureCredential);
            assertEquals(1, azure.constructed().size());
            verify(sdk.builder).credential(bearerCredential);
            verify(sdk.builder, never()).apiKey(anyString());

            assertEquals("offline-token-one", tokenSupplier.get().get());
            assertEquals("offline-token-two", tokenSupplier.get().get());
            for (int attempt = 0; attempt < 3; attempt++) {
                var failure = assertThrows(LangChain4jException.class, tokenSupplier.get()::get);
                assertEquals("Azure OpenAI authentication failed", failure.getMessage());
                assertNull(failure.getCause());
            }
            var contexts = ArgumentCaptor.forClass(TokenRequestContext.class);
            verify(azureCredential, times(5)).getTokenSync(contexts.capture());
            for (var context : contexts.getAllValues()) {
                assertEquals(List.of("https://cognitiveservices.azure.com/.default"), context.getScopes());
            }
            assertNotSame(contexts.getAllValues().get(0), contexts.getAllValues().get(1));
            catalog.close();
            verify(sdk.client).close();
        }
    }

    @ParameterizedTest
    @MethodSource("unconfiguredProperties")
    void missingConfigurationDoesNotCreateClientOrAcquireCredentials(TokenPatternProperties properties) {
        try (var sdk = new MockSdk()) {
            var catalog = new ModelCatalog(properties);
            assertFalse(catalog.configured());
            assertThrows(IllegalStateException.class, catalog::models);
            catalog.close();
            sdk.factory.verifyNoInteractions();
            verifyNoInteractions(sdk.client);
        }
    }

    static Stream<Arguments> unconfiguredProperties() {
        return Stream.of(
                Arguments.of(properties(null, null, false)),
                Arguments.of(properties(" ", ENDPOINT, false)),
                Arguments.of(properties("test-key", " ", false)),
                Arguments.of(properties(null, null, true)));
    }

    @Test
    void closingUnusedCatalogDoesNotInitializeClient() {
        try (var sdk = new MockSdk()) {
            var catalog = new ModelCatalog(properties("test-key", ENDPOINT, false));
            catalog.close();
            assertThrows(IllegalStateException.class, catalog::models);
            sdk.factory.verifyNoInteractions();
        }
    }

    @Test
    void clientInitializationFailureIsSanitized() {
        try (var sdk = new MockSdk()) {
            when(sdk.builder.build()).thenThrow(new OpenAIException("sensitive-credential-value"));
            var catalog = new ModelCatalog(properties("test-key", ENDPOINT, false));
            var failure = assertThrows(LangChain4jException.class, catalog::models);
            assertEquals("Azure OpenAI client could not be initialized", failure.getMessage());
            assertNull(failure.getCause());
            catalog.close();
            verify(sdk.client, never()).close();
        }
    }

    @Test
    void clientCloseFailureIsSanitizedAndDoesNotAllowCatalogReuse() {
        try (var sdk = new MockSdk()) {
            var catalog = new ModelCatalog(properties("test-key", ENDPOINT, false));
            catalog.models();
            doThrow(new OpenAIException("sensitive-credential-value")).when(sdk.client).close();
            var failure = assertThrows(LangChain4jException.class, catalog::close);
            assertEquals("Azure OpenAI client could not be closed", failure.getMessage());
            assertNull(failure.getCause());
            catalog.close();
            verify(sdk.client, times(1)).close();
            assertThrows(IllegalStateException.class, catalog::models);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://offline.openai.azure.com",
            "https://offline.openai.azure.com/",
            " https://offline.openai.azure.com/// ",
            "https://offline.openai.azure.com/openai",
            "https://offline.openai.azure.com/openai/",
            "https://offline.openai.azure.com/openai/v1",
            "https://offline.openai.azure.com/openai/v1/"
    })
    void normalizesAzureResourceOrV1UrlWithoutDuplicatingPath(String endpoint) {
        assertEquals(ENDPOINT + "/openai/v1/", ModelCatalog.azureBaseUrl(endpoint));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not a URL",
            "/relative",
            "http://offline.openai.azure.com",
            "https://user:sensitive-credential-value@offline.openai.azure.com",
            "https://offline.openai.azure.com?key=sensitive-credential-value",
            "https://offline.openai.azure.com#sensitive-credential-value",
            "https://offline.openai.azure.com/openai/deployments/legacy",
            "https://offline.openai.azure.com/other"
    })
    void rejectsAmbiguousOrUnsafeEndpointsWithoutEchoingThem(String endpoint) {
        var failure = assertThrows(IllegalArgumentException.class, () -> ModelCatalog.azureBaseUrl(endpoint));
        assertFalse(failure.getMessage().contains("sensitive-credential-value"));
        assertNull(failure.getCause());
    }

    private static TokenPatternProperties properties(String apiKey, String endpoint, boolean managedIdentity) {
        return new TokenPatternProperties(apiKey, endpoint, managedIdentity,
                "gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.6-sol");
    }

    /** Mock the client factory as well as requests: no credentials, sockets, or Azure calls are used. */
    private static final class MockSdk implements AutoCloseable {
        final OpenAIClient client = mock(OpenAIClient.class);
        final OpenAIOkHttpClient.Builder builder = mock(OpenAIOkHttpClient.Builder.class, RETURNS_SELF);
        final ResponseService responses = mock(ResponseService.class);
        final MockedStatic<OpenAIOkHttpClient> factory;

        MockSdk() {
            Response response;
            try {
                response = ObjectMappers.jsonMapper().readValue("""
                        {"id":"offline-id","object":"response","created_at":0,"status":"completed",
                         "model":"provider-revision",
                         "output":[{"type":"message","id":"msg-offline","role":"assistant","status":"completed",
                                    "content":[{"type":"output_text","text":"answer","annotations":[]}]}],
                         "usage":{"input_tokens":10,"output_tokens":2,"total_tokens":12}}
                        """, Response.class);
            } catch (Exception exception) {
                throw new AssertionError("Could not read offline response fixture", exception);
            }
            when(client.responses()).thenReturn(responses);
            when(responses.create(any(ResponseCreateParams.class))).thenReturn(response);
            when(builder.build()).thenReturn(client);
            factory = mockStatic(OpenAIOkHttpClient.class);
            factory.when(OpenAIOkHttpClient::builder).thenReturn(builder);
        }

        @Override
        public void close() {
            factory.close();
        }
    }
}
