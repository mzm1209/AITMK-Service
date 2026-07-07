package com.example.aitmk.service.impl;

import com.example.aitmk.model.entity.LeadRecordEntity;
import com.example.aitmk.repository.LeadRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeadRecordBackfillService {
    private final LeadRecordRepository leadRecords;
    private final ObjectMapper objectMapper;

    @Transactional
    public BackfillResult backfillLeadTypeAndStatusFromLeadData(int requestedBatchSize) {
        int batchSize = Math.min(Math.max(requestedBatchSize, 1), 1000);
        int scanned = 0;
        int updated = 0;
        int failed = 0;
        var page = leadRecords.findAll(PageRequest.of(0, batchSize));
        while (page.hasContent()) {
            var changed = new ArrayList<LeadRecordEntity>();
            for (LeadRecordEntity entity : page.getContent()) {
                scanned++;
                try {
                    if (!StringUtils.hasText(entity.getLeadData())) continue;
                    JsonNode root = objectMapper.readTree(entity.getLeadData());
                    String leadsType = text(root.path("leadsType"));
                    String leadsStatus = text(root.path("leadsStatus"));
                    if (!Objects.equals(entity.getLeadsType(), leadsType)
                            || !Objects.equals(entity.getLeadsStatus(), leadsStatus)) {
                        entity.setLeadsType(leadsType);
                        entity.setLeadsStatus(leadsStatus);
                        changed.add(entity);
                    }
                } catch (Exception ex) {
                    failed++;
                    log.warn("Failed to backfill lead_records CRM filter columns. id={}", entity.getId(), ex);
                }
            }
            if (!changed.isEmpty()) {
                leadRecords.saveAll(changed);
                updated += changed.size();
            }
            if (!page.hasNext()) break;
            page = leadRecords.findAll(page.nextPageable());
        }
        return new BackfillResult(scanned, updated, failed);
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String value = node.asText();
        return StringUtils.hasText(value) ? value : null;
    }

    public record BackfillResult(int scanned, int updated, int failed) {}
}
