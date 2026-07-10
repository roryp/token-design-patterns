package com.example.tokenpatterns.web;

import com.example.tokenpatterns.agent.ModelCatalog;
import com.example.tokenpatterns.domain.PatternDefinition;
import com.example.tokenpatterns.domain.PatternRunRequest;
import com.example.tokenpatterns.domain.PatternRunResult;
import com.example.tokenpatterns.service.PatternCatalog;
import com.example.tokenpatterns.service.PatternRunner;
import jakarta.validation.Valid;
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
                "liveEnabled", models.liveEnabled(),
                "liveModels", models.liveModelSummary(),
                "agenticVersion", "1.17.2-beta27",
                "defaultMode", "demo");
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
}