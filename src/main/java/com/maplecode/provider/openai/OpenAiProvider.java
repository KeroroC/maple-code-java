package com.maplecode.provider.openai;

import com.maplecode.config.AppConfig;
import com.maplecode.error.ProviderException;
import com.maplecode.http.SseStreamReader;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.StreamChunk;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

public final class OpenAiProvider implements LlmProvider {

    private final AppConfig config;
    private final HttpClient httpClient;
    private final OpenAiRequestMapper mapper = new OpenAiRequestMapper();
    private final OpenAiStreamParser parser = new OpenAiStreamParser();
    private final SseStreamReader sseReader = new SseStreamReader();

    public OpenAiProvider(AppConfig config) {
        this(config, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.timeouts().connectSeconds()))
            .build());
    }

    public OpenAiProvider(AppConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    @Override
    public void stream(ChatRequest request, Consumer<StreamChunk> sink) {
        HttpRequest httpReq = mapper.toHttpRequest(request, config.baseUrl(), config.apiKey());
        HttpResponse<java.util.stream.Stream<String>> resp;
        try {
            resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofLines());
        } catch (Exception e) {
            throw new ProviderException("HTTP request failed: " + e.getMessage(), e);
        }
        if (resp.statusCode() / 100 != 2) {
            String body = readBodyForError(resp);
            throw new ProviderException(
                "OpenAI returned HTTP " + resp.statusCode() + ": " + body);
        }
        parser.reset();
        sseReader.read(resp, ev -> parser.feed(ev, sink));
    }

    private String readBodyForError(HttpResponse<java.util.stream.Stream<String>> resp) {
        try {
            return resp.body().reduce("", (a, b) -> a + b);
        } catch (Exception e) {
            return "<body unavailable>";
        }
    }
}