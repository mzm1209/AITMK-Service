package com.example.aitmk.service.v2;

import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.model.api.v2.V2Exception;
import com.example.aitmk.model.entity.*;
import com.example.aitmk.repository.*;
import com.example.aitmk.security.auth.AuthenticatedUser;
import com.example.aitmk.service.*;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class AiDraftService {
    private final AiActionDraftRepository drafts;private final ConversationRepository conversations;
    private final ConversationQueryService conversationQuery;private final CrmOpenApiService crm;
    private final FollowUpService followUps;private final AppointmentService appointments;private final ObjectMapper json;

    @Transactional
    public AiDraftView apply(Long draftId,String idempotencyKey,AiDraftApplyRequest request,AuthenticatedUser user){
        if(!StringUtils.hasText(idempotencyKey))throw new V2Exception(HttpStatus.BAD_REQUEST,"IDEMPOTENCY_KEY_MISSING","Idempotency-Key 不能为空");
        var prior=drafts.findByIdempotencyKey(idempotencyKey.trim());if(prior.isPresent())return view(prior.get());
        AiActionDraftEntity d=get(draftId);ConversationEntity c=conversationQuery.get(d.getConversationId(),user);requireAssignee(c,user,"AI_DRAFT_NOT_ASSIGNEE");
        if(request==null||!String.valueOf(c.getId()).equals(request.conversationId()))throw new V2Exception(HttpStatus.BAD_REQUEST,"AI_DRAFT_PAYLOAD_INVALID","conversationId 不匹配");
        if(!Objects.equals(c.getAssignedAgentId(),request.expectedAssigneeId()))throw new V2Exception(HttpStatus.CONFLICT,"AI_DRAFT_ASSIGNEE_CHANGED","会话负责人已变化");
        if(!"DRAFT".equals(d.getStatus())&&!"FAILED".equals(d.getStatus()))throw new V2Exception(HttpStatus.CONFLICT,"AI_DRAFT_ALREADY_APPLIED","草稿不可重复应用");
        if(c.getStatus()==PersistenceEnums.ConversationStatus.CLOSED)throw new V2Exception(HttpStatus.CONFLICT,"AI_DRAFT_CONVERSATION_CLOSED","已关闭会话不能应用草稿");
        Map<String,Object> payload=request.payload()==null?readMap(d.getPayloadJson()):request.payload();
        d.setStatus("APPLYING");d.setIdempotencyKey(idempotencyKey.trim());d.setConfirmedBy(user.getAccountRowId());d.setConfirmedAt(Instant.now());d.setConfirmedPayloadJson(write(payload));drafts.save(d);
        try{
            String external=switch(d.getDraftType()){
                case "LEAD_PATCH"->applyLead(payload);
                case "FOLLOW_UP"->followUps.create(followRequest(payload),user).rowId();
                case "APPOINTMENT"->appointments.create(appointmentRequest(payload),user).rowId();
                case "REPLY"->null;
                default->throw new V2Exception(HttpStatus.BAD_REQUEST,"AI_DRAFT_PAYLOAD_INVALID","不支持的草稿类型");
            };
            d.setExternalRowId(external);d.setStatus("APPLIED");d.setErrorCode(null);d.setErrorMessage(null);return view(drafts.save(d));
        }catch(Exception ex){d.setStatus("FAILED");d.setErrorCode(ex instanceof V2Exception ve?ve.getCode():"AI_DRAFT_APPLY_FAILED");d.setErrorMessage(shortText(ex.getMessage()));drafts.save(d);throw ex;}
    }
    @Transactional public AiDraftView discard(Long draftId,AiDraftDiscardRequest request,AuthenticatedUser user){AiActionDraftEntity d=get(draftId);ConversationEntity c=conversationQuery.get(d.getConversationId(),user);requireAssignee(c,user,"AI_DRAFT_NOT_ASSIGNEE");if(!"DRAFT".equals(d.getStatus())&&!"FAILED".equals(d.getStatus()))throw new V2Exception(HttpStatus.CONFLICT,"AI_DRAFT_ALREADY_APPLIED","草稿不可丢弃");d.setStatus("DISCARDED");d.setConfirmedBy(user.getAccountRowId());d.setConfirmedAt(Instant.now());d.setErrorCode(request==null?null:request.reasonCode());d.setErrorMessage(request==null?null:shortText(request.remark()));return view(drafts.save(d));}
    private String applyLead(Map<String,Object> p){String row=text(p,"leadRowId");Object controls=p.get("controls");if(!StringUtils.hasText(row)||!(controls instanceof List<?> list)||list.isEmpty())throw new V2Exception(HttpStatus.BAD_REQUEST,"AI_DRAFT_PAYLOAD_INVALID","线索草稿缺少 leadRowId 或 controls");JsonNode result=crm.frontendEditRow("leads_bank",row,(List<Map<String,Object>>)controls,bool(p,"triggerWorkflow",true));if(result==null||!result.path("success").asBoolean(false))throw new V2Exception(HttpStatus.BAD_GATEWAY,"AI_DRAFT_APPLY_FAILED","更新线索失败",result);return row;}
    private CreateFollowUpRequest followRequest(Map<String,Object> p){String reminder=text(p,"reminderAt");if(StringUtils.hasText(reminder)){try{if(!java.time.OffsetDateTime.parse(reminder).toInstant().isAfter(Instant.now()))throw new Exception();}catch(Exception ex){throw new V2Exception(HttpStatus.BAD_REQUEST,"AI_DRAFT_PAYLOAD_INVALID","提醒时间必须是带时区的未来时间");}}return new CreateFollowUpRequest(longValue(p.get("resourceId")),text(p,"leadRowId"),text(p,"type"),text(p,"summary"),text(p,"details"),reminder,p.get("center"),boolObj(p.get("triggerWorkflow")));}
    private CreateAppointmentRequest appointmentRequest(Map<String,Object> p){
        if(!bool(p,"creatable",false))throw new V2Exception(HttpStatus.UNPROCESSABLE_ENTITY,"AI_APPOINTMENT_INCOMPLETE","预约草稿尚不具备创建条件");
        String lead=text(p,"leadRowId");String date=text(p,"appointmentStartAt");
        if(!StringUtils.hasText(lead)||!StringUtils.hasText(date)||centerRowId(p.get("center"))==null)throw new V2Exception(HttpStatus.UNPROCESSABLE_ENTITY,"AI_APPOINTMENT_INCOMPLETE","预约缺少线索、明确时间或有效校区");
        try{if(!java.time.OffsetDateTime.parse(date).toInstant().isAfter(Instant.now()))throw new Exception();}catch(Exception ex){throw new V2Exception(HttpStatus.UNPROCESSABLE_ENTITY,"AI_APPOINTMENT_INCOMPLETE","预约时间必须是带时区的未来时间");}
        if(!hasCustomerEvidence(p))throw new V2Exception(HttpStatus.UNPROCESSABLE_ENTITY,"AI_APPOINTMENT_INCOMPLETE","预约意愿和时间缺少客户消息证据");
        return new CreateAppointmentRequest(longValue(p.get("resourceId")),lead,text(p,"followUpRowId"),text(p,"contactNumber"),text(p,"studentName"),text(p,"grade"),text(p,"school"),text(p,"parentName"),text(p,"programInterest"),date,text(p,"appointmentInfo"),p.get("center"),text(p,"appointmentStatus"),text(p,"followUpStatus"),text(p,"followUpDueAt"),text(p,"assignedTime"),text(p,"visitStatus"),intValue(p.get("interestLevel")),text(p,"leadsChannel"),text(p,"intern"),boolObj(p.get("triggerWorkflow")));
    }
    private AiActionDraftEntity get(Long id){return drafts.findById(id).orElseThrow(()->new V2Exception(HttpStatus.NOT_FOUND,"AI_DRAFT_NOT_FOUND","AI 草稿不存在"));}
    private void requireAssignee(ConversationEntity c,AuthenticatedUser u,String code){if(u==null||c.getAssignedAgentId()==null||!c.getAssignedAgentId().equals(u.getAccountRowId()))throw new V2Exception(HttpStatus.FORBIDDEN,code,"仅当前负责人可操作草稿");}
    private AiDraftView view(AiActionDraftEntity d){Object p=d.getPayloadJson();try{p=json.readTree(d.getPayloadJson());}catch(Exception ignored){}return new AiDraftView(String.valueOf(d.getId()),String.valueOf(d.getAnalysisId()),String.valueOf(d.getConversationId()),d.getDraftType(),d.getStatus(),p,d.getExternalRowId(),d.getConfirmedBy(),d.getConfirmedAt(),d.getErrorCode(),d.getErrorMessage());}
    private Map<String,Object> readMap(String s){try{return json.readValue(s,new com.fasterxml.jackson.core.type.TypeReference<>(){});}catch(Exception e){throw new V2Exception(HttpStatus.BAD_REQUEST,"AI_DRAFT_PAYLOAD_INVALID","草稿 JSON 无效");}}
    private String write(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private String text(Map<String,Object>m,String k){Object v=m.get(k);return v==null?null:String.valueOf(v);}
    private Long longValue(Object v){try{return v==null?null:Long.valueOf(String.valueOf(v));}catch(Exception e){return null;}}
    private Integer intValue(Object v){try{return v==null?null:Integer.valueOf(String.valueOf(v));}catch(Exception e){return null;}}
    private Boolean boolObj(Object v){return v==null?null:Boolean.valueOf(String.valueOf(v));}private boolean bool(Map<String,Object>m,String k,boolean d){Boolean v=boolObj(m.get(k));return v==null?d:v;}
    private String centerRowId(Object center){if(center instanceof Map<?,?>m){Object v=m.get("rowId");return v==null||String.valueOf(v).isBlank()?null:String.valueOf(v);}return center instanceof String s&&!s.isBlank()?s:null;}
    private boolean hasCustomerEvidence(Map<String,Object>p){Object raw=p.get("fieldEvidence");if(!(raw instanceof List<?> list))return false;boolean intent=false,time=false;for(Object item:list)if(item instanceof Map<?,?>m&&"CUSTOMER".equals(String.valueOf(m.get("source")))){String field=String.valueOf(m.get("field"));if("APPOINTMENT_INTENT".equals(field))intent=true;if("APPOINTMENT_TIME".equals(field))time=true;}return intent&&time;}
    private String shortText(String v){return v==null?null:v.substring(0,Math.min(2000,v.length()));}
}
