package com.example.aitmk.service;

/**
 * 坐席精细化的在线状态枚举。
 * <p>
 * ONLINE：在线且可接——参与分配候选池，可以接收新客户。<br>
 * AWAY：挂机——不参与分配候选池，但已有分配关系保留，不影响正在服务的会话。<br>
 * OFFLINE：离线——不参与分配候选池，不接收新客户，但不释放已有分配关系（保留会话负责人身份）。
 */
public enum AgentPresence {

    ONLINE,
    AWAY,
    OFFLINE;

    /**
     * 该状态是否可参与分配候选池（即坐席可接收新客户）。
     */
    public boolean isAssignable() {
        return this == ONLINE;
    }

    /**
     * 将字符串状态解析为 {@code AgentPresence} 枚举值。
     * <p>
     * 支持的输入：{@code "在线"/"online"} → ONLINE，{@code "挂机"/"away"/"standby"/"paused"} → AWAY，
     * 其他（包括 null/空）→ OFFLINE。
     */
    public static AgentPresence fromString(String value) {
        if (value == null) {
            return OFFLINE;
        }
        return switch (value.trim().toLowerCase()) {
            case "在线", "online" -> ONLINE;
            case "挂机", "away", "standby", "paused" -> AWAY;
            default -> OFFLINE;
        };
    }
}
