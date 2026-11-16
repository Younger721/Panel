package com.panel.interview.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.interview.domain.model.Resume;
import com.panel.interview.domain.model.ResumeProfile;
import com.panel.interview.domain.model.ResumeStatus;
import com.panel.interview.domain.repository.ResumeAnalysisRepository;
import com.panel.interview.domain.repository.ResumeProfileRepository;
import com.panel.interview.domain.repository.ResumeRepository;
import com.panel.knowledge.application.KnowledgeObjectStore;
import com.panel.shared.async.PersistentJobQueue;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.CurrentActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 简历上传与查询。
 *
 * <p>上传只做三件事：存原文、落一行 PENDING、投一条异步任务，然后立即返回。
 * 解析（模型调用，秒级到分钟级）全部在 worker 里做——接口不做同步阻塞。</p>
 *
 * <p>所有按 id 的访问都经过 {@link ResumeAccessGuard}：归属不符一律 404。</p>
 */
@Service
public class ResumeService {

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);

    private static final long MAX_BYTES = 10L * 1024 * 1024;
    /** 编辑器回写的正文上限，比原文件宽松：纯文本 20 万字符已远超正常简历 */
    private static final int MAX_TEXT_CHARS = 200_000;
    /** 单次保存最多接受这么多板块改动（一份简历的板块数远小于此） */
    private static final int MAX_EDIT_SECTIONS = 200;
    /** 板块改动里可能带插图（base64），整体限个大小，别让一个请求把库撑爆 */
    private static final int MAX_EDIT_CHARS = 8 * 1024 * 1024;

    private final ResumeRepository resumes;
    private final ResumeProfileRepository profiles;
    private final ResumeAnalysisRepository analyses;
    private final KnowledgeObjectStore store;
    private final PersistentJobQueue jobs;
    private final ObjectMapper objectMapper;
    private final ResumeAccessGuard access;

    public ResumeService(ResumeRepository resumes,
                         ResumeProfileRepository profiles,
                         ResumeAnalysisRepository analyses,
                         KnowledgeObjectStore store,
                         PersistentJobQueue jobs,
                         ObjectMapper objectMapper,
                         ResumeAccessGuard access) {
        this.resumes = resumes;
        this.profiles = profiles;
        this.analyses = analyses;
        this.store = store;
        this.jobs = jobs;
        this.objectMapper = objectMapper;
        this.access = access;
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
        // Content-Type 由客户端决定，不能信：按扩展名归一，再用魔数验一次内容。
        // 不做这一步的话，`.doc`/压缩包会被当成简历收下，白烧三次模型重试才失败。
        String normalizedType = normalizeContentType(safeName, bytes);
        String hash = sha256(bytes);
        CurrentActor actor = access.currentActor();
        Long ownerUserId = actor.userId();

        // 同一份文件重复上传：复用原记录，避免重复烧模型 token；
        // 但若上一次没解析成功（PENDING 中断 / FAILED），必须重新投一次任务，
        // 否则用户重传同一份简历永远拿不到画像。
        // 去重按 (owner, hash)：跨用户不会命中彼此的记录。
        Resume existing = resumes.findByOwnerAndContentHash(ownerUserId, hash).orElse(null);
        if (existing == null && actor.isPlatformAdmin()) {
            // 迁移前的历史记录没有归属列，管理员重传同一份文件时可以复用，不必重新解析
            existing = resumes.findLegacyByContentHash(hash).orElse(null);
        }
        boolean adoptedByName = false;
        if (existing == null) {
            // 兜底：同名的旧记录、原件已经丢了 —— 认领它，而不是再建一条新的。
            // 不这么做的话，用户每重传一次就多一行坏记录，旧那条永远停在"原件丢失"，
            // 编辑页反复点开旧记录就会反复看到提示。
            existing = resumes.findByOwnerAndFileName(ownerUserId, safeName).stream()
                    .filter(this::sourceMissing)
                    .findFirst()
                    .orElse(null);
            adoptedByName = existing != null;
        }
        if (existing != null) {
            // 之前删过又传回来：复原原记录。画像/批注都还在，不必重新烧一遍模型。
            if (existing.isDeleted()) {
                existing.restore();
                resumes.save(existing);
            }
            // 老数据没有归属（NULL）：这次就是归属人，补上，免得以后自己都看不见
            if (existing.getOwnerUserId() == null) {
                existing.assignOwnerIfAbsent(ownerUserId);
                resumes.save(existing);
            }
            // 原件丢了（对象存储被清理 / 迁移后丢失）：界面会自动退回"只能改正文"的降级模式，
            // 并提示"重新上传可以恢复原版"。既然用户真的重传了、字节就在手上，就把原件补回去，
            // 否则那句提示永远兑现不了 —— 去重分支原样返回，谁也不写对象。
            if (sourceMissing(existing)) {
                String healedKey = "resume/" + UUID.randomUUID() + "/" + safeName;
                store.put(healedKey, bytes, normalizedType);
                existing.healSource(safeName, normalizedType, healedKey, hash);
                resumes.save(existing);
            }
            if (adoptedByName) {
                // 按名字认领的记录，字节可能已经变了（重新导出过），画像必须重算，
                // 不能拿旧画像冒充新内容
                existing.resetForReparse();
                resumes.save(existing);
                enqueueParse(existing.getId());
            } else if (existing.getStatus() != ResumeStatus.PARSED) {
                enqueueParse(existing.getId());
            }
            return view(existing);
        }

        String objectKey = "resume/" + UUID.randomUUID() + "/" + safeName;
        store.put(objectKey, bytes, normalizedType);
        Resume resume = resumes.save(Resume.create(safeName, normalizedType, objectKey, hash, ownerUserId));
        enqueueParse(resume.getId());
        return view(resume);
    }

    /**
     * 简历列表。
     *
     * <p>画像用一次 IN 查询批量取回，不再逐行查：原来 50 条列表就是 51 次查询，
     * 而这一页正是用户进来第一眼要看的——慢查询会直接变成"页面卡住"。</p>
     */
    public List<ResumeView> list() {
        CurrentActor actor = access.currentActor();
        List<Resume> rows = actor.isPlatformAdmin() ? resumes.listRecent() : resumes.listRecentByOwner(actor.userId());
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(Resume::getId).toList();
        Map<Long, ResumeProfile> profileByResume = profiles.findByResumeIdIn(ids).stream()
                .collect(Collectors.toMap(ResumeProfile::getResumeId, profile -> profile, (first, second) -> first));
        return rows.stream().map(row -> view(row, profileByResume.get(row.getId()))).toList();
    }

    public ResumeView find(Long id) {
        return view(access.require(id));
    }

    /**
     * 软删除：只写 {@code deleted_at}，行、原文对象、画像、批注全部保留。
     *
     * <p>列表与详情立刻看不到它（仓储默认过滤），撤销走 {@link #restore}。
     * 正在解析中的简历也能删：worker 下一跳按 id 查不到记录就收尾，
     * 不会把删除状态覆盖回 PENDING/PARSED。</p>
     *
     * <p>幂等：重复删除返回 200 + 同样的 {@code deletedAt}，不抛 404——
     * 前端连点两下、或者两个标签页各点一次，都不该变成一个报错。</p>
     */
    @Transactional
    public ResumeView delete(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Resume resume = access.requireIncludingDeleted(id);
        resume.softDelete();
        resumes.save(resume);
        return view(resume);
    }

    /** 撤销删除。已经不在删除态时返回当前视图，不改动任何字段。 */
    @Transactional
    public ResumeView restore(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Resume resume = access.requireIncludingDeleted(id);
        resume.restore();
        resumes.save(resume);
        // 删除期间如果解析被中断，状态可能停在 PARSING/ANALYZING；撤销后补投一次任务，
        // 否则这一行永远回不到 PARSED，编辑与重新分析都会被 inFlight 挡成 409。
        if (resume.getStatus() == ResumeStatus.PARSING || resume.getStatus() == ResumeStatus.ANALYZING) {
            resume.resetForReparse();
            resumes.save(resume);
            enqueueParse(resume.getId());
        }
        return view(resume);
    }

    /** 已解析则返回结构化画像；未解析时给出明确的业务错误，而不是空对象。 */
    public ParsedResume profile(Long id) {
        Resume resume = access.require(id);
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

    /**
     * 编辑器保存：把改好的正文写回成新的原文，并立即排队重新解析。
     *
     * <p>存成 text/plain 而不是改 DOCX：编辑器产出的就是纯文本，
     * 假装回写二进制格式只会让解析器多做一次无意义的转换。</p>
     *
     * <p>两条通路共用这个方法，差别只在"写哪个指针"：</p>
     * <ul>
     *   <li>{@code format=text}（原版排版 / 结构化编辑器）：覆盖 {@code objectKey}，
     *       同时清掉 md 内容稿——旧稿留着会被下一次解析读回去。</li>
     *   <li>{@code format=markdown}：只写 {@code mdSourceKey}，{@code source_*} 一动不动。
     *       这样原版预览、原位编辑、导出 PDF 都还在，用户只是改了内容稿。</li>
     * </ul>
     *
     * @param text   编辑器里的正文（纯文本或 Markdown，换行即分段）
     * @param edits  PDF 原位编辑的"板块序号 → 富文本 HTML"；markdown 通路传 null 表示"别动已有改动"
     * @param format {@code markdown} 走 md 稿，其余一律按纯文本原文处理
     */
    @Transactional
    public ResumeView saveContent(Long id, String text, Map<String, String> edits, String format) {
        Resume resume = access.require(id);
        if (inFlight(resume)) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        String content = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (content.isEmpty()) {
            // 只传 edits 不传 text 是最容易踩的一脚：前端以为"我只改了第 3 段"，
            // 后端其实要的是整篇正文。原来这里抛的是没有 message 的 VALIDATION_FAILED，
            // 用户在编辑器里点保存只会看到"参数校验失败"四个字，完全不知道差什么。
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "保存的正文不能为空（编辑器要提交整篇纯文本，edits 只是叠加在上面的原位改动）");
        }
        if (content.length() > MAX_TEXT_CHARS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "内容太长（" + content.length() + " 字），上限 " + MAX_TEXT_CHARS + " 字");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String hash = sha256(bytes);
        String stamp = "edited-" + Instant.now().toEpochMilli();
        if (isMarkdown(format)) {
            String objectKey = "resume/" + resume.getId() + "/" + stamp + ".md";
            store.put(objectKey, bytes, "text/markdown");
            resume.applyMarkdownSource(objectKey);
            // 原位改动只在调用方明确给了才重写：md 视图压根不认识板块序号，
            // 传 null 就当作"这次没碰版式"，别把用户在 PDF 里改过的东西一并抹掉。
            if (edits != null) {
                resume.applyEditLayer(sanitizeEdits(edits));
            }
        } else {
            String objectKey = "resume/" + resume.getId() + "/" + stamp + ".txt";
            store.put(objectKey, bytes, "text/plain");
            resume.replaceSource(textName(resume.getFileName()), "text/plain", objectKey, hash);
            resume.clearMarkdownSource();
            resume.applyEditLayer(sanitizeEdits(edits));
        }
        resumes.save(resume);
        // 正文变了：旧画像与旧批注的字符偏移全部失效，留着只会让批注框到无关的句子上
        invalidateDerived(id);
        enqueueParse(resume.getId());
        return view(resume);
    }

    private static boolean isMarkdown(String format) {
        return format != null && format.trim().equalsIgnoreCase("markdown");
    }

    /**
     * 读 Markdown 内容稿的原文；没有内容稿时返回 null。
     *
     * <p>编辑器打开时要的是"用户上次亲手写的那份"，不是解析后重新渲染的 md：
     * 渲染会规范化列表前缀、洗掉行内强调，用户每次打开都发现自己的格式被改过，
     * 会以为保存把内容弄坏了。没有 md 稿（老记录 / 一直用原版排版）时才让前端
     * 退回用渲染结果开个头。</p>
     */
    public String markdownContent(Long id) {
        Resume resume = access.require(id);
        if (!resume.hasMarkdownSource()) {
            return null;
        }
        byte[] bytes = store.read(resume.getMdSourceKey());
        return bytes == null || bytes.length == 0 ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /** 原文不变，重跑解析 + 批注（模型升级或结果不满意时用）。 */
    @Transactional
    public ResumeView reanalyze(Long id) {
        Resume resume = access.require(id);
        if (inFlight(resume)) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        resume.resetForReparse();
        resumes.save(resume);
        enqueueParse(resume.getId());
        return view(resume);
    }

    /** 用户主动放弃当前正在跑的解析/分析，把它退回可重试状态。 */
    @Transactional
    public ResumeView reset(Long id) {
        Resume resume = access.require(id);
        resume.resetForReparse();
        resumes.save(resume);
        invalidateDerived(id);
        enqueueParse(resume.getId());
        return view(resume);
    }

    private void invalidateDerived(Long resumeId) {
        profiles.deleteByResumeId(resumeId);
        analyses.deleteByResumeId(resumeId);
    }

    /**
     * 原文件是否已经读不到（对象存储被清理 / 换桶 / 历史上只改了正文指针）。
     *
     * <p>历史行的 {@code source_object_key} 可能是 NULL，直接丢给 MinIO 只会换来一个
     * NPE，所以这里统一挡掉；读失败同样视为缺失——判定的目的只是"要不要补一份回去"，
     * 真读不出来的记录补了也不亏。</p>
     */
    private boolean sourceMissing(Resume resume) {
        String key = resume.getSourceObjectKey();
        if (key == null || key.isBlank()) {
            return true;
        }
        try {
            byte[] bytes = store.read(key);
            return bytes == null || bytes.length == 0;
        } catch (RuntimeException ex) {
            log.warn("Resume source unreadable: id={}, key={}, error={}", resume.getId(), key, ex.toString());
            return true;
        }
    }

    /**
     * 投递解析任务：必须在业务事务提交之后投，否则 worker 可能先于提交领到任务，
     * 按 id 查不到记录就把任务收尾掉，简历永远停在 PENDING（前端一直转圈）。
     */
    private void enqueueParse(Long resumeId) {
        String type = ResumeParseWorker.JOB_TYPE + ":" + resumeId;
        Runnable publish = () -> {
            try {
                jobs.enqueue(type, null);
            } catch (RuntimeException ex) {
                // 提交后入队失败没有事务可回滚。这时状态停在 PENDING、前端永远转圈，
                // 用户还以为"正在解析"。直接置 FAILED 写清原因，列表页那个
                // 「重新分析」按钮才有意义。
                log.warn("Resume parse enqueue failed: id={}, error={}", resumeId, ex.toString());
                try {
                    resumes.updateStatus(resumeId, ResumeStatus.FAILED,
                            "解析任务投递失败（任务队列不可用），请点「重新分析」重试");
                } catch (RuntimeException inner) {
                    log.error("Resume parse enqueue failed and status not updated: id={}", resumeId, inner);
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    /** 板块改动只在 PDF 原位编辑里产生，超过这个量说明请求不正常。 */
    private String editLayer(Map<String, String> edits) {
        if (edits == null || edits.isEmpty()) {
            return null;
        }
        if (edits.size() > MAX_EDIT_SECTIONS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        int chars = 0;
        for (String html : edits.values()) {
            chars += html == null ? 0 : html.length();
        }
        if (chars > MAX_EDIT_CHARS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        try {
            return objectMapper.writeValueAsString(edits);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    /**
     * 富文本先净化再入库：前端会把它当 HTML 渲染，不能存任何可执行内容。
     *
     * <p>净化会重建标签（jsoup 输出比输入更长），所以拷完再核一次总量：
     * {@code edit_layer} 是 MEDIUMTEXT，超了是数据库报错而不是可读的校验失败。</p>
     */
    private String sanitizeEdits(Map<String, String> edits) {
        if (edits == null || edits.isEmpty()) {
            return null;
        }
        Map<String, String> clean = new java.util.LinkedHashMap<>();
        int chars = 0;
        for (Map.Entry<String, String> entry : edits.entrySet()) {
            String sanitized = RichTextSanitizer.sanitize(entry.getValue());
            chars += sanitized.length();
            clean.put(entry.getKey(), sanitized);
        }
        if (chars > MAX_EDIT_CHARS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "板块内容净化后仍然超出上限，请减少插图后重试");
        }
        return editLayer(clean);
    }

    /**
     * 已经在队列里或正在跑。
     *
     * <p>PENDING 也算：队列按 (tenant,type) 去重，重复投递虽然不会真的跑两遍，
     * 但会白等一个排队位置，而且用户点两下「重新分析」时看到的结果是"什么都没有发生"。
     * 只有 {@link #reset} 允许在 PENDING 时再投——它的职责就是解卡。</p>
     */
    private static boolean inFlight(Resume resume) {
        return resume.getStatus() == ResumeStatus.PENDING
                || resume.getStatus() == ResumeStatus.PARSING
                || resume.getStatus() == ResumeStatus.ANALYZING;
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
        return view(resume, profiles.findByResumeId(resume.getId()).orElse(null));
    }

    /** 单行视图：画像由调用方给，列表走批量查询时就不用再查一次。 */
    private ResumeView view(Resume resume, ResumeProfile profile) {
        return new ResumeView(resume.getId(), resume.getFileName(), resume.getContentType(),
                resume.getStatus(), resume.getLastError(), resume.getCreatedAt(), resume.getDeletedAt(),
                profile != null,
                profile == null ? null : profile.getYearsOfExperience(),
                profile == null ? null : profile.getSkillCount());
    }

    /**
     * 只接受 PDF / DOCX / TXT / MD，并按扩展名归一 Content-Type。
     *
     * <p>PDF 与 DOCX 再校验一次魔数：客户端声明的 Content-Type 不可信，
     * 而解析器是按 Content-Type 分派的，认错格式会在 worker 里白白重试三次。</p>
     *
     * <p>Markdown 必须单独归一成 {@code text/markdown}：解析器先看 text/plain
     * 再看 markdown，`.md` 被标成 text/plain 就会走"逐行当段落"的分支，
     * 标题与列表当场丢光——用户传一份 md 简历进来，看到的是一整篇没有章节的文本。</p>
     */
    private static String normalizeContentType(String fileName, byte[] bytes) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            if (!startsWith(bytes, 0x25, 0x50, 0x44, 0x46)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            return "application/pdf";
        }
        if (name.endsWith(".docx")) {
            boolean zip = startsWith(bytes, 0x50, 0x4B, 0x03, 0x04) || startsWith(bytes, 0x50, 0x4B, 0x05, 0x06);
            if (!zip) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (name.endsWith(".md")) {
            return "text/markdown";
        }
        if (name.endsWith(".txt")) {
            return "text/plain";
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
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

    /**
     * 列表/详情共用视图。null 字段表示"还没解析出来"，与"解析出 0"要能区分。
     *
     * <p>{@code deletedAt} 平时恒为 null——列表只吐出未删除记录；它出现在这里是为了
     * 删除/撤销接口的响应能带回去删时刻，前端做撤销提示时不用再查一次。</p>
     */
    public record ResumeView(Long id, String fileName, String contentType, ResumeStatus status,
                             String lastError, Instant createdAt, Instant deletedAt, boolean hasProfile,
                             Integer yearsOfExperience, Integer skillCount) {
    }
}
