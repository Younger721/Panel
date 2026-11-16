package com.panel.interview.domain.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 一份上传的简历原文。
 *
 * <p>单租户产品，刻意<b>不带 tenant_id</b>：多租户是上一个系统的底座遗留，
 * 本产品永远只有一个租户，加字段只会让每个查询都要多一个恒等条件。</p>
 *
 * <p>状态机：{@code PENDING → PARSING → PARSED / FAILED}。原文与解析结果分表
 * （{@link ResumeProfile}），因为原文是不可变字节，解析结果会随模型升级重跑。</p>
 */
@Entity
@Table(name = "resume")
public class Resume {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 乐观锁版本号。
     *
     * <p>没有它，两条并发路径会互相静默覆盖：用户在编辑器里保存正文（整行 merge），
     * 同一个时刻异步 worker 也在写这一行——后提交的那个把先提交的改动抹掉，
     * 而数据库里看不出任何异常。加上版本号后冲突方会拿到
     * {@code OptimisticLockException}，接口层转成 409 让用户重试。</p>
     *
     * <p>注意：状态迁移走 {@code ResumeRepository.updateStatus} 的窄更新
     * （只写 status/last_error/updated_at），那条路径不参与乐观锁，
     * 因为它本来就不会碰到 objectKey/contentHash/editLayer。</p>
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * 归属用户（V11 迁移加的列）。
     *
     * <p>NULL 表示迁移前的历史数据，按语义归平台公共、只有平台管理员可见；
     * 新上传的记录一律写入上传者 id。所有按 id 的访问都必须经过
     * {@code ResumeAccessGuard}，不允许直接 {@code findById} 后返回。</p>
     */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(nullable = false, length = 128)
    private String contentType;

    @Column(nullable = false, length = 512)
    private String objectKey;

    /** 原文 SHA-256，用于同文件重复上传检测。 */
    @Column(nullable = false, length = 64)
    private String contentHash;

    /**
     * 原始上传文件（编辑器改过之后，{@link #objectKey} 指向新的文本，这里仍指向原件）。
     *
     * <p>PDF 的"原版预览"和保真编辑都要读原件，不能被「保存」覆盖掉。</p>
     */
    @Column(length = 512)
    private String sourceObjectKey;

    @Column(length = 128)
    private String sourceContentType;

    /**
     * Markdown 编辑器保存的内容稿（对象存储 key）。NULL 表示内容稿就是源码原文。
     *
     * <p>和 {@link #sourceObjectKey} 并存是刻意的：版式模板和文本内容会朝两个方向被改，
     * Markdown 编辑只动内容稿，PDF 原位编辑只动 {@code source_*} + {@code edit_layer}。
     * 解析按"有 md 读 md、没有回落原文件"，导出永远按原文件。</p>
     *
     * <p>走 text 通路保存时会把这个指针清掉：正文已经换了，再留着旧 md 只会让下一次
     * 解析读回旧稿，把用户刚存的内容盖回去。</p>
     */
    @Column(length = 512)
    private String mdSourceKey;

    @Column(length = 128)
    private String mdSourceContentType;

    /**
     * PDF 原位编辑留下的板块改动（JSON：板块序号 → 富文本 HTML）。
     *
     * <p>原件不能被改，所以"看到的改动"得单独存一份：再次打开编辑器时按这份数据
     * 还原，换浏览器也一样。用 MEDIUMTEXT 是因为用户可能往板块里插图（base64）。</p>
     */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String editLayer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResumeStatus status = ResumeStatus.PENDING;

