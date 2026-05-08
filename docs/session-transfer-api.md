# 会话转移接口（给 Web 前端）

## 1) 转移会话

- **Method**: `POST`
- **Path**: `/api/session-transfers/transfer`
- **Content-Type**: `application/json`

### 入参（Request Body）

```json
{
  "customerPhone": "8613800138000",
  "targetAgentRowId": "69abbcaf433ec9f4b5e6d0f6"
}
```

字段说明：

- `customerPhone`：客户电话（必填，字符串）
- `targetAgentRowId`：目标坐席账号 rowId（必填，字符串）

### 成功出参（HTTP 200）

```json
{
  "success": true,
  "fromAgent": "69aea988433ec9f4b5e70086",
  "toAgent": "69abbcaf433ec9f4b5e6d0f6"
}
```

字段说明：

- `success`：是否成功
- `fromAgent`：转移前坐席 rowId
- `toAgent`：转移后坐席 rowId

### 失败出参（HTTP 400）

示例 1：客户当前无已分配坐席

```json
{
  "success": false,
  "message": "客户当前无已分配坐席"
}
```

示例 2：目标坐席和当前坐席相同

```json
{
  "success": false,
  "message": "目标坐席与当前坐席相同"
}
```

示例 3：业务异常

```json
{
  "success": false,
  "message": "转移会话失败",
  "error": "xxxx"
}
```

## 2) 前端调用说明

1. 前端从当前会话卡片拿到 `customerPhone`。
2. 前端从坐席列表选择目标坐席，拿到其 `rowId` 作为 `targetAgentRowId`。
3. 调用 `POST /api/session-transfers/transfer`。
4. 返回成功后：
   - 可刷新当前会话负责坐席显示（使用 `toAgent`）；
   - 可重新拉取会话列表 / 会话详情，保持 UI 与服务端一致。

## 3) 服务端行为（便于前端理解）

接口内部会做以下动作：

1. 关闭客户当前“服务中”的坐席分配记录；
2. 新增一条目标坐席“服务中”分配记录；
3. 写入会话转移记录表（`69fd8e82cd23604cb45f0ccd`）；
4. 更新本地内存分配关系，确保当前运行态立即生效。

因此这不是“仅改内存”的操作，而是完整的“重新分配”。
