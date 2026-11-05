package com.nova.tenancy.application;

import com.nova.iam.domain.model.Role;
import com.nova.iam.domain.model.RoleCode;
import com.nova.iam.domain.model.UserAccount;
import com.nova.iam.domain.model.UserRole;
import com.nova.iam.domain.repository.RoleRepository;
import com.nova.iam.domain.repository.UserAccountRepository;
import com.nova.iam.domain.repository.UserRoleRepository;
import com.nova.tenancy.application.port.TenantProvisioningContributor;
import com.nova.tenancy.domain.model.Tenant;
import com.nova.tenancy.domain.repository.TenantRepository;
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
                administrator.passwordHash(), administrator.displayName(), RoleCode.GYM_ADMIN));
        Role gymAdminRole = roleRepository.findByCode(RoleCode.GYM_ADMIN)
                .orElseThrow(() -> new IllegalStateException("GYM_ADMIN role is not initialized"));
        userRoleRepository.save(UserRole.assign(user, gymAdminRole));
        contributors.forEach(contributor -> contributor.contribute(saved));
        return new ProvisionedTenant(saved, user);
    }
}
