package com.panel.ai.infrastructure;
import com.panel.ai.application.AiMemoryType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
interface SpringDataAiMemoryRepository extends JpaRepository<AiMemoryEntity,Long>{
 List<AiMemoryEntity> findAllByTenantIdAndUserId(Long tenantId,Long userId);
 Optional<AiMemoryEntity> findByTenantIdAndUserIdAndTypeAndKey(Long tenantId,Long userId,AiMemoryType type,String key);
}
