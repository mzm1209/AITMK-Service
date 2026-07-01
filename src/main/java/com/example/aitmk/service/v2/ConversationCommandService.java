package com.example.aitmk.service.v2;

import com.example.aitmk.model.api.v2.V2Api;
import com.example.aitmk.model.api.v2.V2Exception;
import com.example.aitmk.model.entity.AssignmentRecordEntity;
import com.example.aitmk.model.entity.ConversationEntity;
import com.example.aitmk.model.entity.ResourceEntity;
import com.example.aitmk.repository.AssignmentRecordRepository;
import com.example.aitmk.repository.ConversationRepository;
import com.example.aitmk.repository.ResourceRepository;
import com.example.aitmk.security.auth.AuthenticatedUser;
import com.example.aitmk.security.auth.Permission;
import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.CrmOpenApiService;
import com.example.aitmk.service.impl.ClueIntegrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static com.example.aitmk.model.entity.PersistenceEnums.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationCommandService {
    private final ConversationRepository conversations;
    private final ResourceRepository resources;
    private final AssignmentRecordRepository assignments;
    private final AgentDispatchService agents;
    private final CrmOpenApiService crm;
    private final ClueIntegrationService clueIntegrationService;
    private final V2AccessService access;
    private final RealtimeEventService events;
    private final RealtimePayloadFactory payloads;

    @Transactional
    public ConversationEntity transfer(Long id, V2Api.TransferRequest req, AuthenticatedUser user) {
        access.require(user, Permission.RESOURCE_ASSIGN);
        ConversationEntity conversation = lock(id);
        access.requireView(user, conversation);
        if (!conversation.getVersion().equals(req.expectedVersion())) throw conflict();
        if (conversation.getStatus() == ConversationStatus.CLOSED)
            throw new V2Exception(HttpStatus.UNPROCESSABLE_ENTITY, "CONVERSATION_CLOSED", "会话已关闭");
        if (req.targetAgentId() == null || req.targetAgentId().isBlank()
                || req.targetAgentId().equals(conversation.getAssignedAgentId()))
            throw new V2Exception(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_AGENT_INVALID", "目标坐席无效");

        ResourceEntity resource = resources.findByIdForUpdate(conversation.getResourceId()).orElseThrow();
        String oldAgent = conversation.getAssignedAgentId();
        assignments.findFirstByResourceIdAndStatusOrderByAssignedAtDesc(resource.getId(), AssignmentStatus.SERVING)
                .ifPresent(assignment -> {
                    assignment.setStatus(AssignmentStatus.TRANSFERRED);
                    assignment.setReplyable(false);
                    assignment.setClosedAt(Instant.now());
                    assignment.setCloseReason(req.reason() != null ? req.reason() : "");
                });
        assignments.flush();
        AssignmentRecordEntity assignment = new AssignmentRecordEntity();
        assignment.setResourceId(resource.getId());
        assignment.setConversationId(conversation.getId());
        assignment.setCustomerPhone(resource.getCustomerPhone());
        assignment.setAgentId(req.targetAgentId());
        assignment.setAssignedBy(user.getAccountRowId());
        assignment.setAssignType(AssignType.TRANSFER);
        assignments.save(assignment);
        resource.setAssignedAgentId(req.targetAgentId());
        resource.setAssignedAt(Instant.now());
        resource.setResourceStatus(ResourceStatus.ASSIGNED);
        conversation.setAssignedAgentId(req.targetAgentId());
        conversation.setStatus(ConversationStatus.HUMAN_ACTIVE);
        resources.saveAndFlush(resource);
        conversations.saveAndFlush(conversation);

        syncCrmTransfer(resource, req.targetAgentId());

        var assignmentPayload = new V2Api.AssignmentChangedPayload(
                oldAgent, req.targetAgentId(), req.reason() != null ? req.reason() : "");
        appendAssignment(conversation, resource, req.targetAgentId(), assignmentPayload);
        appendConversationUpdated(conversation, req.targetAgentId());
        if (oldAgent != null) {
            appendAssignment(conversation, resource, oldAgent, assignmentPayload);
            appendConversationUpdated(conversation, oldAgent);
        }
        return conversation;
    }

    @Transactional
    public ConversationEntity close(Long id, V2Api.CloseRequest req, AuthenticatedUser user) {
        ConversationEntity conversation = lock(id);
        access.requireView(user, conversation);
        if (conversation.getStatus() == ConversationStatus.CLOSED) return conversation;
        if (!conversation.getVersion().equals(req.expectedVersion())) throw conflict();
        ResourceEntity resource = resources.findByIdForUpdate(conversation.getResourceId()).orElseThrow();
        conversation.setStatus(ConversationStatus.CLOSED);
        conversation.setClosedAt(Instant.now());
        conversation.setClosedBy(user.getAccountRowId());
        conversation.setCloseReason(req.reasonCode() + (req.remark() == null ? "" : " - " + req.remark()));
        assignments.findFirstByResourceIdAndStatusOrderByAssignedAtDesc(resource.getId(), AssignmentStatus.SERVING)
                .ifPresent(assignment -> {
                    assignment.setStatus(AssignmentStatus.CLOSED);
                    assignment.setReplyable(false);
                    assignment.setClosedAt(Instant.now());
                    assignment.setCloseReason(conversation.getCloseReason());
                });
        resource.setResourceStatus(switch (req.reasonCode() == null ? "" : req.reasonCode()) {
            case "RESOLVED" -> ResourceStatus.RESOLVED;
            case "INVALID" -> ResourceStatus.INVALID;
            default -> ResourceStatus.CLOSED;
        });
        resources.saveAndFlush(resource);
        conversations.saveAndFlush(conversation);
        // Issue 9: 同步 CRM 关闭分配
        try { crm.closeServingAssignment(resource.getCustomerPhone()); } catch (Exception ex) { log.warn("CRM close assignment failed", ex); }
        appendConversationUpdated(conversation, conversation.getAssignedAgentId());
        return conversation;
    }

    private void appendAssignment(ConversationEntity conversation, ResourceEntity resource, String target,
            V2Api.AssignmentChangedPayload payload) {
        events.append("ASSIGNMENT_CHANGED", "CONVERSATION", conversation.getId(), resource.getId(),
                conversation.getId(), target, conversation.getVersion(), payload);
    }

    private void appendConversationUpdated(ConversationEntity conversation, String target) {
        events.append("CONVERSATION_UPDATED", "CONVERSATION", conversation.getId(), conversation.getResourceId(),
                conversation.getId(), target, conversation.getVersion(), payloads.conversation(conversation, target));
    }

    private void syncCrmTransfer(ResourceEntity resource, String targetAgentId) {
        try {
            if (!crm.closeServingAssignment(resource.getCustomerPhone())) {
                log.warn("CRM close assignment for transfer returned false. customer={}", resource.getCustomerPhone());
            }
        } catch (Exception ex) {
            log.warn("CRM close assignment for transfer failed. customer={}", resource.getCustomerPhone(), ex);
        }
        try {
            if (!crm.addAssignmentRecord(resource.getCustomerPhone(), targetAgentId, "服务中")) {
                log.warn("CRM add assignment for transfer returned false. customer={}, agent={}",
                        resource.getCustomerPhone(), targetAgentId);
            }
        } catch (Exception ex) {
            log.warn("CRM add assignment for transfer failed. customer={}, agent={}",
                    resource.getCustomerPhone(), targetAgentId, ex);
        }
        try {
            var lead = clueIntegrationService.lookupLeadByPhone(resource.getCustomerPhone())
                    .or(() -> clueIntegrationService.createLeadForNewCustomer(
                            resource.getCustomerPhone(), resource.getCustomerName(), targetAgentId));
            lead.map(com.example.aitmk.model.domain.LeadRecord::getRowId)
                    .filter(rowId -> rowId != null && !rowId.isBlank())
                    .ifPresent(rowId -> clueIntegrationService.updateLeadOnAssignment(rowId, targetAgentId));
        } catch (Exception ex) {
            log.warn("CRM lead update for transfer failed. customer={}, agent={}",
                    resource.getCustomerPhone(), targetAgentId, ex);
        }
    }

    private ConversationEntity lock(Long id) {
        return conversations.findByIdForUpdate(id)
                .orElseThrow(() -> new V2Exception(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "会话不存在"));
    }

    private V2Exception conflict() {
        return new V2Exception(HttpStatus.CONFLICT, "VERSION_CONFLICT", "会话已被其他操作更新");
    }
}
