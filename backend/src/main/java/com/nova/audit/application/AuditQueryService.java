package com.nova.audit.application;

import com.nova.audit.domain.OperationAudit;
import com.nova.shared.security.CurrentActor;

import java.util.List;

public interface AuditQueryService {
    List<OperationAudit> list(CurrentActor actor);
}
