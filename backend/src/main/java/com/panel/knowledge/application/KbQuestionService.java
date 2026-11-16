package com.panel.knowledge.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.knowledge.domain.model.KbQuestion;
import com.panel.knowledge.infrastructure.persistence.KbQuestionStore;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 题库浏览：列表 + 详情，标签从 JSON 还原成数组。
 *
 * <p>列表**不带答案**（刷题链路靠 {@code KbQuizService} 的 reveal 下发，浏览列表也不该提前看到）；
 * 答案只在单题详情里返回。</p>
 */
@Service
public class KbQuestionService {

    private final KbQuestionStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    public KbQuestionService(KbQuestionStore store) {
        this.store = store;
    }

    public KbQuestionPage list(Long kbId, int limit, int offset) {
        List<KbQuestionView> items = store.findByKb(kbId, limit, offset).stream()
                .map(question -> toView(question, false))
                .toList();
        return new KbQuestionPage(items, store.countByKb(kbId));
    }

    public KbQuestionView detail(Long questionId) {
        KbQuestion question = store.findById(questionId);
        if (question == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "题目不存在：" + questionId);
        }
        return toView(question, true);
    }

    private KbQuestionView toView(KbQuestion question, boolean withAnswer) {
        return new KbQuestionView(
                question.getId(),
                question.kbId(),
                question.documentId(),
                question.chunkId(),
                question.stem(),
                withAnswer ? question.answer() : null,
                question.difficulty(),
                question.company(),
                question.role(),
                question.year(),
                parseTags(question.tagsJson()),
                question.createdAt());
    }

    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(tagsJson, mapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception ex) {
            return List.of();
        }
    }
}
