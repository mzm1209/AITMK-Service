package com.example.aitmk.service.v2;

import com.example.aitmk.model.entity.*;
import com.example.aitmk.repository.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Slf4j @Service @RequiredArgsConstructor
public class AiConversationAnalysisExecutor {
    private final AiConversationAnalysisRepository analyses; private final AiAnalysisModuleRepository modules;
    private final AiActionDraftRepository drafts; private final AiConversationDifyClient dify;
    private final RealtimeEventService realtime; private final ObjectMapper json;
    @Resource(name="aiConversationWorkflowExecutor") private Executor workflowExecutor;

    @Async("aiConversationExecutor")
    public void execute(Long analysisId,List<String> selected,String replyLanguage){
        AiConversationAnalysisEntity a=analyses.findById(analysisId).orElse(null);if(a==null)return;
        a.setStatus("RUNNING");a.setStartedAt(Instant.now());analyses.save(a);
        try{
            JsonNode snapshot=json.readTree(a.getSnapshotJson());
            Map<String,String> common=readStringMap(snapshot.path("commonInputs"));
            common.put("analysis_id",String.valueOf(a.getId()));common.put("trigger_type",a.getTriggerType());
            String fieldCatalog=snapshot.path("fieldCatalogJson").asText("{\"controls\":[]}");

            CompletableFuture<AiConversationDifyClient.Result> insightFuture=selected.contains("INSIGHT")?runAsync("INSIGHT",common,a):CompletableFuture.completedFuture(null);
            CompletableFuture<AiConversationDifyClient.Result> leadFuture=selected.contains("LEAD_ENRICHMENT")?runAsync("LEAD_ENRICHMENT",with(common,"field_catalog_json",fieldCatalog),a):CompletableFuture.completedFuture(null);
            CompletableFuture.allOf(insightFuture,leadFuture).join();
            AiConversationDifyClient.Result insight=safeJoin(insightFuture);
            String insightData="{}";String insightEnvelope="{}";
            if(insight!=null&&"SUCCESS".equals(insight.status())){insightEnvelope=insight.resultJson();insightData=json.writeValueAsString(insight.result().path("data"));}
            else if(!selected.contains("INSIGHT")){
                AiAnalysisModuleEntity previous=modules.findByAnalysisIdAndModuleType(a.getId(),"INSIGHT").orElse(null);
                if(previous!=null&&"SUCCESS".equals(previous.getStatus())&&previous.getResultJson()!=null){insightEnvelope=previous.getResultJson();insightData=json.writeValueAsString(json.readTree(previous.getResultJson()).path("data"));}
            }

            List<CompletableFuture<AiConversationDifyClient.Result>> phaseB=new ArrayList<>();
            if(selected.contains("REPLY_SUGGESTION"))phaseB.add(runAsync("REPLY_SUGGESTION",replyInputs(common,insightData,replyLanguage),a));
            if(selected.contains("FOLLOW_UP_DRAFT"))phaseB.add(runAsync("FOLLOW_UP_DRAFT",followInputs(common,insightEnvelope),a));
            if(selected.contains("APPOINTMENT_DRAFT"))phaseB.add(runAsync("APPOINTMENT_DRAFT",appointmentInputs(common,insightEnvelope,fieldCatalog),a));
            CompletableFuture.allOf(phaseB.toArray(CompletableFuture[]::new)).join();
        }catch(Exception ex){log.error("AI conversation analysis orchestration failed. analysisId={}",analysisId,ex);a.setErrorMessage(shortText(ex.getMessage()));}
        finish(a);
    }

