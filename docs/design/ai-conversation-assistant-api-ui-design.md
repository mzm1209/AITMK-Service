# IM 工作台 AI 会话助手：接口契约与页面原型设计

版本：v2.0（前端实施版）  
日期：2026-08-10  
范围：AI 会话分析、线索 AI 补全、跟进记录 AI 草稿、预约记录 AI 草稿  
原则：AI 只生成分析和草稿，当前会话负责人确认后才执行发送或 CRM 写入。

## 1. 已确认产品决策

1. 不扫描、不自动补跑历史会话。历史会话只有坐席点击“开始分析”后才生成分析。
2. 新线索进入后，当前会话的客户消息数达到 5 条时，才具备首次自动分析资格。
3. 连续客户消息采用分钟级防抖。当前默认 `1800 秒（30 分钟）`，并保持配置化。
4. 客户分配给谁，谁确认 AI 建议产生的回复、线索更新、跟进记录和预约记录。经理代确认本期不做。
5. AI 能力由多个 Dify Workflow 分别完成，不要求由一个大工作流一次生成全部结果。
6. 单个工作流失败不应使其他工作流结果失效；页面允许对失败模块单独重试。

## 2. 防抖时间建议

### 2.1 首期默认值

建议首期使用：

```properties
aitmk.ai.conversation.auto-analysis-enabled=true
aitmk.ai.conversation.auto-analysis-debounce-seconds=1800
aitmk.ai.conversation.auto-analysis-min-customer-messages=5
aitmk.ai.conversation.auto-analysis-enabled-at=2026-xx-xxTxx:xx:xx+08:00
```

当前调整为 1800 秒（30 分钟）的原因：

- 避免客户间隔较长的连续补充信息被拆成多次分析。
- 每条新客户消息都会重新开始 30 分钟计时，一组会话消息通常只调用一次分析。
- 明显降低 Dify 调用频率和重复草稿数量。
- 该值保持配置化，后续仍可依据线上消息间隔和坐席使用反馈调整。

### 2.2 后续数据校准

建议统计同一会话相邻客户消息的时间差，排除跨天、转接、关闭后重开等长间隔：

- 统计触发后 30 分钟内是否仍有客户补充消息。
- 如果绝大多数会话在 10～15 分钟内已经稳定，可再适当缩短。
- 如果仍存在大量重复分析，继续结合调用成本和坐席反馈评估，不由前端自行调整。

自动分析触发时还要检查：

- 会话创建时间或资源创建时间不早于 `auto-analysis-enabled-at`；
- 会话状态不是 `CLOSED`；
- 已有负责人；
- 当前负责人仍能访问该会话；
- 客户消息数不少于 3；
- `latestCustomerMessageId` 与最近一次成功分析的 `basisLastMessageId` 不同；
- 当前不存在相同 `basisLastMessageId` 的 `QUEUED/RUNNING/SUCCESS` 分析。

系统启动时不运行历史补偿扫描。自动分析只由功能启用后的消息事件和分配事件驱动。

## 3. 触发状态机

### 3.1 自动触发

1. 收到客户消息并成功落库。
2. 判断是否属于功能启用后的新线索/新会话。
3. 统计当前会话客户消息数。
4. 少于 5 条：不创建分析任务。
5. 达到 5 条：创建或更新 `analysis_trigger`，计划执行时间为 `最后客户消息时间 + 1800 秒（30 分钟）`。
6. 防抖期间又收到客户消息：更新 `basisLastMessageId` 并重新计算计划时间。
7. 到期执行前再次校验会话状态、负责人、消息 ID 和重复任务。
8. 创建分析主任务，并按模块调用多个 Dify Workflow。
9. 模块完成后分别保存结果；主任务可能是 `SUCCESS`、`PARTIAL_SUCCESS` 或 `FAILED`。
10. 向当前负责人写入 `AI_ANALYSIS_UPDATED` 实时事件。

### 3.2 手动触发

历史或新会话均可手动触发：

- 不受“至少 5 条客户消息”限制，但至少需要 1 条可分析文本消息。
- 默认复用相同 `basisLastMessageId` 的成功结果。
- 用户勾选或请求 `force=true` 时创建新版本。
- 手动触发不会改变后续新消息的自动防抖逻辑。

### 3.3 转接与关闭

- 防抖期间发生转接：任务保留，执行时以新负责人为 `assigneeId`。
- 分析完成后发生转接：新负责人可以查看分析；原负责人失去确认权限。
- 会话关闭前任务尚未执行：取消自动任务。
- 已关闭会话允许手动分析，但只读展示；默认不允许应用回复、线索、跟进和预约草稿。

## 4. 多 Dify Workflow 拆分

### 4.1 工作流清单

| 模块编码 | Dify Workflow | 输入 | 主要输出 |
|---|---|---|---|
| `INSIGHT` | 会话洞察 | 消息快照、线索快照 | 摘要、意向、阶段、需求、异议、风险、下一步策略 |
| `LEAD_ENRICHMENT` | 线索信息提取 | 消息快照、现有 CRM 字段、字段元数据 | 逐字段建议、置信度、证据、冲突 |
| `REPLY_SUGGESTION` | 建议回复 | 消息快照、洞察结果、品牌约束 | 2～3 条建议回复 |
| `FOLLOW_UP_DRAFT` | 跟进草稿 | 消息快照、洞察结果、线索资料 | 类型、摘要、详情、提醒时间 |
| `APPOINTMENT_DRAFT` | 预约草稿 | 消息快照、洞察结果、线索资料 | 是否适用、时间、校区、说明、缺失项 |

