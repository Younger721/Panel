package com.panel.interview.application;

import com.panel.interview.domain.model.Resume;
import com.panel.interview.domain.model.ResumeProfile;
import com.panel.interview.domain.model.ResumeStatus;
import com.panel.interview.domain.repository.ResumeProfileRepository;
import com.panel.interview.domain.repository.ResumeRepository;
import com.panel.knowledge.application.KnowledgeObjectStore;
import com.panel.shared.async.AsyncJobHandler;
import com.panel.shared.async.PersistentJob;
import com.panel.shared.async.PersistentJobQueue;
import com.panel.shared.error.BusinessException;
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
 * <p>失败策略：本类最多 3 次。达到上限时<b>同时</b>把数据库状态置 FAILED
 * 并把队列任务标记完成——之前是"简历置 FAILED，任务继续 retry 到队列自己的 5 次上限"，
 * 于是状态在 PENDING/FAILED 之间来回抖，前端看到的错误信息每次都不一样。</p>
 *
 * <p>状态迁移一律走 {@link ResumeRepository#updateStatus} 的窄更新：解析期间实体是脱离
 * 持久化上下文的，整行 {@code save} 会把用户在这几十秒里保存的新正文一起盖掉。</p>
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
        Long resumeId = documentId(job);
        // 连软删的也要读出来：用户删了再撤销时，这一行仍然值得解析完
        Resume resume = resumes.findAnyById(resumeId).orElse(null);
        if (resume == null) {
            // 记录已被物理删除（清理任务），任务直接收尾
            jobs.complete(job.id());
            return;
        }
        if (resume.getStatus() == ResumeStatus.PARSED) {
            // 重复投递：已经解析过就不再花一次模型调用
            jobs.complete(job.id());
            return;
        }
        try {
            resumes.updateStatus(resumeId, ResumeStatus.PARSING, null);

            byte[] bytes = store.read(resume.getEffectiveObjectKey());
            if (bytes == null || bytes.length == 0) {
                throw new IllegalStateException("resume object is unavailable");
            }
            // 文件名与 Content-Type 必须跟着源走：有 md 内容稿时解析器要靠 .md /
            // text/markdown 才会走 markdown 分支（# → 标题、- → 列表），
            // 否则整篇会被逐行当段落，章节结构当场丢掉。
            ResumeParsingService.ParseOutcome outcome =
                    parsing.parse(resumeId, resume.getEffectiveFileName(), resume.getEffectiveContentType(), bytes);

            // 解析是几十秒的事，期间用户很可能又保存了一版。这时手上这份结果已经过期，
            // 再写回去会把新的原文指针盖成旧的（改动看似保存了、其实被解析结果抹掉）。
            // 关键是不能就这么收尾：状态还停在 PARSING 的话，这一行永远回不到 PARSED，
            // 保存与重新分析都会被 inFlight 挡成 409。所以退回 PENDING 并重新投递。
            Resume current = resumes.findAnyById(resumeId).orElse(null);
            if (current == null) {
                jobs.complete(job.id());
                return;
            }
            // 比的是"解析时读的那份源"，不是 objectKey：md 内容稿存在时 objectKey
            // 一直指向原 PDF 不会变，只比它就会漏掉"解析期间用户又存了一版 md"。
            if (!current.getEffectiveObjectKey().equals(resume.getEffectiveObjectKey())) {
                log.info("Resume content changed while parsing, requeueing: id={}", resumeId);
                resumes.updateStatus(resumeId, ResumeStatus.PENDING, null);
                jobs.complete(job.id());
                requeue(resumeId);
                return;
            }

            ResumeProfile profile = profiles.findByResumeId(resumeId)
                    .orElseGet(() -> ResumeProfile.create(resumeId, outcome.normalizedJson(), null, null, null));
            profile.replace(outcome.normalizedJson(),
                    outcome.parsed().yearsOfExperience(),
                    outcome.parsed().skills().size(),
                    outcome.modelName());
            profiles.save(profile);

            // 第二段：原文已可用，继续做模块/小点的深度分析（失败会重试）
            resumes.updateStatus(resumeId, ResumeStatus.ANALYZING, null);
            analysis.analyze(resumeId, outcome.document(), resume.getOwnerUserId());

            resumes.updateStatus(resumeId, ResumeStatus.PARSED, null);
            jobs.complete(job.id());
        } catch (RuntimeException ex) {
            int attempts = job.attempts() + 1;
            // BusinessException 表示"用户给的这份文件就是这样"，重试三次结果一样，
            // 只会让用户多等 3 分钟、多烧 3 次模型调用。典型场景是扫描件 OCR 也没认出来。
            boolean retryable = !(ex instanceof BusinessException);
            boolean last = !retryable || attempts >= MAX_ATTEMPTS;
            // 用窄更新写失败态：不要碰用户在这期间改过的原文
            resumes.updateStatus(resumeId, last ? ResumeStatus.FAILED : ResumeStatus.PENDING, ex.getMessage());
            log.warn("Resume parse failed: id={}, attempt={}, retryable={}, last={}, error={}",
                    resumeId, attempts, retryable, last, ex.toString());
            if (last) {
                // 到上限就一起收尾，别让队列再按自己的上限重试，导致状态反复抖动
                jobs.complete(job.id());
            } else {
                try {
                    jobs.retry(job.id(), ex.getMessage(), now);
                } catch (RuntimeException retryFailure) {
                    // 重试入队本身失败也要咽下去：这里抛出去会变成调度线程的未捕获异常
                    log.warn("Resume parse retry enqueue failed: id={}, error={}",
                            resumeId, retryFailure.toString());
                }
            }
        }
    }

    private void requeue(Long resumeId) {
        try {
            jobs.enqueue(JOB_TYPE + ":" + resumeId, null);
        } catch (RuntimeException ex) {
            log.warn("Resume reparse enqueue failed: id={}, error={}", resumeId, ex.toString());
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
