package com.example.aitmk.service.v2;

import com.example.aitmk.model.api.v2.*;
import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.model.entity.ResourceEntity;
import com.example.aitmk.repository.*;
import com.example.aitmk.service.AgentAccountCacheService;
import com.example.aitmk.security.auth.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class ResourceQueryService {
    private final ResourceRepository resources; private final ConversationRepository conversations;
    private final AssignmentRecordRepository assignments; private final ChatMessageRepository messages; private final V2AccessService access; private final EntityManager em; private final AgentAccountCacheService agentAccounts;
    @Transactional(readOnly=true) public ResourceEntity get(Long id,AuthenticatedUser u){var r=resources.findById(id).orElseThrow(()->new V2Exception(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","资源不存在"));var conversation=conversations.findFirstByResourceIdOrderByCreatedAtDescIdDesc(id);if(conversation.isPresent())access.requireView(u,conversation.get());else access.require(u,Permission.RESOURCE_VIEW_ALL);return r;}
    @Transactional(readOnly=true) public ResourceView view(Long id,AuthenticatedUser u){var resource=get(id,u);var lastMessage=messages.findFirstByResourceIdOrderByCreatedAtDescIdDesc(id).orElse(null);return V2Mapper.resource(resource,lastMessage,agentAccounts.getName(resource.getAssignedAgentId()));}

    @Transactional(readOnly=true) public CursorPage<ConversationHistoryView> conversations(Long id,String cursor,int requested,AuthenticatedUser u){get(id,u);int size=bounded(requested);String sql="select id from conversation where resource_id=:resourceId";CursorCodec.Key k=decode(cursor);if(k!=null)sql+=" and (created_at<:at or (created_at=:at and id<:id))";sql+=" order by created_at desc,id desc";var q=em.createNativeQuery(sql).setParameter("resourceId",id);if(k!=null){q.setParameter("at",k.at());q.setParameter("id",k.id());}q.setMaxResults(size+1);@SuppressWarnings("unchecked")List<Number> ids=q.getResultList();boolean more=ids.size()>size;if(more)ids=ids.subList(0,size);var rows=ids.stream().map(n->conversations.findById(n.longValue()).orElseThrow()).toList();var items=rows.stream().map(c->new ConversationHistoryView(c.getId().toString(),c.getStatus().name(),c.getCreatedAt(),c.getVersion())).toList();return new CursorPage<>(items,rows.isEmpty()?null:encode(rows.get(rows.size()-1).getCreatedAt(),rows.get(rows.size()-1).getId()),more);}
    @Transactional(readOnly=true) public CursorPage<AssignmentView> assignments(Long id,String cursor,int requested,AuthenticatedUser u){get(id,u);int size=bounded(requested);String sql="select id from assignment_record where resource_id=:resourceId";CursorCodec.Key k=decode(cursor);if(k!=null)sql+=" and (assigned_at<:at or (assigned_at=:at and id<:id))";sql+=" order by assigned_at desc,id desc";var q=em.createNativeQuery(sql).setParameter("resourceId",id);if(k!=null){q.setParameter("at",k.at());q.setParameter("id",k.id());}q.setMaxResults(size+1);@SuppressWarnings("unchecked")List<Number> ids=q.getResultList();boolean more=ids.size()>size;if(more)ids=ids.subList(0,size);var rows=ids.stream().map(n->assignments.findById(n.longValue()).orElseThrow()).toList();Map<String,String> agentNames=agentAccounts.getNames(rows.stream().map(com.example.aitmk.model.entity.AssignmentRecordEntity::getAgentId).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet()));return new CursorPage<>(rows.stream().map(a->V2Mapper.assignment(a,agentNames.get(a.getAgentId()))).toList(),rows.isEmpty()?null:encode(rows.get(rows.size()-1).getAssignedAt(),rows.get(rows.size()-1).getId()),more);}
    private int bounded(int n){return Math.min(Math.max(n,1),100);}private String encode(Instant at,Long id){return CursorCodec.encode(at,id);}
    private CursorCodec.Key decode(String cursor){return cursor==null||cursor.isBlank()?null:CursorCodec.decode(cursor);}
}
