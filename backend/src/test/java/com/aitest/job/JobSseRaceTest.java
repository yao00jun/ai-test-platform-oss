package com.aitest.job;

import com.aitest.security.PlatformSecurityProperties;
import com.aitest.security.PlatformSessionAccess;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class JobSseRaceTest {
    @Test void completionDuringAnEmptyDrainStillDeliversTerminalEventAndHonorsResumeCursor() throws Exception {
        JobService jobs = mock(JobService.class);
        AtomicBoolean committed = new AtomicBoolean();
        Instant now = Instant.now();
        when(jobs.get("project", "job")).thenAnswer(i -> new Job("job", "project", "TEST", committed.get() ? "SUCCEEDED" : "RUNNING", 0, "", Map.of(), null, now, now));
        when(jobs.events("project", "job", 99)).thenAnswer(i -> committed.getAndSet(true)
                ? List.of(new JobEvent(100, "done", Map.of("status", "SUCCEEDED"), now)) : List.of());
        JobController controller = new JobController(jobs, new PlatformSessionAccess(new PlatformSecurityProperties(false, "", "")));
        try {
            var mvc = MockMvcBuilders.standaloneSetup(controller).build();
            var result = mvc.perform(get("/api/jobs/job/events").param("projectId", "project").header("Last-Event-ID", "99")).andReturn();
            result.getAsyncResult(5000);
            String response = mvc.perform(asyncDispatch(result)).andReturn().getResponse().getContentAsString();
            assertThat(response).contains("id:100", "event:done", "SUCCEEDED");
            verify(jobs, atLeastOnce()).events("project", "job", 99);
            verify(jobs, never()).events("project", "job", 0);
        } finally { controller.close(); }
    }
}
