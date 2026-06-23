package com.example.aitmk.repository;

import com.example.aitmk.model.entity.AssignmentRecordEntity;
import com.example.aitmk.model.entity.PersistenceEnums.AssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
