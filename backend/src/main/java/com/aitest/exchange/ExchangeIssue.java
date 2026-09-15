package com.aitest.exchange;

/** Source locations are safe to expose; messages must never embed uploaded values. */
public record ExchangeIssue(String source, int row, String field, String message) { }
