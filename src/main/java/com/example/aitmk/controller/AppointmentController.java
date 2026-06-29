package com.example.aitmk.controller;

import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.security.auth.CurrentUser;
import com.example.aitmk.service.AppointmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * CRM appointment APIs for the customer info panel.
 */
@RestController
@RequestMapping("/api/appointments")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService appointmentService;

    @PostMapping
    public Response<AppointmentView> create(@RequestBody CreateAppointmentRequest request) {
        return Response.ok(appointmentService.create(request, CurrentUser.get()));
    }

    @GetMapping
    public Response<AppointmentListView> list(
            @RequestParam(required = false) String leadRowId,
            @RequestParam(required = false) String followUpRowId,
            @RequestParam(required = false) Long resourceId,
            @RequestParam(required = false) Integer size) {
        return Response.ok(appointmentService.list(leadRowId, followUpRowId, resourceId, size, CurrentUser.get()));
    }
}
