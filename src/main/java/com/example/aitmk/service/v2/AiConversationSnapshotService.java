package com.example.aitmk.service.v2;

import com.example.aitmk.config.AiConversationProperties;
import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.model.entity.*;
import com.example.aitmk.repository.*;
import com.example.aitmk.service.WorksheetFieldService;
import com.example.aitmk.service.impl.ClueIntegrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class AiConversationSnapshotService {
    private static final String CENTER_CONTROL_ID="66eeb5b0f53d52846e007a35";
    private final ChatMessageRepository messages;
    private final ResourceRepository resources;
    private final LeadRecordRepository leadRecords;
    private final ClueIntegrationService clues;
    private final WorksheetFieldService fields;
    private final AiConversationProperties properties;
    private final ObjectMapper json;

    public Snapshot build(ConversationEntity conversation,String outputLocale,String replyLanguage){
        List<ChatMessageEntity> desc=messages.findByConversationIdOrderByCreatedAtDescIdDesc(conversation.getId(),
                PageRequest.of(0,Math.max(1,properties.getMaxMessages())));
        List<ChatMessageEntity> ordered=new ArrayList<>(desc); Collections.reverse(ordered);
        List<Map<String,Object>> messageItems=new ArrayList<>(); int customerCount=0; int unsupported=0;
        for(ChatMessageEntity m:ordered){
            if(m.getSenderType()==PersistenceEnums.SenderType.CUSTOMER)customerCount++;
            if(m.getMessageType()!=PersistenceEnums.MessageType.TEXT){unsupported++;continue;}
            if(m.getContent()==null||m.getContent().isBlank())continue;
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("messageId",String.valueOf(m.getId())); item.put("senderType",m.getSenderType().name());
            item.put("messageType","TEXT"); item.put("content",m.getContent());
            item.put("sentAt",(m.getSentAt()!=null?m.getSentAt():m.getCreatedAt()).toString()); messageItems.add(item);
        }
        if(messageItems.isEmpty()) throw new com.example.aitmk.model.api.v2.V2Exception(org.springframework.http.HttpStatus.BAD_REQUEST,
                "AI_ANALYZABLE_MESSAGE_MISSING","会话没有可分析文本消息");
        Long basis=Long.valueOf(String.valueOf(messageItems.get(messageItems.size()-1).get("messageId")));
        Map<String,Object> msgDoc=new LinkedHashMap<>(); msgDoc.put("basisLastMessageId",String.valueOf(basis));
        msgDoc.put("truncated",desc.size()>=properties.getMaxMessages());msgDoc.put("unsupportedMediaCount",unsupported);msgDoc.put("messages",messageItems);

        ResourceEntity resource=resources.findById(conversation.getResourceId()).orElseThrow();
        var lead=clues.lookupLeadByPhone(resource.getCustomerPhone()).orElse(null);
        String leadRowId=lead==null?null:lead.getRowId();
        Map<String,Object> crm=new LinkedHashMap<>();crm.put("linked",leadRowId!=null);crm.put("leadRowId",leadRowId);crm.put("profile",lead);

        WorksheetFieldsView fieldView=fields.getFields("leads_bank");
        List<Map<String,Object>> controls=new ArrayList<>();
        for(FieldConfigView f:fieldView.fields()){
            Map<String,Object> c=new LinkedHashMap<>();c.put("controlId",f.controlId());c.put("label",f.controlName());
            c.put("type",type(f.dataType()));c.put("writable",true);
            if(CENTER_CONTROL_ID.equals(f.controlId()))c.put("semanticCode","CENTER");
            List<Map<String,Object>> options=new ArrayList<>();
            for(FieldOption o:f.options()){
                Map<String,Object> opt=new LinkedHashMap<>();opt.put("rowId",o.key());opt.put("value",o.value());opt.put("name",o.value());opt.put("label",o.value());options.add(opt);
            }c.put("options",options);controls.add(c);
        }
        Map<String,Object> fieldCatalog=Map.of("controls",controls);
        String locale=Set.of("zh-CN","id-ID","en-US").contains(outputLocale)?outputLocale:properties.getOutputLocale();
        String reply=replyLanguage==null||replyLanguage.isBlank()?"AUTO":replyLanguage;
        Map<String,String> common=new LinkedHashMap<>(); common.put("schema_version","1.0");
        common.put("conversation_id",String.valueOf(conversation.getId()));common.put("resource_id",String.valueOf(resource.getId()));
        common.put("lead_row_id",leadRowId==null?"":leadRowId);common.put("timezone",properties.getTimezone());
        common.put("current_time",java.time.ZonedDateTime.now(java.time.ZoneId.of(properties.getTimezone())).toOffsetDateTime().toString());common.put("output_locale",locale);
        common.put("messages_json",write(msgDoc));common.put("crm_profile_json",write(crm));
        common.put("business_rules_json",write(Map.of("humanConfirmationRequired",true,"onlyCurrentAssigneeCanApply",true)));
        return new Snapshot(basis,customerCount,leadRowId,locale,reply,common,write(fieldCatalog));
    }
    private String type(int t){return switch(t){case 11->"OPTION";case 27,29->"RELATION";case 8->"NUMBER";case 16->"DATETIME";default->"TEXT";};}
    private String write(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    public record Snapshot(Long basisLastMessageId,int customerMessageCount,String leadRowId,String outputLocale,
                           String replyLanguage,Map<String,String> commonInputs,String fieldCatalogJson){}
}
