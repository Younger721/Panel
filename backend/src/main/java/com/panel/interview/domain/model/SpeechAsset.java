package com.panel.interview.domain.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 一段合成好的语音，按 {@code cache_key} 去重。
 *
 * <p>题目文本重复率极高的（开场白、承接语、同一道题反复朗读），所以缓存是刚需：
 * 一次面试 8 轮大约只有 8 个唯一文本，重听或刷新页面时直接命中。</p>
 *
 * <p>音频字节直接存库（LONGBLOB）。量级很小——一句话 mp3 约 100KB、wav 约 700KB，
 * 一场面试全部音频也就几 MB。这样可以少依赖一个对象存储，
 * 本地开发时 MinIO 没起也不影响语音。真上规模再迁到对象存储，接口不变。</p>
 */
@Entity
@Table(name = "speech_asset",
        uniqueConstraints = @UniqueConstraint(name = "uk_speech_cache_key", columnNames = "cache_key"))
public class SpeechAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** sha256(model|voice|instruction|text)，十六进制 64 位。 */
    @Column(name = "cache_key", nullable = false, length = 64)
    private String cacheKey;

    @Column(nullable = false, length = 64)
    private String model;

    @Column(nullable = false, length = 64)
    private String voice;

    @Column(nullable = false)
    private int chars;

    @Column(name = "mime_type", nullable = false, length = 48)
    private String mimeType;

    @Column(name = "byte_size", nullable = false)
    private int byteSize;

    @Lob
    @Column(name = "audio_bytes", nullable = false, columnDefinition = "LONGBLOB")
    private byte[] audioBytes;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected SpeechAsset() {
    }

    public static SpeechAsset create(String cacheKey, String model, String voice, int chars,
                                     String mimeType, byte[] audioBytes) {
        SpeechAsset asset = new SpeechAsset();
        asset.cacheKey = cacheKey;
        asset.model = model;
        asset.voice = voice;
        asset.chars = chars;
        asset.mimeType = mimeType;
        asset.audioBytes = audioBytes;
        asset.byteSize = audioBytes == null ? 0 : audioBytes.length;
        return asset;
    }

    public Long getId() {
        return id;
    }

    public String getCacheKey() {
        return cacheKey;
    }

    public String getModel() {
        return model;
    }

    public String getVoice() {
        return voice;
    }

    public int getChars() {
        return chars;
    }

    public String getMimeType() {
        return mimeType;
    }

    public int getByteSize() {
        return byteSize;
    }

    public byte[] getAudioBytes() {
        return audioBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
