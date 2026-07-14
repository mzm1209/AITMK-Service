# APP 后台客户中心微服务调用关系详细设计文档

> 生成日期：2026-07-12  
> 代码基线：AITMK-Service 当前工作区  
> 适用范围：APP/IM 后台客户中心、客户信息面板、会话列表、聊天消息、线索、预约、分配转接、实时事件恢复链路  
> 说明：本文档基于当前代码真实实现整理，不描述尚未在代码中落地的能力。

---

## 1. 文档目标

本文档用于说明 APP 后台客户中心在当前系统中的服务边界、接口入口、内部服务调用关系、外部微服务依赖、数据落库关系、核心业务时序、异常降级策略和权限控制规则。

客户中心当前不是一个独立微服务，而是由 `AITMK-Service` 内部多个 Controller / Service / Repository 模块组合实现，并通过 HTTP 调用外部 CRM OpenAPI、Meta WhatsApp Cloud API、Dify AI 服务，通过 WebSocket/STOMP 向前端实时推送事件。

---

## 2. 总体架构

### 2.1 系统参与方

| 参与方 | 类型 | 当前职责 |
|---|---|---|
| APP/IM Web 前端 | 客户端 | 展示客户列表、会话详情、客户信息面板、线索、预约、消息输入和实时事件 |
| AITMK-Service | 后端应用 | 客户中心 REST API、会话查询、消息发送、资源/线索/预约管理、权限控制、实时事件、CRM/WhatsApp/AI 适配 |
| 本地业务库 | MariaDB | 存储资源、会话、消息、分配、未读、线索缓存、预约、实时事件、坐席账号缓存 |
| CRM OpenAPI | 外部系统 | 线索 `leads_bank`、登录账号 `imzhgl`、分配记录、聊天记录、AI 接待池、工作表字段元数据 |
| Meta WhatsApp Cloud API | 外部系统 | 接收入站 webhook、发送文本/媒体消息、上传媒体 |
| Dify AI | 外部系统 | AI 自动回复 |
| WebSocket/STOMP | 推送通道 | 通过 `/user/{agentId}/queue/events` 推送客户中心实时事件 |

### 2.2 调用拓扑

```text
APP/IM Web
  |
  | REST + JWT
  v
AITMK-Service
  |
  |-- Controller
  |     |-- /api/v2/conversations        客户会话中心
  |     |-- /api/v2/resources            客户资源和客户信息面板
  |     |-- /api/leads/clues             CRM 线索代理接口
  |     |-- /api/appointments            预约接口
  |     |-- /api/v2/realtime/events      实时事件补偿拉取
  |     |-- /api/v2/media                媒体上传
  |
  |-- Service
  |     |-- ConversationQueryService      会话列表/详情/消息查询
  |     |-- ConversationCommandService    转接/关闭
  |     |-- MessageCommandService         人工发送消息
  |     |-- ResourceQueryService          资源详情/历史/分配查询
  |     |-- ClueIntegrationService        CRM 线索同步与本地降级
  |     |-- WorksheetFieldService         CRM 字段元数据缓存
  |     |-- AppointmentService            预约创建/查询
  |     |-- RealtimeEventService          实时事件 outbox 写入/恢复
  |     |-- RealtimeEventPublisher        定时发布实时事件
  |     |-- V2AccessService               scope 和会话权限校验
  |
  |-- Repository / Entity
  |     |-- business_resource
  |     |-- conversation
  |     |-- chat_message
  |     |-- assignment_record
  |     |-- conversation_agent_state
  |     |-- realtime_event
  |     |-- lead_records
  |     |-- appointments
  |
  |-- External Adapters
        |-- CrmOpenApiServiceImpl         CRM OpenAPI WebClient
        |-- SendMessageServiceImpl        WhatsApp Cloud API WebClient
        |-- DifyAiServiceImpl             Dify API WebClient
```

---

## 3. 模块边界与代码入口

### 3.1 客户会话中心

| 层级 | 文件 | 职责 |
|---|---|---|
| Controller | `ConversationV2Controller` | `/api/v2/conversations` 会话列表、筛选项、详情、消息分页、发送、已读、转接、关闭 |
| Query Service | `ConversationQueryService` | 会话列表 native SQL 查询、scope 过滤、线索筛选、回复窗口筛选、游标分页、详情聚合 |
| Command Service | `ConversationCommandService` | 转接、关闭、CRM 分配同步、实时事件写入 |
| Message Command | `MessageCommandService` | 幂等发送、回复权限、24h 窗口校验、消息落库、WhatsApp 发送、CRM 聊天记录同步 |
| Access | `V2AccessService` | `mine` / `managed` / `all` 数据域和查看/回复/转接权限 |

