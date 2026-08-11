package com.example.aitmk.controller.v2;
import com.example.aitmk.model.api.v2.V2Api.*;import com.example.aitmk.security.auth.CurrentUser;import com.example.aitmk.service.v2.AiDraftService;import lombok.RequiredArgsConstructor;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v2/ai-drafts") @RequiredArgsConstructor
public class AiDraftV2Controller {private final AiDraftService service;
 @PostMapping("/{draftId}/apply") public Response<AiDraftView> apply(@PathVariable Long draftId,@RequestHeader("Idempotency-Key")String key,@RequestBody AiDraftApplyRequest request){return Response.ok(service.apply(draftId,key,request,CurrentUser.get()));}
 @PostMapping("/{draftId}/discard") public Response<AiDraftView> discard(@PathVariable Long draftId,@RequestBody(required=false)AiDraftDiscardRequest request){return Response.ok(service.discard(draftId,request,CurrentUser.get()));}
}
