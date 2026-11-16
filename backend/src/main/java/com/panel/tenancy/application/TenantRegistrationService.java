package com.panel.tenancy.application;

import com.panel.iam.application.command.RegisterTenantCommand;
import com.panel.iam.domain.model.UserAccount;
import com.panel.tenancy.domain.model.Tenant;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class TenantRegistrationService {

    private final TenantProvisioningService provisioningService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public TenantRegistrationService(TenantProvisioningService provisioningService) {
        this.provisioningService = Objects.requireNonNull(provisioningService, "provisioningService");
    }

    @Transactional
    public TenantProvisioningService.ProvisionedTenant register(RegisterTenantCommand command) {
        Objects.requireNonNull(command, "command");
        String password = requirePassword(command.password());
        return provisioningService.provision(
                Tenant.create(command.tenantCode(), command.tenantName()),
                new TenantProvisioningService.Administrator(
                        UserAccount.normalizeEmail(command.email()),
                        passwordEncoder.encode(password),
                        command.displayName()));
    }

    private static String requirePassword(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password must not be blank");
        }
        return password;
    }
}
