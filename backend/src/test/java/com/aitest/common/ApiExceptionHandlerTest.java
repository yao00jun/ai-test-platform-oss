package com.aitest.common;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.DeferredResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {
    @Test void unusableEventStreamCompletesWithoutJsonOrSecondaryErrors(CapturedOutput output) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FailureEndpoints()).setControllerAdvice(new ApiExceptionHandler()).build();
        var started = mvc.perform(get("/closed-stream")).andReturn();
        assertThat(started.getRequest().isAsyncStarted()).isTrue();
        var response = mvc.perform(asyncDispatch(started)).andReturn().getResponse();
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(output.toString()).doesNotContain("HttpMessageNotWritableException", "Failure in @ExceptionHandler", "failed (AsyncRequestNotUsableException)");
    }

    @Test void unrelatedFailuresStillReturnSanitizedJsonWithARequestId() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FailureEndpoints()).setControllerAdvice(new ApiExceptionHandler()).build();
        var response = mvc.perform(get("/ordinary-failure")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        var body = new JsonCodec().map(response.getContentAsString());
        assertThat(body).containsEntry("code", "INTERNAL_ERROR");
        assertThat(body.get("requestId")).isInstanceOf(String.class).isNotEqualTo("");
        assertThat(response.getContentAsString()).doesNotContain("private-failure-detail");
    }

    @RestController
    static class FailureEndpoints {
        @GetMapping("/closed-stream")
        DeferredResult<Void> closedStream(HttpServletResponse response) {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            var result = new DeferredResult<Void>();
            result.setErrorResult(new AsyncRequestNotUsableException("Response is no longer usable"));
            return result;
        }

        @GetMapping("/ordinary-failure")
        String ordinaryFailure() { throw new IllegalStateException("private-failure-detail"); }
    }
}
