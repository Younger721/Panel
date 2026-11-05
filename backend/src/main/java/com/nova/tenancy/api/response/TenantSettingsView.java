package com.nova.tenancy.api.response;

import com.nova.tenancy.domain.model.TenantSettings;

public record TenantSettingsView(Long tenantId, boolean reservationEnabled,
                                 boolean checkInEnabled, String timezone) {
    public static TenantSettingsView from(TenantSettings settings) {
        return new TenantSettingsView(settings.getTenantId(), settings.isReservationEnabled(),
                settings.isCheckInEnabled(), settings.getTimezone());
    }
}
