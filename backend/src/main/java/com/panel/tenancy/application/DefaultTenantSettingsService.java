package com.panel.tenancy.application;

import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.ActorAccess;
import com.panel.shared.security.CurrentActor;
import com.panel.tenancy.api.response.TenantSettingsView;
import com.panel.tenancy.application.command.UpdateTenantSettingsCommand;
import com.panel.tenancy.domain.model.TenantSettings;
import com.panel.tenancy.domain.repository.TenantSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class DefaultTenantSettingsService implements TenantSettingsService {

    private final TenantSettingsRepository settingsRepository;

    public DefaultTenantSettingsService(TenantSettingsRepository settingsRepository) {
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "settingsRepository");
    }

    @Override
    @Transactional
    public TenantSettingsView getCurrent(CurrentActor actor) {
        Long tenantId = requireGymAdmin(actor, "tenant:settings:read");
        TenantSettings settings = settingsRepository.findByTenantId(tenantId)
                .orElseGet(() -> settingsRepository.save(TenantSettings.create(tenantId)));
        return TenantSettingsView.from(settings);
    }

    @Override
    @Transactional
    public TenantSettingsView updateCurrent(CurrentActor actor, UpdateTenantSettingsCommand command) {
        Long tenantId = requireGymAdmin(actor, "tenant:settings:write");
        Objects.requireNonNull(command, "command");
        TenantSettings settings = settingsRepository.findByTenantId(tenantId)
                .orElseGet(() -> TenantSettings.create(tenantId));
        if (command.reservationEnabled() == null || command.checkInEnabled() == null
                || command.timezone() == null || command.timezone().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        settings.setReservationEnabled(command.reservationEnabled());
        settings.setCheckInEnabled(command.checkInEnabled());
        settings.setTimezone(command.timezone());
        return TenantSettingsView.from(settingsRepository.save(settings));
    }

    @Override
    @Transactional(readOnly = true)
    public TenantSettingsView getForPlatform(Long tenantId, CurrentActor actor) {
        requirePlatform(actor, "platform:tenant:read");
        Long id = requireTenantId(tenantId);
        TenantSettings settings = settingsRepository.findByTenantId(id)
                .orElseGet(() -> TenantSettings.create(id));
        return TenantSettingsView.from(settings);
    }

    @Override
    @Transactional
    public TenantSettingsView updateForPlatform(Long tenantId, CurrentActor actor, UpdateTenantSettingsCommand command) {
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    private static void requirePlatform(CurrentActor actor, String permission) {
        if (actor == null || !actor.isPlatformAdmin() || !actor.hasPermission(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private static Long requireTenantId(Long tenantId) {
        if (tenantId == null || tenantId <= 0) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return tenantId;
    }

    private static Long requireGymAdmin(CurrentActor actor, String permission) {
        ActorAccess.requireGymAdmin(actor, permission);
        return ActorAccess.requireTenantId(actor);
    }
}
