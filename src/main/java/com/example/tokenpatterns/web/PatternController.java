package com.example.tokenpatterns.web;

import com.example.tokenpatterns.agent.ModelCatalog;
import com.example.tokenpatterns.domain.ModelOutputLimitException;
import com.example.tokenpatterns.domain.PatternDefinition;
import com.example.tokenpatterns.domain.PatternRunRequest;
import com.example.tokenpatterns.domain.PatternRunResult;
import com.example.tokenpatterns.service.PatternCatalog;
import com.example.tokenpatterns.service.PatternRunner;
import jakarta.validation.Valid;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class PatternController {

    private static final Logger LOGGER = LoggerFactory.getLogger(PatternController.class);

    private final PatternCatalog catalog;
    private final PatternRunner runner;
    private final ModelCatalog models;

    public PatternController(PatternCatalog catalog, PatternRunner runner, ModelCatalog models) {
        this.catalog = catalog;
        this.runner = runner;
        this.models = models;
    }

    @GetMapping("/patterns")
    public List<PatternDefinition> patterns() {
        return catalog.all();
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
                "modelsConfigured", models.configured(),
                "models", models.modelSummary(),
                "agenticVersion", "1.19.0-beta29");
    }

    @PostMapping("/runs")
    public PatternRunResult run(@Valid @RequestBody PatternRunRequest request) {
        return runner.run(request);
    }

    @DeleteMapping("/cache")
    public ProblemDetail clearCache() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.GONE,
                "The local response cache has been removed. Azure manages prompt-cache retention; this application cannot clear it. Send cacheEnabled=false to bypass provider caching for a run.");
        problem.setTitle("Provider cache is service-managed");
        problem.setType(URI.create("https://example.com/problems/provider-cache"));
        return problem;
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ProblemDetail badRequest(RuntimeException exception) {
        List<Throwable> causes = causes(exception);
        if (causes.stream().anyMatch(LangChain4jException.class::isInstance)) {
            return providerProblem(causes);
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Pattern run could not be started");
        problem.setType(URI.create("https://example.com/problems/pattern-run"));
        return problem;
    }

    @ExceptionHandler(LangChain4jException.class)
    public ProblemDetail modelProviderFailure(LangChain4jException exception) {
        return providerProblem(causes(exception));
    }

    private static ProblemDetail providerProblem(List<Throwable> causes) {
        Integer providerStatus = causes.stream()
                .filter(HttpException.class::isInstance)
                .map(HttpException.class::cast)
                .map(HttpException::statusCode)
                .reduce((first, last) -> last)
                .orElse(null);
        ProviderFailure failure = providerFailure(causes, providerStatus);
        LOGGER.warn("Model provider failure: category={}, providerStatus={}, causeTypes={}",
                failure, failure == ProviderFailure.RATE_LIMIT ? Integer.valueOf(429) : providerStatus,
                causes.stream().map(cause -> cause.getClass().getSimpleName()).toList());
        if (failure == ProviderFailure.RATE_LIMIT) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.TOO_MANY_REQUESTS, failure.detail);
            problem.setTitle("Model provider rate limit reached");
            problem.setType(URI.create("https://example.com/problems/rate-limit"));
            return problem;
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, failure.detail);
        problem.setTitle("Model provider call failed");
        problem.setType(URI.create("https://example.com/problems/model-provider"));
        return problem;
    }

    private static ProviderFailure providerFailure(List<Throwable> causes, Integer providerStatus) {
        if (causes.stream().anyMatch(cause -> cause instanceof RateLimitException
                || (cause instanceof HttpException http && http.statusCode() == 429))) {
            return ProviderFailure.RATE_LIMIT;
        }
        for (Throwable cause : causes.reversed()) {
            if (cause instanceof ModelOutputLimitException) {
                return ProviderFailure.OUTPUT_LIMIT;
            }
            if (cause instanceof ContentFilteredException) {
                return ProviderFailure.CONTENT_FILTER;
            }
            if (cause instanceof TimeoutException) {
                return ProviderFailure.TIMEOUT;
            }
            if (cause instanceof AuthenticationException) {
                return ProviderFailure.AUTHENTICATION;
            }
            if (cause instanceof ModelNotFoundException) {
                return ProviderFailure.DEPLOYMENT_NOT_FOUND;
            }
            if (cause instanceof InvalidRequestException) {
                return ProviderFailure.REQUEST_REJECTED;
            }
        }
        if (providerStatus != null) {
            return switch (providerStatus) {
                case 400, 422 -> ProviderFailure.REQUEST_REJECTED;
                case 401, 403 -> ProviderFailure.AUTHENTICATION;
                case 404 -> ProviderFailure.DEPLOYMENT_NOT_FOUND;
                case 408, 504 -> ProviderFailure.TIMEOUT;
                case 500, 502, 503 -> ProviderFailure.UNAVAILABLE;
                default -> ProviderFailure.UNKNOWN;
            };
        }
        return ProviderFailure.UNKNOWN;
    }

    private static List<Throwable> causes(Throwable exception) {
        List<Throwable> causes = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = exception; current != null && causes.size() < 32 && visited.add(current);
             current = current.getCause()) {
            causes.add(current);
        }
        return causes;
    }

    private enum ProviderFailure {
        RATE_LIMIT("Azure OpenAI throttled this run. Wait a few seconds and run the pattern again, "
                + "or raise the deployment capacity if a whole room is running the lab."),
        OUTPUT_LIMIT("Azure OpenAI reached the response token limit before completing an answer. "
                + "Narrow the request and try again."),
        CONTENT_FILTER("Azure OpenAI blocked the response with its content filter. Rephrase the request and try again."),
        TIMEOUT("Azure OpenAI timed out before completing the response. "
                + "Try again; if this persists, check provider latency and connectivity."),
        AUTHENTICATION("Azure OpenAI rejected the application's credentials or access permissions. "
                + "Check the server's identity and Azure OpenAI role assignments."),
        DEPLOYMENT_NOT_FOUND("Azure OpenAI could not find the configured deployment or endpoint. "
                + "Check the server's Azure OpenAI configuration."),
        REQUEST_REJECTED("Azure OpenAI rejected the model request. "
                + "Check that the configured deployment supports the requested parameters."),
        UNAVAILABLE("Azure OpenAI is temporarily unavailable. Try again shortly."),
        UNKNOWN("The model provider did not complete this run. "
                + "Try again; if it persists, check the server's provider diagnostics.");

        private final String detail;

        ProviderFailure(String detail) {
            this.detail = detail;
        }
    }
}