### 3.2 客户资源与右侧客户信息面板

| 层级 | 文件 | 职责 |
|---|---|---|
| Controller | `ResourceV2Controller` | 资源详情、历史会话、分配历史、CRM profile、绑定线索、新建线索 |
| Service | `ResourceQueryService` | 资源访问校验、资源视图聚合、资源下历史会话和分配分页 |
| CRM Service | `ClueIntegrationService` | 线索按手机号查询、创建、分配字段更新、本地缓存降级 |
| Field Service | `WorksheetFieldService` | `leads_bank` 字段元数据缓存，失败时使用内置字段定义 |

### 3.3 线索与预约

| 层级 | 文件 | 职责 |
|---|---|---|
| Controller | `ClueController` | 线索新增、编辑、列表、按条件查询、按手机号查询、详情、删除 |
| Controller | `WorksheetFieldController` | 工作表字段配置查询 |
| Controller | `AppointmentController` | 预约创建和查询 |
| Service | `AppointmentService` | 预约写 CRM 与本地记录，按资源/线索/跟进过滤查询 |

### 3.4 实时事件

| 层级 | 文件 | 职责 |
|---|---|---|
| Service | `RealtimeEventService` | 写 `realtime_event` outbox，按 agent 恢复未消费事件 |
| Publisher | `RealtimeEventPublisher` | 定时锁定未发布事件并推送到 WebSocket 用户队列 |
| Controller | `RealtimeV2Controller` | 事件恢复接口 |

---

## 4. 客户中心接口清单

### 4.1 会话接口 `/api/v2/conversations`

| 方法 | URL | 说明 | 核心服务 |
|---|---|---|---|
| GET | `/api/v2/conversations` | 客户会话列表，支持 scope、状态、关键词、来源、资源状态、坐席、回复窗口、线索类型、线索状态、游标分页 | `ConversationQueryService.list` |
| GET | `/api/v2/conversations/filter-options` | 获取线索类型/线索状态筛选项 | `ConversationQueryService.filterOptions` |
| GET | `/api/v2/conversations/{id}` | 会话详情和资源摘要 | `ConversationQueryService.detail` |
| GET | `/api/v2/conversations/{id}/messages` | 会话消息分页，按 `createdAt,id` 游标向前翻页 | `ConversationQueryService.messages` |
| POST | `/api/v2/conversations/{id}/messages` | 坐席/主管发送文本或媒体消息，要求 `Idempotency-Key` | `MessageCommandService.send` |
| POST | `/api/v2/conversations/{id}/read` | 标记已读并更新未读数 | `UnreadService.read` |
| POST | `/api/v2/conversations/{id}/transfer` | 转接会话到目标坐席 | `ConversationCommandService.transfer` |
| POST | `/api/v2/conversations/{id}/close` | 关闭会话 | `ConversationCommandService.close` |

列表接口主要参数：

| 参数 | 说明 |
|---|---|
| `scope` | `mine` 当前用户、`managed` 主管管理范围、`all` OWNER 全量 |
| `status` | 会话状态；`PENDING_ASSIGNMENT` 会兼容映射到资源状态过滤 |
| `keyword` | 按客户手机号或客户名模糊查询 |
| `sourceChannel` | 来源渠道，如 `META` |
| `resourceType` | 资源类型 |
| `resourceStatus` | 资源状态 |
| `assignedAgentId` | 指定坐席过滤，必须在当前 scope 内 |
| `replyWindow` | `open`、`expired`、`lt15m`、`lt1h`、`lt4h` |
| `leadType` | 线索类型，来自 `lead_records.leads_type` |
| `leadStatus` | 线索状态，来自 `lead_records.leads_status` |
| `cursor` / `size` | 游标分页，最大 size 为 100 |

### 4.2 资源接口 `/api/v2/resources`

