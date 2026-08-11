package com.example.aitmk.service.v2;

import com.example.aitmk.config.AiConversationProperties;
import com.example.aitmk.model.api.v2.V2Exception;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j @Service @RequiredArgsConstructor
public class AiConversationDifyClient {
    private final AiConversationProperties properties;
    private final ObjectMapper json;
    private final WebClient webClient = WebClient.builder().build();

    public Result run(String moduleType, Map<String,String> inputs, String conversationId) {
        AiConversationProperties.Workflow workflow = workflow(moduleType);
        if (!properties.isEnabled() || !workflow.isEnabled()) {
            throw new V2Exception(HttpStatus.SERVICE_UNAVAILABLE,"AI_ANALYSIS_DISABLED","AI 会话分析模块未启用: "+moduleType);
        }
        if (!StringUtils.hasText(properties.getDify().getBaseUrl()) || !StringUtils.hasText(workflow.getApiKey())) {
            throw new V2Exception(HttpStatus.SERVICE_UNAVAILABLE,"AI_WORKFLOW_NOT_CONFIGURED","Dify 模块配置不完整: "+moduleType);
        }
        String base=trim(properties.getDify().getBaseUrl());
        String url=StringUtils.hasText(workflow.getWorkflowId())
                ? base+"/workflows/"+workflow.getWorkflowId().trim()+"/run" : base+"/workflows/run";
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("inputs",inputs); body.put("response_mode","blocking");
        body.put("user",properties.getDify().getUserPrefix()+"-"+conversationId);
        try {
            String raw=webClient.post().uri(url).header(HttpHeaders.AUTHORIZATION,"Bearer "+workflow.getApiKey().trim())
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(body).retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(Math.max(1,properties.getDify().getTimeoutSeconds())));
            return parse(moduleType,raw);
        } catch(WebClientResponseException ex){
            throw new V2Exception(HttpStatus.BAD_GATEWAY,"AI_WORKFLOW_FAILED","Dify "+moduleType+" 调用失败: HTTP "+ex.getStatusCode().value());
        } catch(V2Exception ex){ throw ex; }
        catch(Exception ex){
            throw new V2Exception(HttpStatus.BAD_GATEWAY,"AI_WORKFLOW_FAILED","Dify "+moduleType+" 调用失败");
        }
    }

    Result parse(String expectedModule,String raw){
        try{
            JsonNode root=json.readTree(raw);
            JsonNode data=root.path("data"); JsonNode outputs=data.path("outputs");
            String outerModule=outputText(outputs.get("module_type"),"module_type");
            String outerSchema=outputText(outputs.get("schema_version"),"schema_version");
            JsonNode resultValue=unwrapOutput(outputs.get("result_json"));
            if(resultValue==null||resultValue.isNull()) throw invalid("Dify 缺少 result_json");
            String resultText=resultValue.isTextual()?resultValue.asText():json.writeValueAsString(resultValue);
            JsonNode result=json.readTree(resultText);
            if(!result.isObject()) throw invalid("result_json 必须是 JSON object");
            String innerModule=result.path("moduleType").asText("");
            String innerSchema=result.path("schemaVersion").asText("");
            if(!expectedModule.equals(outerModule)||!expectedModule.equals(innerModule)) throw invalid("moduleType 不匹配");
            if(!"1.0".equals(outerSchema)||!"1.0".equals(innerSchema)) throw invalid("schemaVersion 不匹配");
            String status=result.path("status").asText("");
            if(!java.util.Set.of("SUCCESS","NOT_APPLICABLE","FAILED").contains(status)) throw invalid("status 非法");
            return new Result(root.path("workflow_run_id").asText(null),data.path("workflow_id").asText(null),
                    status,json.writeValueAsString(result),result,data.path("elapsed_time").asDouble(0),data.path("total_tokens").asLong(0));
        }catch(V2Exception ex){throw ex;}catch(Exception ex){throw invalid("Dify result_json 不是合法 JSON");}
    }

    /**
     * Dify End nodes may expose a Code node's String variable directly, or wrap
     * it as {"output":"..."} when the whole Code-node result was selected.
     */
    private JsonNode unwrapOutput(JsonNode value){
        if(value!=null&&value.isObject()&&value.has("output"))return value.get("output");
        return value;
    }
    private String outputText(JsonNode value,String field){
        JsonNode unwrapped=unwrapOutput(value);
        if(unwrapped==null||unwrapped.isNull()||!unwrapped.isTextual())throw invalid("Dify "+field+" 必须是 String");
        return unwrapped.asText();
    }

    private V2Exception invalid(String message){return new V2Exception(HttpStatus.BAD_GATEWAY,"AI_WORKFLOW_RESULT_INVALID",message);}
    private String trim(String v){String r=v.trim();while(r.endsWith("/"))r=r.substring(0,r.length()-1);return r;}
    private AiConversationProperties.Workflow workflow(String type){return switch(type){
        case "INSIGHT"->properties.getDify().getInsight();
        case "LEAD_ENRICHMENT"->properties.getDify().getLeadEnrichment();
        case "REPLY_SUGGESTION"->properties.getDify().getReplySuggestion();
        case "FOLLOW_UP_DRAFT"->properties.getDify().getFollowUpDraft();
        case "APPOINTMENT_DRAFT"->properties.getDify().getAppointmentDraft();
        default->throw new V2Exception(HttpStatus.BAD_REQUEST,"AI_MODULE_INVALID","不支持的 AI 模块: "+type);
    };}
    public record Result(String workflowRunId,String workflowId,String status,String resultJson,JsonNode result,double elapsedTime,long totalTokens){}
}
