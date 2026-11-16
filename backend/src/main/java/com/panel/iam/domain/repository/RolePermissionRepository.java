package com.panel.iam.domain.repository;

import com.panel.iam.domain.model.RolePermission;
import java.util.Set;

public interface RolePermissionRepository {
    RolePermission save(RolePermission link);
    boolean exists(Long roleId, Long permissionId);
    Set<String> findPermissionCodesByRoleId(Long roleId);
    void deleteByRoleId(Long roleId);
}
