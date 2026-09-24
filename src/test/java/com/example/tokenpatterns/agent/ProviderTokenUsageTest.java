package com.example.tokenpatterns.agent;

import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ProviderTokenUsageTest {

    @Test
    void keepsProviderTotalsAndSubsetsSeparate() {
        var usage = new ProviderTokenUsage(1000, 100, 1100, 800, 100, 40);

        assertEquals(1000, usage.inputTokenCount());
        assertEquals(100, usage.outputTokenCount());
        assertEquals(1100, usage.totalTokenCount());
        assertEquals(800, usage.cachedInputTokens());
        assertEquals(100, usage.cacheWriteTokens());
        assertEquals(40, usage.reasoningTokens());
    }

    @Test
    void unknownSubsetCountsStayUnknownAndExplicitZeroStaysZero() {
        var unknown = new ProviderTokenUsage(100, 20, 120, null, null, null);
        assertEquals(100, unknown.inputTokenCount());
        assertEquals(20, unknown.outputTokenCount());
        assertEquals(120, unknown.totalTokenCount());
        assertNull(unknown.cachedInputTokens());
        assertNull(unknown.cacheWriteTokens());
        assertNull(unknown.reasoningTokens());

        var zero = new ProviderTokenUsage(0, 0, 0, 0, 0, 0);
        assertEquals(0, zero.cachedInputTokens());
        assertEquals(0, zero.cacheWriteTokens());
        assertEquals(0, zero.reasoningTokens());
    }

    @Test
    void additionRetainsAllKnownProviderCountersWithoutDoubleCounting() {
        var first = new ProviderTokenUsage(100, 20, 120, 80, 10, 7);
        var second = new ProviderTokenUsage(10, 5, 15, 3, 2, 1);

        assertEquals(new ProviderTokenUsage(110, 25, 135, 83, 12, 8), first.add(second));
        assertEquals(first.add(second), second.add(first));
        assertSame(first, first.add(null));
        assertEquals(first.add(second), TokenUsage.sum(first, second));
    }

    @Test
    void additionDoesNotTurnPartiallyKnownCountersIntoCompleteTotals() {
        var first = new ProviderTokenUsage(100, 20, 120, 80, 10, 7);
        var second = new ProviderTokenUsage(10, 5, 15, null, 0, null);
        assertEquals(new ProviderTokenUsage(110, 25, 135, null, 10, null), first.add(second));

        TokenUsage plain = new TokenUsage(10, 5, 15);
        var expected = new ProviderTokenUsage(110, 25, 135, null, null, null);
        assertEquals(expected, first.add(plain));
        assertEquals(expected, plain.add(first));
    }

    @Test
    void additionPreservesUnknownGenericTotalsWithoutUnboxingOrInventingValues() {
        var first = new ProviderTokenUsage(100, 20, 120, 80, 10, 7);
        var unknown = first.add(new TokenUsage());
        assertNull(unknown.inputTokenCount());
        assertNull(unknown.outputTokenCount());
        assertNull(unknown.totalTokenCount());
        assertNull(unknown.cachedInputTokens());
        assertNull(unknown.cacheWriteTokens());
        assertNull(unknown.reasoningTokens());

        var partial = first.add(new TokenUsage(5, null, null));
        assertEquals(105, partial.inputTokenCount());
        assertNull(partial.outputTokenCount());
        assertNull(partial.totalTokenCount());
    }

    @Test
    void equalityIncludesProviderSubsets() {
        var usage = new ProviderTokenUsage(100, 20, 120, 80, 10, 7);
        var same = new ProviderTokenUsage(100, 20, 120, 80, 10, 7);
        assertEquals(usage, same);
        assertEquals(usage.hashCode(), same.hashCode());
        assertNotEquals(usage, new ProviderTokenUsage(100, 20, 120, 79, 10, 7));
        assertNotEquals(usage, new ProviderTokenUsage(100, 20, 120, 80, 9, 7));
        assertNotEquals(usage, new ProviderTokenUsage(100, 20, 120, 80, 10, 6));
        assertNotEquals(usage, new TokenUsage(100, 20, 120));
    }

    @Test
    void additionRejectsIntegerOverflow() {
        var largest = new ProviderTokenUsage(Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0, 0, 0);
        var one = new ProviderTokenUsage(1, 0, 1, 0, 0, 0);
        assertThrows(ArithmeticException.class, () -> largest.add(one));
    }

    @ParameterizedTest
    @MethodSource("invalidCounts")
    void rejectsNegativeOrInconsistentTelemetry(int input, int output, int total,
                                               Integer cached, Integer write, Integer reasoning) {
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderTokenUsage(input, output, total, cached, write, reasoning));
    }

    static Stream<Arguments> invalidCounts() {
        return Stream.of(
                Arguments.of(-1, 20, 19, null, null, null),
                Arguments.of(100, -1, 99, null, null, null),
                Arguments.of(100, 20, -1, null, null, null),
                Arguments.of(100, 20, 120, -1, null, null),
                Arguments.of(100, 20, 120, null, -1, null),
                Arguments.of(100, 20, 120, null, null, -1),
                Arguments.of(100, 20, 119, null, null, null),
                Arguments.of(100, 20, 120, 70, 40, null),
                Arguments.of(100, 20, 120, 101, null, null),
                Arguments.of(100, 20, 120, null, 101, null),
                Arguments.of(100, 20, 120, null, null, 21),
                Arguments.of(100, 0, 99, null, null, null),
                Arguments.of(0, 20, 19, null, null, null),
                Arguments.of(Integer.MAX_VALUE, 1, Integer.MAX_VALUE, null, null, null));
    }
}
