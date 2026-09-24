package com.example.tokenpatterns;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(StubModelConfiguration.class)
class PatternControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unversionedUiResourcesMustBeRevalidatedAfterDeployment() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"));
        mockMvc.perform(get("/cache-flow.mjs"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/javascript")));
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

    @Test
    void serializesProviderCacheUsageAndAllowsAnExplicitBypass() throws Exception {
        mockMvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patternId":"caching","input":"What is idempotency?","cacheEnabled":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.cacheStatus").value("bypassed"))
                .andExpect(jsonPath("$.metrics.cachedInputTokens").value(0))
                .andExpect(jsonPath("$.metrics.cacheWriteTokens").value(0))
                .andExpect(jsonPath("$.metrics.modelCalls").value(1))
                .andExpect(jsonPath("$.metrics.avoidedTokens").value(0))
                .andExpect(jsonPath("$.metrics.cacheHit").doesNotExist());
    }

    @Test
    void defaultCacheRequestReportsProviderWriteAndNullUsageRemainsNull() throws Exception {
        mockMvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patternId":"caching","input":"What is idempotency?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.cacheStatus").value("miss-written"))
                .andExpect(jsonPath("$.metrics.cacheWriteTokens").value(org.hamcrest.Matchers.greaterThan(0)));
        mockMvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patternId":"caching","input":"What is idempotency? CACHE_UNKNOWN_FIXTURE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.cacheStatus").value("unknown"))
                .andExpect(jsonPath("$.metrics.cachedInputTokens").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.metrics.cacheWriteTokens").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void doesNotPretendToClearAServiceManagedCache() throws Exception {
        mockMvc.perform(delete("/api/cache"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.title").value("Provider cache is service-managed"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("cacheEnabled=false")));
    }

    @Test
    void rejectsEmptyCacheInputAndMalformedCacheOptions() throws Exception {
        mockMvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patternId":"caching","input":""}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patternId":"caching","input":"What is idempotency?","cacheEnabled":{}}
                                """))
                .andExpect(status().isBadRequest());
    }
}