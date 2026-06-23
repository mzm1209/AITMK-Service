package com.example.aitmk;

import com.example.aitmk.model.entity.*;
import com.example.aitmk.repository.*;
import com.example.aitmk.security.auth.*;
import com.example.aitmk.service.v2.ConversationQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest @ActiveProfiles("test") @Transactional
class V2MicrosecondCursorIntegrationTest {
    @Autowired ResourceRepository resources;
    @Autowired ConversationRepository conversations;
    @Autowired ChatMessageRepository messages;
    @Autowired ConversationQueryService query;

    @Test void thirtyFiveMessagesAcrossSameMicrosecondHaveNoDuplicateOrOmissionAndEachPageIsAscending() {
        Fixture fixture=fixture("mc"+UUID.randomUUID().toString().replace("-","").substring(0,8),"owner-cursor");
        Instant sameMicro=Instant.parse("2026-06-22T01:25:08.227286Z");
        List<String> expected=new ArrayList<>();
        for(int i=0;i<35;i++) expected.add(message(fixture,sameMicro,"m"+i).getId().toString());

        var first=query.messages(fixture.conversation().getId(),null,30,owner("owner-cursor"));
        var second=query.messages(fixture.conversation().getId(),first.nextCursor(),30,owner("owner-cursor"));
        List<String> actual=new ArrayList<>();actual.addAll(first.items().stream().map(m->m.messageId()).toList());actual.addAll(second.items().stream().map(m->m.messageId()).toList());

        assertThat(first.items()).hasSize(30);assertThat(second.items()).hasSize(5);
        assertThat(actual).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        assertAscending(first.items().stream().map(m->Long.valueOf(m.messageId())).toList());
        assertAscending(second.items().stream().map(m->Long.valueOf(m.messageId())).toList());
    }

    @Test void conversationListCursorAlsoPreservesMicrosecondsAndStableIdBoundary() {
        String prefix="cl"+UUID.randomUUID().toString().replace("-","").substring(0,6);
        Instant at=Instant.parse("2026-06-22T01:25:08.227286Z");
        Fixture first=fixture(prefix+"1","owner-list");Fixture second=fixture(prefix+"2","owner-list");
        first.conversation().setLastMessageAt(at);second.conversation().setLastMessageAt(at);conversations.saveAllAndFlush(List.of(first.conversation(),second.conversation()));
        var page1=query.list(owner("owner-list"),"all",null,prefix,null,null,null,null,null,null,1);
        var page2=query.list(owner("owner-list"),"all",null,prefix,null,null,null,null,null,page1.nextCursor(),1);
        assertThat(List.of(page1.items().get(0).conversationId(),page2.items().get(0).conversationId()))
                .containsExactlyInAnyOrder(first.conversation().getId().toString(),second.conversation().getId().toString());
    }

    private Fixture fixture(String phone,String agent){ResourceEntity r=new ResourceEntity();r.setCustomerPhone(phone);r.setAssignedAgentId(agent);r=resources.saveAndFlush(r);ConversationEntity c=new ConversationEntity();c.setResourceId(r.getId());c.setCustomerPhone(phone);c.setAssignedAgentId(agent);c=conversations.saveAndFlush(c);return new Fixture(r,c);}
    private ChatMessageEntity message(Fixture f,Instant at,String content){ChatMessageEntity m=new ChatMessageEntity();m.setResourceId(f.resource().getId());m.setConversationId(f.conversation().getId());m.setCustomerPhone(f.resource().getCustomerPhone());m.setSenderType(PersistenceEnums.SenderType.CUSTOMER);m.setMessageType(PersistenceEnums.MessageType.TEXT);m.setContent(content);m.setSentStatus(PersistenceEnums.SentStatus.DELIVERED);m.setCreatedAt(at);return messages.saveAndFlush(m);}
    private AuthenticatedUser owner(String id){return AuthenticatedUser.builder().accountRowId(id).role(AgentRole.OWNER).permissions(Set.copyOf(Permission.defaultsFor(AgentRole.OWNER))).build();}
    private void assertAscending(List<Long> ids){assertThat(ids).isSorted();}
    private record Fixture(ResourceEntity resource,ConversationEntity conversation){}
}
