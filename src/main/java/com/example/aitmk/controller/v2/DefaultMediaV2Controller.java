package com.example.aitmk.controller.v2;

import com.example.aitmk.model.api.v2.*;
import com.example.aitmk.model.api.v2.V2Api.MediaUploadResult;
import com.example.aitmk.security.auth.*;
import com.example.aitmk.service.SendMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

/** Compatibility upload endpoint. The business account is server configured and never accepted from clients. */
@RestController @RequestMapping("/api/v2/media") @RequiredArgsConstructor
public class DefaultMediaV2Controller {
    private static final long MAX=16L*1024*1024; private final SendMessageService sender;
    @Value("${whatsapp.default-business-account-id:}") private String businessAccountId;
    @PostMapping public V2Api.Response<MediaUploadResult> upload(@RequestParam String mediaType,@RequestPart MultipartFile file){
        if(!CurrentUser.get().hasPermission(Permission.CHAT_REPLY_ASSIGNED))throw new V2Exception(HttpStatus.FORBIDDEN,"FORBIDDEN","无媒体上传权限");
        if(!StringUtils.hasText(businessAccountId))throw new V2Exception(HttpStatus.UNPROCESSABLE_ENTITY,"BUSINESS_ACCOUNT_MISSING","服务端未配置默认业务账号");
        validate(mediaType,file);String name=Optional.ofNullable(file.getOriginalFilename()).orElse("upload.bin");String id=sender.uploadMedia(businessAccountId,mediaType,file);return V2Api.Response.ok(new MediaUploadResult(id,name,file.getContentType(),mediaType.toUpperCase(Locale.ROOT)));}
    private void validate(String type,MultipartFile file){if(file==null||file.isEmpty())throw new V2Exception(HttpStatus.BAD_REQUEST,"EMPTY_FILE","文件不能为空");if(file.getSize()>MAX)throw new V2Exception(HttpStatus.BAD_REQUEST,"FILE_TOO_LARGE","文件不能超过 16MB");String n=Optional.ofNullable(file.getOriginalFilename()).orElse("upload.bin"),m=file.getContentType();if(n.contains("/")||n.contains("\\")||!n.contains("."))throw new V2Exception(HttpStatus.BAD_REQUEST,"FILE_NAME_INVALID","文件名或扩展名无效");boolean ok=StringUtils.hasText(m)&&switch(type==null?"":type.toLowerCase(Locale.ROOT)){case"image"->m.startsWith("image/");case"video"->m.startsWith("video/");case"audio"->m.startsWith("audio/");case"document"->Set.of("application/pdf","text/plain","application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document").contains(m);default->false;};if(!ok)throw new V2Exception(HttpStatus.BAD_REQUEST,"MIME_TYPE_INVALID","MIME 与媒体类型不匹配");}
}
