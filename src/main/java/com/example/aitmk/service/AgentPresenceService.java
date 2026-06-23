package com.example.aitmk.service;

import java.util.List;
import java.util.Set;

/**
 * 统一的坐席在线状态管理服务。
 * <p>
 * 替代分散在 AgentSessionActivityService / PersistentAgentDispatchService / AuthController 中的状态管理逻辑，
 * 为每个坐席维护一个单一状态源：{@link AgentPresence}。
 * 提供登录→状态变更→分配候选池→自动离线→WebSocket 推送的完整状态链路。
 */
public interface AgentPresenceService {

    /**
     * 坐席登录成功时调用。
     * <p>
     * 写入内存状态（默认 AWAY）+ 同步写 CRM（下次心跳或定时任务同步）。
     */
    void onLogin(String agentRowId, String loginRecordRowId);

    /**
     * 登录默认设 AWAY（挂机）。返回之前的状态。
     */
    AgentPresence onLoginDefault(String agentRowId, String loginRecordRowId);

    /**
     * 主动/被动变更坐席状态。
     * <p>
     * 写入内存 + 推送状态变更事件。返回变更后的状态。
     */
    AgentPresence changeStatus(String agentRowId, AgentPresence targetStatus);

    /**
     * 登出时清除所有状态。返回前状态。
     */
    AgentPresence onLogout(String agentRowId);

    /**
     * 获取坐席的登录记录 rowId（用于登出后同步 CRM）。
     */
    String getLoginRecordRowId(String agentRowId);

    /**
     * 心跳上报。更新 lastActiveAt。
     */
    void touch(String agentRowId);

    /**
     * WebSocket 订阅建立时关联 wsSession → agent。增加 WS 连接计数。
     */
    void onWebSocketSubscribe(String sessionId, String agentRowId);

    /**
     * WebSocket 断开时解除关联。减少 WS 连接计数。
     */
    void onWebSocketDisconnect(String sessionId);

    /**
     * 查询当前状态。
     */
    AgentPresence currentStatus(String agentRowId);

    /**
     * 获取所有可分配的坐席 ID（ONLINE 状态的坐席）。返回顺序稳定的集合。
     */
    Set<String> assignableAgents();

    /**
     * 获取所有在线坐席（ONLINE + AWAY，有活跃连接）。
     */
    Set<String> activeAgents();

    /**
     * 扫描超时无心跳的坐席（无 WS 连接且心跳超时），自动设为 OFFLINE。
     */
    List<AutoOfflineResult> scanInactive(int inactiveMinutes);

    /**
     * 自动离线结果记录。
     */
    record AutoOfflineResult(String agentRowId, String loginRecordRowId, String oldStatus) {}
}
