package com.example.aitmk.controller.v2;

import com.example.aitmk.model.api.v2.V2Api.QuickReplyListView;
import com.example.aitmk.model.api.v2.V2Api.QuickReplyRequest;
import com.example.aitmk.model.api.v2.V2Api.QuickReplyView;
import com.example.aitmk.model.api.v2.V2Api.Response;
import com.example.aitmk.security.auth.CurrentUser;
import com.example.aitmk.service.v2.AgentQuickReplyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/quick-replies")
@RequiredArgsConstructor
public class AgentQuickReplyV2Controller {

    private final AgentQuickReplyService service;

    @GetMapping
    public Response<QuickReplyListView> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer size) {
        return Response.ok(service.list(CurrentUser.get(), keyword, category, size));
    }

    @PostMapping
    public Response<QuickReplyView> create(@RequestBody QuickReplyRequest body) {
        return Response.ok(service.create(CurrentUser.get(), body));
    }

    @PutMapping("/{id}")
    public Response<QuickReplyView> update(@PathVariable Long id, @RequestBody QuickReplyRequest body) {
        return Response.ok(service.update(id, CurrentUser.get(), body));
    }

    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable Long id) {
        service.delete(id, CurrentUser.get());
        return Response.ok(null);
    }
}
