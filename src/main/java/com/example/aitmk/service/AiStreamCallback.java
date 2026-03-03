package com.example.aitmk.service;

public interface AiStreamCallback {

    void onMessage(String content);

    void onComplete();

    void onError(Throwable error);
}