package com.example.aitmk.repository;

import com.example.aitmk.model.entity.LeadRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface LeadRecordRepository extends JpaRepository<LeadRecordEntity, Long> {

    Optional<LeadRecordEntity> findByCustomerPhone(String customerPhone);

    Optional<LeadRecordEntity> findByCrmRowId(String crmRowId);

    @Query("select distinct l.leadsType from LeadRecordEntity l where l.leadsType is not null and l.leadsType <> '' order by l.leadsType")
    List<String> findDistinctLeadsTypes();

    @Query("select distinct l.leadsStatus from LeadRecordEntity l where l.leadsStatus is not null and l.leadsStatus <> '' order by l.leadsStatus")
    List<String> findDistinctLeadsStatuses();
}
