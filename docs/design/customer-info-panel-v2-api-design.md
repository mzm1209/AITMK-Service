# 右侧客户信息面板 — 后端 API 功能详细设计 v2

> 关联前端设计：右侧客户信息面板 — 完整前端功能设计 v2
> 设计日期：2026-06-28
> 后端服务：AITMK-Service (Spring Boot 3 + Java 21)

---

## 1. 整体数据流

```
前台右侧面板
  ├─ 打开资源 → GET /api/v2/resources/{id}/crm-profile
  │    ├─ linked=true  → 展示线索详情 + 编辑/预约入口
  │    └─ linked=false → 展示空状态 + 新建线索/绑定线索入口
  │
  ├─ 编辑线索 → PUT /api/leads/clues/{rowId}
  ├─ 新建线索 → POST /api/leads/clues (已有, ClueController.add)
  ├─ 绑定线索 → POST /api/v2/resources/{id}/link-lead
  ├─ 按手机号查线索 → GET /api/leads/clues/query/phone?phone=xxx
  │
  ├─ 预约管理 → POST /api/appointments + GET /api/appointments?resourceId=xxx
  │
  └─ 表单下拉选项 → GET /api/worksheets/{id}/fields
```

### 涉及数据表

| 表 | 用途 | 状态 |
|---|---|---|
| business_resource | 客户资源主表 | 已有 |
| lead_records | CRM 线索缓存 | 已有 (V6) |
| appointments | 预约记录 | **新增 (V7)** |
| CRM leads_bank | 远程线索管理表 | 已有 |

---

## 2. 端点详细设计

### 2.1 GET /api/v2/resources/{id}/crm-profile — 改造

**当前状态**：仅返回 `resourceId/customerPhone/customerName/crmStatus:"NOT_LINKED"`（ResourceV2Controller.java:22），未执行任何线索查询。

**改造目标**：返回完整线索绑定状态和线索数据，支撑前端面板的初始渲染。

#### 请求

```
GET /api/v2/resources/{resourceId}/crm-profile
Authorization: Bearer <token>
```

#### 响应

```json
{
  "success": true,
  "data": {
    "resourceId": "123",
    "customerPhone": "13800138000",
    "customerName": "张三",
    "linked": true,
    "rowId": "abc-def-123",
    "clue": {
      "rowId": "abc-def-123",
      "leadsDate": "2026-06-28",
      "parentName": "张三",
      "studentName": "张小明",
      "phone": "13800138000",
      "center": "上海校区",
      "contactedStatus": "已联系",
      "leadsStatus": "待跟进",
      "leadsType": "Type A",
      "firstCreatChannel": "[{\"sid\":\"...\"}]",
      "programInterest": "数学",
      "school": "第一小学",
      "grade": "三年级",
      "content": "备注信息",
      "assignedTime": "2026-06-28 10:00:00",
      "tmk": "[{\"accountId\":\"agent-001\"}]",
      "visitDate": "2026-07-01",
      "visit": "是",
      "visitStatus": "已到访",
      "pay": "是",
      "paymentDate": "2026-07-01",
      "paymentAmount": "5000"
    },
    "fieldsConfig": {
      "leadsStatus": {
        "controlId": "66b5e34a7e23d13674f24129",
        "type": 11,
        "label": "线索状态",
        "options": ["待跟进", "已联系", "无效"]
      },
      "center": {
        "controlId": "66eeb5b0f53d52846e007a35",
        "type": 27,
        "label": "校区",
        "options": ["上海校区", "北京校区"]
      }
    }
  },
  "requestId": "uuid"
}
```

#### 处理流程

```
ResourceV2Controller.crm(id)
  ├─ 1. ResourceQueryService.get(id, user) → 校验资源存在 + 访问权限
  ├─ 2. 从 resource.customerPhone 取手机号
  ├─ 3. ClueIntegrationService.lookupLeadByPhone(phone)
  │    ├─ CRM 查询 leads_bank (phone=xxx)
  │    │    ├─ 成功 → 解析 LeadRecord, 写入本地 lead_records
  │    │    └─ 失败 → 降级读本地 lead_records
  │    └─ 返回 Optional<LeadRecord>
  ├─ 4. 组装 fieldsConfig（调用 CRM getWorksheetInfo 或从缓存取）
  │    └─ 仅返回前端可编辑字段的配置（线索状态、校区、联系状态等）
  └─ 5. 返回 CrmProfileView
```

