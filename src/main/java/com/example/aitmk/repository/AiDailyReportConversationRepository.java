package com.example.aitmk.repository;

import com.example.aitmk.model.entity.AiDailyReportConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiDailyReportConversationRepository extends JpaRepository<AiDailyReportConversationEntity, Long> {
    List<AiDailyReportConversationEntity> findByReportIdOrderByPriorityScoreDescIdAsc(Long reportId);
}
