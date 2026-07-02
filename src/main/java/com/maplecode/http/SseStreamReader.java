package com.maplecode.http;

import com.maplecode.error.ProviderException;

import java.net.http.HttpResponse;
import java.util.stream.Stream;

public final class SseStreamReader {

    public record SseEvent(String eventType, String data) {}

    private static final String DEFAULT_EVENT = "message";

    public void read(HttpResponse<Stream<String>> response, java.util.function.Consumer<SseEvent> eventSink) {
        String currentEvent = DEFAULT_EVENT;
        StringBuilder data = new StringBuilder();
        /** 是否收到过data: 行 */
        boolean hasData = false;

        try (Stream<String> lines = response.body()) {
            for (String line : (Iterable<String>) lines::iterator) {
                // 空行表示一个事件结束（读完了）
                if (line.isEmpty()) {
                    if (hasData) {
                        eventSink.accept(new SseEvent(currentEvent, data.toString()));
                    }
                    currentEvent = DEFAULT_EVENT;
                    data.setLength(0);
                    hasData = false;
                    continue;
                }
                // : 开头-注释或心跳，丢弃
                if (line.startsWith(":")) {
                    // comment / heartbeat — ignore
                    continue;
                }
                // 事件类型：message、content_block_delta...
                if (line.startsWith("event:")) {
                    currentEvent = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    // 数据内容
                    String payload = line.substring(5).stripTrailing();
                    if (payload.startsWith(" ")) payload = payload.substring(1);
                    if (hasData) data.append('\n');
                    data.append(payload);
                    hasData = true;
                } else if (line.startsWith("id:") || line.startsWith("retry:")) {
                    // v1 doesn't handle
                }
                // other fields — ignore
            }
            // flush if stream ended without trailing blank line (SSE spec allows this)
            if (hasData) {
                eventSink.accept(new SseEvent(currentEvent, data.toString()));
            }
        } catch (RuntimeException e) {
            throw new ProviderException("SSE stream read failed", e);
        }
    }
}
