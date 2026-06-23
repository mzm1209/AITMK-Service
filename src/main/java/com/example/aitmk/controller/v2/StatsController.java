package com.example.aitmk.controller.v2;

import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.security.auth.CurrentUser;
import com.example.aitmk.service.v2.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 运营仪表盘统计（坐席级统计 + 时间范围过滤）。
 */
@RestController
@RequestMapping("/api/v2/stats")
@RequiredArgsConstructor
public class StatsController {

    private final DashboardService dashboardService;

    @GetMapping("/agents")
    public Response<CursorPage<AgentStats>> agentStats(
            @RequestParam(defaultValue = "daily") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "30") int size
    ) {
        CursorPage<AgentStats> result = dashboardService.agentStats(
                CurrentUser.get(), range, from, to, cursor, Math.min(Math.max(size, 1), 200));
        return Response.ok(result);
    }
}
