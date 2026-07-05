package com.example.aitmk;

import com.example.aitmk.model.domain.CrmAgentAccount;
import com.example.aitmk.security.auth.AgentRole;
import com.example.aitmk.security.auth.JwtTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AgentQuickReplyV2ControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;

    @Test
    void currentAgentCanCreateAndListOwnQuickReplies() throws Exception {
        String token = token("quick-agent-1");

        mvc.perform(post("/api/v2/quick-replies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"  课程介绍  ","content":" 您好，给您介绍课程 ","category":" 咨询 ","sortOrder":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.title").value("课程介绍"))
                .andExpect(jsonPath("$.data.content").value("您好，给您介绍课程"))
                .andExpect(jsonPath("$.data.category").value("咨询"))
                .andExpect(jsonPath("$.data.sortOrder").value(2))
                .andExpect(jsonPath("$.data.agentRowId").doesNotExist());

        mvc.perform(get("/api/v2/quick-replies?keyword=课程&category=咨询")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].title").value("课程介绍"))
                .andExpect(jsonPath("$.data.items[0].agentRowId").doesNotExist());
    }

    @Test
    void cannotEditOrDeleteOtherAgentsQuickReply() throws Exception {
        String ownerToken = token("quick-owner");
        String otherToken = token("quick-other");
        String id = create(ownerToken, "跨坐席隔离", "只能本人操作");

        mvc.perform(put("/api/v2/quick-replies/" + id)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"篡改","content":"不应该成功","category":"咨询","sortOrder":0}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUICK_REPLY_NOT_FOUND"));

        mvc.perform(delete("/api/v2/quick-replies/" + id)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUICK_REPLY_NOT_FOUND"));

        mvc.perform(get("/api/v2/quick-replies?keyword=跨坐席")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].content").value("只能本人操作"));
    }

    @Test
    void deletedQuickReplyIsNotReturnedInList() throws Exception {
        String token = token("quick-delete-agent");
        String id = create(token, "删除后隐藏", "列表不返回");

        mvc.perform(delete("/api/v2/quick-replies/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mvc.perform(get("/api/v2/quick-replies?keyword=删除后隐藏")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    void contentLongerThan4096IsRejected() throws Exception {
        String content = "a".repeat(4097);

        mvc.perform(post("/api/v2/quick-replies")
                        .header("Authorization", "Bearer " + token("quick-long-content-agent"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"超长内容\",\"content\":\"" + content + "\",\"category\":\"咨询\",\"sortOrder\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    private String create(String token, String title, String content) throws Exception {
        return mvc.perform(post("/api/v2/quick-replies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"content\":\"" + content + "\",\"category\":\"咨询\",\"sortOrder\":0}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceFirst(".*\"id\":\"([0-9]+)\".*", "$1");
    }

    private String token(String agentRowId) {
        return tokens.generateToken(CrmAgentAccount.builder()
                .rowId(agentRowId)
                .loginAccount(agentRowId)
                .role(AgentRole.TMK)
                .enabled(true)
                .build());
    }
}
