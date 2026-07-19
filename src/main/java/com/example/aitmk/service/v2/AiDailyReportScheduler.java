package com.example.aitmk.service.v2;

import com.example.aitmk.config.AiDailyReportProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Slf4j
@Component
@ConditionalOnProperty(name = "integration.schedulers-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AiDailyReportScheduler {

    private final AiDailyReportService reports;
    private final AiDailyReportProperties properties;

    @Scheduled(cron = "${aitmk.ai.daily-report.cron:0 0 10 * * ?}", zone = "${aitmk.ai.daily-report.zone:Asia/Jakarta}")
    public void generateYesterday() {
        if (!properties.isEnabled()) {
            return;
        }
        LocalDate yesterday = LocalDate.now(ZoneId.of(properties.getZone())).minusDays(1);
        try {
            reports.generateScheduled(yesterday);
        } catch (Exception ex) {
            log.error("AI daily report scheduled generation failed. reportDate={}", yesterday, ex);
        }
    }
}