#### 关键实现细节

- **linked 判断**：`leadOpt.isPresent() && lead.getRowId() != null`（必须同时存在于本地缓存且有关联 CRM rowId，才视为已绑定）
- **fieldsConfig 缓存策略**：首次调用时向 CRM 查询 `leads_bank` 工作表字段定义，缓存在内存 Map 中（带 TTL 3600s）。字段定义变化频率极低，避免每次请求都调 CRM。
- **CRM 降级**：CRM 不可用时，本地 lead_records 仍有数据的依然返回 `linked=true`，但 `rowId` 可能为空。
- **权限**：沿用 `ResourceQueryService.get()` 的权限校验，不额外新增权限点。

#### 新增/改动文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `ResourceV2Controller.java` | 改造 | 替换 crm() 方法 |
| `CrmProfileView` (record) | 新增 | API 响应模型 |
| `FieldsConfigView` (record) | 新增 | 字段配置模型 |
| `WorksheetFieldService` | 新增 | CRM 字段配置缓存服务 |
| `CrmOpenApiService` | 新增方法 | `getWorksheetInfo(String worksheetId)` |

---

### 2.2 PUT /api/leads/clues/{rowId} — 已有（确认不变）

**状态**：已实现（ClueController.java:54）。

前端编辑线索后调用此接口，直接写 CRM。写入成功后前端可选刷新 `crm-profile` 接口获取最新数据。本地 `lead_records` 由后续的 `crm-profile` 查询时同步更新（CRM first 策略）。

#### 请求示例

```json
PUT /api/leads/clues/abc-def-123
{
  "controls": [
    {"controlId": "66b5e34a7e23d13674f24129", "value": "已联系", "valueType": 2},
    {"controlId": "66b36b8cce042770da7218b0", "value": "已联系", "valueType": 2}
  ],
  "triggerWorkflow": true
}
```

---

### 2.3 GET /api/leads/clues/query/phone — 已有（确认不变）

**状态**：已实现（ClueController.java:140）。

当前端点已支持 CRM first、本地 DB fallback，返回 `LeadRecord` 格式数据。前端绑定线索时用于按手机号搜索已有线索。

---

### 2.4 POST /api/v2/resources/{id}/link-lead — 新增

将 CRM 线索与本地资源绑定，写入 `lead_records` 表。这是客户信息面板「绑定已有线索」操作的后端接口。

#### 请求

```
POST /api/v2/resources/{resourceId}/link-lead
Authorization: Bearer <token>
Content-Type: application/json

{
  "rowId": "abc-def-123"
}
```

#### 响应

```json
{
  "success": true,
  "data": {
    "linked": true,
    "rowId": "abc-def-123"
  },
  "requestId": "uuid"
}
```

#### 处理流程

```
ResourceV2Controller.linkLead(id, request)
  ├─ 1. ResourceQueryService.get(id, user) → 校验资源存在 + 权限
  ├─ 2. 通过 CRM getFilterRows(leads_bank, filters=[rowid=request.rowId]) 查询线索
  │    ├─ 未找到 → 400 "CLUE_NOT_FOUND"
  │    └─ 找到 → 解析 LeadRecord, 提取 phone
  ├─ 3. 检查 phone 是否已被其他 resource 绑定 (lead_records.customer_phone 冲突)
  │    └─ 已有其他 resource 且 phone 不同 → 允许（同一客户可以有多条 resource）
  ├─ 4. 如果 resource.customerPhone 为空，用线索 phone 回填
  ├─ 5. 写入 lead_records（upsert by customerPhone）
  │    - customer_phone = clue.phone
  │    - crm_row_id = request.rowId
  │    - lead_data = 序列化 LeadRecord JSON
  │    - crm_synced_at = now
  └─ 6. 返回绑定成功
```

#### 关键实现细节

- **rowId 校验**：必须为 CRM leads_bank 工作表中真实存在的 rowId。查询失败返回 400。
- **幂等性**：同一 resource 重复调用同一 rowId 不会创建重复记录（customer_phone unique 约束 + upsert）。
- **换绑场景**：如果 resource 已绑定 line A，前端发送 link-lead with rowId B → 更新 lead_records.crm_row_id 和 lead_data，覆盖旧绑定。
- **phone 回填 resource**：仅当 `resource.customerPhone` 为 null 或空字符串时回填。已有 phone 的不覆盖（避免覆盖 WhatsApp 真实号码）。

