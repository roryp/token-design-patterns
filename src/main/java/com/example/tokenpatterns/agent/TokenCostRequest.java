package com.example.tokenpatterns.agent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The token counts and per-million rates that a cost request states explicitly. Values are read by their
 * labels, never by position; anything missing, negative, repeated, or ambiguous is rejected, not guessed.
 */
public record TokenCostRequest(BigDecimal inputTokens, BigDecimal outputTokens,
                               BigDecimal inputRate, BigDecimal outputRate) {

    private static final String EXAMPLE =
            "for example \"50M input tokens and 10M output tokens at $0.15/$0.60 per million\"";
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final BigDecimal MAX_TOKENS = new BigDecimal("1000000000000000");

    private static final String NUMBER = "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)";
    private static final String UNIT = "(?:\\s*(k|thousand|m|mn|million|b|bn|billion)\\b)?";
    private static final String KIND = "(input|prompt|output|completion)";
    private static final String PER_MILLION = "(?:per|/)\\s*(?:1\\s*)?(?:m|mn|million)\\b";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    // "50M input tokens", "500,000 output tokens", "-5M input"; a digit inside "$0.60" or "2.5" never starts a count.
    private static final Pattern COUNT_BEFORE_LABEL = Pattern.compile(
            "(?<![\\w$/.,\u2212-])([-\u2212]\\s*)?" + NUMBER + UNIT + "\\s+(?:tokens?\\s+)?" + KIND + "\\b", FLAGS);
    // "input tokens: 500,000", "output = 10M"
    private static final Pattern LABEL_BEFORE_COUNT = Pattern.compile(
            "\\b" + KIND + "(?:\\s+tokens?)?\\s*(?::|=|\\bof\\b)\\s*([-\u2212]\\s*)?" + NUMBER + UNIT + "(?![\\w.,]|\\s*/)", FLAGS);
    // "zero output tokens", "no output"
    private static final Pattern NO_TOKENS = Pattern.compile("\\b(?:zero|no)\\s+(?:tokens?\\s+)?" + KIND + "\\b", FLAGS);
    // "$0.15/$0.60 per million": input rate, then output rate
    private static final Pattern RATE_PAIR = Pattern.compile(
            "([-\u2212]\\s*)?\\$?\\s*([-\u2212]\\s*)?" + NUMBER + "\\s*/\\s*([-\u2212]\\s*)?\\$?\\s*([-\u2212]\\s*)?" + NUMBER
                    + "\\s*" + PER_MILLION, FLAGS);
    // "$0.15 per million input tokens", "$0.60/M output"
    private static final Pattern LABELED_RATE = Pattern.compile(
            "([-\u2212]\\s*)?\\$\\s*([-\u2212]\\s*)?" + NUMBER + "\\s*" + PER_MILLION + "(?:\\s+tokens?)?\\s+(?:for\\s+)?"
                    + KIND + "\\b", FLAGS);

    /** Reads a cost request, or explains in the exception message exactly what it must state. */
    public static TokenCostRequest parse(String request) {
        String text = request == null ? "" : request;
        List<BigDecimal> inputCounts = new ArrayList<>();
        List<BigDecimal> outputCounts = new ArrayList<>();

        Matcher before = COUNT_BEFORE_LABEL.matcher(text);
        while (before.find()) {
            count(before.group(4), before.group(1), before.group(2), before.group(3), inputCounts, outputCounts);
        }
        Matcher after = LABEL_BEFORE_COUNT.matcher(text);
        while (after.find()) {
            count(after.group(1), after.group(2), after.group(3), after.group(4), inputCounts, outputCounts);
        }
        Matcher none = NO_TOKENS.matcher(text);
        while (none.find()) {
            (isInput(none.group(1)) ? inputCounts : outputCounts).add(BigDecimal.ZERO);
        }

        List<BigDecimal[]> ratePairs = new ArrayList<>();
        Matcher pair = RATE_PAIR.matcher(text);
        while (pair.find()) {
            rejectNegative(pair.group(1), pair.group(2), pair.group(4), pair.group(5));
            ratePairs.add(new BigDecimal[] {number(pair.group(3)), number(pair.group(6))});
        }
        List<BigDecimal> inputRates = new ArrayList<>();
        List<BigDecimal> outputRates = new ArrayList<>();
        Matcher labeled = LABELED_RATE.matcher(text);
        while (labeled.find()) {
            rejectNegative(labeled.group(1), labeled.group(2));
            (isInput(labeled.group(4)) ? inputRates : outputRates).add(number(labeled.group(3)));
        }

        BigDecimal inputTokens = single(inputCounts,
                "The request must state an input token count, " + EXAMPLE + ".",
                "The request states more than one input token count; state it once.");
        BigDecimal outputTokens = single(outputCounts,
                "The request must state an output token count (use 0 for none), " + EXAMPLE + ".",
                "The request states more than one output token count; state it once.");

        BigDecimal inputRate;
        BigDecimal outputRate;
        if (ratePairs.size() > 1 || (!ratePairs.isEmpty() && (!inputRates.isEmpty() || !outputRates.isEmpty()))
                || inputRates.size() > 1 || outputRates.size() > 1) {
            throw new IllegalArgumentException("The request states the rates more than once; state each rate once.");
        }
        if (ratePairs.size() == 1) {
            inputRate = ratePairs.getFirst()[0];
            outputRate = ratePairs.getFirst()[1];
        } else if (inputRates.size() == 1 && outputRates.size() == 1) {
            inputRate = inputRates.getFirst();
            outputRate = outputRates.getFirst();
        } else {
            throw new IllegalArgumentException("The request must state both per-million rates, " + EXAMPLE + ".");
        }
        return new TokenCostRequest(inputTokens, outputTokens, inputRate, outputRate);
    }

    /** Exact cost in dollars; token counts are whole tokens and rates are dollars per million tokens. */
    public BigDecimal total() {
        return inputTokens.multiply(inputRate).add(outputTokens.multiply(outputRate)).divide(MILLION);
    }

    /** The auditable calculation, for example "0.5M input × $0.15/M + 0.1M output × $0.60/M = $0.135". */
    public String describe() {
        return "%sM input × $%s/M + %sM output × $%s/M = $%s".formatted(
                plain(inputTokens.divide(MILLION)), money(inputRate),
                plain(outputTokens.divide(MILLION)), money(outputRate), money(total()));
    }

    private static void count(String kind, String sign, String digits, String unit,
                              List<BigDecimal> inputCounts, List<BigDecimal> outputCounts) {
        rejectNegative(sign);
        BigDecimal tokens = number(digits).multiply(multiplier(unit));
        if (tokens.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Token counts must be whole tokens.");
        }
        if (tokens.compareTo(MAX_TOKENS) > 0) {
            throw new IllegalArgumentException("Token counts above 1,000,000,000,000,000 are not supported.");
        }
        (isInput(kind) ? inputCounts : outputCounts).add(tokens);
    }

    private static void rejectNegative(String... signs) {
        for (String sign : signs) {
            if (sign != null) {
                throw new IllegalArgumentException("Token counts and rates cannot be negative.");
            }
        }
    }

    private static BigDecimal single(List<BigDecimal> values, String missing, String repeated) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException(missing);
        }
        if (values.size() > 1) {
            throw new IllegalArgumentException(repeated);
        }
        return values.getFirst();
    }

    private static boolean isInput(String kind) {
        String normalized = kind.toLowerCase(Locale.ROOT);
        return normalized.equals("input") || normalized.equals("prompt");
    }

    private static BigDecimal number(String digits) {
        return new BigDecimal(digits.replace(",", ""));
    }

    private static BigDecimal multiplier(String unit) {
        if (unit == null) {
            return BigDecimal.ONE;
        }
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "k", "thousand" -> BigDecimal.valueOf(1_000);
            case "m", "mn", "million" -> MILLION;
            default -> BigDecimal.valueOf(1_000_000_000);
        };
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String money(BigDecimal value) {
        int scale = Math.min(6, Math.max(2, value.stripTrailingZeros().scale()));
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }
}
