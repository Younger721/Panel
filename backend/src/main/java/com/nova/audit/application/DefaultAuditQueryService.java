package com.nova.audit.application;

import com.nova.audit.domain.OperationAudit;
import com.nova.audit.domain.repository.OperationAuditRepository;
import com.nova.shared.security.CurrentActor;
import com.nova.shared.security.ActorAccess;
import com.nova.shared.error.BusinessException;
import com.nova.shared.error.ErrorCode;
import com.nova.iam.domain.model.RoleCode;
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
