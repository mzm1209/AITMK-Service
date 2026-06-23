package com.example.aitmk.repository;
import com.example.aitmk.model.entity.ConversationAgentStateEntity;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
public interface ConversationAgentStateRepository extends JpaRepository<ConversationAgentStateEntity,Long>{
 Optional<ConversationAgentStateEntity> findByConversationIdAndAgentId(Long conversationId,String agentId);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from ConversationAgentStateEntity s where s.conversationId=:c and s.agentId=:a")
 Optional<ConversationAgentStateEntity> findForUpdate(@Param("c") Long conversationId,@Param("a") String agentId);
}