    @Column(length = 500)
    private String lastError;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * 软删除时间戳，null 表示还在库里。
     *
     * <p>简历删掉不该真的删行：原文对象、画像、批注都还在存储里，物理删除只会
     * 留下"对象孤儿"，而且误删无从补救。所以删除只做一件事——写这个时间戳，
     * 列表/详情按它过滤，{@link #restore()} 把它清掉即可完整复原。</p>
     */
    @Column
    private Instant deletedAt;

    protected Resume() {
    }

    public static Resume create(String fileName, String contentType, String objectKey, String contentHash,
                                Long ownerUserId) {
        Resume resume = new Resume();
        resume.ownerUserId = ownerUserId;
        resume.fileName = fileName;
        resume.contentType = contentType;
        resume.objectKey = objectKey;
        resume.contentHash = contentHash;
        resume.sourceObjectKey = objectKey;
        resume.sourceContentType = contentType;
        resume.status = ResumeStatus.PENDING;
        return resume;
    }

    public void markParsing() {
        this.status = ResumeStatus.PARSING;
        this.lastError = null;
        touch();
    }

    /** 原文已解析，进入模块/小点的深度分析阶段。 */
    public void markAnalyzing() {
        this.status = ResumeStatus.ANALYZING;
        this.lastError = null;
        touch();
    }

    public void markParsed() {
        this.status = ResumeStatus.PARSED;
        this.lastError = null;
        touch();
    }

    public void markFailed(String error) {
        this.status = ResumeStatus.FAILED;
        this.lastError = truncate(error);
        touch();
    }

    /** 重试未耗尽时退回待解析，让租约再次生效。 */
    public void markRetryable(String error) {
        this.status = ResumeStatus.PENDING;
        this.lastError = truncate(error);
        touch();
    }

    /**
     * 编辑器改完回写：换一份新的文本原文，并重新排队解析。
     *
     * <p>原文是不可变字节这一条在这里被打破——但新内容本身仍是不可变的，
     * 只是 objectKey 指向新的对象，旧对象保留，出问题还能追溯。</p>
     */
    public void replaceSource(String fileName, String contentType, String objectKey, String contentHash) {
        // 第一次改之前先把"原件"记下来：老数据没有 source_* 字段，这里补一次快照，
        // 之后 PDF 的原版预览/保真编辑仍然读得到最初那份文件
        if (sourceObjectKey == null || sourceObjectKey.isBlank()) {
            sourceObjectKey = this.objectKey;
            sourceContentType = this.contentType;
        }
        this.fileName = fileName;
        this.contentType = contentType;
        this.objectKey = objectKey;
        this.contentHash = contentHash;
        this.status = ResumeStatus.PENDING;
        this.lastError = null;
        touch();
    }

    /**
     * 原件丢失后补写一份：同时改当前对象指针和"原件"指针。
     *
     * <p>和 {@link #replaceSource} 的区别：那个方法会把旧 objectKey 快照成原件，
     * 这里旧原件本来就是丢的，留在 source 上只会让预览继续 404。</p>
     */
    public void healSource(String fileName, String contentType, String objectKey, String contentHash) {
        this.fileName = fileName;
        this.contentType = contentType;
        this.objectKey = objectKey;
        this.sourceObjectKey = objectKey;
        this.sourceContentType = contentType;
        this.contentHash = contentHash;
        touch();
    }

    /**
     * Markdown 编辑器保存：内容稿换成新的 md，版式相关的 {@code source_*} 一动不碰。
     *
     * <p>和 {@link #replaceSource} 分开的原因是"改内容"和"改原件"不是一回事：
     * 原版预览、原位编辑、导出 PDF 全都挂在 {@code source_*} 上，md 保存去动它会
     * 让这三条链路一起失效。所以这里只挪 {@code mdSourceKey} 这一个指针。</p>
     *
     * <p>{@code contentHash} 也刻意不更新：它是"上传原件"的指纹，上传去重和页面预览缓存
     * 都按它算。跟着 md 稿变会让同一份 PDF 再上传时认不出是同一份、预览图全部重建。</p>
     */
    public void applyMarkdownSource(String objectKey) {
        this.mdSourceKey = objectKey;
        this.mdSourceContentType = "text/markdown";
        this.status = ResumeStatus.PENDING;
        this.lastError = null;
        touch();
    }

    /** 正文改回纯文本通路时调用：旧 md 稿已经过期，留着会被下一次解析读回去。 */
    public void clearMarkdownSource() {
        this.mdSourceKey = null;
        this.mdSourceContentType = null;
    }

    /** 原文不变，只重跑解析与分析（模型升级，或上一次结果不满意）。 */
    public void resetForReparse() {
        this.status = ResumeStatus.PENDING;
        this.lastError = null;
        touch();
    }

    /**
     * 软删除：只记时间戳，状态机不动。
     *
     * <p>刻意不把 status 改成某个 DELETED 值：状态机表达的是解析流水线走到哪一步，
     * 删除是记录级的可见性问题，两者混在一起会让 worker 的幂等判断多一堆特例。</p>
     */
    public void softDelete() {
        if (deletedAt == null) {
            this.deletedAt = Instant.now();
            touch();
        }
    }

    /** 撤销删除；未删除时是空操作，重复撤销不会改动 updatedAt。 */
    public void restore() {
        if (deletedAt != null) {
            this.deletedAt = null;
            touch();
        }
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    /** 老数据没有归属列（NULL）：补归属时用，避免历史记录永远只有管理员可见。 */
    public void assignOwnerIfAbsent(Long ownerUserId) {
        if (this.ownerUserId == null && ownerUserId != null) {
            this.ownerUserId = ownerUserId;
            touch();
        }
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getContentHash() {
        return contentHash;
    }

    /** 原件位置；老数据没有这两个字段时退回当前对象，行为与从前一致。 */
    public String getSourceObjectKey() {
        return sourceObjectKey == null || sourceObjectKey.isBlank() ? objectKey : sourceObjectKey;
    }

    public String getSourceContentType() {
        return sourceContentType == null || sourceContentType.isBlank() ? contentType : sourceContentType;
    }

    /** 传 null 表示清空（行改动全部还原）。 */
    public void applyEditLayer(String json) {
        this.editLayer = json == null || json.isBlank() ? null : json;
    }

    public String getEditLayer() {
        return editLayer;
    }

    public String getMdSourceKey() {
        return mdSourceKey;
    }

    /**
     * 解析要读的那份原文：有 md 内容稿就用它，否则用上传的原文件。
     *
     * <p>三个 getter 是同一个判断的三次展开（key / 类型 / 文件名）。解析器按
     * Content-Type 和扩展名分派，md 三者缺一个都会退化成"逐行当段落"——章节结构就没了。</p>
     */
    public String getEffectiveObjectKey() {
        return hasMarkdownSource() ? mdSourceKey : objectKey;
    }

    public String getEffectiveContentType() {
        if (!hasMarkdownSource()) {
            return getContentType();
        }
        return mdSourceContentType == null || mdSourceContentType.isBlank() ? "text/markdown" : mdSourceContentType;
    }

    public String getEffectiveFileName() {
        return hasMarkdownSource() ? markdownName(fileName) : fileName;
    }

    public boolean hasMarkdownSource() {
        return mdSourceKey != null && !mdSourceKey.isBlank();
    }

    /** `张三-简历.pdf` → `张三-简历.md`，只换后缀；解析器靠它认出 markdown。 */
    private static String markdownName(String fileName) {
        String base = fileName == null || fileName.isBlank() ? "resume" : fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return base + ".md";
    }

    public ResumeStatus getStatus() {
        return status;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