### 4.2 编排方式

- `INSIGHT` 和 `LEAD_ENRICHMENT` 可以并行执行。
- `REPLY_SUGGESTION`、`FOLLOW_UP_DRAFT`、`APPOINTMENT_DRAFT` 可以使用消息快照直接运行，也可以选择消费 `INSIGHT` 结果。
- 后端编排器不要求所有模块同时成功。
- `APPOINTMENT_DRAFT` 可以返回 `NOT_APPLICABLE`，表示当前证据不足，不属于失败。
- 每个模块有独立 `workflowRunId`、耗时、状态、错误码和重试次数。
- 后端必须校验各模块 JSON Schema，不能把 Dify 原始输出直接交给前端或 CRM。

### 4.3 Dify 配置

建议新增独立配置，不复用运营日报的 API Key 和超时时间：

```properties
aitmk.ai.conversation.dify.base-url=
aitmk.ai.conversation.dify.user-prefix=aitmk-conversation
aitmk.ai.conversation.dify.insight.api-key=
aitmk.ai.conversation.dify.lead-enrichment.api-key=
aitmk.ai.conversation.dify.reply-suggestion.api-key=
aitmk.ai.conversation.dify.follow-up-draft.api-key=
aitmk.ai.conversation.dify.appointment-draft.api-key=
aitmk.ai.conversation.dify.timeout-seconds=60
```

## 5. 数据模型

### 5.1 `ai_conversation_analysis`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT | 分析 ID |
| `conversation_id` | BIGINT | 会话 ID |
| `resource_id` | BIGINT | 资源 ID |
| `lead_row_id` | VARCHAR(191) | 生成时关联的 CRM 线索 |
| `assignee_id` | VARCHAR(64) | 生成时负责人 |
| `trigger_type` | VARCHAR(16) | `AUTO` / `MANUAL` |
| `basis_last_message_id` | BIGINT | 分析依据的最后消息 |
| `customer_message_count` | INT | 快照中的客户消息数 |
| `status` | VARCHAR(32) | `QUEUED/RUNNING/SUCCESS/PARTIAL_SUCCESS/FAILED/CANCELLED` |
| `snapshot_json` | LONGTEXT | 脱敏、裁剪后的输入快照 |
| `schema_version` | VARCHAR(32) | 输出契约版本 |
| `created_by` | VARCHAR(64) | 自动任务为 `system` |
| `created_at` | DATETIME(6) | 创建时间 |
| `started_at` | DATETIME(6) | 开始时间 |
| `completed_at` | DATETIME(6) | 完成时间 |
| `error_message` | VARCHAR(2000) | 主任务错误摘要 |

唯一约束建议：

```text
(conversation_id, basis_last_message_id, trigger_type, schema_version)
```

手动强制重跑可通过增加 `version` 或 `request_id` 支持。

### 5.2 `ai_analysis_module`

| 字段 | 说明 |
|---|---|
| `analysis_id` | 所属主分析 |
| `module_type` | 五类模块编码 |
| `status` | `PENDING/RUNNING/SUCCESS/NOT_APPLICABLE/FAILED` |
| `workflow_run_id` | Dify Workflow 运行 ID |
| `result_json` | 结构化结果 |
| `input_hash` | 输入去重和排障 |
| `attempt_count` | 重试次数 |
| `started_at/completed_at` | 模块时间 |
| `error_code/error_message` | 模块错误 |

唯一约束：`(analysis_id, module_type)`。

### 5.3 `ai_action_draft`

| 字段 | 说明 |
|---|---|
| `id` | 草稿 ID |
| `analysis_id` | 来源分析 |
| `module_id` | 来源模块 |
| `draft_type` | `LEAD_PATCH/FOLLOW_UP/APPOINTMENT/REPLY` |
| `payload_json` | AI 原始草稿 |
| `status` | `DRAFT/APPLYING/APPLIED/DISCARDED/EXPIRED/FAILED` |
| `external_row_id` | CRM 写入成功后的 rowId |
| `idempotency_key` | 应用幂等键 |
| `confirmed_by` | 确认坐席 |
| `confirmed_payload_json` | 坐席修改后的最终内容 |
| `confirmed_at` | 确认时间 |
| `error_code/error_message` | 应用错误 |

## 6. 公共响应格式

继续沿用 v2：

```json
{
  "success": true,
  "data": {},
  "requestId": "req-xxx"
}
```

失败：

```json
{
  "success": false,
  "error": {
    "code": "AI_ANALYSIS_NOT_FOUND",
    "message": "尚未生成会话分析",
    "details": {}
  },
  "requestId": "req-xxx"
}
```

## 7. 会话分析接口

### 7.1 查询最新分析

```http
GET /api/v2/conversations/{conversationId}/ai-analysis/latest
```

权限：复用 `ConversationQueryService.get(conversationId, user)`。

无分析时建议返回 `200`，而不是 `404`：

```json
{
  "success": true,
  "data": {
    "available": false,
    "manualAnalysisAllowed": true,
    "autoAnalysisEligible": false,
    "reason": "HISTORICAL_CONVERSATION"
  }
}
```

有分析时：

