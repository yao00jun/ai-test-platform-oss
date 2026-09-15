package com.aitest.job;

import java.time.Instant;
import java.util.Map;

public record JobEvent(long seq, String type, Map<String, Object> data, Instant time) { }
