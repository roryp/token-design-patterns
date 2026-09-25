package com.example.tokenpatterns.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TokenCostRequestTest {

    @Test
    void readsTheWorkshopSample() {
        var cost = TokenCostRequest.parse(
                "Estimate monthly cost for 50M input tokens and 10M output tokens at $0.15/$0.60 per million.");
        assertThat(cost.inputTokens()).isEqualByComparingTo("50000000");
        assertThat(cost.outputTokens()).isEqualByComparingTo("10000000");
        assertThat(cost.total()).isEqualByComparingTo("13.50");
        assertThat(cost.describe()).isEqualTo("50M input × $0.15/M + 10M output × $0.60/M = $13.50");
    }

    /** Commas are thousands separators and a bare count is tokens, never millions. */
    @Test
    void readsThousandsSeparatorsAsOneNumberAndKeepsSmallTotalsExact() {
        var cost = TokenCostRequest.parse(
                "Estimate monthly cost for 500,000 input tokens and 100,000 output tokens at $0.15/$0.60 per million.");
        assertThat(cost.inputTokens()).isEqualByComparingTo("500000");
        assertThat(cost.outputTokens()).isEqualByComparingTo("100000");
        assertThat(cost.total()).isEqualByComparingTo("0.135");
        assertThat(cost.describe()).isEqualTo("0.5M input × $0.15/M + 0.1M output × $0.60/M = $0.135");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "2.5M input tokens and 0 output tokens at $0.20/$0.80 per million | 0.50",
            "2.5M input tokens and no output tokens at $0.20/$0.80 per million | 0.50",
            "10M output tokens and 50M input tokens at $0.15/$0.60 per million | 13.50",
            "For 3 agents in 2026, estimate 50M input tokens and 10M output tokens at $0.15/$0.60 per million | 13.50",
            "50 million input tokens and 10 million output tokens at $0.15/$0.60 per 1M tokens | 13.50",
            "2B input tokens and 500k output tokens at $0.15/$0.60 per million | 300.30",
            "input tokens: 1,000,000; output tokens: 250,000; rates $0.15/$0.60 per million | 0.30",
            "50M input tokens and 10M output tokens at $0.15 per million input tokens and $0.60 per million output tokens | 13.50",
            "50M prompt tokens and 10M completion tokens at $0.15/$0.60 per M | 13.50"
    })
    void readsValuesByTheirLabelsNotTheirPositions(String request, String total) {
        assertThat(TokenCostRequest.parse(request).total()).isEqualByComparingTo(new BigDecimal(total));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "Estimate monthly cost for -5M input tokens and 10M output tokens at $0.15/$0.60 per million. | cannot be negative",
            "Estimate monthly cost for 5M input tokens and \u22122M output tokens at $0.15/$0.60 per million. | cannot be negative",
            "Estimate 5M input tokens and 10M output tokens at -$0.15/$0.60 per million. | cannot be negative",
            "Estimate 5M input tokens and 10M output tokens at $0.15/$-0.60 per million. | cannot be negative",
            "Estimate monthly cost at $0.15/$0.60 per million. | must state an input token count",
            "Estimate cost for 50M input tokens at $0.15/$0.60 per million. | must state an output token count",
            "Estimate cost for 50M input tokens and 10M output tokens. | must state both per-million rates",
            "Estimate cost for 50M input tokens and 10M output tokens at $0.15 per million input tokens. | must state both per-million rates",
            "50M input tokens, 20M input tokens and 10M output tokens at $0.15/$0.60 per million | more than one input token count",
            "50M input tokens and 10M output tokens at $0.15/$0.60 per million or $0.20/$0.80 per million | more than once",
            "1,5M input tokens and 10M output tokens at $0.15/$0.60 per million | must state an input token count",
            "1.5 input tokens and 10M output tokens at $0.15/$0.60 per million | whole tokens",
            "2000000B input tokens and 10M output tokens at $0.15/$0.60 per million | not supported",
            "Estimate monthly cost for 50000000 tokens at $0.15/$0.60 per million. | must state an input token count"
    })
    void rejectsMissingNegativeRepeatedOrAmbiguousValuesInsteadOfGuessing(String request, String message) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TokenCostRequest.parse(request))
                .withMessageContaining(message);
    }
}
