package com.example.aitmk.repository;

import com.example.aitmk.model.entity.ChatMessageEntity;
import com.example.aitmk.model.entity.PersistenceEnums.SenderType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {
    boolean existsByExternalMessageId(String externalMessageId);
    Optional<ChatMessageEntity> findByExternalMessageId(String externalMessageId);
    List<ChatMessageEntity> findByCustomerPhoneOrderByCreatedAtAscIdAsc(String customerPhone);
    Page<ChatMessageEntity> findByCustomerPhone(String customerPhone, Pageable pageable);
    Optional<ChatMessageEntity> findFirstByCustomerPhoneOrderByCreatedAtDescIdDesc(String customerPhone);
    Optional<ChatMessageEntity> findFirstByCustomerPhoneAndSenderTypeOrderByCreatedAtDescIdDesc(String customerPhone, SenderType senderType);
    Optional<ChatMessageEntity> findByClientRequestId(String clientRequestId);
    Optional<ChatMessageEntity> findFirstByResourceIdOrderByCreatedAtDescIdDesc(Long resourceId);
    List<ChatMessageEntity> findByConversationIdOrderByCreatedAtDescIdDesc(Long conversationId, Pageable pageable);
    @Query("select m from ChatMessageEntity m where m.conversationId=:conversationId and " +
            "(m.createdAt<:beforeAt or (m.createdAt=:beforeAt and m.id<:beforeId)) order by m.createdAt desc,m.id desc")
    List<ChatMessageEntity> findBefore(@Param("conversationId") Long conversationId,
                                      @Param("beforeAt") java.time.Instant beforeAt,
                                      @Param("beforeId") Long beforeId, Pageable pageable);
    long countByConversationIdAndSenderType(Long conversationId, SenderType senderType);
    long countByConversationIdAndSenderTypeAndIdGreaterThan(Long conversationId, SenderType senderType, Long id);
}