#### 新增/改动文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `ResourceV2Controller.java` | 新增方法 | `linkLead()` |
| `LinkLeadRequest` (record) | 新增 | 请求 DTO |

---

### 2.5 POST /api/appointments + GET /api/appointments — 新增

客户信息面板的预约功能。前端在面板内新建/查看预约记录。

#### 2.5.1 数据表设计（Flyway V7）

```sql
CREATE TABLE appointments (
    id                BIGINT NOT NULL AUTO_INCREMENT,
    resource_id       BIGINT NOT NULL,
    title             VARCHAR(255) NOT NULL,
    appointment_time  DATETIME(6) NOT NULL,
    duration_minutes  INT NOT NULL DEFAULT 30,
    notes             TEXT,
    status            VARCHAR(32) NOT NULL DEFAULT 'SCHEDULED',
    created_by        VARCHAR(64),
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_appointment_resource FOREIGN KEY (resource_id) REFERENCES business_resource(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_appointments_resource ON appointments (resource_id, appointment_time);
```

status 枚举值：`SCHEDULED`（已预约）、`COMPLETED`（已完成）、`CANCELLED`（已取消）

#### 2.5.2 POST /api/appointments — 创建预约

```
POST /api/appointments
Authorization: Bearer <token>
Content-Type: application/json

{
  "resourceId": 123,
  "title": "试听课预约",
  "appointmentTime": "2026-07-01T14:00:00+08:00",
  "durationMinutes": 60,
  "notes": "客户希望试听数学课"
}
```

**响应**：
```json
{
  "success": true,
  "data": {
    "id": 1,
    "resourceId": 123,
    "title": "试听课预约",
    "appointmentTime": "2026-07-01T14:00:00+08:00",
    "durationMinutes": 60,
    "notes": "客户希望试听数学课",
    "status": "SCHEDULED",
    "createdBy": "agent-001",
    "createdAt": "2026-06-28T10:00:00Z",
    "updatedAt": "2026-06-28T10:00:00Z"
  },
  "requestId": "uuid"
}
```

**处理流程**：
1. 校验 `resourceId` 存在且有访问权限（通过 `ResourceQueryService.get()`)
2. 校验 `appointmentTime` 为未来时间（至少比当前时间晚 5 分钟）
3. 校验 `durationMinutes` 在 5-480 分钟范围内
4. `createdBy` 从当前认证用户的 agentId 取值
5. 保存到数据库，返回 AppointmentView

#### 2.5.3 GET /api/appointments — 查询预约列表

```
GET /api/appointments?resourceId=123&status=SCHEDULED
Authorization: Bearer <token>
```

**查询参数**：
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| resourceId | long | 是 | 客户资源 ID |
| status | string | 否 | 过滤状态：SCHEDULED/COMPLETED/CANCELLED |

**响应**：
```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": 1,
        "resourceId": 123,
        "title": "试听课预约",
        "appointmentTime": "2026-07-01T14:00:00+08:00",
        "durationMinutes": 60,
        "notes": "客户希望试听数学课",
        "status": "SCHEDULED",
        "createdBy": "agent-001",
        "createdAt": "2026-06-28T10:00:00Z",
        "updatedAt": "2026-06-28T10:00:00Z"
      }
    ]
  },
  "requestId": "uuid"
}
```

#### 新增/改动文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `AppointmentEntity.java` | 新增 | JPA 实体 |
| `AppointmentRepository.java` | 新增 | JPA Repository |
| `AppointmentController.java` | 新增 | REST 控制器 |
| `AppointmentService.java` | 新增 | 业务逻辑 |
| `AppointmentView` (record) | 新增 | API 响应 DTO |
| `CreateAppointmentRequest` (record) | 新增 | 创建请求 DTO |
| `V7__add_appointments_table.sql` | 新增 | Flyway 迁移 |

---

### 2.6 GET /api/worksheets/{id}/fields — 新增

获取 CRM 工作表字段配置（下拉选项等），供前端的线索编辑表单使用。前端需要知道每个字段有哪些可选值（如下拉框、单选框的选项列表）。

#### 请求

```
GET /api/worksheets/leads_bank/fields
Authorization: Bearer <token>
```

**路径参数**：
| 参数 | 类型 | 说明 |
|------|------|------|
| id | string | CRM 工作表 ID，如 `leads_bank` |

#### 响应

