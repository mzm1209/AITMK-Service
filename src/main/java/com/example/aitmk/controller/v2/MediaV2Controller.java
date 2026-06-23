package com.example.aitmk.controller.v2;

import com.example.aitmk.model.api.v2.*;
import com.example.aitmk.model.api.v2.V2Api.MediaUploadResult;
import com.example.aitmk.security.auth.CurrentUser;
import com.example.aitmk.service.SendMessageService;
import com.example.aitmk.service.v2.ConversationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController @RequestMapping("/api/v2/conversations/{conversationId}/media") @RequiredArgsConstructor
public class MediaV2Controller {
    private static final long MAX = 16L * 1024 * 1024;
    private final SendMessageService sender; private final ConversationQueryService conversations;
    @PostMapping public V2Api.Response<MediaUploadResult> upload(@PathVariable Long conversationId,
            @RequestParam String mediaType, @RequestPart MultipartFile file) {
        var conversation = conversations.get(conversationId, CurrentUser.get());
        if (!StringUtils.hasText(conversation.getBusinessAccountId())) throw new V2Exception(HttpStatus.UNPROCESSABLE_ENTITY,"BUSINESS_ACCOUNT_MISSING","会话未绑定业务账号");
        validate(mediaType,file); String name=Optional.ofNullable(file.getOriginalFilename()).orElse("upload.bin");
        String id=sender.uploadMedia(conversation.getBusinessAccountId(),mediaType,file);
        return V2Api.Response.ok(new MediaUploadResult(id,name,file.getContentType(),mediaType.toUpperCase(Locale.ROOT)));
    }
    private void validate(String type,MultipartFile file) {
        if(file==null||file.isEmpty())throw new V2Exception(HttpStatus.BAD_REQUEST,"EMPTY_FILE","文件不能为空");
        if(file.getSize()>MAX)throw new V2Exception(HttpStatus.BAD_REQUEST,"FILE_TOO_LARGE","文件不能超过 16MB");
        String n=Optional.ofNullable(file.getOriginalFilename()).orElse("upload.bin"),m=file.getContentType();
        if(n.contains("/")||n.contains("\\")||!n.contains("."))throw new V2Exception(HttpStatus.BAD_REQUEST,"FILE_NAME_INVALID","文件名或扩展名无效");
        boolean allowed=StringUtils.hasText(m)&&switch(type==null?"":type.toLowerCase(Locale.ROOT)){case"image"->m.startsWith("image/");case"video"->m.startsWith("video/");case"audio"->m.startsWith("audio/");case"document"->Set.of("application/pdf","text/plain","application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document").contains(m);default->false;};
        if(!allowed)throw new V2Exception(HttpStatus.BAD_REQUEST,"MIME_TYPE_INVALID","MIME 与媒体类型不匹配");
    }
}
