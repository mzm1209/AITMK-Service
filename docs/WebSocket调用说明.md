# AITMK WebSocket 调用说明（Web端接入）

本文档用于指导 Web 端接入 AITMK 实时消息推送能力。

## 1. 协议与端点

- 协议：WebSocket + STOMP
- 握手端点：`/ws`
- 订阅前缀：`/topic`
- 坐席订阅主题：`/topic/agent/{agentRowId}`

> 示例：坐席 `agentRowId=abc123`，订阅 `/topic/agent/abc123`

---

## 2. 服务端推送消息结构

服务端统一推送 `AgentPushMessage`：

```json
{
  "type": "history | new_message",
  "agentRowId": "坐席ID",
  "customerPhone": "客户手机号",
  "messages": [
    {
      "customerId": "客户手机号",
      "sender": "customer | ai | agent",
      "message": "消息内容",
      "timestamp": "2026-01-01T12:00:00Z"
    }
  ]
}
```

字段说明：
- `type`
  - `history`：分配后推送该客户完整历史
  - `new_message`：该客户后续增量消息
- `messages`
  - `history` 时通常是多条
  - `new_message` 时通常只有一条

---

## 3. 前端接入流程

1. 调用登录接口获取 `accountRowId`（即 `agentRowId`）
2. 建立 STOMP 连接到 `/ws`
3. 订阅 `/topic/agent/{agentRowId}`
4. 收到 `history`：刷新当前客户历史消息
5. 收到 `new_message`：按客户维度增量追加

---

## 4. 断线重连与失败消息补发

服务端有失败消息缓冲机制：
- 若推送失败，服务端会按坐席缓存未投递消息
- 客户端重连后需主动调用补发接口

补发接口：
- `POST /api/agent/ws/reconnected`
- 请求体：

```json
{
  "agentRowId": "坐席ID"
}
```

调用时机：
- STOMP 重连成功且重新订阅完成后，立即调用一次。

---

## 5. 建议的前端处理策略

1. **连接成功后立刻订阅**：避免漏消息
2. **幂等处理**：按 `customerPhone + timestamp + message` 做去重
3. **断线自动重连**：指数退避（1s/2s/5s/...）
4. **重连后调用补发接口**：拉回服务端失败缓存
5. **与轮询接口配合**：页面初始化先请求历史接口，再接收实时增量

---

## 6. 最简伪代码（stompjs）

```javascript
import { Client } from '@stomp/stompjs';

const agentRowId = 'abc123';

const client = new Client({
  brokerURL: 'wss://你的域名/ws',
  reconnectDelay: 2000,
  onConnect: async () => {
    client.subscribe(`/topic/agent/${agentRowId}`, (frame) => {
      const data = JSON.parse(frame.body);
      if (data.type === 'history') {
        // 全量历史
      } else if (data.type === 'new_message') {
        // 增量消息
      }
    });

    // 重连成功后请求失败消息补发
    await fetch('/api/agent/ws/reconnected', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ agentRowId })
    });
  }
});

client.activate();
```

---

## 7. 常见问题

1. **收不到消息？**
   - 检查订阅主题是否和 `agentRowId` 一致。
   - 检查服务端是否已把客户分配给该坐席（服务中状态）。

2. **重连后有漏消息？**
   - 确认重连成功后调用了 `/api/agent/ws/reconnected`。

3. **历史重复渲染？**
   - 客户端需做去重与幂等处理。
