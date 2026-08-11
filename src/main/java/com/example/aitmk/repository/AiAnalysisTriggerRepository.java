package com.example.aitmk.repository;
import com.example.aitmk.model.entity.AiAnalysisTriggerEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant; import java.util.List;
public interface AiAnalysisTriggerRepository extends JpaRepository<AiAnalysisTriggerEntity,Long>{
    List<AiAnalysisTriggerEntity> findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(String status, Instant due, Pageable pageable);
}