```json
{
  "success": true,
  "data": {
    "worksheetId": "leads_bank",
    "worksheetName": "线索管理",
    "fields": [
      {
        "controlId": "66b5e34a7e23d13674f24129",
        "controlName": "线索状态",
        "dataType": 11,
        "options": [
          {"key": "待跟进", "value": "待跟进"},
          {"key": "已联系", "value": "已联系"},
          {"key": "无效", "value": "无效"}
        ]
      },
      {
        "controlId": "66eeb5b0f53d52846e007a35",
        "controlName": "校区",
        "dataType": 27,
        "options": [
          {"key": "sh-001", "value": "上海校区"},
          {"key": "bj-001", "value": "北京校区"}
        ]
      },
      {
        "controlId": "687fa4dd005dfd294df9dc3e",
        "controlName": "手机号",
        "dataType": 3,
        "options": []
      }
    ]
  },
  "requestId": "uuid"
}
```

#### 处理流程

```
WorksheetFieldController.getFields(worksheetId)
  ├─ 1. 校验 worksheetId 在白名单内 (leads_bank, ltjl, ltjl1...)
  ├─ 2. 调 CRM API: POST /api/v2/open/worksheet/getWorksheetInfo
  │    body: { appKey, sign, worksheetId }
  ├─ 3. 解析返回的 controls 数组
  │    - 提取 controlId, controlName, dataType
  │    - 对 type=11(下拉)/27(关联) 字段，提取 options
  │    - 对 type=2(文本)/3(手机)/8(数字) 字段，返回空 options
  ├─ 4. 缓存结果 (本地 Caffeine, TTL=3600s)
  └─ 5. 返回 WorksheetFieldsView
```

#### 关键实现细节

- **CRM API 路径**：`POST {crm.baseUrl}/api/v2/open/worksheet/getWorksheetInfo`
- **白名单 worksheetId**：防止前端查询未授权工作表（如 `imzhgl` 登录表）。配置在 `application.properties`：`crm.worksheet-field-whitelist=leads_bank,ltjl,ltjl1`
- **缓存 key**：`worksheetId`
- **CRM 不可用降级**：返回本地硬编码的白名单字段基本配置（即 `ClueIntegrationService` 中已知的 21 个 controlId 的基本信息），保证前端表单仍可渲染。
- **权限**：需要有效的坐席登录态，不额外做细粒度权限控制。

#### 新增/改动文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `WorksheetFieldController.java` | 新增 | REST 控制器 |
| `WorksheetFieldService.java` | 新增 | 字段配置服务（含缓存） |
| `WorksheetFieldsView` (record) | 新增 | API 响应 DTO |
| `CrmOpenApiService` | 新增方法 | `getWorksheetInfo(String worksheetId)` |
| `CrmOpenApiServiceImpl` | 新增方法 | 对应实现 |
| `application.properties` | 新增配置 | `crm.worksheet-field-whitelist` |

---

## 3. 文件总览

### 新增文件

```
src/main/java/com/example/aitmk/
├── controller/
│   ├── AppointmentController.java          # 预约 CRUD
│   └── WorksheetFieldController.java       # 工作表字段查询
├── service/
│   └── WorksheetFieldService.java          # 字段配置缓存服务
├── model/
│   ├── entity/
│   │   └── AppointmentEntity.java          # 预约 JPA 实体
│   ├── domain/
│   │   └── CreateAppointmentRequest.java   # 创建预约请求 DTO
│   └── api/v2/
│       └── (在 V2Api.java 追加 records)
│           ├── CrmProfileView              # crm-profile 响应
│           ├── LinkLeadRequest             # link-lead 请求
│           ├── AppointmentView             # 预约响应
│           ├── WorksheetFieldsView         # 字段配置响应
│           └── FieldConfigView             # 单个字段配置
├── repository/
│   └── AppointmentRepository.java          # 预约 Repository
└── resources/db/migration/
    └── V7__add_appointments_table.sql      # Flyway 迁移
```

### 改动文件

```
src/main/java/com/example/aitmk/
├── controller/v2/
│   └── ResourceV2Controller.java           # 改造 crm() + 新增 linkLead()
├── service/
│   └── CrmOpenApiService.java              # 新增 getWorksheetInfo 接口方法
├── service/impl/
│   └── CrmOpenApiServiceImpl.java          # 实现 getWorksheetInfo
├── service/v2/
│   └── ResourceQueryService.java           # 可选：提取 crmProfile 查询方法
└── resources/
    └── application.properties              # 新增 crm.worksheet-field-whitelist
```

