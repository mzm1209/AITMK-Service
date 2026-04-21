package com.example.aitmk.service.impl;

import com.example.aitmk.config.WhatsAppConfig;
import com.example.aitmk.service.SendMessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SendMessageServiceImpl implements SendMessageService {

    private final WhatsAppConfig config;

    private final WebClient webClient = WebClient.builder().build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String uploadMedia(String from, String mediaType, MultipartFile file) {
        String normalizedType = normalizeMediaType(mediaType);
        String resolvedMimeType = resolveMimeType(normalizedType, file);
        String url = config.getGraphUrl() + "/" + from + "/media";
        log.info("WhatsApp uploadMedia request. from={}, mediaType={}, mime={}, filename={}, url={}",
                from, normalizedType, resolvedMimeType, file.getOriginalFilename(), url);

        try {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("messaging_product", "whatsapp");
            builder.part("type", resolvedMimeType);
            builder.part("file", file.getBytes())
                    .filename(file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename())
                    .contentType(MediaType.parseMediaType(resolvedMimeType));

            String response = webClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getAccessToken())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .bodyValue(builder.build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(response == null ? "{}" : response);
            String mediaId = root.path("id").asText("");
            if (!StringUtils.hasText(mediaId)) {
                throw new IllegalStateException("Meta 上传成功但未返回 mediaId: " + response);
            }
            log.info("WhatsApp uploadMedia success, type={}, mime={}, mediaId={}", normalizedType, resolvedMimeType, mediaId);
            return mediaId;
        } catch (WebClientResponseException e) {
            log.error("WhatsApp uploadMedia error, status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("读取上传文件失败", e);
        }
    }

    @Override
    public void sendTextMessage(String from, String to, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", to);
        body.put("type", "text");

        Map<String, String> text = new HashMap<>();
        text.put("body", message);
        body.put("text", text);

        postMessage(from, body, "sendTextMessage");
    }

    @Override
    public void sendMediaMessage(String from,
                                 String to,
                                 String mediaType,
                                 String mediaId,
                                 String mediaUrl,
                                 String filename,
                                 String caption) {
        String normalizedType = normalizeMediaType(mediaType);

        Map<String, String> mediaBody = new HashMap<>();
        if (StringUtils.hasText(mediaId)) {
            mediaBody.put("id", mediaId.trim());
        } else if (StringUtils.hasText(mediaUrl)) {
            mediaBody.put("link", mediaUrl.trim());
        } else {
            throw new IllegalArgumentException("mediaId 与 mediaUrl 不能同时为空");
        }

        if (("image".equals(normalizedType) || "video".equals(normalizedType) || "document".equals(normalizedType))
                && StringUtils.hasText(caption)) {
            mediaBody.put("caption", caption.trim());
        }
        if ("document".equals(normalizedType) && StringUtils.hasText(filename)) {
            mediaBody.put("filename", filename.trim());
        }

    @Override
    public void sendDocumentMessage(String from, String to, String documentUrl, String filename, String caption) {
        Map<String, String> document = new HashMap<>();
        document.put("link", documentUrl);
        if (filename != null && !filename.isBlank()) {
            document.put("filename", filename);
        }
        if (caption != null && !caption.isBlank()) {
            document.put("caption", caption);
        }
        sendMediaMessage(from, to, "document", document, "sendDocumentMessage");
    }

    private void sendMediaMessage(String from, String to, String type, Map<String, String> mediaBody, String actionName) {
        Map<String, Object> body = new HashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", to);
        body.put("type", normalizedType);
        body.put(normalizedType, mediaBody);

        postMessage(from, body, "sendMediaMessage-" + normalizedType);
    }

    @Override
    public void sendImageMessage(String from, String to, String imageUrl, String caption) {
        sendMediaMessage(from, to, "image", null, imageUrl, null, caption);
    }

    @Override
    public void sendVideoMessage(String from, String to, String videoUrl, String caption) {
        sendMediaMessage(from, to, "video", null, videoUrl, null, caption);
    }

    @Override
    public void sendAudioMessage(String from, String to, String audioUrl) {
        sendMediaMessage(from, to, "audio", null, audioUrl, null, null);
    }

    @Override
    public void sendDocumentMessage(String from, String to, String documentUrl, String filename, String caption) {
        sendMediaMessage(from, to, "document", null, documentUrl, filename, caption);
    }

    private String normalizeMediaType(String mediaType) {
        String normalized = mediaType == null ? "" : mediaType.trim().toLowerCase();
        if (!"image".equals(normalized)
                && !"video".equals(normalized)
                && !"audio".equals(normalized)
                && !"document".equals(normalized)) {
            throw new IllegalArgumentException("mediaType 仅支持 image/video/audio/document");
        }
        return normalized;
    }

    private String resolveMimeType(String mediaType, MultipartFile file) {
        String contentType = file.getContentType();
        if (StringUtils.hasText(contentType) && !MediaType.APPLICATION_OCTET_STREAM_VALUE.equalsIgnoreCase(contentType.trim())) {
            return contentType.trim();
        }

        String filename = file.getOriginalFilename();
        String byFilename = resolveMimeTypeByFilename(filename);
        if (StringUtils.hasText(byFilename)) {
            return byFilename;
        }

        return switch (mediaType) {
            case "image" -> "image/jpeg";
            case "video" -> "video/mp4";
            case "audio" -> "audio/mpeg";
            case "document" -> "application/pdf";
            default -> "application/pdf";
        };
    }

    private String resolveMimeTypeByFilename(String filename) {
        if (!StringUtils.hasText(filename) || !filename.contains(".")) {
            return null;
        }
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "mp4" -> "video/mp4";
            case "3gp", "3gpp" -> "video/3gpp";
            case "aac" -> "audio/aac";
            case "m4a" -> "audio/mp4";
            case "mp3" -> "audio/mpeg";
            case "amr" -> "audio/amr";
            case "ogg" -> "audio/ogg";
            case "opus" -> "audio/opus";
            case "pdf" -> "application/pdf";
            case "txt" -> "text/plain";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default -> null;
        };
    }

    private void postMessage(String from, Map<String, Object> body, String actionName) {
        String url = config.getGraphUrl() + "/" + from + "/messages";
        log.info("WhatsApp {} request. url={}, body={}", actionName, url, body);
        webClient.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getAccessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .doOnNext(resp -> log.info("WhatsApp {} response: {}", actionName, resp))
                .doOnError(err -> log.error("WhatsApp {} error", actionName, err))
                .subscribe();
    }
}