| 方法 | URL | 说明 | 核心服务 |
|---|---|---|---|
| GET | `/api/v2/resources/{id}` | 查询客户资源详情 | `ResourceQueryService.view` |
| GET | `/api/v2/resources/{id}/conversations` | 查询该资源下历史会话 | `ResourceQueryService.conversations` |
| GET | `/api/v2/resources/{id}/assignments` | 查询该资源分配历史 | `ResourceQueryService.assignments` |
| GET | `/api/v2/resources/{id}/crm-profile` | 客户信息面板初始化：返回线索绑定状态、线索数据、字段配置 | `ResourceV2Controller.crm` |
| POST | `/api/v2/resources/{id}/link-lead` | 将已有 CRM 线索绑定到当前资源 | `ResourceV2Controller.linkLead` |
| POST | `/api/v2/resources/{id}/create-lead` | 按资源手机号和客户名创建 CRM 线索并自动绑定 | `ResourceV2Controller.createLead` |

### 4.3 线索接口 `/api/leads/clues`

| 方法 | URL | 说明 | 外部调用 |
|---|---|---|---|
| POST | `/api/leads/clues` | 新增 CRM 线索 | CRM `addRow` |
| PUT | `/api/leads/clues/{rowId}` | 编辑 CRM 线索 | CRM `editRow` |
| GET | `/api/leads/clues` | 通用列表查询 | CRM `getFilterRows` |
| POST | `/api/leads/clues/query` | 自定义 filters 查询 | CRM `getFilterRows` |
| GET | `/api/leads/clues/query/phone` | 按手机号查询，CRM first，本地 fallback | `ClueIntegrationService.lookupLeadByPhone` |
| POST | `/api/leads/clues/query/fields` | 按白名单字段构造查询 | CRM `getFilterRows` |
| GET | `/api/leads/clues/{rowId}` | 按 rowId 查询线索详情 | CRM `getFilterRows(rowid)` |
| DELETE | `/api/leads/clues/{rowId}` | 删除 CRM 线索 | CRM `deleteRow` |

### 4.4 预约接口 `/api/appointments`

| 方法 | URL | 说明 |
|---|---|---|
| POST | `/api/appointments` | 创建预约，支持 resourceId、leadRowId、followUpRowId、学生/家长/校区/状态等字段 |
| GET | `/api/appointments` | 按 leadRowId、followUpRowId、resourceId 查询预约 |

### 4.5 实时事件接口

| 方法 | URL | 说明 |
|---|---|---|
| GET | `/api/v2/realtime/events` | 按当前登录用户恢复实时事件，支持 `after` 和 `size` |
| WebSocket | `/user/{agentId}/queue/events` | 服务端向指定坐席推送事件 |

---

## 5. 核心数据模型

### 5.1 本地业务表

| 表 | Entity | 主要用途 |
|---|---|---|
| `business_resource` | `ResourceEntity` | 客户资源主表，唯一客户手机号、客户名、来源、资源状态、当前负责人、最后消息时间 |
| `conversation` | `ConversationEntity` | 会话主表，关联资源，记录会话状态、AI 状态、负责人、关闭信息、版本号 |
| `chat_message` | `ChatMessageEntity` | 消息明细，支持 externalMessageId 去重、clientRequestId 幂等、媒体字段、广告 referral 字段 |
| `assignment_record` | `AssignmentRecordEntity` | 分配历史，记录服务中、转接、关闭、负责人、分配人、可回复状态 |
| `conversation_agent_state` | `ConversationAgentStateEntity` | 每坐席每会话的已读位置和未读数 |
| `realtime_event` | `RealtimeEventEntity` | 实时事件 outbox 和恢复游标 |
| `lead_records` | `LeadRecordEntity` | CRM 线索本地缓存，按 customer_phone 唯一，保存 crm_row_id、lead_data、leads_type、leads_status |
| `appointments` | `AppointmentEntity` | 预约本地记录，关联 resource、leadRowId、followUpRowId |
| `agent_accounts` | `AgentAccountEntity` | 坐席账号本地缓存，用于名称展示、角色、权限、管理范围 |

### 5.2 主关系

```text
business_resource 1 --- N conversation
business_resource 1 --- N chat_message
business_resource 1 --- N assignment_record
conversation       1 --- N chat_message
conversation       1 --- N conversation_agent_state
conversation       1 --- N realtime_event
business_resource 1 --- N appointments
business_resource.customer_phone --- lead_records.customer_phone
```

### 5.3 关键唯一性和并发控制

