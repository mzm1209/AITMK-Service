package com.example.aitmk.service.impl;

import com.example.aitmk.model.entity.ResourceEntity;
import com.example.aitmk.repository.ResourceRepository;
import com.example.aitmk.service.BusinessResourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessResourceServiceImpl implements BusinessResourceService {

    private final ResourceRepository resourceRepository;

    @Override
    @Transactional
    public ResourceEntity getOrCreateByPhone(String phone) {
        resourceRepository.upsertByCustomerPhone(phone);
        Number idValue = resourceRepository.selectLastInsertId();
        Long id = idValue.longValue();
        return resourceRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException(
                        "business_resource upsert 后未找到记录, phone=" + phone + ", id=" + id));
    }
}
