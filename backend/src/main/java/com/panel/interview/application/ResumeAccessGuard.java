package com.panel.interview.application;

import com.panel.interview.domain.model.Resume;
import com.panel.interview.domain.repository.ResumeRepository;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.CurrentActorProvider;
import org.springframework.stereotype.Component;

/**
 * 简历归属校验：所有按 id 访问简历（含画像/批注/预览/导出/编辑/删除）的唯一入口。
 *
 * <p>语义与 V11 迁移一致：{@code owner_user_id = NULL} 是迁移前的历史数据（视为平台公共），
 * 只有平台管理员可见；其余记录只有本人可见。越权一律抛 {@code RESOURCE_NOT_FOUND}，
 * 不返回 403——403 会让调用方知道"这个 id 存在但你没权限"，等于把 id 空间暴露出去。</p>
 */
@Component
public class ResumeAccessGuard {

    private final ResumeRepository resumes;
    private final CurrentActorProvider actors;

    public ResumeAccessGuard(ResumeRepository resumes, CurrentActorProvider actors) {
        this.resumes = resumes;
        this.actors = actors;
    }

    /** 未删除且当前用户可以访问的简历。 */
    public Resume require(Long resumeId) {
        CurrentActor actor = actors.requireCurrent();
        return resumes.findById(resumeId)
                .filter(resume -> visible(resume.getOwnerUserId(), actor))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 含已软删除的记录：删除/撤销删除用，归属规则不变。 */
    public Resume requireIncludingDeleted(Long resumeId) {
        CurrentActor actor = actors.requireCurrent();
        return resumes.findAnyById(resumeId)
                .filter(resume -> visible(resume.getOwnerUserId(), actor))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 当前用户 id，用于新建记录时写归属。 */
    public Long currentUserId() {
        return actors.requireCurrent().userId();
    }

    public CurrentActor currentActor() {
        return actors.requireCurrent();
    }

    public static boolean visible(Long ownerUserId, CurrentActor actor) {
        if (actor.isPlatformAdmin()) {
            return true;
        }
        return ownerUserId != null && ownerUserId.equals(actor.userId());
    }
}
