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
    private final ResumeAnalysisService analysis;
    private final PersistentJobQueue jobs;

    public ResumeParseWorker(ResumeRepository resumes,
                             ResumeProfileRepository profiles,
                             KnowledgeObjectStore store,
                             ResumeParsingService parsing,
                             ResumeAnalysisService analysis,
                             PersistentJobQueue jobs) {
        this.resumes = resumes;
        this.profiles = profiles;
        this.store = store;
        this.parsing = parsing;
        this.analysis = analysis;
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

            // 解析是几十秒的事，期间用户很可能又保存了一版。这时手上这份结果已经过期，
            // 再写回去会把新的原文指针盖成旧的（改动看似保存了、其实被解析结果抹掉）。
            // 直接把任务收尾，让新投递的那次任务去处理最新内容。
            Resume current = resumes.findById(resume.getId()).orElse(null);
            if (current == null || !current.getObjectKey().equals(resume.getObjectKey())) {
                log.info("Resume content changed while parsing, dropping stale result: id={}", resume.getId());
                jobs.complete(job.id());
                return;
            }

            ResumeProfile profile = profiles.findByResumeId(resume.getId())
                    .orElseGet(() -> ResumeProfile.create(resume.getId(), outcome.normalizedJson(), null, null, null));
            profile.replace(outcome.normalizedJson(),
                    outcome.parsed().yearsOfExperience(),
                    outcome.parsed().skills().size(),
                    outcome.modelName());
            profiles.save(profile);

            // 第二段：原文已可用，继续做模块/小点的深度分析（失败会重试）
            current.markAnalyzing();
            resumes.save(current);
            analysis.analyze(resume.getId(), outcome.document());

            Resume done = resumes.findById(resume.getId()).orElse(current);
            done.markParsed();
            resumes.save(done);
            jobs.complete(job.id());
        } catch (RuntimeException ex) {
            int attempts = job.attempts() + 1;
            // 同样用最新的一份，别把失败状态写到已经被新版本替换掉的原文上
            Resume failed = resumes.findById(resume.getId()).orElse(resume);
            if (attempts >= MAX_ATTEMPTS) {
                failed.markFailed(ex.getMessage());
            } else {
                failed.markRetryable(ex.getMessage());
            }
            resumes.save(failed);
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
