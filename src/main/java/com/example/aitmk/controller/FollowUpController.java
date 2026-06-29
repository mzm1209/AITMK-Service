package com.example.aitmk.controller;

import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.security.auth.CurrentUser;
import com.example.aitmk.service.FollowUpService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/follow-ups")
@RequiredArgsConstructor
public class FollowUpController {

    private final FollowUpService followUpService;

    @GetMapping
    public Response<FollowUpListView> list(
            @RequestParam String leadRowId,
            @RequestParam(required = false) Long resourceId,
            @RequestParam(required = false) Integer size) {
        var user = CurrentUser.get();
        followUpService.validateResourceAccess(resourceId, user);
        return Response.ok(followUpService.list(leadRowId, size, user));
    }

    @PostMapping
    public Response<FollowUpView> create(@RequestBody CreateFollowUpRequest request) {
        return Response.ok(followUpService.create(request, CurrentUser.get()));
    }
}
