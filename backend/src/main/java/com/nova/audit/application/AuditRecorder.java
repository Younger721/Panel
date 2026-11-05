package com.nova.audit.application;

import com.nova.audit.domain.AuditEvent;

public interface AuditRecorder {
    void record(AuditEvent event);
}
