package com.panel.knowledge.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动时把上次进程中断在中间状态的文档重新入队，避免永远卡在 PARSING/INDEXING。 */
@Component
public class KbIngestRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KbIngestRecoveryRunner.class);

    private final KbDocumentRepository documents;
    private final KbIngestService ingest;

    public KbIngestRecoveryRunner(KbDocumentRepository documents, KbIngestService ingest) {
        this.documents = documents;
        this.ingest = ingest;
    }

    @Override
    public void run(ApplicationArguments args) {
        var pending = documents.findRecoverable();
        if (pending.isEmpty()) {
            return;
        }
        log.info("恢复 {} 个未完成的知识库文档入库任务", pending.size());
        pending.forEach(document -> ingest.enqueue(document.getId()));
    }
}
