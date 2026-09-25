package com.aitest.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(Problem.class)
    ResponseEntity<?> problem(Problem problem) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", problem.code()); body.put("message", problem.getMessage());
        body.put("details", problem.details()); body.put("requestId", UUID.randomUUID().toString());
        return ResponseEntity.status(problem.status()).body(body);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    ResponseEntity<?> badRequest(Exception exception) {
        return problem(Problem.invalid("请求字段无效，请检查必填字段和 JSON 格式"));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> integrity(DataIntegrityViolationException exception) {
        return problem(new Problem(409, "REFERENCE_CONFLICT", "记录存在引用或重复值，当前更改未保存"));
    }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<?> notFound(Exception exception) { return problem(Problem.missing()); }
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void disconnected(AsyncRequestNotUsableException exception) {
        // The client has closed the asynchronous response; no replacement body can be sent.
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception exception) {
        String requestId = UUID.randomUUID().toString();
        LOG.error("Request {} failed", requestId, exception);
        return ResponseEntity.internalServerError().body(Map.of("code", "INTERNAL_ERROR", "message", "操作失败，请根据请求编号查看服务日志", "requestId", requestId));
    }
}
