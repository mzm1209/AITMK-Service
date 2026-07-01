package com.example.aitmk.repository;

import com.example.aitmk.model.entity.AgentAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentAccountRepository extends JpaRepository<AgentAccountEntity, String> {
}