| 机制 | 说明 |
|---|---|
| `business_resource.customer_phone` unique | 当前实现一个客户手机号对应一条资源 |
| `chat_message.external_message_id` unique | 防止 WhatsApp webhook 重复消息重复落库 |
| `chat_message.client_request_id` unique | 前端人工发送使用 `Idempotency-Key` 做幂等 |
| `conversation.active_resource_id` unique | 每个资源最多保留一个非关闭会话 |
| `conversation.version` / `business_resource.version` | 乐观锁版本，用于转接/关闭期望版本校验 |
| `conversation_agent_state(conversation_id, agent_id)` unique | 一个坐席对一个会话只有一条未读状态 |
| `realtime_event.event_id` unique | 事件恢复和游标定位 |

---

## 6. 核心调用流程

### 6.1 客户会话列表查询

```text
APP
  -> GET /api/v2/conversations?scope=mine&...
  -> ConversationV2Controller.list
  -> ConversationQueryService.list
       1. V2AccessService.requireScope 校验 scope
       2. V2AccessService.requireAgentWithinScope 校验 assignedAgentId 不越权
       3. 拼接 native SQL：
          - conversation join business_resource
          - 如有 leadType/leadStatus，则 join lead_records
          - 根据状态、来源、资源状态、关键词、回复窗口、线索字段过滤
          - 根据 scope 限制 assigned_agent_id
          - 根据 cursor 做 keyset pagination
       4. 查询 size + 1 条 id 判断 hasMore
       5. 回表读取 ConversationEntity
       6. 逐条 access.canView 二次过滤
       7. 批量读取坐席名称 AgentAccountCacheService.getNames
       8. 聚合 ResourceEntity、最新消息、ConversationAgentState
       9. 计算 replyable 和 replyDeadline
  <- CursorPage<ConversationSummary>
```

关键规则：

| 规则 | 当前实现 |
|---|---|
| 分页 | 基于 `coalesce(c.last_message_at,c.created_at), c.id` 的游标分页 |
| 回复窗口 | `last_customer_message_at + 24h` |
| 未读归属 | `mine` 用当前登录人；`managed/all` 默认用会话当前负责人 |
| 线索筛选 | 依赖 `lead_records` 本地缓存，不直接实时扫 CRM |
| 筛选项来源 | 优先 CRM worksheet 字段选项，失败时合并本地 distinct 值 |

### 6.2 客户信息面板初始化

```text
APP
  -> GET /api/v2/resources/{resourceId}/crm-profile
  -> ResourceV2Controller.crm
       1. CurrentUser.get 获取登录用户
       2. ResourceQueryService.get(resourceId, user)
          - 如果资源已有会话，按会话权限校验
          - 如果无会话，需要 RESOURCE_VIEW_ALL
       3. 读取 resource.customerPhone / customerName
       4. 如手机号存在：
          -> ClueIntegrationService.lookupLeadByPhone(phone)
              a. CRM getFilterRows(leads_bank, phone)
              b. 成功：parse LeadRecord 并 upsert lead_records
              c. 失败：读取本地 lead_records fallback
       5. WorksheetFieldService.getFieldsConfig(leads_bank)
          - 1 小时缓存
          - CRM 失败时返回内置 leads_bank 字段定义
       6. 返回 CrmProfileView
```

返回字段：

| 字段 | 说明 |
|---|---|
| `resourceId` | 资源 ID |
| `customerPhone` | 客户手机号 |
| `customerName` | 客户名 |
| `linked` | 是否查到带 `rowId` 的 CRM 线索 |
| `rowId` | CRM 线索 rowId |
| `clue` | 解析后的 `LeadRecord` |
| `fieldsConfig` | 工作表字段配置，供前端渲染表单 |

### 6.3 绑定已有线索

```text
APP
  -> POST /api/v2/resources/{resourceId}/link-lead { rowId }
  -> ResourceV2Controller.linkLead
       1. ResourceQueryService.get 校验资源和权限
       2. CrmOpenApiService.getRowByRowId(leads_bank,rowId)
          - 不存在：400 CLUE_NOT_FOUND
       3. 从 CRM row 提取手机号 controlId=687fa4dd005dfd294df9dc3e
          - 缺失：400 CLUE_PHONE_MISSING
       4. ClueIntegrationService.lookupLeadByPhone(phone)
          - 获取完整 LeadRecord
       5. 如果 resource.customerPhone 为空，则回填手机号
       6. upsert lead_records：
          - customer_phone
          - crm_row_id
          - lead_data
          - leads_type
          - leads_status
          - crm_synced_at
  <- { linked: true, rowId }
```

### 6.4 新建线索并绑定

