package com.panel.audit.application;

import com.panel.audit.domain.AuditEvent;

public interface AuditRecorder {
    void record(AuditEvent event);
}
