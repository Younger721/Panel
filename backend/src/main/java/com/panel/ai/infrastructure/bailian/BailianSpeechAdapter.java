package com.panel.ai.infrastructure.bailian;

import com.fasterxml.jackson.databind.JsonNode;
import com.panel.ai.application.SpeechSynthesisGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 百炼 Qwen-Audio-TTS 适配器（非实时合成接口）。
 *
 * <p>两步：先 POST 拿音频 URL（仅 24 小时有效），再 GET 下载字节。
 * 所以缓存必须落在我们自己的对象存储里，不能直接存这个 URL。</p>
 */
@Component
public class BailianSpeechAdapter implements SpeechSynthesisGateway {

    private static final Logger log = LoggerFactory.getLogger(BailianSpeechAdapter.class);

    private final RestClient client;
    private final HttpClient httpClient;
    private final SpeechProperties properties;
    private final DashScopeProperties dashScope;

    @Autowired
    public BailianSpeechAdapter(RestClient.Builder builder,
                                SpeechProperties properties,
                                DashScopeProperties dashScope) {
        this.properties = properties;
        this.dashScope = dashScope;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(10, properties.getTimeoutSeconds())));
        this.client = builder.requestFactory(requestFactory).build();
    }

    @Override
    public SpeechAudio synthesize(String text, String voice, String instruction) {
        String apiKey = resolveApiKey();
        if (!properties.isEnabled() || apiKey.isBlank() || text == null || text.isBlank()) {
            return null;
        }
        String effectiveVoice = voice == null || voice.isBlank() ? properties.getVoice() : voice;
        try {
            String audioUrl = requestAudioUrl(apiKey, text, effectiveVoice, instruction);
            if (audioUrl == null || audioUrl.isBlank()) {
                log.warn("Speech synthesis returned no audio url: model={}, voice={}",
                        properties.getModel(), effectiveVoice);
                return null;
            }
            byte[] content = download(audioUrl);
            if (content == null || content.length == 0) {
                log.warn("Speech synthesis downloaded empty audio: voice={}", effectiveVoice);
                return null;
            }
            return new SpeechAudio(content, mimeType(), properties.getModel(), effectiveVoice);
        } catch (Exception failure) {
            log.warn("Speech synthesis failed: model={}, voice={}, reason={}",
                    properties.getModel(), effectiveVoice, safeReason(failure));
            return null;
        }
    }

    /**
     * OSS 预签名 URL：Signature 里带 {@code %3D}、{@code +}、{@code /}，
     * 交给 RestClient 的 UriTemplate 会被再编码一次，OSS 直接回 {@code SignatureDoesNotMatch}。
     * 所以这里用 {@link HttpClient} 直接下，URI 原样不动。
     */
    private byte[] download(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(10, properties.getTimeoutSeconds())))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("audio download failed: HTTP " + response.statusCode());
        }
        return response.body();
    }

    private String requestAudioUrl(String apiKey, String text, String voice, String instruction) throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("text", text);
        input.put("voice", voice);
        input.put("format", properties.getFormat());
        input.put("sample_rate", properties.getSampleRate());
        String effectiveInstruction = instruction == null || instruction.isBlank()
                ? properties.getInstruction() : instruction;
        if (effectiveInstruction != null && !effectiveInstruction.isBlank()) {
            input.put("instruction", effectiveInstruction);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("input", input);

        JsonNode response = client.post()
                .uri(properties.getEndpoint())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            return null;
        }
        return response.path("output").path("audio").path("url").asText(null);
    }

    private String resolveApiKey() {
        String key = properties.getApiKey();
        return key == null || key.isBlank() ? dashScope.apiKeyForChat() : key;
    }

    private String mimeType() {
        String format = properties.getFormat() == null ? "" : properties.getFormat().trim().toLowerCase();
        return switch (format) {
            case "mp3" -> "audio/mpeg";
            case "opus" -> "audio/opus";
            case "pcm" -> "audio/pcm";
            default -> "audio/wav";
        };
    }

    private static String safeReason(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        String trimmed = message.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "…";
    }
}
