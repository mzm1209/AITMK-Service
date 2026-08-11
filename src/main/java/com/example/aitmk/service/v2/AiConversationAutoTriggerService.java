package com.example.aitmk.service.v2;

import com.example.aitmk.config.AiConversationProperties;
import com.example.aitmk.model.entity.*;
import com.example.aitmk.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j @Service @RequiredArgsConstructor
public class AiConversationAutoTriggerService {
    private final AiConversationProperties properties;private final AiAnalysisTriggerRepository triggers;
    private final ChatMessageRepository messages;private final ConversationRepository conversations;
    private final ResourceRepository resources;private final AiConversationSnapshotService snapshots;
    private final AiConversationAnalysisService analyses;

    @Transactional
    public void onCustomerMessage(ConversationEntity c,ChatMessageEntity message){
        if(!properties.isEnabled()||!properties.isAutoAnalysisEnabled()||c.getAssignedAgentId()==null)return;
        if(c.getStatus()==PersistenceEnums.ConversationStatus.CLOSED)return;
        Instant enabledAt=properties.getAutoAnalysisEnabledAt();
        // An explicit rollout boundary is required so existing leads are never
        // silently treated as new leads when automatic analysis is enabled.
        if(enabledAt==null)return;
        ResourceEntity r=resources.findById(c.getResourceId()).orElse(null);
        if(r==null||r.getCreatedAt()==null||r.getCreatedAt().isBefore(enabledAt))return;
        long count=messages.countByConversationIdAndSenderType(c.getId(),PersistenceEnums.SenderType.CUSTOMER);
        if(count<properties.getAutoAnalysisMinCustomerMessages())return;
        AiAnalysisTriggerEntity t=triggers.findById(c.getId()).orElseGet(AiAnalysisTriggerEntity::new);
        t.setConversationId(c.getId());t.setResourceId(c.getResourceId());t.setBasisLastMessageId(message.getId());
        t.setScheduledAt((message.getCreatedAt()==null?Instant.now():message.getCreatedAt()).plusSeconds(Math.max(1,properties.getAutoAnalysisDebounceSeconds())));
        t.setStatus("PENDING");triggers.save(t);
    }

    @Scheduled(fixedDelayString="${aitmk.ai.conversation.trigger-scan-delay-ms:30000}")
    @Transactional
    public void runDue(){
        if(!properties.isEnabled()||!properties.isAutoAnalysisEnabled())return;
        for(AiAnalysisTriggerEntity t:triggers.findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc("PENDING",Instant.now(),PageRequest.of(0,20))){
            ConversationEntity c=conversations.findById(t.getConversationId()).orElse(null);
            if(c==null||c.getStatus()==PersistenceEnums.ConversationStatus.CLOSED||c.getAssignedAgentId()==null){t.setStatus("CANCELLED");triggers.save(t);continue;}
            var latest=messages.findFirstByConversationIdAndSenderTypeOrderByCreatedAtDescIdDesc(c.getId(),PersistenceEnums.SenderType.CUSTOMER).orElse(null);
            if(latest==null||!latest.getId().equals(t.getBasisLastMessageId())){t.setStatus("CANCELLED");triggers.save(t);continue;}
            try{var snapshot=snapshots.build(c,properties.getOutputLocale(),"AUTO");if(!snapshot.basisLastMessageId().equals(t.getBasisLastMessageId())){t.setStatus("CANCELLED");}else{analyses.createAuto(c,snapshot);t.setStatus("DISPATCHED");}}catch(Exception ex){log.warn("AI auto analysis dispatch failed. conversationId={}",c.getId(),ex);t.setStatus("FAILED");}triggers.save(t);
        }
    }
}
