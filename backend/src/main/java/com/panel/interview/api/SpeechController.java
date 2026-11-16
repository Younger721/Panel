package com.panel.interview.api;

import com.panel.interview.application.SpeechService;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 面试官语音。
 *
 * <p>只朗读<b>服务端已存在</b>的文本（题目或人设固定话术），不接受任意 text 参数——
 * 否则接口会变成免费 TTS 代理，被人拿去刷额度。</p>
 */
@RestController
@RequestMapping("/api/v1/interview/sessions")
@SecurityRequirement(name = "bearerAuth")
public class SpeechController {

    private final SpeechService speech;
    private final CurrentActorProvider actors;

    public SpeechController(SpeechService speech, CurrentActorProvider actors) {
        this.speech = speech;
        this.actors = actors;
    }

    /**
     * @param turnSeq 朗读第几轮的题目
     * @param canned  {@code OPENING} 开场白 / {@code ACK} 承接语（与 turnSeq 二选一）
     */
    @GetMapping("/{id}/speech")
    public ResponseEntity<byte[]> speech(@PathVariable Long id,
                                         @RequestParam(required = false) Integer turnSeq,
                                         @RequestParam(required = false) String canned) {
        require("interview:read");
        Optional<SpeechService.SpeechAudio> audio = turnSeq != null
                ? speech.forTurn(id, turnSeq)
                : speech.forCanned(id, canned == null || canned.isBlank()
                        ? SpeechService.CANNED_OPENING : canned);
        if (audio.isEmpty()) {
            // 204 而不是 500：前端据此静音降级，不该弹错误
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }
        SpeechService.SpeechAudio value = audio.get();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(value.mimeType()))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .body(value.content());
    }

    private void require(String permission) {
        CurrentActor actor = actors.requireCurrent();
        if (!actor.hasPermission(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