```text
APP
  -> POST /api/v2/resources/{resourceId}/create-lead
  -> ResourceV2Controller.createLead
       1. ResourceQueryService.get 校验资源和权限
       2. 校验 resource.customerPhone 非空
       3. ClueIntegrationService.createLeadForNewCustomer(phone, customerName, currentAgent)
          a. CRM addRow(leads_bank)
             - 手机号、家长姓名、线索日期、默认线索类型 Type D
             - 分配时间
             - TMK、跟进员工
             - 首次录入渠道、最新录入渠道、最新到访渠道
          b. 成功后按手机号重新 query CRM 拿完整记录
          c. upsert lead_records
          d. CRM 失败时创建 local-only LeadRecord，rowId 为空
  <- { linked: true, rowId, clue }
```

### 6.5 人工发送消息

```text
APP
  -> POST /api/v2/conversations/{id}/messages
     Header: Idempotency-Key
  -> MessageCommandService.send
       1. 校验 Idempotency-Key 非空
       2. 按 client_request_id 查询是否已发送
          - 已存在且同会话：直接返回已有消息
          - 已存在但不同会话：409 IDEMPOTENCY_CONFLICT
       3. conversation 加锁读取
       4. V2AccessService.requireReply
          - 必须能查看会话
          - 必须有 CHAT_REPLY_ASSIGNED
          - 必须能回复当前 assignedAgentId
       5. 校验会话未关闭
       6. resource 加锁读取
       7. 校验 24h 回复窗口未关闭
       8. 校验文本或媒体参数
       9. 写 chat_message，状态 PENDING
       10. 更新 conversation.lastMessageAt、resource.lastMessageAt、resource.lastAgentMessageAt
       11. 写 MESSAGE_CREATED realtime_event
       12. 事务提交后：
           - SendMessageService 发送 WhatsApp 文本/媒体
           - CrmOpenApiService.addChatRecord 写 CRM 聊天记录
  <- 202 Accepted + SendMessageResult
```

说明：

- WhatsApp 发送发生在事务提交之后，避免外部请求成功但本地事务回滚。
- CRM 聊天记录同步失败只记录 warn，不中断主流程。
- 消息真正发送状态由 `SendMessageServiceImpl` 后续更新本地消息状态。

### 6.6 会话转接

```text
APP
  -> POST /api/v2/conversations/{id}/transfer
     { targetAgentId, reason, expectedVersion }
  -> ConversationCommandService.transfer
       1. 校验 RESOURCE_ASSIGN 权限
       2. conversation for update
       3. access.requireView 校验可查看
       4. 校验 expectedVersion，冲突返回 409 VERSION_CONFLICT
       5. 校验会话未关闭
       6. 校验 targetAgentId 非空且不能等于当前负责人
       7. resource for update
       8. 当前 SERVING assignment 标记 TRANSFERRED、replyable=false、closedAt=now
       9. flush assignment，避免唯一约束/活动记录冲突
       10. 新建 assignment_record：
           - assignType=TRANSFER
           - agentId=targetAgentId
           - assignedBy=currentUser
       11. 更新 resource.assignedAgentId、assignedAt、resourceStatus=ASSIGNED
       12. 更新 conversation.assignedAgentId、status=HUMAN_ACTIVE
       13. UnreadService.initializeForAssignment 初始化目标坐席未读状态
       14. syncCrmTransfer：
           - CRM closeServingAssignment(customerPhone)
           - CRM addAssignmentRecord(customerPhone,targetAgentId,"服务中")
           - 查询或创建 lead
           - updateLeadOnAssignment(rowId,targetAgentId)
       15. 写 ASSIGNMENT_CHANGED / CONVERSATION_UPDATED 给新旧坐席
  <- ConversationDetail
```

### 6.7 会话关闭

```text
APP
  -> POST /api/v2/conversations/{id}/close
     { reasonCode, remark, expectedVersion }
  -> ConversationCommandService.close
       1. conversation for update
       2. access.requireView
       3. 如果已 CLOSED，直接返回
       4. 校验 expectedVersion
       5. resource for update
       6. conversation.status=CLOSED
       7. 写 closedAt、closedBy、closeReason
       8. 当前 SERVING assignment 标记 CLOSED、replyable=false
       9. resource.resourceStatus：
          - RESOLVED -> RESOLVED
          - INVALID -> INVALID
          - 其他 -> CLOSED
       10. CRM closeServingAssignment(customerPhone)，失败只 warn
       11. 写 CONVERSATION_UPDATED 给当前负责人
  <- ConversationDetail
```

### 6.8 实时事件投递和补偿

