package com.example.aitmk.repository;
import com.example.aitmk.model.entity.RealtimeEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.QueryHints;
import org.hibernate.cfg.AvailableSettings;
public interface RealtimeEventRepository extends JpaRepository<RealtimeEventEntity,Long>{
 long countByConversationIdAndEventType(Long conversationId,String eventType);
 List<RealtimeEventEntity> findByConversationIdAndEventTypeOrderByIdAsc(Long conversationId,String eventType);
 Optional<RealtimeEventEntity> findByEventIdAndTargetAgentId(String eventId,String targetAgentId);
 List<RealtimeEventEntity> findByTargetAgentIdAndIdGreaterThanOrderByIdAsc(String agentId,Long id,Pageable page);
 List<RealtimeEventEntity> findByTargetAgentIdOrderByIdAsc(String agentId,Pageable page);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @QueryHints(@QueryHint(name = AvailableSettings.JAKARTA_LOCK_TIMEOUT, value = "-2")) @Query("select e from RealtimeEventEntity e where e.publishedAt is null and e.publishAttempts<:max order by e.id")
 List<RealtimeEventEntity> lockUnpublished(@Param("max") int max,Pageable page);
}
