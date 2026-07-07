package com.example.aitmk.controller.v2;

import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.security.auth.CurrentUser;
import com.example.aitmk.service.v2.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v2/conversations") @RequiredArgsConstructor
public class ConversationV2Controller {
    private final ConversationQueryService query; private final MessageCommandService message;
    private final UnreadService unread; private final ConversationCommandService command;

    @GetMapping public Response<CursorPage<ConversationSummary>> list(
            @RequestParam(defaultValue="mine") String scope, @RequestParam(required=false) String status,
            @RequestParam(required=false) String keyword, @RequestParam(required=false) String sourceChannel,
            @RequestParam(required=false) String resourceType, @RequestParam(required=false) String resourceStatus,
            @RequestParam(required=false) String queue, @RequestParam(required=false) String assignedAgentId,
            @RequestParam(required=false) String replyWindow, @RequestParam(required=false) String leadType,
            @RequestParam(required=false) String leadStatus, @RequestParam(required=false) String cursor,
            @RequestParam(defaultValue="30") int size) {
        return Response.ok(query.list(CurrentUser.get(), scope, status, keyword, sourceChannel, resourceType,
                resourceStatus, queue, assignedAgentId, replyWindow, leadType, leadStatus, cursor, size));
    }
    @GetMapping("/{id}") public Response<ConversationDetail> detail(@PathVariable Long id) { return Response.ok(query.detail(id, CurrentUser.get())); }
    @GetMapping("/{id}/messages") public Response<CursorPage<MessageView>> messages(@PathVariable Long id,
            @RequestParam(required=false) String before, @RequestParam(defaultValue="50") int size) { return Response.ok(query.messages(id,before,size,CurrentUser.get())); }
    @PostMapping("/{id}/messages") public ResponseEntity<Response<SendMessageResult>> send(@PathVariable Long id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody SendMessageRequest body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Response.ok(new SendMessageResult(message.send(id,key,body,CurrentUser.get()))));
    }
    @PostMapping("/{id}/read") public Response<ReadResult> read(@PathVariable Long id,@RequestBody ReadRequest body) { var u=CurrentUser.get();return Response.ok(unread.read(query.get(id,u),u.getAccountRowId(),Long.valueOf(body.lastReadMessageId()))); }
    @PostMapping("/{id}/transfer") public Response<ConversationDetail> transfer(@PathVariable Long id,@RequestBody TransferRequest body) { var user=CurrentUser.get();command.transfer(id,body,user);return Response.ok(query.transferResult(id,user)); }
    @PostMapping("/{id}/close") public Response<ConversationDetail> close(@PathVariable Long id,@RequestBody CloseRequest body) { command.close(id,body,CurrentUser.get());return Response.ok(query.detail(id,CurrentUser.get())); }
}