```text
业务服务
  -> RealtimeEventService.append(...)
       写 realtime_event，包含 eventType、targetAgentId、payloadJson、aggregateVersion

定时任务 RealtimeEventPublisher.publish
  -> repo.lockUnpublished(...)
  -> socket.convertAndSendToUser(targetAgentId, "/queue/events", envelope)
  -> 成功：publishedAt=now
  -> 失败：publishAttempts + 1

APP 重连/补偿
  -> GET /api/v2/realtime/events?after={eventId}&size=...
  -> RealtimeEventService.recover(currentAgent, after, size)
       - after 不存在或不属于当前 agent：410 EVENT_CURSOR_EXPIRED
       - 返回该 agent 后续事件 CursorPage
```

事件类型：

| 事件 | 触发点 | 用途 |
|---|---|---|
| `MESSAGE_CREATED` | 人工发送消息、入站/AI 编排等消息创建场景 | 前端追加/刷新消息 |
| `ASSIGNMENT_CHANGED` | 转接或分配变更 | 前端更新负责人和列表归属 |
| `CONVERSATION_UPDATED` | 转接、关闭、状态变化 | 前端刷新会话详情、replyable、资源状态 |
| `UNREAD_COUNT_CHANGED` | 未读状态变化 | 前端更新未读数 |

---

## 7. 外部微服务调用关系

### 7.1 CRM OpenAPI

适配入口：`CrmOpenApiService` / `CrmOpenApiServiceImpl`

| 方法 | 用途 |
|---|---|
| `verifyLogin` | 登录校验 |
| `addAgentLoginRecord` / `updateAgentLoginStatus` | 坐席登录/离线状态写 CRM |
| `addAssignmentRecord` | 新增客户分配记录 |
| `closeServingAssignment` | 关闭当前服务中的分配记录 |
| `updateServingAssignmentReplyable` | 更新当前分配是否可回复 |
| `addChatRecord` | 写聊天记录到 CRM |
| `openAiReception` / `closeAiReception` / `assignAiReception` | AI 接待池状态维护 |
| `frontendAddRow` | 面向前端的通用 CRM 新增 |
| `frontendGetFilterRows` | 通用 CRM 查询 |
| `frontendEditRow` | 通用 CRM 更新 |
| `frontendDeleteRow` | 通用 CRM 删除 |
| `getWorksheetInfo` | 查询工作表字段定义 |
| `getRowByRowId` | 按 rowId 查询单条线索 |

客户中心主要使用的 CRM worksheet：

| worksheetId | 说明 |
|---|---|
| `leads_bank` | 线索管理表 |
| `imzhgl` | IM 坐席登录账号表，用于 TMK/跟进员工账号映射 |

### 7.2 WhatsApp Cloud API

适配入口：`SendMessageServiceImpl`

| 调用场景 | 说明 |
|---|---|
| 文本发送 | `MessageCommandService` 事务提交后调用 `sendTextMessage` |
| 媒体发送 | `MessageCommandService` 事务提交后调用 `sendMediaMessage` |
| 媒体上传 | 媒体接口上传文件到 Meta 后返回 `mediaId` |
| webhook 入站 | `WhatsAppWebhookController` 接收，`WhatsAppWebhookServiceImpl` 编排资源、会话、消息、AI、分配和推送 |

### 7.3 Dify AI

适配入口：`DifyAiServiceImpl` / `AiOrchestrationService`

当前客户中心侧主要感知结果：

- 客户入站后如进入 AI 接待或兜底回复链路，系统生成 AI 消息。
- AI 消息与人工消息一样写入 `chat_message`，并通过实时事件或旧版 WebSocket 推给坐席。
- AI 接待池状态会通过 CRM `openAiReception` / `assignAiReception` / `closeAiReception` 维护。

---

## 8. 权限与数据域设计

### 8.1 认证

- `/api/auth/login` 和 `/webhook` 允许匿名访问。
- `/api/**` 统一要求 JWT。
- `/api/v2/**` 错误统一返回 `V2Api.Failure` 格式，包含 `requestId`。

### 8.2 角色与 scope

| 角色 | 可用 scope | 说明 |
|---|---|---|
| `TMK` | `mine` | 只能看自己负责的会话 |
| `MANAGER` | `mine`、`managed` | 可看自己及 `managedAgentIds` 内坐席会话 |
| `OWNER` | `all` | 可看全量 |

### 8.3 查看和回复规则