```json
{
  "success": true,
  "data": {
    "available": true,
    "analysisId": "901",
    "conversationId": "328",
    "resourceId": "71",
    "leadRowId": "crm-row-id",
    "assigneeId": "agent-row-id",
    "triggerType": "AUTO",
    "status": "PARTIAL_SUCCESS",
    "basisLastMessageId": "11982",
    "currentLastMessageId": "11982",
    "stale": false,
    "generatedAt": "2026-07-27T15:40:00+08:00",
    "modules": {
      "INSIGHT": {
        "status": "SUCCESS",
        "result": {
          "summary": "家长咨询六年级数学课程，重点关注周末上课和距离。",
          "intentLevel": "HIGH",
          "intentConfidence": 0.86,
          "conversationStage": "APPOINTMENT_NEGOTIATION",
          "appointmentReadiness": "NEED_MORE_INFO",
          "needs": ["六年级数学", "周末上课"],
          "objections": ["距离"],
          "risks": [],
          "nextBestActions": [
            "确认客户所在区域和意向校区",
            "确认本周六或周日的具体到访时间"
          ],
          "evidence": [
            {
              "messageId": "11980",
              "occurredAt": "2026-07-27T15:31:20+08:00",
              "excerpt": "周末有课吗？我们孩子六年级"
            }
          ]
        }
      },
      "LEAD_ENRICHMENT": {
        "status": "SUCCESS",
        "draftId": "1201"
      },
      "REPLY_SUGGESTION": {
        "status": "SUCCESS",
        "draftId": "1202"
      },
      "FOLLOW_UP_DRAFT": {
        "status": "SUCCESS",
        "draftId": "1203"
      },
      "APPOINTMENT_DRAFT": {
        "status": "NOT_APPLICABLE",
        "reason": "APPOINTMENT_TIME_MISSING"
      }
    }
  },
  "requestId": "req-xxx"
}
```

### 7.2 手动创建分析

```http
POST /api/v2/conversations/{conversationId}/ai-analysis
Idempotency-Key: <uuid>
Content-Type: application/json
```

```json
{
  "force": false,
  "modules": [
    "INSIGHT",
    "LEAD_ENRICHMENT",
    "REPLY_SUGGESTION",
    "FOLLOW_UP_DRAFT",
    "APPOINTMENT_DRAFT"
  ]
}
```

返回 `202 Accepted`：

```json
{
  "success": true,
  "data": {
    "analysisId": "901",
    "status": "QUEUED",
    "reused": false,
    "basisLastMessageId": "11982"
  },
  "requestId": "req-xxx"
}
```

规则：

- `force=false`：相同消息依据存在成功或运行中分析时复用。
- `force=true`：创建新版本，仅允许当前负责人操作。
- 历史会话允许调用。
- 无文本消息时返回 `AI_ANALYZABLE_MESSAGE_MISSING`。

### 7.3 查询指定分析

```http
GET /api/v2/conversations/{conversationId}/ai-analysis/{analysisId}
```

用于轮询。建议前端间隔：前 15 秒每 2 秒一次，之后每 5 秒一次，最长 60 秒；有实时事件时立即停止轮询。

### 7.4 重试单个模块

```http
POST /api/v2/conversations/{conversationId}/ai-analysis/{analysisId}/modules/{moduleType}/retry
Idempotency-Key: <uuid>
```

返回 `202`。只允许重试 `FAILED` 或显式强制重试的模块。

## 8. 草稿接口

### 8.1 查询分析草稿

```http
GET /api/v2/conversations/{conversationId}/ai-analysis/{analysisId}/drafts
```

### 8.2 线索补全草稿

```json
{
  "draftId": "1201",
  "draftType": "LEAD_PATCH",
  "status": "DRAFT",
  "linked": true,
  "leadRowId": "crm-row-id",
  "fields": [
    {
      "controlId": "66b30ef13e774217ade66e77",
      "label": "年级",
      "currentValue": "",
      "suggestedValue": "六年级",
      "suggestedDisplay": "六年级",
      "confidence": 0.97,
      "conflict": false,
      "defaultSelected": true,
      "evidence": [
        {
          "messageId": "11980",
          "excerpt": "我们孩子六年级"
        }
      ]
    }
  ]
}
```

应用：

```http
POST /api/v2/ai-drafts/{draftId}/apply
Idempotency-Key: <uuid>
```

```json
{
  "conversationId": "328",
  "expectedAssigneeId": "agent-row-id",
  "payload": {
    "leadRowId": "crm-row-id",
    "controls": [
      {
        "controlId": "66b30ef13e774217ade66e77",
        "value": "六年级"
      }
    ],
    "triggerWorkflow": true
  }
}
```

规则：

- 仅当前负责人。
- 后端重新读取 CRM 当前值，防止草稿生成后数据已变化。
- 默认只应用空字段。
- 非空冲突字段必须携带 `overrideConfirmed=true`。
- 关系字段必须是有效 rowId。
- 写入成功后刷新本地 `lead_records` 缓存。

### 8.3 跟进记录草稿

```json
{
  "draftId": "1203",
  "draftType": "FOLLOW_UP",
  "status": "DRAFT",
  "payload": {
    "resourceId": "71",
    "leadRowId": "crm-row-id",
    "type": "Record",
    "summary": "家长咨询六年级数学周末课程",
    "details": "客户关注周末上课安排和校区距离，待确认所在区域及到访时间。",
    "reminderAt": null,
    "center": null
  }
}
```

应用接口与现有 `CreateFollowUpRequest` 对齐。后端调用 `FollowUpService.create`，而不是由前端直接拼装 CRM controls。

### 8.4 预约记录草稿

