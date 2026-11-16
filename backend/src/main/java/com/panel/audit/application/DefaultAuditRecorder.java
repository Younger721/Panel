package com.panel.audit.application;

import com.panel.audit.domain.AuditEvent;
import com.panel.audit.domain.OperationAudit;
import com.panel.audit.domain.repository.OperationAuditRepository;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.CurrentActorProvider;
import com.panel.shared.security.ActorAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class DefaultAuditRecorder implements AuditRecorder {
    private final OperationAuditRepository repository;
    private final CurrentActorProvider actors;
    private final RejectedAuditWriter rejectedAuditWriter;

    public DefaultAuditRecorder(OperationAuditRepository repository, CurrentActorProvider actors,
                                RejectedAuditWriter rejectedAuditWriter) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.rejectedAuditWriter = Objects.requireNonNull(rejectedAuditWriter, "rejectedAuditWriter");
    }

    @Override
    @Transactional
    public void record(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        CurrentActor actor = actors.requireCurrent();
        OperationAudit audit = OperationAudit.create(actor.userId(), ActorAccess.tenantId(actor), event.action(),
                event.resourceType(), event.resourceId(), event.auditResult(), event.traceId(), event.metadata());
        if (event.auditResult() == com.panel.audit.domain.AuditResult.REJECTED) {
            rejectedAuditWriter.record(audit);
        } else {
            repository.save(audit);
        }
    }
}
