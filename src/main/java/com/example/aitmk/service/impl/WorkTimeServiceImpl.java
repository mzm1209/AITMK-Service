package com.example.aitmk.service.impl;

import com.example.aitmk.service.WorkTimeService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class WorkTimeServiceImpl implements WorkTimeService {

    private static final String START_TIME_CONTROL_ID = "69fd6fc2cd23604cb45f095d";
    private static final String END_TIME_CONTROL_ID = "69fd7074cd23604cb45f0969";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("H:mm[:ss]");

    private final WorkTimeSettingCacheServiceImpl workTimeCache;

    @Override
    public boolean isWorkingTimeNow() {
        JsonNode rows = workTimeCache.snapshot();
        if (!rows.isArray() || rows.isEmpty()) {
            return true;
        }
        LocalTime now = LocalTime.now();
        for (JsonNode row : rows) {
            String start = row.path(START_TIME_CONTROL_ID).asText("");
            String end = row.path(END_TIME_CONTROL_ID).asText("");
            if (start.isBlank() || end.isBlank()) continue;
            LocalTime st = LocalTime.parse(start, FORMATTER);
            LocalTime et = LocalTime.parse(end, FORMATTER);
            if (!now.isBefore(st) && !now.isAfter(et)) return true;
        }
        return false;
    }
}
