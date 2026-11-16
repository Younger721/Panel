package com.panel.tenancy.api.response;

import com.panel.tenancy.domain.model.TenantSettings;

public record TenantSettingsView(Long tenantId, boolean reservationEnabled,
                                 boolean checkInEnabled, String timezone) {
    public static TenantSettingsView from(TenantSettings settings) {
        return new TenantSettingsView(settings.getTenantId(), settings.isReservationEnabled(),
                settings.isCheckInEnabled(), settings.getTimezone());
    }
}
