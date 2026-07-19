package com.example.aitmk.controller.v2;

import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.security.auth.CurrentUser;
import com.example.aitmk.service.v2.AiDailyReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/ai-reports/daily")
@RequiredArgsConstructor
public class AiDailyReportV2Controller {

    private final AiDailyReportService service;

    @GetMapping
    public Response<AiDailyReportListView> list(
            @RequestParam(required = false) String reportDate,
            @RequestParam(required = false) Integer size) {
        return Response.ok(service.list(CurrentUser.get(), reportDate, size));
    }

    @GetMapping("/{id}")
    public Response<AiDailyReportView> detail(@PathVariable Long id) {
        return Response.ok(service.detail(CurrentUser.get(), id));
    }

    @PostMapping("/generate")
    public Response<AiDailyReportView> generate(@RequestBody(required = false) AiDailyReportGenerateRequest request) {
        return Response.ok(service.generate(CurrentUser.get(), request));
    }

    @PostMapping("/{id}/regenerate")
    public Response<AiDailyReportView> regenerate(@PathVariable Long id) {
        return Response.ok(service.regenerate(CurrentUser.get(), id));
    }
}