```json
{
  "draftId": "1204",
  "draftType": "APPOINTMENT",
  "status": "DRAFT",
  "applicable": true,
  "missingRequiredFields": [],
  "payload": {
    "resourceId": "71",
    "leadRowId": "crm-row-id",
    "followUpRowId": null,
    "appointmentDate": "2026-08-01 10:00:00",
    "appointmentTimeSource": "客户表示本周六上午十点可以到访",
    "appointmentInfo": "六年级数学课程咨询与校区参观",
    "appointmentStatus": "Appointed, Waiting for visit",
    "center": "center-row-id"
  }
}
```

以下情况禁止应用：

- 时间只是“有空再去”“周末看看”等无法转换为明确时间；
- 校区为必填但没有有效 relation rowId；
- 线索未关联；
- 会话已转给其他坐席；
- 草稿已被应用、丢弃或过期。

### 8.5 丢弃与反馈

```http
POST /api/v2/ai-drafts/{draftId}/discard
```

```json
{
  "reasonCode": "INACCURATE",
  "remark": "客户说的是下周，不是本周"
}
```

## 9. 建议回复契约

回复草稿：

```json
{
  "draftId": "1202",
  "draftType": "REPLY",
  "status": "DRAFT",
  "suggestions": [
    {
      "id": "r1",
      "style": "FRIENDLY",
      "content": "可以的，我们周末有六年级数学课程。请问您目前住在哪个区域，我先帮您推荐距离更合适的校区？",
      "evidenceMessageIds": ["11980", "11981"]
    },
    {
      "id": "r2",
      "style": "DIRECT",
      "content": "周末可以安排。请告诉我您所在区域，以及周六、周日哪天更方便，我帮您确认校区和时间。",
      "evidenceMessageIds": ["11980", "11981"]
    }
  ]
}
```

“插入输入框”是纯前端动作，不调用发送接口。最终发送继续走现有：

```http
POST /api/v2/conversations/{conversationId}/messages
Idempotency-Key: <uuid>
```

## 10. 实时事件

新增：

```json
{
  "eventType": "AI_ANALYSIS_UPDATED",
  "conversationId": "328",
  "resourceId": "71",
  "payload": {
    "analysisId": "901",
    "status": "PARTIAL_SUCCESS",
    "completedModules": ["INSIGHT", "LEAD_ENRICHMENT", "REPLY_SUGGESTION"],
    "failedModules": ["FOLLOW_UP_DRAFT"],
    "notApplicableModules": ["APPOINTMENT_DRAFT"],
    "basisLastMessageId": "11982"
  }
}
```

只投递给当前负责人。转接后原负责人不再接收该会话的 AI 事件。

## 11. 权限与并发

### 11.1 查看

- 能查看会话的人可以查看已有分析。
- 生成、重试、应用、丢弃仅限当前负责人。
- 当前负责人判断必须在后端执行，不能只由前端隐藏按钮。

### 11.2 确认

应用草稿时同时校验：

- `conversation.assignedAgentId == currentUser.accountRowId`
- `expectedAssigneeId == conversation.assignedAgentId`
- 会话未关闭
- 草稿状态为 `DRAFT/FAILED`
- 草稿所属会话与请求会话一致

不满足时返回 `AI_DRAFT_ASSIGNEE_CHANGED`，前端刷新会话并关闭确认抽屉。

### 11.3 幂等

- 分析创建、模块重试、草稿应用都要求 `Idempotency-Key`。
- `ai_action_draft.idempotency_key` 唯一。
- CRM 写入成功但本地响应超时时，通过草稿状态和 `external_row_id` 返回已有结果。

## 12. 错误码

| HTTP | 错误码 | 场景 |
|---|---|---|
| 400 | `AI_ANALYZABLE_MESSAGE_MISSING` | 没有可分析文本 |
| 400 | `AI_MODULE_INVALID` | 不支持的模块 |
| 400 | `AI_DRAFT_PAYLOAD_INVALID` | 最终确认内容校验失败 |
| 403 | `AI_ANALYSIS_NOT_ASSIGNEE` | 非当前负责人生成或重试 |
| 403 | `AI_DRAFT_NOT_ASSIGNEE` | 非当前负责人确认 |
| 404 | `AI_ANALYSIS_NOT_FOUND` | 指定分析不存在 |
| 404 | `AI_DRAFT_NOT_FOUND` | 指定草稿不存在 |
| 409 | `AI_ANALYSIS_ALREADY_RUNNING` | 相同消息依据已有任务 |
| 409 | `AI_DRAFT_ALREADY_APPLIED` | 草稿已应用 |
| 409 | `AI_DRAFT_ASSIGNEE_CHANGED` | 会话已转接 |
| 409 | `AI_DRAFT_SOURCE_CHANGED` | CRM 当前值与草稿依据不一致 |
| 422 | `AI_APPOINTMENT_INCOMPLETE` | 预约缺少明确时间或关系字段 |
| 502 | `AI_WORKFLOW_FAILED` | Dify 调用失败 |
| 502 | `AI_WORKFLOW_RESULT_INVALID` | Dify 输出不符合 Schema |
| 503 | `AI_ANALYSIS_DISABLED` | 功能或模块未启用 |

## 13. 前端实现方案（最终汇总）

本章取代早期页面原型，以 5 个 Dify Workflow v3.2 最终输出和当前后端代码为准。前端不直接调用 Dify，不解析 Dify 节点中间变量，也不保存任何 Dify API Key。

