package com.panel.audit.application;

import com.panel.audit.domain.OperationAudit;

public interface RejectedAuditWriter {
    void record(OperationAudit audit);
}
