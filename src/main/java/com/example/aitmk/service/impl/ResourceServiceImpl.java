package com.example.aitmk.service.impl;

import com.example.aitmk.model.entity.ResourceEntity;
import com.example.aitmk.repository.ResourceRepository;
import com.example.aitmk.service.ResourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ResourceServiceImpl implements ResourceService {
    private final ResourceRepository repository;

    @Override @Transactional
    public ResourceEntity getOrCreate(String customerPhone) {
        return repository.findByCustomerPhone(customerPhone).orElseGet(() -> {
            ResourceEntity entity = new ResourceEntity();
            entity.setCustomerPhone(customerPhone);
            entity.setSourceExternalId(customerPhone);
            return repository.save(entity);
        });
    }

    @Override @Transactional(readOnly = true)
    public ResourceEntity getRequired(String customerPhone) {
        return repository.findByCustomerPhone(customerPhone)
                .orElseThrow(() -> new IllegalArgumentException("Resource not found: " + customerPhone));
    }
}
