package com.example.aitmk.controller.v2;

import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.security.auth.CurrentUser;
import com.example.aitmk.service.v2.AiConversationAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v2/conversations/{conversationId}/ai-analysis") @RequiredArgsConstructor
public class AiConversationV2Controller {
    private final AiConversationAnalysisService service;
    @GetMapping("/latest") public Response<AiAnalysisView> latest(@PathVariable Long conversationId){return Response.ok(service.latest(conversationId,CurrentUser.get()));}
    @GetMapping("/{analysisId}") public Response<AiAnalysisView> get(@PathVariable Long conversationId,@PathVariable Long analysisId){return Response.ok(service.get(conversationId,analysisId,CurrentUser.get()));}
    @PostMapping public ResponseEntity<Response<AiAnalysisAccepted>> create(@PathVariable Long conversationId,@RequestHeader("Idempotency-Key")String key,@RequestBody(required=false)AiAnalysisRequest request){return ResponseEntity.status(HttpStatus.ACCEPTED).body(Response.ok(service.createManual(conversationId,key,request,CurrentUser.get())));}
    @PostMapping("/{analysisId}/modules/{moduleType}/retry") public ResponseEntity<Response<AiAnalysisAccepted>> retry(@PathVariable Long conversationId,@PathVariable Long analysisId,@PathVariable String moduleType,@RequestHeader("Idempotency-Key")String key){return ResponseEntity.status(HttpStatus.ACCEPTED).body(Response.ok(service.retry(conversationId,analysisId,moduleType,key,CurrentUser.get())));}
    @GetMapping("/{analysisId}/drafts") public Response<AiDraftListView> drafts(@PathVariable Long conversationId,@PathVariable Long analysisId){return Response.ok(service.listDrafts(conversationId,analysisId,CurrentUser.get()));}
}
