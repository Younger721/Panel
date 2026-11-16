package com.panel.audit.application;

import com.panel.audit.domain.OperationAudit;
import com.panel.shared.security.CurrentActor;

import java.util.List;

public interface AuditQueryService {
    List<OperationAudit> list(CurrentActor actor);
}
