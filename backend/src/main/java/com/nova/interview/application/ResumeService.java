package com.nova.interview.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nova.interview.domain.model.Resume;
import com.nova.interview.domain.model.ResumeProfile;
import com.nova.interview.domain.model.ResumeStatus;
import com.nova.interview.domain.repository.ResumeProfileRepository;
import com.nova.interview.domain.repository.ResumeRepository;
import com.nova.knowledge.application.KnowledgeObjectStore;
import com.nova.shared.async.PersistentJobQueue;
import com.nova.shared.error.BusinessException;
import com.nova.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 简历上传与查询。
 *
 * <p>上传只做三件事：存原文、落一行 PENDING、投一条异步任务，然后立即返回。
 * 解析（模型调用，秒级到分钟级）全部在 worker 里做——接口不做同步阻塞。</p>
 */
@Service
public class ResumeService {

    private static final long MAX_BYTES = 10L * 1024 * 1024;
    /** 编辑器回写的正文上限，比原文件宽松：纯文本 20 万字符已远超正常简历 */
    private static final int MAX_TEXT_CHARS = 200_000;

    private final ResumeRepository resumes;
    private final ResumeProfileRepository profiles;
    private final KnowledgeObjectStore store;
    private final PersistentJobQueue jobs;
    private final ObjectMapper objectMapper;

    public ResumeService(ResumeRepository resumes,
                         ResumeProfileRepository profiles,
                         KnowledgeObjectStore store,
                         PersistentJobQueue jobs,
                         ObjectMapper objectMapper) {
        this.resumes = resumes;
        this.profiles = profiles;
        this.store = store;
        this.jobs = jobs;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ResumeView upload(String fileName, String contentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (bytes.length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String safeName = sanitize(fileName);
        String hash = sha256(bytes);

        // 同一份文件重复上传：复用原记录，避免重复烧模型 token；
        // 但若上一次没解析成功（PENDING 中断 / FAILED），必须重新投一次任务，
        // 否则用户重传同一份简历永远拿不到画像。
        Resume existing = resumes.findByContentHash(hash).orElse(null);
        if (existing != null) {
            if (existing.getStatus() != ResumeStatus.PARSED) {
                jobs.enqueue(ResumeParseWorker.JOB_TYPE + ":" + existing.getId(), null);
            }
            return view(existing);
        }

        String objectKey = "resume/" + UUID.randomUUID() + "/" + safeName;
        store.put(objectKey, bytes, contentType == null ? "application/octet-stream" : contentType);
        Resume resume = resumes.save(Resume.create(safeName,
                contentType == null ? "application/octet-stream" : contentType, objectKey, hash));
        jobs.enqueue(ResumeParseWorker.JOB_TYPE + ":" + resume.getId(), null);
        return view(resume);
    }

    public List<ResumeView> list() {
        return resumes.listRecent().stream().map(this::view).toList();
    }

    public ResumeView find(Long id) {
        return view(require(id));
    }

    /** 已解析则返回结构化画像；未解析时给出明确的业务错误，而不是空对象。 */
    public ParsedResume profile(Long id) {
        Resume resume = require(id);
        if (resume.getStatus() != ResumeStatus.PARSED) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        ResumeProfile profile = profiles.findByResumeId(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        try {
            return objectMapper.readValue(profile.getPayloadJson(), ParsedResume.class);
        } catch (Exception ex) {
            throw new IllegalStateException("stored resume profile is not readable", ex);
        }
    }

    private Resume require(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return resumes.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 编辑器保存：把改好的正文写回成新的原文，并立即排队重新解析。
     *
     * <p>存成 text/plain 而不是改 DOCX：编辑器产出的就是纯文本，
     * 假装回写二进制格式只会让解析器多做一次无意义的转换。</p>
     *
     * @param text 编辑器里的正文（纯文本，换行即分段）
     */
    @Transactional
    public ResumeView saveContent(Long id, String text) {
        Resume resume = require(id);
        if (inFlight(resume)) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        String content = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (content.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (content.length() > MAX_TEXT_CHARS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String objectKey = "resume/" + resume.getId() + "/edited-" + Instant.now().toEpochMilli() + ".txt";
        store.put(objectKey, bytes, "text/plain");
        resume.replaceSource(textName(resume.getFileName()), "text/plain", objectKey, sha256(bytes));
        resumes.save(resume);
        jobs.enqueue(ResumeParseWorker.JOB_TYPE + ":" + resume.getId(), null);
        return view(resume);
    }

    /** 原文不变，重跑解析 + 批注（模型升级或结果不满意时用）。 */
    @Transactional
    public ResumeView reanalyze(Long id) {
        Resume resume = require(id);
        if (inFlight(resume)) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        resume.resetForReparse();
        resumes.save(resume);
        jobs.enqueue(ResumeParseWorker.JOB_TYPE + ":" + resume.getId(), null);
        return view(resume);
    }

    /** 解析/分析进行中时再投一次任务没有意义，还会重复烧模型 token。 */
    private static boolean inFlight(Resume resume) {
        return resume.getStatus() == ResumeStatus.PARSING || resume.getStatus() == ResumeStatus.ANALYZING;
    }

    private static String textName(String fileName) {
        String base = fileName == null || fileName.isBlank() ? "resume" : fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return sanitize(base + ".txt");
    }

    private ResumeView view(Resume resume) {
        ResumeProfile profile = profiles.findByResumeId(resume.getId()).orElse(null);
        return new ResumeView(resume.getId(), resume.getFileName(), resume.getContentType(),
                resume.getStatus(), resume.getLastError(), resume.getCreatedAt(),
                profile != null,
                profile == null ? null : profile.getYearsOfExperience(),
                profile == null ? null : profile.getSkillCount());
    }

    private static String sanitize(String fileName) {
        String name = fileName == null || fileName.isBlank() ? "resume" : fileName.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** 列表/详情共用视图。null 字段表示"还没解析出来"，与"解析出 0"要能区分。 */
    public record ResumeView(Long id, String fileName, String contentType, ResumeStatus status,
                             String lastError, Instant createdAt, boolean hasProfile,
                             Integer yearsOfExperience, Integer skillCount) {
    }
}
