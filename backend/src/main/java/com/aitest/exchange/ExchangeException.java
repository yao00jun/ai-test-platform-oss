package com.aitest.exchange;

public final class ExchangeException extends RuntimeException {
    private final ExchangeIssue issue;
    public ExchangeException(String source, int row, String field, String message) {
        super(message); issue = new ExchangeIssue(source, row, field, message);
    }
    public ExchangeIssue issue() { return issue; }
}
