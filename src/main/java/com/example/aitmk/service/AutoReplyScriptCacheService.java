package com.example.aitmk.service;

import com.fasterxml.jackson.databind.JsonNode;

public interface AutoReplyScriptCacheService {

    void reload();

    JsonNode snapshot();

    String firstReplyScript();
}
