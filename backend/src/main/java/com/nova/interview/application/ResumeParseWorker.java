package com.nova.interview.application;

import com.nova.interview.domain.model.Resume;
import com.nova.interview.domain.model.ResumeProfile;
import com.nova.interview.domain.repository.ResumeProfileRepository;
import com.nova.interview.domain.repository.ResumeRepository;
import com.nova.knowledge.application.KnowledgeObjectStore;
import com.nova.shared.async.AsyncJobHandler;
import com.nova.shared.async.PersistentJob;
import com.nova.shared.async.PersistentJobQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 简历解析任务：{@code RESUME_PARSE:<resumeId>}。
 *
 * <p>幂等是这套设计的前提——Kafka 至少一次投递、轮询也会重复触发，
 * 所以进入处理先看状态：已 PARSED 直接完成，不重复调模型。</p>
 *
 * <p>失败策略与知识入库一致：重试 3 次（由队列退避控制），
 * 耗尽后简历置 FAILED 并把错误留在 {@code last_error}，用户能看到原因。</p>
 */
@Component
public class ResumeParseWorker implements AsyncJobHandler {

    private static final Logger log = LoggerFactory.getLogger(ResumeParseWorker.class);

    public static final String JOB_TYPE = "RESUME_PARSE";
    private static final int MAX_ATTEMPTS = 3;

    private final ResumeRepository resumes;
    private final ResumeProfileRepository profiles;
    private final KnowledgeObjectStore store;
    private final ResumeParsingService parsing;
    private final PersistentJobQueue jobs;

    public ResumeParseWorker(ResumeRepository resumes,
                             ResumeProfileRepository profiles,
                             KnowledgeObjectStore store,
                             ResumeParsingService parsing,
                             PersistentJobQueue jobs) {
        this.resumes = resumes;
        this.profiles = profiles;
        this.store = store;
        this.parsing = parsing;
        this.jobs = jobs;
    }

    @Override
    public boolean supports(String type) {
        return type != null && type.startsWith(JOB_TYPE + ":");
    }

    @Override
    public void handle(PersistentJob job, Instant now) {
        Resume resume = resumes.findById(documentId(job)).orElse(null);
        if (resume == null) {
            jobs.complete(job.id());
            return;
        }
        // 重复投递：已经解析过就不再花一次模型调用
        if (resume.getStatus() == com.nova.interview.domain.model.ResumeStatus.PARSED) {
            jobs.complete(job.id());
            return;
        }
        try {
            resume.markParsing();
            resumes.save(resume);

            byte[] bytes = store.read(resume.getObjectKey());
            if (bytes == null || bytes.length == 0) {
                throw new IllegalStateException("resume object is unavailable");
            }
            ResumeParsingService.ParseOutcome outcome =
                    parsing.parse(resume.getId(), resume.getFileName(), resume.getContentType(), bytes);

            ResumeProfile profile = profiles.findByResumeId(resume.getId())
                    .orElseGet(() -> ResumeProfile.create(resume.getId(), outcome.normalizedJson(), null, null, null));
            profile.replace(outcome.normalizedJson(),
                    outcome.parsed().yearsOfExperience(),
                    outcome.parsed().skills().size(),
                    outcome.modelName());
            profiles.save(profile);

            resume.markParsed();
            resumes.save(resume);
            jobs.complete(job.id());
        } catch (RuntimeException ex) {
            int attempts = job.attempts() + 1;
            if (attempts >= MAX_ATTEMPTS) {
                resume.markFailed(ex.getMessage());
            } else {
                resume.markRetryable(ex.getMessage());
            }
            resumes.save(resume);
            log.warn("Resume parse failed: id={}, attempt={}, error={}", resume.getId(), attempts, ex.toString());
            jobs.retry(job.id(), ex.getMessage(), now);
        }
    }

    private static Long documentId(PersistentJob job) {
        String suffix = job.type().substring(JOB_TYPE.length() + 1);
        try {
            return Long.parseLong(suffix);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("malformed resume job type: " + job.type(), ex);
        }
    }
}