| 能力 | 规则 |
|---|---|
| 查看会话 | OWNER 需 `CHAT_VIEW_ALL`；MANAGER 需 `CHAT_VIEW_MANAGED` 且负责人是自己或下属；TMK 需 `CHAT_VIEW_OWN` 且负责人是自己 |
| 回复会话 | 先满足查看权限，再要求 `CHAT_REPLY_ASSIGNED`，且 `canReply(user, assignedAgentId)` 为 true |
| 指定坐席过滤 | `assignedAgentId` 必须在当前 `scope` 范围内，否则 403 |
| 转接 | 必须有 `RESOURCE_ASSIGN`，并能查看当前会话 |

---

## 9. 可靠性与降级策略

### 9.1 CRM 降级

| 场景 | 当前策略 |
|---|---|
| 按手机号查询线索 | CRM first；CRM 异常时读本地 `lead_records` |
| 创建线索 | CRM first；CRM 失败时创建 local-only LeadRecord，`rowId` 为空 |
| 更新线索分配字段 | CRM first；失败时尝试更新本地 `lead_records.lead_data` |
| 字段配置 | CRM first + 1 小时缓存；失败时使用 `leads_bank` 内置字段配置 |
| 聊天记录同步 CRM | 失败只 warn，不影响本地消息发送主流程 |
| 转接/关闭同步 CRM 分配 | 失败只 warn，不回滚本地转接/关闭 |

### 9.2 实时事件可靠性

| 能力 | 当前策略 |
|---|---|
| 事件持久化 | 业务事务内写 `realtime_event` |
| 推送 | 定时任务扫描未发布事件并通过 WebSocket 发送 |
| 失败重试 | 增加 `publishAttempts`，后续扫描继续尝试 |
| 客户端补偿 | 前端可用 `after=eventId` 拉取当前坐席后续事件 |
| 游标失效 | 事件不存在或不属于当前坐席时返回 `EVENT_CURSOR_EXPIRED` |

### 9.3 幂等与并发

| 场景 | 机制 |
|---|---|
| 前端重复点击发送 | `Idempotency-Key` -> `chat_message.client_request_id` 唯一约束 |
| webhook 重复投递 | `external_message_id` 唯一约束 |
| 转接/关闭并发 | `expectedVersion` + `@Version` + `findByIdForUpdate` |
| 分配记录切换 | 先关闭旧 SERVING assignment 并 flush，再插入新 assignment |

---

## 10. 关键字段映射

### 10.1 `leads_bank` 字段

| 业务字段 | controlId | 当前用途 |
|---|---|---|
| 线索日期 | `66c1e299666ad6264b6f5e15` | 创建线索时写入 |
| 家长姓名 | `66bdb9a46e5c3bc8e0c7df9a` | 客户名/联系人 |
| 孩子姓名 | `66b1f86d9d2c721e325fac78` | 客户资料展示 |
| 手机号 | `687fa4dd005dfd294df9dc3e` | 线索查询、绑定、唯一客户标识 |
| 校区 | `66eeb5b0f53d52846e007a35` | 客户资料/预约 |
| 联系状态 | `66b36b8cce042770da7218b0` | 线索状态展示 |
| 线索状态 | `66b5e34a7e23d13674f24129` | 会话列表筛选、本地缓存字段 |
| 线索类型 | `681c86c01e19a610d7200418` | 会话列表筛选、本地缓存字段，默认 `Type D` |
| 首次录入渠道 | `67d3f3f3286831392e292f7a` | 创建线索时写 Meta 渠道 |
| 意向科目 | `66b310829b545d2337ac4433` | 客户资料展示 |
| 学校 | `66b3692d3e774217ade72e25` | 客户资料/预约 |
| 年级 | `66b30ef13e774217ade66e77` | 客户资料/预约 |
| 备注 | `6736e7c6f53d52846e00b0a3` | 客户资料展示 |
| 线索分配时间 | `66bb90bece042770da7b7041` | 创建线索时写入 |
| TMK | `68c252c0b75138cd755fb620` | 分配/转接时更新 |
| 跟进员工 | `66b3692d3e774217ade72e29` | 分配/转接时更新 |
| 到访日期 | `6836a4ef811c335bfbcdf342` | 客户资料/预约 |
| 是否已到访 | `68382b94811c335bfbcdf7ac` | 客户资料 |
| 到访状态 | `683edab9811c335bfbce53eb` | 客户资料/预约 |
| 是否已成交 | `68383410811c335bfbcdf7c9` | 客户资料 |
| 成交日期 | `683832d8811c335bfbcdf7bf` | 客户资料 |
| 成交金额 | `6836a787811c335bfbcdf35a` | 客户资料 |

