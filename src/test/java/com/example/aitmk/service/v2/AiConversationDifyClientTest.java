package com.example.aitmk.service.v2;

import com.example.aitmk.config.AiConversationProperties;
import com.example.aitmk.model.api.v2.V2Exception;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiConversationDifyClientTest {
    private final AiConversationDifyClient client = new AiConversationDifyClient(new AiConversationProperties(), new ObjectMapper());

    @Test
    void parsesAndValidatesBothEnvelopes() {
        String raw = """
                {"workflow_run_id":"run-1","data":{"workflow_id":"wf-1","elapsed_time":1.2,"total_tokens":99,
                "outputs":{"module_type":"INSIGHT","schema_version":"1.0","result_json":"{\\\"moduleType\\\":\\\"INSIGHT\\\",\\\"schemaVersion\\\":\\\"1.0\\\",\\\"status\\\":\\\"SUCCESS\\\",\\\"data\\\":{}}"}}}
                """;
        var result = client.parse("INSIGHT", raw);
        assertEquals("run-1", result.workflowRunId());
        assertEquals("SUCCESS", result.status());
    }

    @Test
    void rejectsMismatchedModule() {
        String raw = """
                {"data":{"outputs":{"module_type":"INSIGHT","schema_version":"1.0",
                "result_json":"{\\\"moduleType\\\":\\\"REPLY_SUGGESTION\\\",\\\"schemaVersion\\\":\\\"1.0\\\",\\\"status\\\":\\\"SUCCESS\\\"}"}}}
                """;
        V2Exception error = assertThrows(V2Exception.class, () -> client.parse("INSIGHT", raw));
        assertEquals("AI_WORKFLOW_RESULT_INVALID", error.getCode());
    }

    @Test
    void parsesDifyCodeNodeOutputWrappers() {
        String raw = """
                {"workflow_run_id":"run-wrapped","data":{"workflow_id":"wf-wrapped","elapsed_time":2.5,"total_tokens":123,
                "outputs":{"module_type":{"output":"INSIGHT"},"schema_version":{"output":"1.0"},
                "result_json":{"output":"{\\"moduleType\\":\\"INSIGHT\\",\\"schemaVersion\\":\\"1.0\\",\\"status\\":\\"SUCCESS\\",\\"data\\":{\\"summary\\":\\"ok\\"}}"}}}}
                """;
        var result = client.parse("INSIGHT", raw);
        assertEquals("run-wrapped", result.workflowRunId());
        assertEquals("wf-wrapped", result.workflowId());
        assertEquals("SUCCESS", result.status());
        assertEquals("ok", result.result().path("data").path("summary").asText());
    }

    @Test
    void rejectsWrappedNonStringModuleType() {
        String raw = """
                {"data":{"outputs":{"module_type":{"output":{"value":"INSIGHT"}},"schema_version":{"output":"1.0"},
                "result_json":{"output":"{\\"moduleType\\":\\"INSIGHT\\",\\"schemaVersion\\":\\"1.0\\",\\"status\\":\\"SUCCESS\\"}"}}}}
                """;
        V2Exception error = assertThrows(V2Exception.class, () -> client.parse("INSIGHT", raw));
        assertEquals("AI_WORKFLOW_RESULT_INVALID", error.getCode());
        assertEquals("Dify module_type 必须是 String", error.getMessage());
    }
}
