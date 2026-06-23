package com.example.aitmk;

import com.example.aitmk.model.entity.*;
import com.example.aitmk.repository.*;
import com.example.aitmk.security.auth.*;
import com.example.aitmk.service.v2.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest @ActiveProfiles("test") @Transactional
class ResourceViewLastMessageIntegrationTest {
    @Autowired ResourceRepository resources;
    @Autowired ConversationRepository conversations;
    @Autowired ChatMessageRepository messages;
    @Autowired ResourceQueryService resourceQuery;
    @Autowired ConversationQueryService conversationQuery;

    @Test void textMessageIsReturnedWithoutMedia() {
        Fixture f=fixture(); ChatMessageEntity message=message(f,"TEXT","latest text",Instant.now());
        var view=resourceQuery.view(f.resource().getId(),owner());
        assertThat(view.lastMessage()).isNotNull();
        assertThat(view.lastMessage().messageId()).isEqualTo(message.getId().toString());
        assertThat(view.lastMessage().content()).isEqualTo("latest text");
        assertThat(view.lastMessage().media()).isNull();
    }

    @Test void mediaMessageUsesNestedMediaContract() {
        Fixture f=fixture(); ChatMessageEntity message=message(f,"IMAGE","caption",Instant.now());
        message.setMediaId("media-1");message.setMediaUrl("https://media.invalid/1");message.setMimeType("image/jpeg");message.setFileName("photo.jpg");messages.saveAndFlush(message);
        var last=resourceQuery.view(f.resource().getId(),owner()).lastMessage();
        assertThat(last.media()).isNotNull();
        assertThat(last.media().mediaId()).isEqualTo("media-1");
        assertThat(last.media().mimeType()).isEqualTo("image/jpeg");
        assertThat(last.media().fileName()).isEqualTo("photo.jpg");
    }

    @Test void resourceWithoutMessagesReturnsNullLastMessageAndKeepsExistingFields() {
        Fixture f=fixture(); var view=resourceQuery.view(f.resource().getId(),owner());
        assertThat(view.lastMessage()).isNull();
        assertThat(view.resourceId()).isEqualTo(f.resource().getId().toString());
        assertThat(view.customerPhone()).isEqualTo(f.resource().getCustomerPhone());
        assertThat(view.sourceChannel()).isEqualTo("META");
        assertThat(view.resourceType()).isEqualTo("NEW_LEAD");
        assertThat(view.resourceStatus()).isEqualTo("PENDING_ASSIGNMENT");
        assertThat(view.createdAt()).isNotNull();assertThat(view.updatedAt()).isNotNull();
    }

    @Test void latestMessageUsesCreatedAtThenMessageIdDescending() {
        Fixture f=fixture(); Instant same=Instant.parse("2026-06-20T10:15:30Z");
        message(f,"TEXT","older time",same.minusSeconds(1));
        message(f,"TEXT","same time first",same);
        ChatMessageEntity winner=message(f,"TEXT","same time second",same);
        var last=resourceQuery.view(f.resource().getId(),owner()).lastMessage();
        assertThat(last.messageId()).isEqualTo(winner.getId().toString());
        assertThat(last.content()).isEqualTo("same time second");
    }

    @Test void conversationDetailAndResourceDetailUseIdenticalResourceView() {
        Fixture f=fixture();message(f,"DOCUMENT","document",Instant.now());
        ChatMessageEntity latest=messages.findFirstByResourceIdOrderByCreatedAtDescIdDesc(f.resource().getId()).orElseThrow();
        latest.setMediaId("doc-1");latest.setMimeType("application/pdf");latest.setFileName("contract.pdf");messages.saveAndFlush(latest);
        var resourceView=resourceQuery.view(f.resource().getId(),owner());
        var embedded=conversationQuery.detail(f.conversation().getId(),owner()).resource();
        assertThat(embedded).isEqualTo(resourceView);
        assertThat(embedded.lastMessage().media().fileName()).isEqualTo("contract.pdf");
    }

    private Fixture fixture(){ResourceEntity r=new ResourceEntity();r.setCustomerPhone("rv-"+UUID.randomUUID().toString().replace("-","").substring(0,20));r=resources.saveAndFlush(r);ConversationEntity c=new ConversationEntity();c.setResourceId(r.getId());c.setCustomerPhone(r.getCustomerPhone());c=conversations.saveAndFlush(c);return new Fixture(r,c);}
    private ChatMessageEntity message(Fixture f,String type,String content,Instant at){ChatMessageEntity m=new ChatMessageEntity();m.setResourceId(f.resource().getId());m.setConversationId(f.conversation().getId());m.setCustomerPhone(f.resource().getCustomerPhone());m.setSenderType(PersistenceEnums.SenderType.CUSTOMER);m.setMessageType(PersistenceEnums.MessageType.valueOf(type));m.setContent(content);m.setSentStatus(PersistenceEnums.SentStatus.DELIVERED);m.setCreatedAt(at);return messages.saveAndFlush(m);}
    private AuthenticatedUser owner(){return AuthenticatedUser.builder().accountRowId("owner-resource-test").role(AgentRole.OWNER).permissions(Set.copyOf(Permission.defaultsFor(AgentRole.OWNER))).managedAgentIds(java.util.List.of()).build();}
    private record Fixture(ResourceEntity resource,ConversationEntity conversation){}
}
