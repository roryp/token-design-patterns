package com.example.tokenpatterns.agent;

import com.example.tokenpatterns.domain.PatternRunRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Builds the exact system instructions that the caching agent sends and the UI displays. */
@Component
public class CacheInstructions {

    private static final Pattern SESSION = Pattern.compile(PatternRunRequest.CACHE_SESSION_PATTERN);

    private final String policy;

    public CacheInstructions() {
        try {
            policy = new ClassPathResource("prompts/cache-policy.txt").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("The cache policy resource could not be read", exception);
        }
        if (policy.isBlank()) {
            throw new IllegalStateException("The cache policy resource is empty");
        }
    }

    /**
     * Without a session this is the shared workshop policy. A session line comes first so the whole prefix is
     * new to the provider: that session's first cached run is a genuine miss that writes the cache.
     */
    public String forSession(String session) {
        if (session == null) {
            return policy;
        }
        if (!SESSION.matcher(session).matches()) {
            throw new IllegalArgumentException("cacheSession must be a lowercase version 4 UUID.");
        }
        return "Cache test session " + session
                + ". This line only makes these instructions new to the provider cache for one browser session;"
                + " ignore it when answering.\n\n" + policy;
    }
}
