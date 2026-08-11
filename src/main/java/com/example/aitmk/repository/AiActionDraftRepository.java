package com.example.aitmk.repository;
import com.example.aitmk.model.entity.AiActionDraftEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.Optional;
public interface AiActionDraftRepository extends JpaRepository<AiActionDraftEntity,Long>{
    List<AiActionDraftEntity> findByAnalysisIdOrderByIdAsc(Long analysisId);
    Optional<AiActionDraftEntity> findByIdempotencyKey(String idempotencyKey);
}
