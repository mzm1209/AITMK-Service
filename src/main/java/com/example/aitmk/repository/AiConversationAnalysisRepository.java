package com.example.aitmk.repository;
import com.example.aitmk.model.entity.AiConversationAnalysisEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface AiConversationAnalysisRepository extends JpaRepository<AiConversationAnalysisEntity,Long>{
    Optional<AiConversationAnalysisEntity> findFirstByConversationIdOrderByIdDesc(Long conversationId);
    Optional<AiConversationAnalysisEntity> findByIdAndConversationId(Long id,Long conversationId);
    Optional<AiConversationAnalysisEntity> findFirstByConversationIdAndBasisLastMessageIdAndStatusInOrderByIdDesc(Long conversationId,Long basisLastMessageId,java.util.Collection<String> statuses);
    Optional<AiConversationAnalysisEntity> findByRequestId(String requestId);
}