### 13.1 实施边界

首期前端必须实现：

1. 会话右栏展示 AI 洞察、建议回复和 3 类草稿入口。
2. 历史会话和新会话均可由当前负责人手动分析。
3. 接收自动分析结果，但不在浏览器实现“5 条消息 + 30 分钟防抖”；自动触发完全由后端负责。
4. 模块级状态、模块级失败和模块级重试。
5. 建议回复只插入输入框，由坐席再次确认并使用原消息发送接口发送。
6. 线索补全、跟进记录、预约记录必须进入可编辑确认界面，再调用草稿应用接口。
7. 转接、关闭、结果过期、重复点击、后端部分成功均有明确 UI 状态。

首期不实现：

- 经理审批或代确认；
- 浏览器直接写 CRM；
- AI 自动发送回复；
- 前端定时触发自动分析；
- 页面打开时批量分析历史会话；
- 根据 Dify 原始文本猜测字段，所有展示都只消费后端结构化结果。

### 13.2 当前代码接入点

前端项目现有结构（`/Users/xingzhan/code/v0-im`）的实际接入位置：

| 现有文件 | 当前职责 | AI 改造 |
|---|---|---|
| `components/workbench.tsx` | 会话选择、实时事件、刷新版本 | 保存当前 AI 事件版本和“插入回复”桥接状态 |
| `components/resource-panel.tsx` | 360px 右栏、线索/跟进/预约 Tabs | 新增 `AI 助手` Tab；去掉“未关联线索时整个右栏提前返回” |
| `components/chat-panel.tsx` | 消息区和输入区组合 | 接收待插入文本并传给 `MessageInput` |
| `components/message-input.tsx` | 内部维护 `content` 并发送 | 增加受控插入入口，保留人工点击发送 |
| `components/lead-info-tab.tsx` | 线索查看和编辑 | 应用线索草稿成功后触发 CRM profile 刷新 |
| `components/follow-up-create-sheet.tsx` | 创建跟进 | 支持 AI 初值，但最终提交改走 draft apply |
| `components/appointment-create-sheet.tsx` | 创建预约 | 支持 AI 初值、缺失项提示和创建条件校验 |
| `lib/workbench-types.ts` | 会话与实时事件类型 | 增加 `AI_ANALYSIS_UPDATED` 联合类型 |
| `lib/workbench-contracts.ts` | 运行时响应解析 | 增加 AI API 和五模块的防御性解析 |
| `lib/workbench-api.ts` | v2 API 请求封装 | 可以转调独立的 `ai-conversation-api.ts` |

`resource-panel.tsx` 当前在 `linked=false` 时直接返回“未关联线索”空态，这会阻止会话洞察和建议回复展示。改造后应始终显示 `AI 助手`；只有 `LEAD_PATCH`、`FOLLOW_UP`、`APPOINTMENT` 的应用动作根据 `leadRowId/linked` 禁用。

### 13.3 页面信息架构

继续使用现有 360px 右栏，不把四种 AI 能力拆成四个一级 Tab。建议保留现有业务 Tab，并新增一个聚合入口：

```text
┌────────────────────────────────┐
│ 客户摘要 / 当前负责人           │
├────────────────────────────────┤
│ AI助手 │ 线索 │ 跟进 │ 预约      │
├────────────────────────────────┤
│ AI助手                          │
│ 分析至消息 #23403 · 手动分析     │
│ [客户有新消息，结果可能已过期]   │
│                                │
│ 意向：中  60%   阶段：待跟进     │
│ 预约准备度：暂不可预约           │
│                                │
│ 会话摘要                        │
│ ……                             │
│                                │
│ 需求 / 积极信号 / 异议 / 风险    │
│ ……                             │
│                                │
│ 下一步策略                      │
│ 1. 重新确认当前需求              │
│ 2. 核验产品适配                  │
│                                │
│ 建议回复                        │
│ [友好] ……              [插入]   │
│ [直接] ……              [插入]   │
│                                │
│ 待确认草稿                      │
│ 线索补全 · 2 项          [查看]  │
│ 跟进记录 · 已生成        [查看]  │
│ 预约记录 · 当前不适用     [原因]  │
└────────────────────────────────┘
```

顶部动作规则：

- `available=false`：显示“开始分析”。
- `QUEUED/RUNNING`：按钮显示“分析中”，不可重复提交。
- 已完成且 `stale=false`：显示“重新分析”，默认 `force=true`。
- `stale=true`：主按钮改成“基于最新消息重新分析”。
- 非当前负责人：分析结果可见，但生成、重试、应用、丢弃按钮隐藏或禁用，并显示“仅当前负责人可操作”。最终权限仍以后端响应为准。
- 会话已关闭：保留洞察只读，禁止所有草稿应用和建议回复插入。

### 13.4 前端领域类型

新增 `lib/ai-conversation-types.ts`，ID 全部使用 `string`，不要转换为 JavaScript `number`：

```ts
export type AiModuleType =
  | 'INSIGHT'
  | 'LEAD_ENRICHMENT'
  | 'REPLY_SUGGESTION'
  | 'FOLLOW_UP_DRAFT'
  | 'APPOINTMENT_DRAFT'

export type AiAnalysisStatus =
  | 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'PARTIAL_SUCCESS' | 'FAILED' | 'CANCELLED'

export type AiModuleStatus =
  | 'PENDING' | 'RUNNING' | 'SUCCESS' | 'NOT_APPLICABLE' | 'FAILED'

export interface AiModuleView<T> {
  status: AiModuleStatus
  workflowRunId?: string | null
  result?: {
    moduleType: AiModuleType
    schemaVersion: '1.0'
    status: 'SUCCESS' | 'NOT_APPLICABLE' | 'FAILED'
    data: T
    error?: { code?: string; message?: string }
  }
  draftId?: string
  errorCode?: string
}
```

