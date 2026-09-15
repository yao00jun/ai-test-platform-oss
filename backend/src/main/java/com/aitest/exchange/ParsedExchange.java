package com.aitest.exchange;

import java.util.List;

public record ParsedExchange(ExchangeBundle bundle, List<ExchangeIssue> errors) {
    public ParsedExchange { errors = List.copyOf(errors); }
    public static ParsedExchange valid(ExchangeBundle bundle) { return new ParsedExchange(bundle, List.of()); }
    public static ParsedExchange invalid(ExchangeIssue issue) { return new ParsedExchange(ExchangeBundle.of(List.of()), List.of(issue)); }
}
