package com.example.aitmk.service;

import com.example.aitmk.model.entity.ConversationEntity;
import com.example.aitmk.model.entity.ResourceEntity;

public interface ConversationService {
    ConversationEntity getOrCreateActive(ResourceEntity resource, String businessAccountId, String channel);
}
