package com.panel.tenancy.application;

import com.panel.iam.domain.model.Role;
import com.panel.iam.domain.model.RoleCode;
import com.panel.iam.domain.model.UserAccount;
import com.panel.iam.domain.model.UserRole;
import com.panel.iam.domain.repository.RoleRepository;
import com.panel.iam.domain.repository.UserAccountRepository;
import com.panel.iam.domain.repository.UserRoleRepository;
import com.panel.tenancy.application.port.TenantProvisioningContributor;
import com.panel.tenancy.domain.model.Tenant;
import com.panel.tenancy.domain.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DefaultTenantProvisioningService implements TenantProvisioningService {
    private final TenantRepository tenantRepository;
    private final UserAccountRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final List<TenantProvisioningContributor> contributors;

    public DefaultTenantProvisioningService(TenantRepository tenantRepository, UserAccountRepository userRepository,
                                            RoleRepository roleRepository, UserRoleRepository userRoleRepository,
                                            List<TenantProvisioningContributor> contributors) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.contributors = contributors;
    }

    @Override
    @Transactional
    public ProvisionedTenant provision(Tenant tenant, Administrator administrator) {
        if (administrator == null) {
            throw new IllegalArgumentException("Tenant administrator is required");
        }
        Tenant saved = tenantRepository.save(tenant);
        String email = UserAccount.normalizeEmail(administrator.email());
        String username = administrator.username() == null || administrator.username().isBlank()
                ? UserAccount.defaultUsernameFromEmail(email)
                : UserAccount.normalizeUsername(administrator.username());
        UserAccount user = userRepository.save(UserAccount.tenantUser(saved.getId(), email, username,
                administrator.passwordHash(), administrator.displayName(), RoleCode.ADMIN));
        Role gymAdminRole = roleRepository.findByCode(RoleCode.ADMIN)
                .orElseThrow(() -> new IllegalStateException("ADMIN role is not initialized"));
        userRoleRepository.save(UserRole.assign(user, gymAdminRole));
        contributors.forEach(contributor -> contributor.contribute(saved));
        return new ProvisionedTenant(saved, user);
    }
}
