package com.example.aitmk.service.v2;

import com.example.aitmk.config.AiDifyWorkflowProperties;
import com.example.aitmk.model.api.v2.V2Exception;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DifyWorkflowClient {

    private final AiDifyWorkflowProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient = WebClient.builder().build();

    public DifyWorkflowResult runDailyReport(DifyDailyReportRequest request) {
        validateConfig();
        String url = trimTrailingSlash(properties.getBaseUrl()) + "/workflows/run";
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("report_date", request.reportDate());
        inputs.put("report_context_json", request.reportContextJson());
        inputs.put("summary_json", request.summaryJson());
        inputs.put("agent_stats_json", request.agentStatsJson());
        inputs.put("conversation_json", request.conversationJson());
        inputs.put("data_quality_json", request.dataQualityJson());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inputs", inputs);
        body.put("response_mode", "blocking");
        body.put("user", properties.getDailyReportUser());

        log.info("Dify daily report workflow request. url={}, reportDate={}, apiKey={}",
                url, request.reportDate(), maskKey(properties.getApiKey()));
        try {
            String raw = webClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(timeoutSeconds()));
            return parse(raw);
        } catch (WebClientResponseException ex) {
            throw new V2Exception(HttpStatus.BAD_GATEWAY, "DIFY_WORKFLOW_FAILED",
                    "Dify Workflow 调用失败: HTTP " + ex.getStatusCode().value());
        } catch (V2Exception ex) {
            throw ex;
        } catch (Exception ex) {
            if (isBlockingTimeout(ex)) {
                throw new V2Exception(HttpStatus.BAD_GATEWAY, "DIFY_TIMEOUT",
                        "Dify Workflow 调用超时: 本地等待超过 " + timeoutSeconds() + " 秒，Dify 可能仍在后台完成");
            }
            throw new V2Exception(HttpStatus.BAD_GATEWAY, "DIFY_WORKFLOW_FAILED",
                    "Dify Workflow 调用失败: " + safeMessage(ex));
        }
    }

    private int timeoutSeconds() {
        return Math.max(1, properties.getTimeoutSeconds());
    }

    private boolean isBlockingTimeout(Exception ex) {
        String message = ex.getMessage();
        return ex instanceof IllegalStateException
                && message != null
                && message.contains("Timeout on blocking read");
    }

    DifyWorkflowResult parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new V2Exception(HttpStatus.BAD_GATEWAY, "DIFY_EMPTY_RESPONSE", "Dify Workflow 返回为空");
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            String workflowRunId = text(root.path("workflow_run_id"));
            JsonNode outputs = root.path("data").path("outputs");
            JsonNode resultNode = firstPresent(outputs, "result_json", "resultJson", "result");
            if (resultNode.isMissingNode() || resultNode.isNull()) {
                throw new V2Exception(HttpStatus.BAD_GATEWAY, "DIFY_RESULT_MISSING", "Dify Workflow 缺少 data.outputs.result");
            }
            String resultJson = resultNode.isTextual() ? resultNode.asText() : objectMapper.writeValueAsString(resultNode);
            JsonNode parsedResult = objectMapper.readTree(resultJson);
            parsedResult = unwrapResultEnvelope(parsedResult);
            if (!parsedResult.isObject()) {
                throw new V2Exception(HttpStatus.BAD_GATEWAY, "DIFY_RESULT_INVALID_JSON", "Dify result 必须是 JSON object");
            }
            return new DifyWorkflowResult(workflowRunId, objectMapper.writeValueAsString(parsedResult), parsedResult);
        } catch (V2Exception ex) {
            throw ex;
        } catch (Exception ex) {
            throw new V2Exception(HttpStatus.BAD_GATEWAY, "DIFY_RESULT_INVALID_JSON", "Dify result 不是合法 JSON");
        }
    }

    private void validateConfig() {
        if (!properties.isEnabled()) {
            throw new V2Exception(HttpStatus.SERVICE_UNAVAILABLE, "DIFY_DISABLED", "Dify Workflow 未启用");
        }
        if (!StringUtils.hasText(properties.getBaseUrl()) || !StringUtils.hasText(properties.getApiKey())) {
            throw new V2Exception(HttpStatus.SERVICE_UNAVAILABLE, "DIFY_NOT_CONFIGURED", "Dify Workflow 配置不完整");
        }
    }

    private String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private JsonNode firstPresent(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode node = root.path(name);
            if (!node.isMissingNode() && !node.isNull()) {
                return node;
            }
        }
        return objectMapper.missingNode();
    }

    private JsonNode unwrapResultEnvelope(JsonNode node) {
        JsonNode inner = node.path("result");
        if (inner.isObject() && looksLikeDailyReport(inner)) {
            return inner;
        }
        return node;
    }

    private boolean looksLikeDailyReport(JsonNode node) {
        return node.has("statusOverview")
                || node.has("executiveSummary")
                || node.has("riskLevel")
                || node.has("businessHealthScore")
                || node.has("conversationReviews")
                || node.has("conversationCases");
    }

    private String safeMessage(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private String maskKey(String key) {
        if (!StringUtils.hasText(key)) return "<empty>";
        String trimmed = key.trim();
        if (trimmed.length() <= 8) return "****";
        return trimmed.substring(0, 4) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    public record DifyDailyReportRequest(String reportDate,
                                         String reportContextJson,
                                         String summaryJson,
                                         String agentStatsJson,
                                         String conversationJson,
                                         String dataQualityJson) {
        public static DifyDailyReportRequest from(java.time.LocalDate reportDate,
                                                  String reportContextJson,
                                                  String summaryJson,
                                                  String agentStatsJson,
                                                  String conversationJson,
                                                  String dataQualityJson) {
            return new DifyDailyReportRequest(reportDate.toString(), reportContextJson, summaryJson,
                    agentStatsJson, conversationJson, dataQualityJson);
        }
    }

    public record DifyWorkflowResult(String workflowRunId, String resultJson, JsonNode result) {}
}
