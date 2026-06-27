package com.example.aitmk.repository;

import com.example.aitmk.model.entity.CrmSyncTaskEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.hibernate.cfg.AvailableSettings;
import java.util.List;

public interface CrmSyncTaskRepository extends JpaRepository<CrmSyncTaskEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = AvailableSettings.JAKARTA_LOCK_TIMEOUT, value = "-2"))
    @Query("select t from CrmSyncTaskEntity t where t.status in :statuses and t.retryCount < t.maxRetries order by t.createdAt asc")
    List<CrmSyncTaskEntity> lockPending(@Param("statuses") List<String> statuses, Pageable pageable);

    long countByStatus(String status);
}
