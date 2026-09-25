package com.aitest.notification;

import com.aitest.engine.http.HttpTransport;
import com.aitest.execution.*;
import com.aitest.job.*;
import org.springframework.stereotype.Component;
import javax.net.ssl.SSLException;
import java.net.*;
import java.net.http.HttpConnectTimeoutException;
import java.time.Instant;
import java.util.*;

@Component
public final class NotificationJobHandler implements JobHandler {
    private final NotificationService notifications;
    private final WebhookProtocol protocol;
    private final HttpTransport transport;
    public NotificationJobHandler(NotificationService notifications, WebhookProtocol protocol, HttpTransport transport) { this.notifications = notifications; this.protocol = protocol; this.transport = transport; }
    @Override public String kind() { return NotificationService.KIND; }
    @Override public Map<String, Object> execute(JobContext job, Map<String, Object> input) {
        String delivery = Values.text(input, "deliveryId", ""), attempt = Values.text(input, "attemptId", "");
        var send = notifications.begin(job, delivery, attempt);
        if (send == null) return Map.of("deliveryId", delivery, "outcome", "NOT_SENT");
        WebhookProtocol.Result result;
        try {
            var request = protocol.request(send.config(), send.report(), Instant.now());
            int timeout = Values.integer(send.config(), "timeoutSeconds", 10, 1, 60) * 1000;
            int connectTimeout = Math.max(100, Math.min(5000, timeout / 3));
            var response = transport.exchange("POST", request.url(), Map.of("Content-Type", "application/json; charset=utf-8", "X-AITest-Delivery-Id", delivery), request.body(), timeout,
                    Map.of("connectTimeoutMs", connectTimeout, "maxResponseBytes", 16384, "followRedirects", false, "trustSelfSigned", false), new ExecutionContext(Map.of(), job::checkpoint));
            result = protocol.response(Values.text(send.config(), "platform", "DINGTALK"), ((Number) response.get("status")).intValue(), response.get("body").toString());
        } catch (Exception failure) {
            // Once send may have started, only definite pre-connection failures are safe
            // to replay. Never persist exception messages containing signed URLs.
            boolean tlsFailure = causedBy(failure, SSLException.class);
            boolean notConnected = tlsFailure || causedBy(failure, ConnectException.class) || causedBy(failure, UnknownHostException.class) || causedBy(failure, HttpConnectTimeoutException.class);
            String code = tlsFailure ? "TLS_FAILED" : notConnected ? "CONNECT_FAILED" : "DELIVERY_NOT_CONFIRMED";
            result = new WebhookProtocol.Result(notConnected ? "REJECTED" : "UNCERTAIN", notConnected && !tlsFailure, code, null);
        }
        boolean interrupted = Thread.interrupted();
        try { notifications.completed(job, delivery, attempt, result); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
        return Map.of("deliveryId", delivery, "attemptId", attempt, "outcome", result.outcome());
    }
    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) if (type.isInstance(current)) return true;
        return false;
    }
}
