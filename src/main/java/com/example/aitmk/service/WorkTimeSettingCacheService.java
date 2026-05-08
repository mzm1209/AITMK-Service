package com.example.aitmk.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工作时间设置缓存服务。
 */
public interface WorkTimeSettingCacheService {

    void reload();

    JsonNode snapshot();
}