---

## 4. 错误处理约定

所有新接口沿用在 v2 包下的 `V2Exception` + `V2ExceptionHandler`，使用 `V2Api.Response<T>` 和 `V2Api.Failure` 响应格式。

| 场景 | HTTP 状态码 | errorCode |
|------|------------|-----------|
| 资源不存在 | 404 | RESOURCE_NOT_FOUND |
| 无权访问资源 | 403 | ACCESS_DENIED |
| 线索 rowId 不存在 | 400 | CLUE_NOT_FOUND |
| 绑定的线索无手机号 | 400 | CLUE_PHONE_MISSING |
| 预约时间已过 | 400 | APPOINTMENT_TIME_PAST |
| 工作表不在白名单 | 400 | WORKSHEET_NOT_ALLOWED |
| CRM 不可用 | 502 | CRM_UNAVAILABLE |

---

## 5. 与前端交互时序

### 场景 A：打开客户信息面板（已绑定线索）

```
前端                    后端                           CRM
 │── GET /crm-profile ──→│                              │
 │                        │── lookupLeadByPhone(phone) ──→│
 │                        │←── LeadRecord ───────────────│
 │                        │── upsert local lead_records   │
 │                        │── getWorksheetInfo ──────────→│
 │                        │←── fields config ────────────│
 │←── {linked:true, clue, fieldsConfig} ──│
 │                        │                              │
 │─ 展示线索详情 + 编辑入口
```

### 场景 B：点击「绑定线索」按钮

```
前端                    后端                           CRM
 │── GET /clues/query/phone?phone=xxx ──→│              │
 │←── {rows: [...]} ────│                               │
 │                        │                              │
 │── 用户选择一条线索                                      │
 │                        │                              │
 │── POST /resources/{id}/link-lead {rowId} ──→│         │
 │                        │── getFilterRows(rowId) ──────→│
 │                        │←── clue row ─────────────────│
 │                        │── upsert lead_records        │
 │←── {linked:true} ─────│                               │
 │                        │                              │
 │── GET /crm-profile (刷新) ──→│                         │
 │←── {linked:true, clue} ─│                             │
```

### 场景 C：创建预约

```
前端                    后端
 │── POST /appointments {resourceId, title, time} ──→│
 │←── {id, status:"SCHEDULED"} ──│
 │                        │
 │── GET /appointments?resourceId=123 ──→│
 │←── {items: [...]} ────│
```

---

## 6. 设计决策记录

1. **fieldsConfig 为什么不直接放在 crm-profile 响应里，而要单独做一个 /worksheets/{id}/fields 端点？**
   - crm-profile 的 fieldsConfig 仅返回「当前线索工作表的可编辑字段」，是 crm-profile 响应的子集。
   - /worksheets/{id}/fields 是通用端点，未来可用于其他工作表（如分配记录编辑）。
   - crm-profile 里的 fieldsConfig 由 WorksheetFieldService 提供缓存支持，两者共享同一缓存。

2. **link-lead 为什么不在 ClueController 里做？**
   - link-lead 的核心操作是「绑定 resource ↔ clue」，属于 resource 维度的操作。
   - ResourceV2Controller 已有权限校验（`ResourceQueryService.get()` access control）。
   - 保持 REST 语义清晰：`/resources/{id}/...` 是资源子资源。

3. **预约为什么不写 CRM 而只写本地 DB？**
   - 当前 CRM 没有预约管理工作表（与线索管理 `leads_bank`、分配记录 `ltjl` 不同）。
   - 预约是 IM 工作台内部功能，写本地 DB 足以满足需求。
   - 后续如需同步 CRM，可通过 `AppointmentService` 追加 CRM 写入逻辑，不影响接口契约。

---

## 7. 实施顺序建议

| 优先级 | 端点 | 理由 |
|--------|------|------|
| P0 | GET /api/worksheets/{id}/fields | 被 crm-profile 依赖，且独立性强 |
| P0 | GET /api/v2/resources/{id}/crm-profile（改造） | 核心接口，前端面板主数据源 |
| P1 | POST /api/v2/resources/{id}/link-lead | 绑定线索核心操作 |
| P2 | POST /api/appointments + GET /api/appointments | 预约功能，相对独立 |
| — | PUT /api/leads/clues/{rowId} | 已有，确认即可 |
| — | GET /api/leads/clues/query/phone | 已有，确认即可 |
