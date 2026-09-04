package com.example.tokenpatterns.web;

import com.example.tokenpatterns.agent.ModelCatalog;
import com.example.tokenpatterns.domain.PatternDefinition;
import com.example.tokenpatterns.domain.PatternRunRequest;
import com.example.tokenpatterns.domain.PatternRunResult;
import com.example.tokenpatterns.service.PatternCatalog;
import com.example.tokenpatterns.service.PatternRunner;
import jakarta.validation.Valid;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.exception.RateLimitException;
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
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PatternController {

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
    public Map<String, String> clearCache() {
        runner.clearCache();
        return Map.of("status", "cleared");
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ProblemDetail badRequest(RuntimeException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Pattern run could not be started");
        problem.setType(URI.create("https://example.com/problems/pattern-run"));
        return problem;
    }

    @ExceptionHandler(LangChain4jException.class)
    public ProblemDetail modelProviderFailure(LangChain4jException exception) {
        if (isRateLimited(exception)) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Azure OpenAI throttled this run. Wait a few seconds and run the pattern again, "
                            + "or raise the deployment capacity if a whole room is running the lab.");
            problem.setTitle("Model provider rate limit reached");
            problem.setType(URI.create("https://example.com/problems/rate-limit"));
            return problem;
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY,
                "The model provider did not complete this run: " + exception.getMessage());
        problem.setTitle("Model provider call failed");
        problem.setType(URI.create("https://example.com/problems/model-provider"));
        return problem;
    }

    private static boolean isRateLimited(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof RateLimitException
                    || (current instanceof HttpException http && http.statusCode() == 429)) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }
}