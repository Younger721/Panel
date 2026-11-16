package com.panel.interview.domain.repository;

import com.panel.interview.domain.model.Resume;
import com.panel.interview.domain.model.ResumeStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 简历原文仓储。
 *
 * <p>按 id 的查询一律配合 {@code ResumeAccessGuard} 使用：归属判断在服务层做，
 * 越权统一返回 404（而不是 403，避免泄漏 id 是否存在）。
 * {@code ownerUserId = null} 表示 V11 迁移前的历史数据（平台公共，仅管理员可见）。</p>
 */
public interface ResumeRepository {

    Resume save(Resume resume);

    /** 只返回未删除的简历；已软删的当作不存在。 */
    Optional<Resume> findById(Long id);

    /** 含已删除：撤销删除时用，其他调用方不该用它绕过可见性。 */
    Optional<Resume> findAnyById(Long id);

    /** 最近 50 份（未删除），列表页用。 */
    List<Resume> listRecent();

    /** 某个用户最近 50 份（未删除）。 */
    List<Resume> listRecentByOwner(Long ownerUserId);

    /**
     * 同内容去重：同样字节的文件重复上传直接复用原记录。
     *
     * <p>这里<b>包含已删除</b>的记录——用户删掉后又传同一份文件，
     * 语义上就是"我还要这份简历"，复用原行再复原比新建一行更省模型调用。</p>
     */
    Optional<Resume> findByContentHash(String contentHash);

    /** 同一上传者的同内容去重：跨用户不会命中彼此的记录。 */
    Optional<Resume> findByOwnerAndContentHash(Long ownerUserId, String contentHash);

    /** 迁移前没有归属列的历史记录：管理员重传同文件时可以复用，不必重新解析。 */
    Optional<Resume> findLegacyByContentHash(String contentHash);

    /**
     * 同一上传者的同名记录，按 id 倒序。
     *
     * <p>只用于"原件已经丢了、用户又把同一个文件传回来"这一种兜底：字节可能因为
     * 重新导出而变了，hash 对不上，但语义上就是同一份简历。命中后必须强制重新解析，
     * 不能拿旧画像冒充新内容。</p>
     */
    List<Resume> findByOwnerAndFileName(Long ownerUserId, String fileName);

    /**
     * 只推进状态，整行不覆盖。
     *
     * <p>worker 在解析期间会把实体脱离持久化上下文（几十秒的模型调用不能占着事务），
     * 回来再 {@code save} 就会把读到的整行快照 merge 回去——用户在这期间保存的新正文、
     * 撤销删除写回的 {@code deleted_at}，统统被抹掉。所以状态迁移一律走这条窄更新。</p>
     *
     * @param error 要写入 {@code last_error} 的内容，null 表示清空
     */
    int updateStatus(Long id, ResumeStatus status, String error);

    /** 长时间停在 PARSING/ANALYZING 的记录（进程崩溃、任务被丢弃留下的僵尸行）。 */
    List<Resume> findStuck(Instant updatedBefore);
}
