package com.example.aitmk.service.impl;

import com.example.aitmk.model.entity.*;
import com.example.aitmk.model.entity.PersistenceEnums.*;
import com.example.aitmk.repository.*;
import com.example.aitmk.service.AgentPresenceService;
import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.AssignmentPersistenceService;
import com.example.aitmk.service.v2.RealtimeEventService;
import com.example.aitmk.service.v2.RealtimePayloadFactory;
import com.example.aitmk.model.api.v2.V2Api;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.persistence.PersistenceContext;
import java.util.stream.Collectors;

@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class PersistentAgentDispatchService implements AgentDispatchService, AssignmentPersistenceService {
    private final ResourceRepository resources;
    private final ConversationRepository conversations;
    private final AssignmentRecordRepository assignments;
    private final RealtimeEventService events;
    private final RealtimePayloadFactory payloads;
    private final AgentPresenceService presenceService;
    private final Map<String, AgentProfile> profiles = new ConcurrentHashMap<>();
    @Lazy
    @Autowired
    private PersistentAgentDispatchService self;
    @PersistenceContext
    private EntityManager entityManager;
    @org.springframework.beans.factory.annotation.Value("${test.resource.phone-prefix:69906210000}")
    private String testPhonePrefix;
    @org.springframework.beans.factory.annotation.Value("${test.agent.whitelist:c5c62f71-fa3e-4256-9ef8-6fa1039bd824,1e09631a-4c21-4992-89cf-faefe1de684f,d7f38a2f-f905-4b45-989e-d2ff02d9b88f,9a9defde-6b75-4343-81a2-45c04bcb1b58}")
    private String testAgentWhitelist;
    private final AtomicInteger roundRobin = new AtomicInteger();
    private static final EnumSet<ConversationStatus> ACTIVE = EnumSet.of(ConversationStatus.ACTIVE, ConversationStatus.AI_ACTIVE, ConversationStatus.HUMAN_ACTIVE);

    @Override public void markOnline(String id) { if (StringUtils.hasText(id)) presenceService.changeStatus(id.trim(), com.example.aitmk.service.AgentPresence.ONLINE); }
    @Override public void markOffline(String id) { if (StringUtils.hasText(id)) presenceService.changeStatus(id.trim(), com.example.aitmk.service.AgentPresence.OFFLINE); }
    @Override public boolean hasOnlineAgent() { return !presenceService.assignableAgents().isEmpty(); }
    @Override @Transactional(readOnly = true) public Optional<String> getAssignedAgent(String phone) { return currentAgent(phone); }

    @Override @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public Optional<String> assignIfAbsent(String phone) {
        if (!StringUtils.hasText(phone)) return Optional.empty();
        ResourceEntity resource = lockOrCreate(phone);
        if (resource.getAssignedAgentId() != null) {
            log.debug("assignIfAbsent: resource {} already assigned to agent {}", resource.getId(), resource.getAssignedAgentId());
            return Optional.of(resource.getAssignedAgentId());
        }
        String selected = selectAgent(phone);
        if (selected == null) { resource.setResourceStatus(ResourceStatus.PENDING_ASSIGNMENT); resources.save(resource); return Optional.empty(); }
        return assignLocked(resource, selected, AssignType.AUTO, "SYSTEM");
    }

    @Override @Transactional
    public void markUnassigned(String phone) {
        ResourceEntity resource = lockOrCreate(phone);
        if (resource.getAssignedAgentId() == null) resource.setResourceStatus(ResourceStatus.PENDING_ASSIGNMENT);
        resources.save(resource);
    }

    @Override @Transactional
    public void unassignCustomer(String phone) {
        resources.findByCustomerPhoneForUpdate(phone).ifPresent(resource -> closeAssignment(resource, "UNASSIGNED", AssignmentStatus.CLOSED));
    }

    @Override @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public Optional<String> assignSpecific(String phone, String agent) {
        if (!StringUtils.hasText(phone) || !StringUtils.hasText(agent)) return Optional.empty();
        ResourceEntity resource = lockOrCreate(phone);
        if (resource.getAssignedAgentId() != null) {
            return Optional.of(resource.getAssignedAgentId());
        }
        if (!onlineAgentsSnapshot().contains(agent.trim())) {
            log.info("assignSpecific: target agent {} not online. phone={}", agent, phone);
            return Optional.empty();
        }
        return assignLocked(resource, agent.trim(), AssignType.AUTO, "SYSTEM");
    }

    @Override @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public Optional<String> transferCustomer(String phone, String target, String assignedBy) {
        if (!StringUtils.hasText(target)) return Optional.empty();
        ResourceEntity resource = resources.findByCustomerPhoneForUpdate(phone).orElse(null);
        if (resource == null || resource.getAssignedAgentId() == null) return Optional.empty();
        closeAssignment(resource, "TRANSFER", AssignmentStatus.TRANSFERRED);
        return assignLocked(resource, target.trim(), AssignType.TRANSFER,
                StringUtils.hasText(assignedBy) ? assignedBy.trim() : "SYSTEM");
    }

    @Override @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public Optional<String> assignOnePendingCustomerToAgent(String agent) {
        if (!StringUtils.hasText(agent)) return Optional.empty();
        // Try PENDING_ASSIGNMENT first, then AI_SERVING as fallback.
        // Non-whitelist agents skip test resources (phone prefix match).
        // Whitelist agents can pick up any pending resource (test or real).
        ResourceEntity candidate = null;
        for (ResourceStatus status : new ResourceStatus[]{ResourceStatus.PENDING_ASSIGNMENT, ResourceStatus.AI_SERVING}) {
            if (isWhitelistAgent(agent)) {
                candidate = resources.findFirstByResourceStatusOrderByCreatedAtAsc(status).orElse(null);
            } else {
                candidate = resources.findFirstByResourceStatusAndCustomerPhoneNotLikeOrderByCreatedAtAsc(
                        status, testPhonePrefix + "%").orElse(null);
            }
            if (candidate != null) break;
        }
        if (candidate == null) return Optional.empty();
        ResourceEntity locked = resources.findByCustomerPhoneForUpdate(candidate.getCustomerPhone()).orElse(null);
        if (locked == null || locked.getAssignedAgentId() != null) return Optional.empty();
        return assignLocked(locked, agent.trim(), AssignType.AUTO, "SYSTEM").map(ignored -> locked.getCustomerPhone());
    }

    @Override public Set<String> onlineAgentsSnapshot() { return presenceService.assignableAgents(); }
    @Override @Transactional(readOnly = true) public Map<String, String> assignmentsSnapshot() { return currentAssignments(); }

    @Override public void replaceState(Set<String> online, Map<String, String> ignoredCrmAssignments) {
        // Online status is now managed by presenceService.
        if (online != null) {
            online.stream().filter(StringUtils::hasText).map(String::trim)
                    .forEach(id -> presenceService.changeStatus(id, com.example.aitmk.service.AgentPresence.ONLINE));
        }
        if (ignoredCrmAssignments != null && !ignoredCrmAssignments.isEmpty())
            log.info("Ignored {} CRM assignments because local database is authoritative", ignoredCrmAssignments.size());
    }

    @Override public void setAgentProfile(String id, String level, double weight, int maxLoad) {
        if (StringUtils.hasText(id)) profiles.put(id.trim(), new AgentProfile(level == null ? "中级" : level, Math.max(weight, 0.1), Math.max(maxLoad, 1)));
    }
    @Override @Transactional
    public void markCustomerMessageAt(String phone) {
        Instant now = Instant.now();
        resources.findByCustomerPhoneForUpdate(phone).ifPresent(resource -> {
            resource.setLastCustomerMessageAt(max(resource.getLastCustomerMessageAt(), now));
            resource.setLastMessageAt(max(resource.getLastMessageAt(), now));
            resources.save(resource);
        });
    }

    @Override @Transactional
    public void markAgentReplied(String phone) {
        Instant now = Instant.now();
        resources.findByCustomerPhoneForUpdate(phone).ifPresent(resource -> {
            // Transition ASSIGNED → FOLLOWING_UP on first agent reply,
            // signaling the agent has started real follow-up work.
            if (resource.getResourceStatus() == ResourceStatus.ASSIGNED) {
                resource.setResourceStatus(ResourceStatus.FOLLOWING_UP);
            }

            resource.setLastAgentMessageAt(max(resource.getLastAgentMessageAt(), now));
            resource.setLastMessageAt(max(resource.getLastMessageAt(), now));
            resources.save(resource);
        });
    }



    @Override @Transactional(readOnly = true)
    public Optional<String> currentAgent(String phone) {
        return resources.findByCustomerPhone(phone).map(ResourceEntity::getAssignedAgentId).filter(StringUtils::hasText);
    }
    @Override @Transactional(readOnly = true)
    public Map<String, String> currentAssignments() {
        return assignments.findByStatus(AssignmentStatus.SERVING).stream().collect(Collectors.toMap(AssignmentRecordEntity::getCustomerPhone, AssignmentRecordEntity::getAgentId, (a,b) -> a, LinkedHashMap::new));
    }
    @Override @Transactional(readOnly = true)
    public boolean hasServed(String phone, String agent) { return assignments.existsByCustomerPhoneAndAgentId(phone, agent); }

    private Optional<String> assignLocked(ResourceEntity resource, String agent, AssignType type, String by) {
        if (resource.getAssignedAgentId() != null) {
            log.warn("assignLocked: resource {} already assigned to agent {}, skipping", resource.getId(), resource.getAssignedAgentId());
            return Optional.of(resource.getAssignedAgentId());
        }
        Instant now = Instant.now();
        ConversationEntity conversation = conversations.findFirstByResourceIdAndStatusInOrderByCreatedAtDesc(resource.getId(), ACTIVE).orElse(null);
        AssignmentRecordEntity record = new AssignmentRecordEntity();
        record.setResourceId(resource.getId()); record.setConversationId(conversation == null ? null : conversation.getId());
        record.setCustomerPhone(resource.getCustomerPhone()); record.setAgentId(agent); record.setAssignedBy(by); record.setAssignType(type);
        // Check for existing SERVING assignment before INSERT to avoid tainting the transaction.
        // When the DB unique constraint fires (uq_assignment_active_resource), the Hibernate
        // session becomes unrecoverable even with noRollbackFor on the caller, which kills the
        // outer updateStatus transaction and prevents agents from going ONLINE.
        var existingServing = assignments.findFirstByResourceIdAndStatusOrderByAssignedAtDesc(
                resource.getId(), AssignmentStatus.SERVING);
        if (existingServing.isPresent()) {
            log.warn("assignLocked: existing SERVING for resource {}, returning existing agent={}",
                    resource.getId(), existingServing.get().getAgentId());
            return Optional.of(existingServing.get().getAgentId());
        }
        assignments.saveAndFlush(record);
        String oldAgent = resource.getAssignedAgentId();
        resource.setAssignedAgentId(agent); resource.setAssignedAt(now); resource.setResourceStatus(ResourceStatus.ASSIGNED); resources.saveAndFlush(resource);
        if (conversation != null) {
            conversation.setAssignedAgentId(agent); conversation.setStatus(ConversationStatus.HUMAN_ACTIVE); conversations.saveAndFlush(conversation);
            var assignmentPayload = new V2Api.AssignmentChangedPayload(oldAgent, agent, type.name());
            events.append("ASSIGNMENT_CHANGED", "CONVERSATION", conversation.getId(), resource.getId(), conversation.getId(), agent, conversation.getVersion(), assignmentPayload);
            events.append("CONVERSATION_UPDATED", "CONVERSATION", conversation.getId(), resource.getId(), conversation.getId(), agent, conversation.getVersion(), payloads.conversation(conversation, agent));
            if (oldAgent != null && !oldAgent.equals(agent)) {
                events.append("ASSIGNMENT_CHANGED", "CONVERSATION", conversation.getId(), resource.getId(), conversation.getId(), oldAgent, conversation.getVersion(), assignmentPayload);
                events.append("CONVERSATION_UPDATED", "CONVERSATION", conversation.getId(), resource.getId(), conversation.getId(), oldAgent, conversation.getVersion(), payloads.conversation(conversation, oldAgent));
            }
        }
        return Optional.of(agent);
    }

    private void closeAssignment(ResourceEntity resource, String reason, AssignmentStatus finalStatus) {
        Instant now = Instant.now();
        assignments.findFirstByResourceIdAndStatusOrderByAssignedAtDesc(resource.getId(), AssignmentStatus.SERVING).ifPresent(a -> {
            a.setStatus(finalStatus); a.setReplyable(false); a.setClosedAt(now); a.setCloseReason(reason); assignments.saveAndFlush(a);
        });
        String oldAgent = resource.getAssignedAgentId();
        resource.setAssignedAgentId(null); resource.setAssignedAt(null); resource.setResourceStatus(ResourceStatus.PENDING_ASSIGNMENT); resource.setLastCustomerMessageAt(null); resources.saveAndFlush(resource); // Issue 1: 关闭分配时重置 lCMAt
        conversations.findFirstByResourceIdAndStatusInOrderByCreatedAtDesc(resource.getId(), ACTIVE).ifPresent(c -> {
            c.setAssignedAgentId(null); c.setStatus(ConversationStatus.AI_ACTIVE); conversations.saveAndFlush(c);
            if (oldAgent != null) {
                events.append("ASSIGNMENT_CHANGED", "CONVERSATION", c.getId(), resource.getId(), c.getId(), oldAgent, c.getVersion(),
                        new V2Api.AssignmentChangedPayload(oldAgent, null, reason));
                events.append("CONVERSATION_UPDATED", "CONVERSATION", c.getId(), resource.getId(), c.getId(), oldAgent, c.getVersion(),
                        payloads.conversation(c, oldAgent));
            }
        });
    }

    private ResourceEntity lockOrCreate(String phone) {
        if (!StringUtils.hasText(phone)) throw new IllegalArgumentException("customerPhone must not be blank");
        return resources.findByCustomerPhoneForUpdate(phone).orElseGet(() -> { ResourceEntity r = new ResourceEntity(); r.setCustomerPhone(phone); r.setSourceExternalId(phone); return resources.saveAndFlush(r); });
    }
    private String selectAgent(String phone) {
        List<String> candidates = new ArrayList<>(presenceService.assignableAgents());
        candidates.removeIf(agent -> assignments.findByAgentIdAndStatus(agent, AssignmentStatus.SERVING).size() >= profiles.getOrDefault(agent, new AgentProfile("中级", 1, 8)).maxLoad());
        // Test resources: only assign to whitelisted test agents
        if (isTestPhone(phone)) {
            candidates.removeIf(agent -> !isWhitelistAgent(agent));
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(Math.floorMod(roundRobin.getAndIncrement(), candidates.size()));
    }
    private Instant max(Instant current, Instant candidate) {
        return current == null || candidate.isAfter(current) ? candidate : current;
    }
    private boolean isTestPhone(String phone) {
        return phone != null && phone.startsWith(testPhonePrefix);
    }
    private boolean isWhitelistAgent(String agentId) {
        return agentId != null && java.util.Arrays.asList(testAgentWhitelist.split(",")).contains(agentId.trim());
    }

    private record AgentProfile(String level, double weight, int maxLoad) {}
}
