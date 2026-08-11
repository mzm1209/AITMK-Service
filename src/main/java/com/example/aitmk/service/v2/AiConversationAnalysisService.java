package com.example.aitmk.service.v2;

import com.example.aitmk.config.AiConversationProperties;
import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.model.api.v2.V2Exception;
import com.example.aitmk.model.entity.*;
import com.example.aitmk.repository.*;
import com.example.aitmk.security.auth.AuthenticatedUser;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class AiConversationAnalysisService {
    public static final List<String> ALL=List.of("INSIGHT","LEAD_ENRICHMENT","REPLY_SUGGESTION","FOLLOW_UP_DRAFT","APPOINTMENT_DRAFT");
    private final AiConversationProperties properties; private final ConversationQueryService conversationQuery;
    private final ConversationRepository conversations; private final ChatMessageRepository messages;
    private final AiConversationSnapshotService snapshots; private final AiConversationAnalysisRepository analyses;
    private final AiAnalysisModuleRepository modules; private final AiActionDraftRepository drafts;
    private final AiConversationAnalysisExecutor executor; private final V2AccessService access; private final ObjectMapper json;

    @Transactional
    public AiAnalysisAccepted createManual(Long conversationId,String idempotencyKey,AiAnalysisRequest request,AuthenticatedUser user){
        if(!properties.isEnabled())throw new V2Exception(HttpStatus.SERVICE_UNAVAILABLE,"AI_ANALYSIS_DISABLED","AI 会话分析未启用");
        if(idempotencyKey==null||idempotencyKey.isBlank())throw new V2Exception(HttpStatus.BAD_REQUEST,"IDEMPOTENCY_KEY_MISSING","Idempotency-Key 不能为空");
        ConversationEntity c=conversationQuery.get(conversationId,user); requireCurrentAssignee(c,user,"AI_ANALYSIS_NOT_ASSIGNEE");
        Optional<AiConversationAnalysisEntity> byRequest=analyses.findByRequestId("manual:"+idempotencyKey.trim());
        if(byRequest.isPresent())return accepted(byRequest.get(),true);
        List<String> requested=normalizeModules(request==null?null:request.modules());
        var snapshot=snapshots.build(c,request==null?null:request.outputLocale(),request==null?null:request.replyLanguage());
        boolean force=request!=null&&Boolean.TRUE.equals(request.force());
        if(!force){
            var existing=analyses.findFirstByConversationIdAndBasisLastMessageIdAndStatusInOrderByIdDesc(conversationId,snapshot.basisLastMessageId(),List.of("QUEUED","RUNNING","SUCCESS","PARTIAL_SUCCESS"));
            if(existing.isPresent())return accepted(existing.get(),true);
        }
        AiConversationAnalysisEntity a=create(c,snapshot,"MANUAL","manual:"+idempotencyKey.trim(),user.getAccountRowId(),requested);
        dispatchAfterCommit(a.getId(),requested,snapshot.replyLanguage()); return accepted(a,false);
    }

    @Transactional
    public AiConversationAnalysisEntity createAuto(ConversationEntity c,AiConversationSnapshotService.Snapshot snapshot){
        var existing=analyses.findFirstByConversationIdAndBasisLastMessageIdAndStatusInOrderByIdDesc(c.getId(),snapshot.basisLastMessageId(),List.of("QUEUED","RUNNING","SUCCESS","PARTIAL_SUCCESS"));
        if(existing.isPresent())return existing.get();
        String request="auto:"+c.getId()+":"+snapshot.basisLastMessageId();
        AiConversationAnalysisEntity a=create(c,snapshot,"AUTO",request,"system",ALL);
        dispatchAfterCommit(a.getId(),ALL,snapshot.replyLanguage());return a;
    }

    private AiConversationAnalysisEntity create(ConversationEntity c,AiConversationSnapshotService.Snapshot s,String trigger,String request,String creator,List<String> selected){
        AiConversationAnalysisEntity a=new AiConversationAnalysisEntity();a.setConversationId(c.getId());a.setResourceId(c.getResourceId());
        a.setLeadRowId(s.leadRowId());a.setAssigneeId(c.getAssignedAgentId());a.setTriggerType(trigger);a.setBasisLastMessageId(s.basisLastMessageId());
        a.setCustomerMessageCount(s.customerMessageCount());a.setStatus("QUEUED");a.setRequestId(request);a.setCreatedBy(creator);
        try{Map<String,Object> doc=new LinkedHashMap<>();doc.put("commonInputs",s.commonInputs());doc.put("fieldCatalogJson",s.fieldCatalogJson());doc.put("outputLocale",s.outputLocale());doc.put("replyLanguage",s.replyLanguage());doc.put("modules",selected);a.setSnapshotJson(json.writeValueAsString(doc));}catch(Exception ex){throw new IllegalStateException(ex);}
        a=analyses.save(a);for(String type:selected){AiAnalysisModuleEntity m=new AiAnalysisModuleEntity();m.setAnalysisId(a.getId());m.setModuleType(type);m.setStatus("PENDING");modules.save(m);}return a;
    }

    @Transactional(readOnly=true)
    public AiAnalysisView latest(Long conversationId,AuthenticatedUser user){conversationQuery.get(conversationId,user);return analyses.findFirstByConversationIdOrderByIdDesc(conversationId).map(a->view(a,user)).orElseGet(()->new AiAnalysisView(false,true,false,"NO_ANALYSIS",null,String.valueOf(conversationId),null,null,null,null,null,null,null,false,null,Map.of()));}
    @Transactional(readOnly=true)
    public AiAnalysisView get(Long conversationId,Long analysisId,AuthenticatedUser user){conversationQuery.get(conversationId,user);AiConversationAnalysisEntity a=analyses.findByIdAndConversationId(analysisId,conversationId).orElseThrow(()->new V2Exception(HttpStatus.NOT_FOUND,"AI_ANALYSIS_NOT_FOUND","AI 分析不存在"));return view(a,user);}
    @Transactional(readOnly=true)
    public AiDraftListView listDrafts(Long conversationId,Long analysisId,AuthenticatedUser user){conversationQuery.get(conversationId,user);AiConversationAnalysisEntity a=analyses.findByIdAndConversationId(analysisId,conversationId).orElseThrow(()->new V2Exception(HttpStatus.NOT_FOUND,"AI_ANALYSIS_NOT_FOUND","AI 分析不存在"));return new AiDraftListView(drafts.findByAnalysisIdOrderByIdAsc(a.getId()).stream().map(this::draftView).toList());}

    @Transactional
    public AiAnalysisAccepted retry(Long conversationId,Long analysisId,String moduleType,String idempotencyKey,AuthenticatedUser user){
        if(idempotencyKey==null||idempotencyKey.isBlank())throw new V2Exception(HttpStatus.BAD_REQUEST,"IDEMPOTENCY_KEY_MISSING","Idempotency-Key 不能为空");
        ConversationEntity c=conversationQuery.get(conversationId,user);requireCurrentAssignee(c,user,"AI_ANALYSIS_NOT_ASSIGNEE");
        String type=moduleType==null?"":moduleType.trim().toUpperCase(Locale.ROOT);if(!ALL.contains(type))throw new V2Exception(HttpStatus.BAD_REQUEST,"AI_MODULE_INVALID","不支持的 AI 模块");
        AiConversationAnalysisEntity a=analyses.findByIdAndConversationId(analysisId,conversationId).orElseThrow(()->new V2Exception(HttpStatus.NOT_FOUND,"AI_ANALYSIS_NOT_FOUND","AI 分析不存在"));
        AiAnalysisModuleEntity m=modules.findByAnalysisIdAndModuleType(a.getId(),type).orElseThrow(()->new V2Exception(HttpStatus.NOT_FOUND,"AI_MODULE_NOT_FOUND","分析模块不存在"));
        if("RUNNING".equals(m.getStatus()))throw new V2Exception(HttpStatus.CONFLICT,"AI_ANALYSIS_ALREADY_RUNNING","模块正在运行");
        m.setStatus("PENDING");m.setErrorCode(null);m.setErrorMessage(null);modules.save(m);a.setStatus("QUEUED");a.setCompletedAt(null);analyses.save(a);
        dispatchAfterCommit(a.getId(),List.of(type),"AUTO");return accepted(a,false);
    }

    private AiAnalysisView view(AiConversationAnalysisEntity a,AuthenticatedUser user){
        Map<String,Object> mv=new LinkedHashMap<>();for(AiAnalysisModuleEntity m:modules.findByAnalysisIdOrderByIdAsc(a.getId())){Map<String,Object>x=new LinkedHashMap<>();x.put("status",m.getStatus());x.put("workflowRunId",m.getWorkflowRunId());if(m.getResultJson()!=null)try{x.put("result",json.readTree(m.getResultJson()));}catch(Exception ignored){}drafts.findByAnalysisIdOrderByIdAsc(a.getId()).stream().filter(d->d.getModuleId().equals(m.getId())).findFirst().ifPresent(d->x.put("draftId",String.valueOf(d.getId())));if(m.getErrorCode()!=null)x.put("errorCode",m.getErrorCode());mv.put(m.getModuleType(),x);}
        Long current=messages.findByConversationIdOrderByCreatedAtDescIdDesc(a.getConversationId(),org.springframework.data.domain.PageRequest.of(0,1)).stream().findFirst().map(ChatMessageEntity::getId).orElse(null);
        return new AiAnalysisView(true,true,false,null,String.valueOf(a.getId()),String.valueOf(a.getConversationId()),String.valueOf(a.getResourceId()),a.getLeadRowId(),a.getAssigneeId(),a.getTriggerType(),a.getStatus(),String.valueOf(a.getBasisLastMessageId()),current==null?null:String.valueOf(current),!Objects.equals(current,a.getBasisLastMessageId()),a.getCompletedAt()!=null?a.getCompletedAt():a.getCreatedAt(),mv);
    }
    private AiDraftView draftView(AiActionDraftEntity d){Object payload=d.getPayloadJson();try{payload=json.readTree(d.getPayloadJson());}catch(Exception ignored){}return new AiDraftView(String.valueOf(d.getId()),String.valueOf(d.getAnalysisId()),String.valueOf(d.getConversationId()),d.getDraftType(),d.getStatus(),payload,d.getExternalRowId(),d.getConfirmedBy(),d.getConfirmedAt(),d.getErrorCode(),d.getErrorMessage());}
    private List<String> normalizeModules(List<String> raw){if(raw==null||raw.isEmpty())return ALL;LinkedHashSet<String>s=new LinkedHashSet<>();for(String x:raw){String v=x==null?"":x.trim().toUpperCase(Locale.ROOT);if(!ALL.contains(v))throw new V2Exception(HttpStatus.BAD_REQUEST,"AI_MODULE_INVALID","不支持的 AI 模块: "+x);s.add(v);}if(s.stream().anyMatch(v->List.of("REPLY_SUGGESTION","FOLLOW_UP_DRAFT","APPOINTMENT_DRAFT").contains(v)))s.add("INSIGHT");return new ArrayList<>(s);}
    private void requireCurrentAssignee(ConversationEntity c,AuthenticatedUser u,String code){if(c.getAssignedAgentId()==null||u==null||!c.getAssignedAgentId().equals(u.getAccountRowId()))throw new V2Exception(HttpStatus.FORBIDDEN,code,"仅当前负责人可执行此操作");}
    private AiAnalysisAccepted accepted(AiConversationAnalysisEntity a,boolean reused){return new AiAnalysisAccepted(String.valueOf(a.getId()),a.getStatus(),reused,String.valueOf(a.getBasisLastMessageId()));}
    private void dispatchAfterCommit(Long id,List<String> selected,String replyLanguage){
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()){
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCommit(){executor.execute(id,selected,replyLanguage);}
            });
        }else executor.execute(id,selected,replyLanguage);
    }
}
