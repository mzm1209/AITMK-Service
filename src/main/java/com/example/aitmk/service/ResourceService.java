package com.example.aitmk.service;

import com.example.aitmk.model.entity.ResourceEntity;

public interface ResourceService {
    ResourceEntity getOrCreate(String customerPhone);
    ResourceEntity getRequired(String customerPhone);
}
