package com.example.aitmk.repository;

import com.example.aitmk.model.entity.AiDailyReportEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AiDailyReportRepository extends JpaRepository<AiDailyReportEntity, Long> {
    List<AiDailyReportEntity> findByReportDateOrderByVersionDesc(LocalDate reportDate);
    List<AiDailyReportEntity> findAllByOrderByReportDateDescVersionDesc(Pageable pageable);
    Optional<AiDailyReportEntity> findFirstByReportDateAndStatusOrderByVersionDesc(LocalDate reportDate, AiDailyReportEntity.Status status);
}
