package com.example.tokenpatterns;

import com.example.tokenpatterns.service.PatternRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(StubModelConfiguration.class)
class PatternControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PatternRunner runner;

    @BeforeEach
    void resetCache() {
        runner.clearCache();
    }

    @Test
    void returnsThePatternCatalog() throws Exception {
        mockMvc.perform(get("/api/patterns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[0].id").value("router"));
    }

    @Test
    void executesAPattern() throws Exception {
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "patternId": "triage",
                                  "input": "What does HTTP 429 mean?"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patternId").value("triage"))
                .andExpect(jsonPath("$.metrics.modelCalls").value(1))
                .andExpect(jsonPath("$.trace.length()").isNumber());
    }

    @Test
    void reportsProviderThrottlingAsRateLimitedRatherThanServerError() throws Exception {
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "patternId": "triage",
                                  "input": "Explain backpressure %s"
                                }
                                """.formatted(StubChatModel.RATE_LIMIT_TRIGGER)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.title").value("Model provider rate limit reached"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("throttled")));
    }

    @Test
    void rejectsAnUnknownPatternAsABadRequest() throws Exception {
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "patternId": "does-not-exist",
                                  "input": "hello"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Pattern run could not be started"));
    }
}