package com.example.aitmk.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Getter @Setter
@Entity
@Table(name = "agent_accounts")
public class AgentAccountEntity {
    @Id
    @Column(name = "row_id", nullable = false, length = 64)
    private String rowId;

    @Column(name = "login_account", nullable = false, length = 191)
    private String loginAccount;

    @Column(name = "role", nullable = false, length = 32)
    private String role = "TMK";

    @Column(name = "managed_agent_ids", length = 2048)
    private String managedAgentIds = "";

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist void prePersist() { if (updatedAt == null) updatedAt = Instant.now(); }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }
}
