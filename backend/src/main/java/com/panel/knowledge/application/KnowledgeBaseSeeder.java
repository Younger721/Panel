package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KbChunkStrategy;
import com.panel.knowledge.domain.model.KbKind;
import com.panel.knowledge.domain.model.KbVisibility;
import com.panel.knowledge.domain.model.KnowledgeBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 首次启动时创建三个内置知识库；已存在则跳过，可反复执行。 */
@Component
public class KnowledgeBaseSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseSeeder.class);

    private final KnowledgeBaseRepository repository;

    public KnowledgeBaseSeeder(KnowledgeBaseRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed("question-bank", "面试题库", "按题目、考点、参考答案、评分点组织的面试题库",
                KbKind.QUESTION_BANK, KbVisibility.INTERNAL, KbChunkStrategy.QA, 400, 0, 8, "0.250");
        seed("tech-knowledge", "技术知识", "八股、框架原理、项目复盘等技术资料",
                KbKind.TECH_DOC, KbVisibility.PUBLIC, KbChunkStrategy.TECH_DOC, 500, 60, 8, "0.200");
        seed("job-knowledge", "岗位与公司", "JD、公司介绍、面经与岗位要求",
                KbKind.JOB_KNOWLEDGE, KbVisibility.PUBLIC, KbChunkStrategy.JD, 400, 40, 6, "0.200");
    }

    private void seed(String code, String name, String description, KbKind kind, KbVisibility visibility,
                      KbChunkStrategy strategy, int chunkSize, int chunkOverlap, int topK, String threshold) {
        if (repository.findByCode(code).isPresent()) {
            return;
        }
        repository.save(KnowledgeBase.builtin(code, name, description, kind, visibility, strategy,
                chunkSize, chunkOverlap, topK, threshold));
        log.info("已创建内置知识库：{}（{}）", name, code);
    }
}
