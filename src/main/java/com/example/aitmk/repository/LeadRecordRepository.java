package com.example.aitmk.repository;

import com.example.aitmk.model.entity.LeadRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface LeadRecordRepository extends JpaRepository<LeadRecordEntity, Long> {

    Optional<LeadRecordEntity> findByCustomerPhone(String customerPhone);

    Optional<LeadRecordEntity> findByCrmRowId(String crmRowId);
}
