package com.example.tokenpatterns.web;

import com.example.tokenpatterns.agent.ModelCatalog;
import com.example.tokenpatterns.domain.ModelOutputLimitException;
import com.example.tokenpatterns.service.PatternCatalog;
import com.example.tokenpatterns.service.PatternRunner;
import dev.langchain4j.agentic.agent.AgentInvocationException;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PatternController.class)
@ExtendWith(OutputCaptureExtension.class)
class PatternControllerErrorTest {

    private static final String UNTRUSTED = "untrusted-secret-marker\r\nforged-log-entry";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PatternController controller;

    @MockitoBean
    private PatternCatalog catalog;

    @MockitoBean
    private PatternRunner runner;

    @MockitoBean
    private ModelCatalog models;

    @ParameterizedTest
    @MethodSource("providerFailures")
    void unwrapsAgenticFailuresWithoutExposingExceptionMessages(LangChain4jException cause, String category,
                                                               String detail, int expectedStatus,
                                                               CapturedOutput output) throws Exception {
        var wrapper = new AgentInvocationException("Failed to invoke agent method: " + UNTRUSTED,
                new InvocationTargetException(new AgentInvocationException(UNTRUSTED,
                        new InvocationTargetException(cause))));
        when(runner.run(any())).thenThrow(wrapper);

        String body = run().andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.title").value(expectedStatus == 429
                        ? "Model provider rate limit reached" : "Model provider call failed"))
                .andExpect(jsonPath("$.type").value(expectedStatus == 429
                        ? "https://example.com/problems/rate-limit" : "https://example.com/problems/model-provider"))
                .andExpect(jsonPath("$.detail").value(containsString(detail)))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("untrusted-secret-marker"));
        assertFalse(body.contains("forged-log-entry"));
        assertFalse(body.contains("Failed to invoke agent method"));
        assertFalse(output.getAll().contains("untrusted-secret-marker"));
        assertFalse(output.getAll().contains("forged-log-entry"));
        assertTrue(output.getAll().contains("category=" + category));
        assertTrue(output.getAll().contains("AgentInvocationException"));
    }

    static Stream<Arguments> providerFailures() {
        return Stream.of(
                Arguments.of(new ModelOutputLimitException(), "OUTPUT_LIMIT", "response token limit", 502),
                Arguments.of(new ContentFilteredException(UNTRUSTED), "CONTENT_FILTER", "content filter", 502),
                Arguments.of(new TimeoutException(UNTRUSTED), "TIMEOUT", "timed out", 502),
                Arguments.of(new AuthenticationException(UNTRUSTED), "AUTHENTICATION", "role assignments", 502),
                Arguments.of(new ModelNotFoundException(UNTRUSTED), "DEPLOYMENT_NOT_FOUND", "configured deployment", 502),
                Arguments.of(new InvalidRequestException(UNTRUSTED), "REQUEST_REJECTED", "requested parameters", 502),
                Arguments.of(new RateLimitException(UNTRUSTED), "RATE_LIMIT", "throttled", 429),
                Arguments.of(new HttpException(429, UNTRUSTED), "RATE_LIMIT", "throttled", 429),
                Arguments.of(new HttpException(400, UNTRUSTED), "REQUEST_REJECTED", "requested parameters", 502),
                Arguments.of(new HttpException(422, UNTRUSTED), "REQUEST_REJECTED", "requested parameters", 502),
                Arguments.of(new HttpException(401, UNTRUSTED), "AUTHENTICATION", "role assignments", 502),
                Arguments.of(new HttpException(403, UNTRUSTED), "AUTHENTICATION", "role assignments", 502),
                Arguments.of(new HttpException(404, UNTRUSTED), "DEPLOYMENT_NOT_FOUND", "configured deployment", 502),
                Arguments.of(new HttpException(408, UNTRUSTED), "TIMEOUT", "timed out", 502),
                Arguments.of(new HttpException(504, UNTRUSTED), "TIMEOUT", "timed out", 502),
                Arguments.of(new HttpException(500, UNTRUSTED), "UNAVAILABLE", "temporarily unavailable", 502),
                Arguments.of(new HttpException(502, UNTRUSTED), "UNAVAILABLE", "temporarily unavailable", 502),
                Arguments.of(new HttpException(503, UNTRUSTED), "UNAVAILABLE", "temporarily unavailable", 502),
                Arguments.of(new HttpException(418, UNTRUSTED), "UNKNOWN", "provider diagnostics", 502),
                Arguments.of(new LangChain4jException(UNTRUSTED), "UNKNOWN", "provider diagnostics", 502));
    }

    @Test
    void logsOnlyTheProviderStatusAndClassificationsForHttpFailures(CapturedOutput output) throws Exception {
        when(runner.run(any())).thenThrow(new HttpException(403, UNTRUSTED));
        run().andExpect(status().isBadGateway());
        assertTrue(output.getAll().contains("category=AUTHENTICATION, providerStatus=403"));
        assertFalse(output.getAll().contains("untrusted-secret-marker"));
    }

    @Test
    void rateLimitSurvivesMoreThanTenAgentWrappers() throws Exception {
        LangChain4jException exception = new RateLimitException(UNTRUSTED);
        for (int depth = 0; depth < 20; depth++) {
            exception = new LangChain4jException(UNTRUSTED, exception);
        }
        when(runner.run(any())).thenThrow(exception);
        run().andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value(containsString("throttled")));
    }

    @Test
    void validationWrappersDoNotMisclassifyProviderFailuresAsBadRequests() throws Exception {
        for (RuntimeException wrapper : new RuntimeException[] {
                new IllegalStateException(UNTRUSTED, new ModelOutputLimitException()),
                new IllegalArgumentException(UNTRUSTED, new ModelOutputLimitException())}) {
            doThrow(wrapper).when(runner).run(any());
            run().andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.detail").value(containsString("response token limit")));
        }
    }

    @Test
    void cyclicCausesTerminateWithASafeGenericFailure(CapturedOutput output) {
        var first = new LangChain4jException(UNTRUSTED);
        var second = new RuntimeException(UNTRUSTED, first);
        first.initCause(second);

        // Spring's own exception resolver cannot traverse a cyclic cause graph.
        var problem = assertTimeout(Duration.ofSeconds(2), () -> controller.modelProviderFailure(first));
        assertEquals(502, problem.getStatus());
        assertTrue(problem.getDetail().contains("provider diagnostics"));
        assertFalse(output.getAll().contains("untrusted-secret-marker"));
    }

    @Test
    void excessiveCauseDepthRemainsBounded(CapturedOutput output) {
        LangChain4jException exception = new RateLimitException(UNTRUSTED);
        for (int depth = 0; depth < 40; depth++) {
            exception = new LangChain4jException(UNTRUSTED, exception);
        }
        when(runner.run(any())).thenThrow(exception);

        assertTimeout(Duration.ofSeconds(2), () -> run().andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value(containsString("provider diagnostics"))));
        assertFalse(output.getAll().contains("untrusted-secret-marker"));
    }

    @Test
    void ordinaryClientValidationStillReturns400() throws Exception {
        when(runner.run(any())).thenThrow(new IllegalArgumentException("Unknown pattern"));
        run().andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Pattern run could not be started"))
                .andExpect(jsonPath("$.detail").value("Unknown pattern"));
    }

    private ResultActions run() throws Exception {
        return mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"patternId":"triage","input":"Design a secure distributed multi-region payment system."}
                        """));
    }
}
