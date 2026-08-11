package com.example.aitmk.repository;
import com.example.aitmk.model.entity.AiAnalysisModuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.Optional;
public interface AiAnalysisModuleRepository extends JpaRepository<AiAnalysisModuleEntity,Long>{
    List<AiAnalysisModuleEntity> findByAnalysisIdOrderByIdAsc(Long analysisId);
    Optional<AiAnalysisModuleEntity> findByAnalysisIdAndModuleType(Long analysisId,String moduleType);
}
