package com.example.aitmk.service;

public interface AiService {

    String chat(String query);

    void chatStream(String query, AiStreamCallback callback);
}