    private CompletableFuture<AiConversationDifyClient.Result> runAsync(String type,Map<String,String> inputs,AiConversationAnalysisEntity a){
        return CompletableFuture.supplyAsync(()->runModule(type,inputs,a),workflowExecutor);
    }
    @Transactional
    public AiConversationDifyClient.Result runModule(String type,Map<String,String> inputs,AiConversationAnalysisEntity a){
        AiAnalysisModuleEntity m=modules.findByAnalysisIdAndModuleType(a.getId(),type).orElseThrow();
        m.setStatus("RUNNING");m.setAttemptCount(m.getAttemptCount()+1);m.setStartedAt(Instant.now());m.setInputHash(hash(inputs));modules.save(m);
        try{
            AiConversationDifyClient.Result r=dify.run(type,inputs,String.valueOf(a.getConversationId()));
            m.setWorkflowRunId(r.workflowRunId());m.setResultJson(r.resultJson());m.setCompletedAt(Instant.now());m.setStatus(r.status());
            if("FAILED".equals(r.status())){m.setErrorCode(r.result().path("error").path("code").asText("AI_WORKFLOW_FAILED"));m.setErrorMessage(r.result().path("error").path("message").asText("workflow returned FAILED"));}
            modules.save(m);if("SUCCESS".equals(r.status()))createDraft(a,m,r.result());return r;
        }catch(Exception ex){m.setStatus("FAILED");m.setCompletedAt(Instant.now());m.setErrorCode(ex instanceof com.example.aitmk.model.api.v2.V2Exception ve?ve.getCode():"AI_WORKFLOW_FAILED");m.setErrorMessage(shortText(ex.getMessage()));modules.save(m);return null;}
    }
    private void createDraft(AiConversationAnalysisEntity a,AiAnalysisModuleEntity m,JsonNode result){
        String draftType=switch(m.getModuleType()){case "LEAD_ENRICHMENT"->"LEAD_PATCH";case "REPLY_SUGGESTION"->"REPLY";case "FOLLOW_UP_DRAFT"->"FOLLOW_UP";case "APPOINTMENT_DRAFT"->"APPOINTMENT";default->null;};
        if(draftType==null)return;JsonNode data=result.path("data");
        if("APPOINTMENT".equals(draftType)&&!data.path("applicable").asBoolean(false))return;
        AiActionDraftEntity d=new AiActionDraftEntity();d.setAnalysisId(a.getId());d.setModuleId(m.getId());d.setConversationId(a.getConversationId());d.setDraftType(draftType);
        try{ObjectNode payload=data.deepCopy();payload.put("resourceId",a.getResourceId());if(a.getLeadRowId()!=null)payload.put("leadRowId",a.getLeadRowId());d.setPayloadJson(json.writeValueAsString(payload));}catch(Exception ex){throw new IllegalStateException(ex);}drafts.save(d);
    }
    private void finish(AiConversationAnalysisEntity a){List<AiAnalysisModuleEntity> rows=modules.findByAnalysisIdOrderByIdAsc(a.getId());long success=rows.stream().filter(x->Set.of("SUCCESS","NOT_APPLICABLE").contains(x.getStatus())).count();long failed=rows.stream().filter(x->"FAILED".equals(x.getStatus())).count();a.setStatus(failed==0?"SUCCESS":success==0?"FAILED":"PARTIAL_SUCCESS");a.setCompletedAt(Instant.now());analyses.save(a);Map<String,Object> payload=new LinkedHashMap<>();payload.put("analysisId",String.valueOf(a.getId()));payload.put("status",a.getStatus());payload.put("basisLastMessageId",String.valueOf(a.getBasisLastMessageId()));payload.put("completedModules",rows.stream().filter(x->"SUCCESS".equals(x.getStatus())).map(AiAnalysisModuleEntity::getModuleType).toList());payload.put("failedModules",rows.stream().filter(x->"FAILED".equals(x.getStatus())).map(AiAnalysisModuleEntity::getModuleType).toList());payload.put("notApplicableModules",rows.stream().filter(x->"NOT_APPLICABLE".equals(x.getStatus())).map(AiAnalysisModuleEntity::getModuleType).toList());realtime.append("AI_ANALYSIS_UPDATED","AI_ANALYSIS",a.getId(),a.getResourceId(),a.getConversationId(),a.getAssigneeId(),null,payload);}
    private Map<String,String> replyInputs(Map<String,String> c,String insight,String language){Map<String,String>x=new LinkedHashMap<>(c);x.put("insight_json",insight);x.put("reply_language",language==null?"AUTO":language);x.put("reply_policy_json",write(Map.of("replyable",true,"suggestionCount",3,"maxReplyLength",240,"maxRecentMessages",12,"maxEmojiCount",2,"allowedStyles",List.of("FRIENDLY","DIRECT","OBJECTION_HANDLING"),"allowAfterAgentMessage",false,"allowAfterAiMessage",false,"requireAgentConfirmation",true)));return x;}
    private Map<String,String> followInputs(Map<String,String> c,String insight){Map<String,String>x=new LinkedHashMap<>(c);x.put("insight_json",insight);x.put("follow_up_options_json",write(Map.of("allowedTypes",List.of("Record"),"defaultType","Record","centerOptions",List.of(),"maxMessages",30,"maxSummaryLength",120,"maxDetailsLength",2000,"maxFacts",12)));return x;}
    private Map<String,String> appointmentInputs(Map<String,String> c,String insight,String fields){Map<String,String>x=new LinkedHashMap<>(c);x.put("insight_json",insight);x.put("field_catalog_json",fields);x.put("appointment_options_json",write(Map.of("allowedStatuses",List.of("Appointed, Waiting for visit"),"defaultStatus","Appointed, Waiting for visit")));return x;}
    private Map<String,String> with(Map<String,String> c,String k,String v){Map<String,String>x=new LinkedHashMap<>(c);x.put(k,v);return x;}
    private Map<String,String> readStringMap(JsonNode node){Map<String,String>m=new LinkedHashMap<>();node.fields().forEachRemaining(e->m.put(e.getKey(),e.getValue().asText()));return m;}
    private String write(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private AiConversationDifyClient.Result safeJoin(CompletableFuture<AiConversationDifyClient.Result> f){try{return f.join();}catch(Exception ex){return null;}}
    private String hash(Map<String,String> value){try{byte[]b=MessageDigest.getInstance("SHA-256").digest(new TreeMap<>(value).toString().getBytes(StandardCharsets.UTF_8));return java.util.HexFormat.of().formatHex(b);}catch(Exception e){return null;}}
    private String shortText(String v){return v==null?null:v.substring(0,Math.min(2000,v.length()));}
}
