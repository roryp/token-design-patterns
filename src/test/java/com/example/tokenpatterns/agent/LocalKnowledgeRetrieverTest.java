package com.example.tokenpatterns.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalKnowledgeRetrieverTest {

    @Test
    void parallelQuestionsRetrieveTheParallelEntriesRatherThanCorpusOrder() {
        String context = PatternAgents.LocalKnowledgeRetriever.retrieveContext(
                "How can I execute independent tasks in parallel with LangChain4j agentic workflows?");
        assertThat(context.lines()).hasSize(2)
                .allSatisfy(line -> assertThat(line).startsWith("Parallel"));
        assertThat(context).contains("parallelMapperBuilder", "parallelBuilder").doesNotContain("Conditional");
    }

    @Test
    void identifiersAreSplitIntoWords() {
        String context = PatternAgents.LocalKnowledgeRetriever.retrieveContext("How do I use parallelMapperBuilder?");
        assertThat(context.lines().findFirst()).hasValueSatisfying(line ->
                assertThat(line).startsWith("Parallel mapper (parallelMapperBuilder)"));
    }

    @Test
    void theWorkshopSampleRetrievesAgenticScopeFirst() {
        String context = PatternAgents.LocalKnowledgeRetriever.retrieveContext(
                "How does AgenticScope share state between agents?");
        assertThat(context.lines().findFirst()).hasValueSatisfying(line -> assertThat(line).startsWith("AgenticScope:"));
    }

    @Test
    void unrelatedQuestionsRetrieveNothingInsteadOfTheFirstEntries() {
        assertThat(PatternAgents.LocalKnowledgeRetriever.retrieveContext("Which volcanoes erupted in Iceland last year?"))
                .isEqualTo(PatternAgents.LocalKnowledgeRetriever.NO_RELEVANT_KNOWLEDGE);
    }
}
