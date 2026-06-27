package com.example.aitmk.service.v2;

import com.example.aitmk.model.api.v2.*;
import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.model.entity.*;
import com.example.aitmk.repository.*;
import com.example.aitmk.security.auth.*;
import com.example.aitmk.service.CrmOpenApiService;
import com.example.aitmk.service.SendMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.util.StringUtils;
import java.time.Instant;
import static com.example.aitmk.model.entity.PersistenceEnums.*;

@Slf4j @Service @RequiredArgsConstructor
public class MessageCommandService {
    private final ConversationRepository conversations; private final ResourceRepository resources;
    private final ChatMessageRepository messages; private final V2AccessService access;
    private final RealtimeEventService events; private final RealtimePayloadFactory payloads; private final SendMessageService sender;
    private final CrmOpenApiService crm;

    @Transactional
    public MessageView send(Long conversationId, String key, SendMessageRequest req, AuthenticatedUser user) {
        if (!StringUtils.hasText(key)) throw new V2Exception(HttpStatus.BAD_REQUEST,"IDEMPOTENCY_KEY_REQUIRED","缺少 Idempotency-Key");
        var existing = messages.findByClientRequestId(key);
        if (existing.isPresent()) {
            if (!existing.get().getConversationId().equals(conversationId)) throw new V2Exception(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","幂等键已用于其他会话");
            return V2Mapper.message(existing.get());
        }
        ConversationEntity c = conversations.findByIdForUpdate(conversationId).orElseThrow(() -> new V2Exception(HttpStatus.NOT_FOUND,"CONVERSATION_NOT_FOUND","会话不存在"));
        access.requireReply(user,c);
        if (c.getStatus()==ConversationStatus.CLOSED) throw new V2Exception(HttpStatus.UNPROCESSABLE_ENTITY,"CONVERSATION_CLOSED","会话已关闭");
        ResourceEntity r=resources.findByIdForUpdate(c.getResourceId()).orElseThrow();
        if(r.getLastCustomerMessageAt()==null||r.getLastCustomerMessageAt().plusSeconds(86400).isBefore(Instant.now()))throw new V2Exception(HttpStatus.UNPROCESSABLE_ENTITY,"REPLY_WINDOW_CLOSED","24 小时回复窗口已关闭");
        MessageType type=parse(req.messageType()); MessageMediaRequest media=req.media();
        if(type==MessageType.TEXT&&!StringUtils.hasText(req.content()))throw new V2Exception(HttpStatus.BAD_REQUEST,"CONTENT_REQUIRED","文本内容不能为空");
        if(type!=MessageType.TEXT&&(media==null||!StringUtils.hasText(media.mediaId())))throw new V2Exception(HttpStatus.BAD_REQUEST,"MEDIA_REQUIRED","媒体标识不能为空");
        ChatMessageEntity m=new ChatMessageEntity();m.setConversationId(c.getId());m.setResourceId(r.getId());m.setCustomerPhone(r.getCustomerPhone());m.setBusinessAccountId(c.getBusinessAccountId());m.setClientRequestId(key);
        m.setSenderType(user.getRole()==AgentRole.MANAGER||user.getRole()==AgentRole.OWNER?SenderType.MANAGER:SenderType.AGENT);m.setSenderId(user.getAccountRowId());m.setOperatorRole(user.getRole().name());m.setMessageType(type);m.setContent(req.content());
        if(media!=null){m.setMediaId(media.mediaId());m.setMimeType(media.mimeType());m.setFileName(media.fileName());}
        if(StringUtils.hasText(req.retryOfMessageId()))m.setRetryOfMessageId(Long.valueOf(req.retryOfMessageId()));
        m=messages.saveAndFlush(m);c.setLastMessageAt(m.getCreatedAt());r.setLastMessageAt(m.getCreatedAt());r.setLastAgentMessageAt(m.getCreatedAt());
        resources.saveAndFlush(r);conversations.saveAndFlush(c);
        events.append("MESSAGE_CREATED","MESSAGE",m.getId(),r.getId(),c.getId(),c.getAssignedAgentId(),c.getVersion(),payloads.message(m));
        long id=m.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void afterCommit(){
            if(type==MessageType.TEXT)sender.sendTextMessage(c.getBusinessAccountId(),r.getCustomerPhone(),req.content(),id);
            else sender.sendMediaMessage(c.getBusinessAccountId(),r.getCustomerPhone(),type.name().toLowerCase(),media.mediaId(),null,media.fileName(),req.content(),id);
            // Issue 10: 同步坐席回复到 CRM 聊天记录（失败不影响主流程）
            try { crm.addChatRecord(c.getBusinessAccountId(),r.getCustomerPhone(),user.getAccountRowId(),"人工",req.content()); } catch(Exception ex) { log.warn("CRM add agent chat record failed",ex); }
        }});
        return V2Mapper.message(m);
    }
    private MessageType parse(String value){try{return MessageType.valueOf((value==null?"TEXT":value).toUpperCase());}catch(Exception e){throw new V2Exception(HttpStatus.BAD_REQUEST,"MESSAGE_TYPE_INVALID","不支持的消息类型");}}
}
