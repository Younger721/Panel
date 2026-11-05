package com.nova.audit.application;

import com.nova.audit.domain.OperationAudit;

public interface RejectedAuditWriter {
    void record(OperationAudit audit);
}
