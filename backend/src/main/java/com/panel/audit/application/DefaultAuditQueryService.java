package com.panel.audit.application;

import com.panel.audit.domain.OperationAudit;
import com.panel.audit.domain.repository.OperationAuditRepository;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.ActorAccess;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.iam.domain.model.RoleCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class DefaultAuditQueryService implements AuditQueryService {
    private final OperationAuditRepository repository;

    public DefaultAuditQueryService(OperationAuditRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    @Transactional(readOnly = true)
    public List<OperationAudit> list(CurrentActor actor) {
        requireAuditReader(actor);
        Long tenantId = ActorAccess.tenantId(actor);
        return tenantId == null ? repository.findAll() : repository.findAllByTenantId(tenantId);
    }

    private static void requireAuditReader(CurrentActor actor) {
        if (actor == null || (!actor.isPlatformAdmin() && !actor.roles().contains(RoleCode.ADMIN))) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
