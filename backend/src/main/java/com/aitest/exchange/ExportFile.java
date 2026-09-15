package com.aitest.exchange;

public record ExportFile(String filename, String mediaType, byte[] bytes) { }
