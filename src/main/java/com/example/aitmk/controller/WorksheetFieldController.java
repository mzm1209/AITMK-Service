package com.example.aitmk.controller;

import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.service.WorksheetFieldService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * CRM worksheet field configuration endpoint.
 * Returns control metadata including dropdown options for form rendering.
 */
@RestController
@RequestMapping("/api/worksheets")
@RequiredArgsConstructor
public class WorksheetFieldController {

    private final WorksheetFieldService worksheetFieldService;

    @GetMapping("/{id}/fields")
    public Response<WorksheetFieldsView> getFields(@PathVariable String id) {
        return Response.ok(worksheetFieldService.getFields(id));
    }
}
