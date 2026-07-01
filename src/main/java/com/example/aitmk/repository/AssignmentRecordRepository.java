package com.example.aitmk.repository;

import com.example.aitmk.model.entity.AssignmentRecordEntity;
import com.example.aitmk.model.entity.PersistenceEnums.AssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssignmentRecordRepository extends JpaRepository<AssignmentRecordEntity, Long> {
    Optional<AssignmentRecordEntity> findFirstByResourceIdAndStatusOrderByAssignedAtDesc(Long resourceId, AssignmentStatus status);
    Optional<AssignmentRecordEntity> findFirstByCustomerPhoneAndStatusOrderByAssignedAtDesc(String customerPhone, AssignmentStatus status);
    List<AssignmentRecordEntity> findByStatus(AssignmentStatus status);
    List<AssignmentRecordEntity> findByAgentIdAndStatus(String agentId, AssignmentStatus status);
    boolean existsByCustomerPhoneAndAgentId(String customerPhone, String agentId);
    List<AssignmentRecordEntity> findByResourceIdOrderByAssignedAtDesc(Long resourceId);
    List<AssignmentRecordEntity> findByConversationIdOrderByAssignedAtDesc(Long conversationId);

    @Query("select a.agentId, count(a) from AssignmentRecordEntity a "
            + "where a.agentId in :agentIds and a.status = :status group by a.agentId")
    List<Object[]> countServingByAgentIds(@Param("agentIds") Collection<String> agentIds,
                                          @Param("status") AssignmentStatus status);
}