重要：后端 `modules[moduleType].result` 保存的是完整 `result_json`，业务数据位于 `result.data`。早期原型里直接读取 `result.summary`、`result.intentLevel` 是错误的。

五类 `data` 至少声明以下稳定字段：

- `INSIGHT`：`summary`、`intent.level/confidence/reason/evidenceMessageIds`、`conversationStage`、`appointmentReadiness`、`needs`、`positiveSignals`、`objections`、`timeliness`、`risks`、`missingInfo`、`nextBestActions`、`knowledgeUsage`。
- `LEAD_ENRICHMENT`：`candidates[]`、`warningCodes`、`candidateCount`、`defaultSelectedCount`、`conflictCount`。
- `REPLY_SUGGESTION`：`replyLanguage`、`suggestions[]`、`knowledgeUsage`、`agentConfirmationRequired`。
- `FOLLOW_UP_DRAFT`：`applicable`、`reasonCode`、`type`、`summary`、`details`、`facts[]`、`nextStep`、`reminderAt`、`centerCandidate`、`warnings`。
- `APPOINTMENT_DRAFT`：`applicable`、`creatable`、`reasonCode`、`missingRequiredFields`、`appointmentStartAt`、`appointmentInfo`、`appointmentStatus`、`center`、`fieldEvidence`、`warnings`。

数组元素在最终 Workflow 中可能包含更多说明和证据字段，解析器应保留未知字段，但必须校验关键字段的基础类型。`NOT_APPLICABLE` 是正常业务结果，不进入错误 Toast。

### 13.5 API 封装

新增 `lib/ai-conversation-api.ts`：

```ts
getLatestAiAnalysis(conversationId)
getAiAnalysis(conversationId, analysisId)
createAiAnalysis(conversationId, body, idempotencyKey)
retryAiModule(conversationId, analysisId, moduleType, idempotencyKey)
listAiDrafts(conversationId, analysisId)
applyAiDraft(draftId, body, idempotencyKey)
discardAiDraft(draftId, body)
```

请求约定：

- 手动分析默认提交五模块；局部“换一版”使用模块重试接口。
- `REPLY_SUGGESTION/FOLLOW_UP_DRAFT/APPOINTMENT_DRAFT` 单独请求时后端会自动补 `INSIGHT`。
- 创建分析、模块重试、草稿应用，每次用户动作生成一个 UUID 幂等键；请求超时重试必须复用同一个键。
- HTTP `202` 只表示进入队列，不能当作生成完成。
- `create` 返回 `reused=true` 时直接使用返回的 `analysisId` 查询，不重复弹出“已创建”。
- `apply` 必须发送当前 `conversationId`、页面当前 `assignedAgentId` 作为 `expectedAssigneeId`，以及坐席编辑后的完整 `payload`。

### 13.6 数据获取、轮询和实时更新

选择会话后的顺序：

1. 立即请求 `GET .../ai-analysis/latest`，与资源和 CRM profile 并行，不阻塞聊天。
2. `available=false` 显示未分析空态，不当作接口错误。
3. `QUEUED/RUNNING` 时开始轮询指定分析：前 15 秒每 2 秒、之后每 5 秒、最长 60 秒。
4. 收到 `AI_ANALYSIS_UPDATED` 且 `conversationId` 等于当前会话时，立即刷新该 `analysisId` 并停止对应轮询。
5. 非当前会话的事件只记录“该会话 AI 有更新”标记，可在会话列表展示小圆点；不要抢切当前会话。
6. 切换会话、退出页面或分析完成时取消定时器和未完成请求，避免旧会话响应覆盖新会话。

实时事件类型增加：

```ts
type AiAnalysisUpdatedEvent = {
  eventType: 'AI_ANALYSIS_UPDATED'
  conversationId: string
  resourceId: string
  payload: {
    analysisId: string
    status: AiAnalysisStatus
    basisLastMessageId: string
    completedModules: AiModuleType[]
    failedModules: AiModuleType[]
    notApplicableModules: AiModuleType[]
  }
}
```

如果 WebSocket/SSE 断线，沿用工作台现有全量恢复机制，恢复后重新请求当前会话 `latest`；不要在前端补发分析任务。

### 13.7 Workflow 1：会话洞察展示

使用 `modules.INSIGHT.result.data`：

- 意向等级：`HIGH/MEDIUM/LOW/UNKNOWN` 映射为高/中/低/未知；置信度显示百分比。
- 会话阶段、预约准备度使用枚举翻译表，不直接展示英文编码。
- 摘要完整展示，超过约 6 行可折叠。
- `needs/positiveSignals/objections/risks/missingInfo` 分组展示；空数组隐藏该分组。
- `nextBestActions` 按 `priority` 排序，展示 `actionType` 和说明文本。
- `timeliness.status=STALE` 显示“对话内容较久，建议重新确认”的警示，不等同于页面 `stale`。
- `knowledgeUsage.status=NO_HIT/FAILED/UNUSABLE` 显示轻量提示；不得把未验证业务事实展示为确定结论。
- 证据消息 ID 可点击时滚动定位聊天消息；定位不到时只显示证据摘要，不报错。

