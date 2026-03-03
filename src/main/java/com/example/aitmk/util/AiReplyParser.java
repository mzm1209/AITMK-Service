package com.example.aitmk.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AiReplyParser {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 从 AI 回复 JSON 中提取最终 answer 文本
     *
     * @param aiReplyJson AI 返回的 JSON 字符串
     * @return answer 字段内容，如果解析失败返回空字符串
     */
    public static String parseAnswer(String aiReplyJson) {
        if (aiReplyJson == null || aiReplyJson.isEmpty()) {
            return "";
        }

        try {
            JsonNode root = objectMapper.readTree(aiReplyJson);

            // answer 字段可能在根节点
            JsonNode answerNode = root.path("answer");

            if (answerNode.isMissingNode() || answerNode.isNull()) {
                log.warn("AI reply JSON does not contain 'answer': {}", aiReplyJson);
                return "";
            }

            return answerNode.asText();

        } catch (Exception e) {
            log.error("Failed to parse AI reply JSON: {}", aiReplyJson, e);
            return "";
        }
    }
}