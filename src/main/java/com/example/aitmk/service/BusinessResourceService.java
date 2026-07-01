package com.example.aitmk.service;

import com.example.aitmk.model.entity.ResourceEntity;

/**
 * 统一负责 {@code business_resource} 的幂等获取或创建。
 * 利用 {@code customer_phone} 唯一索引做并发控制，不使用 {@code SELECT FOR UPDATE}。
 */
public interface BusinessResourceService {

    /**
     * 幂等获取或创建资源行。
     * 使用 {@code INSERT ... ON DUPLICATE KEY UPDATE} 实现原子 upsert，
     * 高并发下不会产生 gap lock 死锁。
     */
    ResourceEntity getOrCreateByPhone(String phone);
}
