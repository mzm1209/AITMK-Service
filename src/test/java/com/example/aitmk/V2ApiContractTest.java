package com.example.aitmk;

import com.example.aitmk.model.api.v2.V2Api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class V2ApiContractTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test void conversationSummaryAndDetailUseNestedCustomerAndFlatDetail() throws Exception {
        var summary = new ConversationSummary("11","22",new CustomerBrief("86138***","Alice"),"META","HUMAN_ACTIVE",
                "ASSIGNED","TRANSFERRED",new AgentBrief("agent-1","Amy"),true,Instant.parse("2026-06-20T10:00:00Z"),
                2,"9",null,Instant.parse("2026-06-20T01:00:00Z"),null,null,3);
        var resource = new ResourceView("22","86138***","Alice","META","NEW_LEAD","ASSIGNED",
                new AgentBrief("agent-1","Amy"),null,Instant.EPOCH,Instant.EPOCH,4);
        var node=json.valueToTree(ConversationDetail.of(summary,resource));
        assertThat(node.path("customer").path("phone").asText()).isEqualTo("86138***");
        assertThat(node.path("resourceStatus").asText()).isEqualTo("ASSIGNED");
        assertThat(node.path("aiState").asText()).isEqualTo("TRANSFERRED");
        assertThat(node.path("assignedAgent").path("name").asText()).isEqualTo("Amy");
        assertThat(node.has("conversation")).isFalse();
        assertThat(node.has("assignments")).isFalse();
        assertThat(node.path("resource").path("resourceId").asText()).isEqualTo("22");
    }

    @Test void messageMediaIsNestedAndTextMediaIsNull() {
        var media = new MessageView("1","2","3",null,"key","AGENT","a","IMAGE",null,
                new MediaView("m1",null,"image/jpeg","a.jpg"),"PENDING",null,null,Instant.EPOCH,null,null,null);
        var text = new MessageView("2","2","3",null,"key2","AGENT","a","TEXT","hello",
                null,"PENDING",null,null,Instant.EPOCH,null,null,null);
        var imageNode=json.valueToTree(media);var textNode=json.valueToTree(text);
        assertThat(imageNode.path("media").path("mediaId").asText()).isEqualTo("m1");
        assertThat(imageNode.has("mediaId")).isFalse();
        assertThat(textNode.path("media").isNull()).isTrue();
    }

    @Test void sendResponseWrapsMessageAndHistoryUsesCursorPage() {
        var message = new MessageView("1","2","3",null,"key","AGENT","a","TEXT","hello",null,
                "PENDING",null,null,Instant.EPOCH,null,null,null);
        var response = Response.ok(new SendMessageResult(message));
        var node=json.valueToTree(response);
        assertThat(node.path("data").path("message").path("messageId").asText()).isEqualTo("1");
        var page=json.valueToTree(new CursorPage<>(List.of(new ConversationHistoryView("2","CLOSED",Instant.EPOCH,1)),"next",true));
        assertThat(page.path("items").isArray()).isTrue();assertThat(page.path("nextCursor").asText()).isEqualTo("next");assertThat(page.path("hasMore").asBoolean()).isTrue();
    }

    @Test void dashboardUsesRequiredFieldNamesAndSeconds() {
        var node=json.valueToTree(new DashboardSummary(1,2,3,4,5,6,1.25,2.5));
        assertThat(node.path("pendingAssignments").asLong()).isEqualTo(2);
        assertThat(node.path("expiringReplyWindows").asLong()).isEqualTo(4);
        assertThat(node.path("todayReceived").asLong()).isEqualTo(5);
        assertThat(node.path("firstHumanResponseP50Seconds").asDouble()).isEqualTo(1.25);
        assertThat(node.has("firstHumanResponseP50Ms")).isFalse();
    }
}
