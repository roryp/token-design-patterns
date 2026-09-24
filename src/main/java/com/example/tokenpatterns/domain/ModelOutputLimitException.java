package com.example.tokenpatterns.domain;

import dev.langchain4j.exception.NonRetriableException;

public final class ModelOutputLimitException extends NonRetriableException {

    public ModelOutputLimitException() {
        super("Azure OpenAI response was truncated because the completion token limit was reached");
    }
}