### 10.2 渠道映射

| 字段 | 当前值 |
|---|---|
| Meta 渠道 SID | `80f32937-d16d-4d82-8d0c-739b596cfb39` |
| 默认线索类型 | `Type D` |
| CRM 时间格式 | `yyyy-M-d HH:mm:ss` |

---

## 11. 配置项

| 配置 | 说明 |
|---|---|
| `crm.clue.worksheet-id` | `ClueController` 默认线索工作表，默认 `leads_bank` |
| `crm.worksheet-field-whitelist` | 允许查询字段配置的 worksheet 白名单，默认 `leads_bank` |
| `integration.schedulers-enabled` | 是否启用调度器，包括实时 outbox 发布等，默认启用 |
| `realtime.outbox.delay-ms` | 实时事件发布扫描间隔，默认 1000ms |

---

## 12. 客户中心端到端链路总览

### 12.1 页面首次打开

```text
1. 前端登录后拿 JWT
2. GET /api/v2/conversations?scope=mine
3. 前端展示客户列表、未读数、replyable、最后消息
4. 用户点击会话
5. GET /api/v2/conversations/{id}
6. GET /api/v2/conversations/{id}/messages
7. GET /api/v2/resources/{resourceId}/crm-profile
8. 前端展示右侧客户信息面板
9. 建立 WebSocket 订阅 /user/queue/events
10. 如重连，GET /api/v2/realtime/events?after=lastEventId
```

### 12.2 客户发消息后

```text
Meta Webhook
  -> WhatsAppWebhookController
  -> WhatsAppWebhookServiceImpl
       - 去重并写 chat_message
       - 更新 resource/conversation 最后消息时间
       - 分配或保持当前负责人
       - 必要时触发 AI 回复
       - 写 CRM 聊天记录/分配/AI 接待池
       - 写实时事件或旧版推送
APP
  <- WebSocket event
  -> 更新列表、消息、未读、回复窗口
```

### 12.3 坐席回复后

```text
APP
  -> POST /api/v2/conversations/{id}/messages
AITMK-Service
  -> 本地消息落库
  -> 写 MESSAGE_CREATED outbox
  -> 事务后发送 WhatsApp
  -> 尝试写 CRM 聊天记录
APP
  <- WebSocket MESSAGE_CREATED
客户
  <- WhatsApp message
```

---

## 13. 当前实现边界

1. 客户中心本地资源以 `customer_phone` 唯一，当前不支持一个手机号多资源并行。
2. 会话列表线索筛选依赖 `lead_records` 本地缓存，CRM 数据如果尚未同步到本地，列表筛选可能短暂不包含最新 CRM 状态。
3. `crm-profile` 的 linked 判断依赖查到的 `LeadRecord.rowId`，local-only 线索虽然有本地数据，但没有 CRM rowId 时不能视为完整 CRM 绑定。
4. 转接不会强依赖目标坐席在线，核心校验是权限、版本、会话未关闭和目标坐席 ID 有效性。
5. CRM、WhatsApp、Dify 均为外部依赖；当前主流程尽量保证本地事实先落库，外部同步失败以日志和降级为主。
6. 实时事件投递是 outbox 模式，前端必须支持事件恢复接口，不能只依赖 WebSocket 在线推送。

---

## 14. 建议测试点

| 测试项 | 目标 |
|---|---|
| 会话列表 scope | TMK/Manager/Owner 在 `mine/managed/all` 下不能越权 |
| 线索筛选 | `leadType`、`leadStatus` 与 `lead_records` 缓存一致 |
| 回复窗口 | `last_customer_message_at` 超过 24h 后 `replyable=false` 且发送返回 `REPLY_WINDOW_CLOSED` |
| 幂等发送 | 同一 `Idempotency-Key` 重试返回同一消息，不重复发送 |
| 转接 | 旧 assignment 关闭、新 assignment 创建、目标未读初始化、CRM 分配和线索 TMK 更新 |
| 关闭 | conversation/assignment/resource 状态一致，CRM 服务中记录关闭 |
| crm-profile | CRM 成功、本地 fallback、字段配置 fallback 三种路径 |
| 实时事件 | WebSocket 推送失败后可通过 `/api/v2/realtime/events` 补偿 |

