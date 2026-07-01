package com.example.aitmk.service.impl;

import com.example.aitmk.model.entity.*;
import com.example.aitmk.model.entity.PersistenceEnums.*;
import com.example.aitmk.repository.*;
import com.example.aitmk.service.AgentPresenceService;
import com.example.aitmk.service.BusinessResourceService;
import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.AssignmentPersistenceService;
import com.example.aitmk.service.v2.RealtimeEventService;
import com.example.aitmk.service.v2.RealtimePayloadFactory;
import com.example.aitmk.service.v2.UnreadService;
import com.example.aitmk.model.api.v2.V2Api;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.persistence.PersistenceContext;
import java.util.stream.Collectors;

@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class PersistentAgentDispatchService implements AgentDispatchService, AssignmentPersistenceService {
    private final ResourceRepository resources;
    private final BusinessResourceService businessResourceService;
    private final AgentProfileSyncService profileSync;
    private final ConversationRepository conversations;
    private final AssignmentRecordRepository assignments;
    private final RealtimeEventService events;
    private final RealtimePayloadFactory payloads;
    private final UnreadService unreadService;
    private final AgentPresenceService presenceService;
    private final Map<String, AgentProfile> profiles = new ConcurrentHashMap<>();
    @Lazy
    @Autowired
    private PersistentAgentDispatchService self;
    @PersistenceContext
    private EntityManager entityManager;
    @org.springframework.beans.factory.annotation.Value("${agent.level.weight.senior:0}")
    private double seniorWeight;
    @org.springframework.beans.factory.annotation.Value("${agent.level.weight.middle:0}")
    private double middleWeight;
    @org.springframework.beans.factory.annotation.Value("${agent.level.weight.junior:0}")
    private double juniorWeight;
    @org.springframework.beans.factory.annotation.Value("${test.resource.phone-prefix:}")
    private String testPhonePrefix;
    @org.springframework.beans.factory.annotation.Value("${test.agent.whitelist:}")
    private String testAgentWhitelist;
    @org.springframework.beans.factory.annotation.Value("${agent.pending-assignment.scan-page-size:100}")
    private int pendingScanPageSize;
    private static final EnumSet<ConversationStatus> ACTIVE = EnumSet.of(ConversationStatus.ACTIVE, ConversationStatus.AI_ACTIVE, ConversationStatus.HUMAN_ACTIVE);

    @Override public void markOnline(String id) {
        if (StringUtils.hasText(id)) {
            String agentId = id.trim();
            presenceService.changeStatus(agentId, com.example.aitmk.service.AgentPresence.ONLINE);
            var profile = profileSync.loadProfile(agentId);
            if (profile != null) {
                setAgentProfile(agentId, profile.level(), 0, profile.maxLoad());
            } else {
                profiles.remove(agentId);
                log.warn("Agent profile missing from CRM, agent will not join weighted dispatch. agent={}", agentId);
            }
        }
    }
    @Override public void markOffline(String id) { if (StringUtils.hasText(id)) presenceService.changeStatus(id.trim(), com.example.aitmk.service.AgentPresence.OFFLINE); }
    @Override public boolean hasOnlineAgent() { return !presenceService.assignableAgents().isEmpty(); }
    @Override @Transactional(readOnly = true) public Optional<String> getAssignedAgent(String phone) { return currentAgent(phone); }

    @Override @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public Optional<String> assignIfAbsent(String phone) {
        if (!StringUtils.hasText(phone)) return Optional.empty();
        ResourceEntity resource = getOrCreate(phone);
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
        ResourceEntity resource = getOrCreate(phone);
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
        if (!canAssignTestPhone(phone, agent)) {
            log.warn("assignSpecific rejected by test-phone whitelist. phone={}, agent={}", phone, agent);
            return Optional.empty();
        }
        ResourceEntity resource = getOrCreate(phone);
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
        if (!canAssignTestPhone(phone, target)) {
            log.warn("transferCustomer rejected by test-phone whitelist. phone={}, target={}", phone, target);
            return Optional.empty();
        }
        ResourceEntity resource = resources.findByCustomerPhoneForUpdate(phone).orElse(null);
        if (resource == null || resource.getAssignedAgentId() == null) return Optional.empty();
        closeAssignment(resource, "TRANSFER", AssignmentStatus.TRANSFERRED);
        return assignLocked(resource, target.trim(), AssignType.TRANSFER,
                StringUtils.hasText(assignedBy) ? assignedBy.trim() : "SYSTEM");
    }

    @Override @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public Optional<String> assignOnePendingCustomerToAgent(String agent) {
        if (!StringUtils.hasText(agent)) return Optional.empty();
        for (ResourceStatus status : new ResourceStatus[]{ResourceStatus.PENDING_ASSIGNMENT, ResourceStatus.AI_SERVING}) {
            long lastSeenId = 0L;
            int pageSize = Math.max(1, pendingScanPageSize);
            while (true) {
                List<ResourceEntity> candidates = resources.findByResourceStatusAndIdGreaterThanOrderByIdAsc(
                        status, lastSeenId, PageRequest.of(0, pageSize));
                log.info("Pending assignment scan. triggerAgent={}, status={}, lastSeenId={}, pageSize={}, candidateCount={}",
                        agent, status, lastSeenId, pageSize, candidates.size());
                if (candidates.isEmpty()) break;
                for (ResourceEntity candidate : candidates) {
                    lastSeenId = Math.max(lastSeenId, candidate.getId());
                    ResourceEntity locked = resources.findByCustomerPhoneForUpdate(candidate.getCustomerPhone()).orElse(null);
                    if (locked == null || locked.getAssignedAgentId() != null) continue;
                    String selected = selectAgent(locked.getCustomerPhone());
                    if (selected == null) {
                        log.info("Pending assignment candidate skipped because no eligible agent. phone={}, status={}",
                                locked.getCustomerPhone(), status);
                        continue;
                    }
                    return assignLocked(locked, selected, AssignType.AUTO, "SYSTEM").map(ignored -> locked.getCustomerPhone());
                }
            }
        }
        log.info("Pending assignment scan found no assignable resource. triggerAgent={}", agent);
        return Optional.empty();
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
        if (!StringUtils.hasText(id)) return;
        String agentId = id.trim();
        String normalizedLevel = normalizeLevel(level);
        if (!StringUtils.hasText(normalizedLevel)) {
            profiles.remove(agentId);
            log.warn("Agent profile ignored because level is blank. agent={}", agentId);
            return;
        }
        double levelWeight = levelWeight(normalizedLevel);
        if (levelWeight <= 0) {
            profiles.remove(agentId);
            log.warn("Agent profile ignored because level weight is not configured. agent={}, level={}",
                    agentId, normalizedLevel);
            return;
        }
        profiles.put(agentId, new AgentProfile(normalizedLevel, Math.max(maxLoad, 1)));
        log.info("Agent profile accepted for weighted dispatch. agent={}, level={}, levelWeight={}, maxLoad={}",
                agentId, normalizedLevel, levelWeight, Math.max(maxLoad, 1));
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
            unreadService.initializeForAssignment(conversation, agent);
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

    private ResourceEntity getOrCreate(String phone) {
        if (!StringUtils.hasText(phone)) throw new IllegalArgumentException("customerPhone must not be blank");
        return businessResourceService.getOrCreateByPhone(phone);
    }
    private String selectAgent(String phone) {
        List<String> candidates = new ArrayList<>(presenceService.assignableAgents());
        if (candidates.isEmpty()) return null;
        if (isTestPhone(phone)) {
            candidates = candidates.stream().filter(this::isTestWhitelistAgent).toList();
            if (candidates.isEmpty()) {
                log.warn("No online whitelist agent for test phone. phone={}, testPhonePrefix={}",
                        phone, testPhonePrefix);
                return null;
            }
        }
        Map<String, Long> loadMap = loadServingCounts(candidates);
        Map<String, List<String>> agentsByLevel = new HashMap<>();
        for (String agent : candidates) {
            if (!ensureProfileLoaded(agent)) {
                log.warn("Agent skipped from dispatch because CRM level or configured level weight is missing. agent={}", agent);
                continue;
            }
            AgentProfile profile = profiles.get(agent);
            agentsByLevel.computeIfAbsent(profile.level(), ignored -> new ArrayList<>()).add(agent);
        }
        if (agentsByLevel.isEmpty()) {
            log.warn("No weighted dispatch candidate found. phone={}, onlineAgents={}", phone, candidates);
            return null;
        }
        String selectedLevel = selectLevel(agentsByLevel, loadMap);
        if (selectedLevel == null) return null;
        List<String> levelAgents = agentsByLevel.getOrDefault(selectedLevel, List.of());
        String selectedAgent = selectAgentInLevel(levelAgents, loadMap);
        logIfOverloaded(selectedAgent, loadMap);
        log.info("Agent selected by level weight. phone={}, agent={}, level={}, candidates={}, loadMap={}",
                phone, selectedAgent, selectedLevel, candidates, loadMap);
        return selectedAgent;
    }

    private Map<String, Long> loadServingCounts(List<String> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) return Collections.emptyMap();
        List<Object[]> rows = assignments.countServingByAgentIds(agentIds, AssignmentStatus.SERVING);
        Map<String, Long> result = new HashMap<>();
        for (Object[] row : rows) result.put((String) row[0], ((Number) row[1]).longValue());
        for (String agentId : agentIds) result.putIfAbsent(agentId, 0L);
        return result;
    }
    private Instant max(Instant current, Instant candidate) {
        return current == null || candidate.isAfter(current) ? candidate : current;
    }
    private boolean ensureProfileLoaded(String agentId) {
        if (!StringUtils.hasText(agentId)) return false;
        String normalized = agentId.trim();
        AgentProfile existing = profiles.get(normalized);
        if (existing != null && levelWeight(existing.level()) > 0) return true;
        var profile = profileSync.loadProfile(normalized);
        if (profile == null) {
            profiles.remove(normalized);
            return false;
        }
        setAgentProfile(normalized, profile.level(), 0, profile.maxLoad());
        return profiles.containsKey(normalized);
    }

    private String selectLevel(Map<String, List<String>> agentsByLevel, Map<String, Long> loadMap) {
        return agentsByLevel.keySet().stream()
                .min(Comparator
                        .comparingDouble((String level) -> (levelLoad(agentsByLevel.get(level), loadMap) + 1.0)
                                / levelWeight(level))
                        .thenComparingLong(level -> levelLoad(agentsByLevel.get(level), loadMap))
                        .thenComparing(level -> level))
                .orElse(null);
    }

    private String selectAgentInLevel(List<String> agents, Map<String, Long> loadMap) {
        return agents.stream()
                .min(Comparator
                        .comparingLong((String agent) -> loadMap.getOrDefault(agent, 0L))
                        .thenComparing(agent -> agent))
                .orElse(null);
    }

    private long levelLoad(List<String> agents, Map<String, Long> loadMap) {
        if (agents == null || agents.isEmpty()) return 0L;
        return agents.stream().mapToLong(agent -> loadMap.getOrDefault(agent, 0L)).sum();
    }

    private double levelWeight(String level) {
        String normalized = normalizeLevel(level);
        if (!StringUtils.hasText(normalized)) return 0;
        return switch (normalized) {
            case "高级" -> Math.max(seniorWeight, 0);
            case "中级" -> Math.max(middleWeight, 0);
            case "初级" -> Math.max(juniorWeight, 0);
            default -> 0;
        };
    }

    private String normalizeLevel(String level) {
        return level == null ? "" : level.trim();
    }

    private boolean canAssignTestPhone(String phone, String agentId) {
        return !isTestPhone(phone) || isTestWhitelistAgent(agentId);
    }

    private boolean isTestPhone(String phone) {
        return StringUtils.hasText(phone)
                && StringUtils.hasText(testPhonePrefix)
                && phone.trim().startsWith(testPhonePrefix.trim());
    }

    private boolean isTestWhitelistAgent(String agentId) {
        if (!StringUtils.hasText(agentId) || !StringUtils.hasText(testAgentWhitelist)) return false;
        String normalizedAgent = agentId.trim();
        return Arrays.stream(testAgentWhitelist.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .anyMatch(normalizedAgent::equals);
    }

    private void logIfOverloaded(String agentId, Map<String, Long> loadMap) {
        if (!StringUtils.hasText(agentId)) return;
        AgentProfile profile = profiles.get(agentId);
        if (profile == null) return;
        long load = loadMap.getOrDefault(agentId, 0L);
        if (load >= profile.maxLoad()) {
            log.warn("Agent overloaded but still assigned. agent={}, level={}, load={}, maxLoad={}",
                    agentId, profile.level(), load, profile.maxLoad());
        }
    }

    private record AgentProfile(String level, int maxLoad) {}
}
