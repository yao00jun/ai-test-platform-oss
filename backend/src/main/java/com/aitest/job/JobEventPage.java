package com.aitest.job;

import java.util.List;

/** State and events observed by one database statement, including an empty terminal page. */
public record JobEventPage(String status, List<JobEvent> events) {
    public static final int LIMIT = 1000;
    public boolean terminal() { return Job.terminal(status); }
}
