package com.example.aitmk.controller.v2;

import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.security.auth.CurrentUser;
import com.example.aitmk.service.v2.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/dashboard")
@RequiredArgsConstructor
public class DashboardV2Controller {
    private final DashboardService service;

    @GetMapping("/summary")
    public Response<DashboardSummary> summary(@RequestParam(defaultValue = "mine") String scope) {
        return Response.ok(service.summary(CurrentUser.get(), scope));
    }

    @GetMapping("/analytics")
    public Response<DashboardAnalytics> analytics(
            @RequestParam(defaultValue = "mine") String scope,
            @RequestParam(defaultValue = "day") String granularity,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) String agentId) {
        return Response.ok(service.analytics(CurrentUser.get(), scope, granularity, from, to, agentId));
    }
}
