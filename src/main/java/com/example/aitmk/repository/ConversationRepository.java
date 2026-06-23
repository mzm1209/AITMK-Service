package com.example.aitmk.repository;

import com.example.aitmk.model.entity.ConversationEntity;
import com.example.aitmk.model.entity.PersistenceEnums.ConversationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<ConversationEntity, Long> {
    Optional<ConversationEntity> findFirstByResourceIdAndStatusInOrderByCreatedAtDesc(Long resourceId, Collection<ConversationStatus> statuses);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ConversationEntity c where c.resourceId=:resourceId and c.status in :statuses order by c.createdAt desc")
    List<ConversationEntity> findActiveForUpdate(@Param("resourceId") Long resourceId,
                                                  @Param("statuses") Collection<ConversationStatus> statuses);
    Optional<ConversationEntity> findFirstByCustomerPhoneAndStatusInOrderByCreatedAtDesc(String customerPhone, Collection<ConversationStatus> statuses);
    List<ConversationEntity> findByCustomerPhoneOrderByCreatedAtAsc(String customerPhone);
    List<ConversationEntity> findByResourceIdOrderByCreatedAtDesc(Long resourceId);
    Optional<ConversationEntity> findFirstByResourceIdOrderByCreatedAtDescIdDesc(Long resourceId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ConversationEntity c where c.id=:id")
    Optional<ConversationEntity> findByIdForUpdate(@Param("id") Long id);
}
