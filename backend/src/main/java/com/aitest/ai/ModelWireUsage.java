package com.aitest.ai;

import com.aitest.common.JsonCodec;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.*;
import java.io.IOException;
import java.util.Map;

/** Bounded, non-consuming observation of provider JSON/SSE usage before the SDK normalizes it. */
final class ModelWireUsage {
    private static final long LIMIT = 2_100_000;
    private static final JsonCodec JSON = new JsonCodec();
    private final ModelCallTelemetry telemetry;
    private final boolean streaming;
    private final Buffer pending = new Buffer();
    private final StringBuilder event = new StringBuilder();
    private boolean discardedLine, discardedEvent, discardedBody;
    private ModelWireUsage(ModelCallTelemetry telemetry, boolean streaming) { this.telemetry = telemetry; this.streaming = streaming; }

    static ResponseBody wrap(ResponseBody body, ModelCallTelemetry telemetry) {
        MediaType type = body.contentType();
        if (type == null || !(type.toString().contains("text/event-stream") || type.toString().contains("json"))) return body;
        var observer = new ModelWireUsage(telemetry, type.toString().contains("text/event-stream"));
        BufferedSource source = Okio.buffer(new ForwardingSource(body.source()) {
            @Override public long read(Buffer sink, long count) throws IOException {
                long received = super.read(sink, count);
                if (received > 0) {
                    sink.copyTo(observer.pending, sink.size() - received, received);
                    observer.drain();
                } else if (received == -1) observer.end();
                return received;
            }
            @Override public void close() throws IOException {
                // A JSON parser may stop after the final object without requesting EOF.
                // Parse only the bytes actually consumed; incomplete JSON remains unknown.
                try { observer.end(); } finally { super.close(); }
            }
        });
        return new ResponseBody() {
            @Override public MediaType contentType() { return body.contentType(); }
            @Override public long contentLength() { return body.contentLength(); }
            @Override public BufferedSource source() { return source; }
        };
    }
    private void drain() throws IOException {
        if (!streaming) { if (discardedBody || pending.size() > LIMIT) { pending.clear(); discardedBody = true; } return; }
        long newline;
        while ((newline = pending.indexOf((byte) '\n')) >= 0) {
            if (discardedLine || newline > LIMIT) { pending.skip(newline + 1); discardedLine = false; discardedEvent = true; continue; }
            String line = pending.readUtf8LineStrict();
            if (line.isEmpty()) { if (!discardedEvent) accept(event.toString()); event.setLength(0); discardedEvent = false; }
            else if (line.startsWith("data:") && !discardedEvent) {
                if (event.length() + line.length() > LIMIT) { event.setLength(0); discardedEvent = true; }
                else { if (!event.isEmpty()) event.append('\n'); event.append(line.substring(5).stripLeading()); }
            }
        }
        if (pending.size() > LIMIT) { pending.clear(); discardedLine = true; discardedEvent = true; }
    }
    private void end() {
        if (!streaming && !discardedBody) accept(pending.readUtf8());
        if (streaming && !discardedEvent && pending.size() == 0 && !event.isEmpty()) accept(event.toString());
        pending.clear(); event.setLength(0);
    }
    private void accept(String data) {
        if (data.isBlank() || data.equals("[DONE]")) return;
        try { if (JSON.map(data).get("usage") instanceof Map<?, ?> usage) telemetry.wireUsage(usage); }
        catch (RuntimeException ignored) { /* Invalid or oversized metadata is unknown; the SDK still validates the response. */ }
    }
}
