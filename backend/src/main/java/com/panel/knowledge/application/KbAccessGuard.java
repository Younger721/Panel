package com.panel.knowledge.application;

import com.panel.iam.domain.model.RoleCode;
import com.panel.knowledge.domain.model.KbVisibility;
import com.panel.knowledge.domain.model.KnowledgeBase;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.CurrentActorProvider;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * 可见性闸门：{@code kb_base.visibility} 不再是摆设。
 *
 * <p>规则：{@code PUBLIC} 谁都能读；{@code INTERNAL} 只对平台管理员 / 管理员 / 面试官开放。
 * 列表接口必须用 {@link #canRead} 过滤，单个读接口用 {@link #ensureReadable} 拦住，
 * 否则候选人能靠猜 id 直接读到内部题库。</p>
 */
@Component
public class KbAccessGuard {

    private static final Set<RoleCode> INTERNAL_ROLES =
            EnumSet.of(RoleCode.PLATFORM_ADMIN, RoleCode.ADMIN, RoleCode.INTERVIEWER);

    private final CurrentActorProvider actors;

    public KbAccessGuard(CurrentActorProvider actors) {
        this.actors = actors;
    }

    public boolean canRead(KnowledgeBase base) {
        if (base == null || base.visibility() == KbVisibility.PUBLIC) {
            return true;
        }
        CurrentActor actor = actors.current().orElse(null);
        return actor != null && actor.roles().stream().anyMatch(INTERNAL_ROLES::contains);
    }

    public void ensureReadable(KnowledgeBase base) {
        if (!canRead(base)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "这个知识库仅内部可见：" + (base == null ? "" : base.name()));
        }
    }
}
