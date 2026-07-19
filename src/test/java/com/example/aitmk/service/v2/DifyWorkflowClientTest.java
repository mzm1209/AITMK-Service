package com.example.aitmk.service.v2;

import com.example.aitmk.config.AiDifyWorkflowProperties;
import com.example.aitmk.model.api.v2.V2Exception;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DifyWorkflowClientTest {

    private final DifyWorkflowClient client = new DifyWorkflowClient(new AiDifyWorkflowProperties(), new ObjectMapper());

    @Test
    void parsesWorkflowRunIdAndJsonResultString() {
        DifyWorkflowClient.DifyWorkflowResult result = client.parse("""
                {
                  "workflow_run_id":"run-123",
                  "data":{"outputs":{"result":"{\\\"executive_summary\\\":\\\"ok\\\",\\\"risk_level\\\":\\\"LOW\\\",\\\"business_health_score\\\":90}"}}
                }
                """);

        assertThat(result.workflowRunId()).isEqualTo("run-123");
        assertThat(result.result().path("executive_summary").asText()).isEqualTo("ok");
        assertThat(result.resultJson()).contains("\"business_health_score\":90");
    }

    @Test
    void unwrapsNestedResultObjectFromDifyOutput() {
        DifyWorkflowClient.DifyWorkflowResult result = client.parse("""
                {
                  "workflow_run_id":"run-nested",
                  "data":{"outputs":{"result":{"result":{"executiveSummary":"nested ok","riskLevel":"HIGH","businessHealthScore":null}}}}
                }
                """);

        assertThat(result.workflowRunId()).isEqualTo("run-nested");
        assertThat(result.result().path("executiveSummary").asText()).isEqualTo("nested ok");
        assertThat(result.result().has("result")).isFalse();
    }

    @Test
    void readsResultJsonOutputBeforeResultOutput() {
        DifyWorkflowClient.DifyWorkflowResult result = client.parse("""
                {
                  "workflow_run_id":"run-json",
                  "data":{"outputs":{
                    "result":"{\\\"executiveSummary\\\":\\\"wrong\\\"}",
                    "result_json":"{\\\"executiveSummary\\\":\\\"right\\\",\\\"riskLevel\\\":\\\"LOW\\\"}"
                  }}
                }
                """);

        assertThat(result.result().path("executiveSummary").asText()).isEqualTo("right");
    }

    @Test
    void rejectsInvalidResultJson() {
        assertThatThrownBy(() -> client.parse("""
                {
                  "workflow_run_id":"run-123",
                  "data":{"outputs":{"result":"not-json"}}
                }
                """))
                .isInstanceOfSatisfying(V2Exception.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("DIFY_RESULT_INVALID_JSON"));
    }

    @Test
    void rejectsMissingResult() {
        assertThatThrownBy(() -> client.parse("""
                {"workflow_run_id":"run-123","data":{"outputs":{}}}
                """))
                .isInstanceOfSatisfying(V2Exception.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("DIFY_RESULT_MISSING"));
    }
}
