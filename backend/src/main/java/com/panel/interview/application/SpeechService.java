package com.panel.interview.application;

import com.panel.ai.application.SpeechSynthesisGateway;
import com.panel.ai.infrastructure.bailian.SpeechProperties;
import com.panel.interview.domain.model.InterviewSession;
import com.panel.interview.domain.model.InterviewTurn;
import com.panel.interview.domain.model.InterviewerPersona;
import com.panel.interview.domain.model.SpeechAsset;
import com.panel.interview.domain.repository.InterviewSessionRepository;
import com.panel.interview.domain.repository.InterviewTurnRepository;
import com.panel.interview.domain.repository.SpeechAssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 把「题目文本」变成「可播放的音频」。
 *
 * <p>刻意<b>不加 {@code @Transactional}</b>：合成一次要几秒，事务里包着外部 HTTP 调用
 * 会长时间占着数据库连接。这里每步都是独立的短操作。</p>
 *
 * <p>契约同 {@link SpeechSynthesisGateway}：拿不到音频就返回 {@link Optional#empty()}，
 * 上层把它翻译成 204，前端静音继续面试。</p>
 */
@Service
public class SpeechService {

    private static final Logger log = LoggerFactory.getLogger(SpeechService.class);

    /** 开场白（放在 Planner 出题前，盖住冷启动）。 */
    public static final String CANNED_OPENING = "OPENING";
    /** 中性承接语（用户答完后立刻出一声，避免静默）。 */
    public static final String CANNED_ACK = "ACK";

    private final SpeechSynthesisGateway gateway;
    private final SpeechAssetRepository assets;
    private final InterviewSessionRepository sessions;
    private final InterviewTurnRepository turns;
    private final SpeechProperties properties;

    public SpeechService(SpeechSynthesisGateway gateway,
                         SpeechAssetRepository assets,
                         InterviewSessionRepository sessions,
                         InterviewTurnRepository turns,
                         SpeechProperties properties) {
        this.gateway = gateway;
        this.assets = assets;
        this.sessions = sessions;
        this.turns = turns;
        this.properties = properties;
    }

    /** 朗读某一轮的问题。seq 不存在时返回空。 */
    public Optional<SpeechAudio> forTurn(Long sessionId, int seq) {
        InterviewSession session = sessions.findById(sessionId).orElse(null);
        if (session == null) {
            return Optional.empty();
        }
        InterviewTurn turn = turns.findBySessionIdAndSeq(sessionId, seq).orElse(null);
        if (turn == null || turn.getQuestion() == null || turn.getQuestion().isBlank()) {
            return Optional.empty();
        }
        return resolve(turn.getQuestion(), personaOf(session));
    }

    /** 固定话术：开场白或承接语。 */
    public Optional<SpeechAudio> forCanned(Long sessionId, String key) {
        InterviewSession session = sessions.findById(sessionId).orElse(null);
        if (session == null) {
            return Optional.empty();
        }
        InterviewerPersona persona = personaOf(session);
        String text = CANNED_ACK.equalsIgnoreCase(key)
                ? persona.ack()
                : persona.opening(session.getQuestionCount());
        return resolve(text, persona);
    }

    private InterviewerPersona personaOf(InterviewSession session) {
        return InterviewerPersona.fromCode(session.getPersona());
    }

    private Optional<SpeechAudio> resolve(String text, InterviewerPersona persona) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        int limit = Math.max(1, properties.getMaxChars());
        if (normalized.length() > limit) {
            normalized = normalized.substring(0, limit);
        }
        String voice = persona.voice();
        String instruction = persona.instruction();
        String cacheKey = cacheKey(properties.getModel(), voice, instruction, normalized);

        SpeechAsset cached = assets.findByCacheKey(cacheKey).orElse(null);
        if (cached != null && cached.getAudioBytes() != null && cached.getAudioBytes().length > 0) {
            return Optional.of(new SpeechAudio(cached.getAudioBytes(), cached.getMimeType(), cached.getVoice()));
        }

        SpeechSynthesisGateway.SpeechAudio synthesized = gateway.synthesize(normalized, voice, instruction);
        if (synthesized == null || synthesized.content() == null || synthesized.content().length == 0) {
            return Optional.empty();
        }
        persistQuietly(cacheKey, normalized, synthesized);
        return Optional.of(new SpeechAudio(synthesized.content(), synthesized.mimeType(), synthesized.voice()));
    }

    /** 写缓存失败不影响这一次播放，只是下次还要重新合成。 */
    private void persistQuietly(String cacheKey, String text,
                                SpeechSynthesisGateway.SpeechAudio synthesized) {
        try {
            assets.save(SpeechAsset.create(cacheKey, synthesized.model(), synthesized.voice(),
                    text.length(), synthesized.mimeType(), synthesized.content()));
        } catch (DataIntegrityViolationException duplicate) {
            // 并发合成同一句：别人已经写进去了，正常
        } catch (Exception failure) {
            log.warn("Speech cache write failed: key={}, reason={}", cacheKey, failure.getMessage());
        }
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replaceAll("\\*\\*|__|`", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String cacheKey(String model, String voice, String instruction, String text) {
        String payload = model + "|" + voice + "|" + instruction + "|" + text;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record SpeechAudio(byte[] content, String mimeType, String voice) {
    }
}
