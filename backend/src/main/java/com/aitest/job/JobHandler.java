package com.aitest.job;

import java.util.Map;

public interface JobHandler {
    String kind();
    Map<String, Object> execute(JobContext context, Map<String, Object> input) throws Exception;
}