页面 `stale` 与 Workflow 的 `timeliness` 是两个维度：前者表示分析后又有新消息，后者表示分析输入本身已经陈旧。

### 13.8 Workflow 3：建议回复

每条建议展示风格、内容、用途和风险提示：

- `requiresVerification=true` 或 `warnings` 非空：显示“发送前需核验”。
- `usesBusinessFacts=true`：可展示知识引用入口；没有 `knowledgeReferenceIds` 时不得标记“已核验”。
- 点击“插入输入框”只更新 `MessageInput` 文本并聚焦，不能调用发送接口。
- 输入框已有内容时弹出“替换 / 追加 / 取消”，避免覆盖坐席输入。
- 插入后仍受现有 4096 字符、24 小时回复窗口、附件和发送幂等逻辑约束。
- “换一版”只重试 `REPLY_SUGGESTION`，不要重新创建整个分析。

`MessageInput` 当前内部维护 `content`，建议新增：

```ts
interface SuggestedReplyInsertion {
  id: string       // 每次点击唯一，保证相同文本也能再次插入
  content: string
}
```

由 `Workbench` 保存 insertion，经过 `ChatPanel` 传给 `MessageInput`；`MessageInput` 消费后回调清空。不要把 AI 回复直接塞进消息发送队列。

### 13.9 Workflow 2：线索补全确认

打开 `LEAD_PATCH` 草稿 Sheet 后使用 `payload.candidates` 生成逐字段表格：

- 默认勾选 `defaultSelected=true && conflict=false && needsResolution=false`。
- 显示 `label`、当前值 `existingValue`、建议值 `displayValue || value`、置信度和客户证据。
- `conflict=true` 默认不勾选；用户主动选择后显示覆盖确认。
- `needsResolution=true` 禁止直接提交，关系/选项字段必须由坐席选择合法选项。
- 关系字段提交真实 `rowId`，不能提交显示名称或语义枚举。
- 未关联线索、没有 `leadRowId`、没有候选项时禁用“确认并更新线索”。

提交时把选择结果转换为后端需要的结构：

```json
{
  "conversationId": "328",
  "expectedAssigneeId": "agent-row-id",
  "payload": {
    "leadRowId": "crm-row-id",
    "controls": [
      { "controlId": "student_age", "value": "15" },
      { "controlId": "preferred_center", "value": "center-kg" }
    ],
    "triggerWorkflow": true
  }
}
```

应用成功后关闭 Sheet、刷新 CRM profile，并将草稿标记为 `APPLIED`。当前后端写入入口是统一草稿应用接口，不允许复用普通线索编辑接口绕过确认记录。

### 13.10 Workflow 4：跟进记录确认

复用 `follow-up-create-sheet.tsx` 的字段和样式，但区分普通创建与 AI 草稿确认：

- 初值来自 `payload.type/summary/details/reminderAt/centerCandidate`。
- `facts`、`nextStep`、证据和 `warnings` 作为辅助信息展示，不直接拼进用户可编辑字段。
- `nextStep.status=SUGGESTED` 必须标注为建议，不能展示成双方已约定。
- `reminderAt` 为空允许创建；不为空必须保持带时区 ISO 格式且为未来时间。
- `centerCandidate.needsResolution=true` 时要求坐席重新选择校区。
- `applicable=false` 展示原因，不创建可应用草稿按钮。

提交完整编辑后 payload；成功后刷新跟进列表。失败时 Sheet 保留用户输入并允许用新的幂等键再次提交；网络超时重试原请求则复用旧键。

### 13.11 Workflow 5：预约记录确认

预约 UI 必须同时区分：

1. `status=NOT_APPLICABLE` 或 `applicable=false`：正常显示“当前未形成预约意向”和 `reasonCode`。
2. `applicable=true, creatable=false`：显示预约讨论信息和 `missingRequiredFields`，允许坐席补充页面字段，但只有补齐后端硬校验所需证据的草稿才能应用。
3. `creatable=true`：允许打开预约确认 Sheet。

确认 Sheet 展示并允许编辑 `appointmentStartAt`、`appointmentInfo`、`appointmentStatus`、`center` 及学生/家长信息。提交前前端做快速校验，但以后端为最终准则：

- 已关联 `leadRowId`；
- `appointmentStartAt` 是带时区、晚于当前时间的 ISO 时间；
- `center.rowId` 存在且来自后端选项；
- `fieldEvidence` 同时包含客户来源的 `APPOINTMENT_INTENT` 和 `APPOINTMENT_TIME`；
- `creatable=true`。

不得通过用户在 Sheet 中临时填写时间，就伪造 `fieldEvidence` 或把 `creatable` 从 false 改为 true。若聊天中尚无客户明确确认，应提示坐席先继续沟通，再基于新消息重新分析。

### 13.12 草稿通用状态与错误处理

| 草稿状态 | UI 行为 |
|---|---|
| `DRAFT` | 可编辑、应用、丢弃 |
| `APPLYING` | 锁定表单和按钮，显示提交中 |
| `APPLIED` | 只读展示成功状态和 `externalRowId` |
| `FAILED` | 保留内容，展示 `errorCode/errorMessage`，允许修改后重试 |
| `DISCARDED` | 只读，不允许恢复；需要时重新生成模块 |
| `EXPIRED` | 只读，提示基于最新消息重新分析 |

关键错误处理：

