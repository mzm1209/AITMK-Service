package com.example.aitmk.repository;

import com.example.aitmk.model.entity.AgentQuickReplyEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentQuickReplyRepository extends JpaRepository<AgentQuickReplyEntity, Long> {

    @Query("""
            select q from AgentQuickReplyEntity q
            where q.agentRowId = :agentRowId
              and q.enabled = true
              and q.deletedAt is null
              and (:category is null or q.category = :category)
              and (:keyword is null or lower(q.title) like lower(concat('%', :keyword, '%'))
                   or lower(q.content) like lower(concat('%', :keyword, '%')))
            order by q.sortOrder asc, q.updatedAt desc
            """)
    List<AgentQuickReplyEntity> searchActive(
            @Param("agentRowId") String agentRowId,
            @Param("keyword") String keyword,
            @Param("category") String category,
            Pageable pageable);

    Optional<AgentQuickReplyEntity> findByIdAndAgentRowIdAndDeletedAtIsNull(Long id, String agentRowId);
}