- `AI_DRAFT_ASSIGNEE_CHANGED`：关闭 Sheet，刷新会话详情和负责人，不自动重试。
- `AI_DRAFT_CONVERSATION_CLOSED`：转只读并提示会话已关闭。
- `AI_APPOINTMENT_INCOMPLETE`：在预约字段区域展示缺失原因。
- `AI_WORKFLOW_RESULT_INVALID`：对应模块错误卡，不影响成功模块。
- `AI_ANALYSIS_DISABLED`：AI Tab 显示服务暂不可用，不影响聊天和 CRM Tabs。
- `401/403`：沿用全局鉴权；非当前负责人错误不要吞掉。
- 未知错误：展示 `requestId`，保留当前表单输入。

### 13.13 建议新增文件

```text
components/ai-assistant-tab.tsx
components/ai-analysis-header.tsx
components/ai-insight-card.tsx
components/ai-reply-suggestions.tsx
components/ai-draft-overview.tsx
components/ai-lead-draft-sheet.tsx
components/ai-follow-up-draft-sheet.tsx
components/ai-appointment-draft-sheet.tsx
components/ai-module-state.tsx
hooks/use-ai-conversation-analysis.ts
lib/ai-conversation-api.ts
lib/ai-conversation-contracts.ts
lib/ai-conversation-types.ts
```

职责约束：

- API 文件只负责 HTTP 和 v2 `Response<T>` 解包。
- contracts 文件负责运行时类型校验和兼容性降级。
- hook 负责切会话、轮询、实时刷新、取消请求和 mutation 状态。
- 展示组件不直接调用 API。
- 草稿 Sheet 只提交其对应 draft，不直接调用 CRM API。

### 13.14 推荐开发顺序

1. 定义类型、契约解析器和 API 封装，并用固定 JSON 建单元测试。
2. 在 `workbench-types.ts` 和 `workbench.tsx` 接入 `AI_ANALYSIS_UPDATED`。
3. 改造 `resource-panel.tsx`，保证未关联 CRM 时 AI Tab 仍可见。
4. 实现查询、手动分析、轮询、模块状态和模块重试。
5. 实现 Workflow 1 洞察与 Workflow 3 回复插入链路。
6. 实现 Workflow 2 线索补全确认与 CRM 刷新。
7. 复用现有 Sheet 实现 Workflow 4、5 草稿确认。
8. 补齐转接、关闭、过期、断线恢复、幂等和错误态测试。

## 14. 前端验收矩阵

### 14.1 基础与触发

1. 打开历史会话不会产生 POST 请求；点击“开始分析”才创建分析。
2. 前端不实现 5 条消息和 30 分钟计时器，只展示后端自动分析结果。
3. 手动测试环境自动分析关闭时，页面仍可完整使用手动分析。
4. 同一次按钮动作只生成一个幂等键；超时重试复用该键。
5. `202/reused=true`、排队、运行、成功、部分成功、失败均正确展示。

### 14.2 五个 Workflow

6. INSIGHT 从 `result.data` 读取，意向、摘要、风险、时效性和下一步策略正确映射。
7. LEAD_ENRICHMENT 正确处理默认勾选、冲突、待解析关系和真实 rowId。
8. REPLY_SUGGESTION 可插入但绝不自动发送；已有输入时不会静默覆盖。
9. FOLLOW_UP_DRAFT 区分客户事实、坐席承诺和 AI 建议，提醒时间校验正确。
10. APPOINTMENT_DRAFT 的 `NOT_APPLICABLE` 不显示错误，`creatable=false` 不能伪造后提交。
11. 单个模块失败时，其他成功模块和草稿仍可使用。
12. 模块重试不会清空其他模块结果。

### 14.3 权限、并发与恢复

13. 只有当前负责人可以生成、重试、应用或丢弃草稿。
14. 会话转接后旧负责人正在打开的 Sheet 被关闭，提交返回冲突时不重复写入。
15. 会话关闭后洞察保留只读，所有写动作禁用。
16. 新消息使 `stale=true` 后保留旧结果并明确提示重新分析。
17. 草稿应用重复点击不会生成重复 CRM 记录。
18. 实时事件断线恢复后当前分析状态与服务端一致。
19. AI 服务错误不影响消息发送、线索、跟进和预约原有功能。
20. 未关联线索时仍能查看洞察和插入回复，但 CRM 类草稿不可应用。

## 15. 当前前后端边界说明

后端接口、五 Workflow 编排、结果持久化、V16 数据库迁移、自动触发基础逻辑、草稿确认接口和 `AI_ANALYSIS_UPDATED` 事件已经实现。前端应按照本章消费实际接口。

仍需在联调中特别验证：

- Workflow 真实返回的可选字段是否与 v3.2 示例完全一致；
- 线索字段控件类型和关系字段提交值与现有 `LeadInfoTab` 的转换规则；
- 实时事件在前端现有 `TypedRealtimeEvent` 解析器中的落点；
- 当前登录用户的 `agentRowId` 与分析返回 `assigneeId` 的比较；
- Follow-up 和 Appointment Sheet 的表单字段能否无损回填 AI payload；
- Dify 知识库引用是否需要首期开放证据详情入口。

测试环境当前配置为：AI 会话助手开启、5 个 Workflow 开启、自动分析关闭、Dify blocking 超时 900 秒。前端首轮联调应以手动分析为主；待页面、权限和草稿确认链路稳定后，再在后端配置明确的 `auto-analysis-enabled-at` 并开启自动分析。
