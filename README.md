# cc-study-consent

管理研究方案版本、参与者知情同意与研究活动执行之间的授权关系。

## 开发环境

- JDK 21
- Spring Boot 4.1.1
- Maven Wrapper 3.9.9
- H2（开发默认文件库，测试使用内存库）

## 本地运行

启动服务：

    ./mvnw spring-boot:run

运行测试：

    ./mvnw clean test

## 主要业务规则

### 1. 研究方案版本

- 版本号在同一研究内从 1 开始**严格递增**；发布在数据库行级写锁（`PESSIMISTIC_WRITE`）+ 乐观锁保护下进行，并发发布不会产生重复或跳号。
- 每个版本声明**允许的活动类型集合**，并标记相对上一版本的变更类型：
  - `INITIAL`：首个版本；
  - `SUBSTANTIVE`：实质变更；
  - `NON_SUBSTANTIVE`：非实质变更。
- 已发布版本**不可修改、不可删除**（应用不提供更新入口，活动集合以快照存储）；同一研究同时只有一个**当前版本**（`Study.currentVersion`）。
- 参与者只能对**当前版本**签署同意。

### 2. 同意签署

- 同意事件携带调用方提供的**唯一外部事件号**（数据库唯一约束）并记录**签署时间**：
  - 相同事件号 + 相同内容（研究/参与者/类型/版本/活动集合/时间）→ **幂等**，返回既有事件；
  - 相同事件号 + 内容不同 → **冲突 409**。
- 选择的活动类型**不能超出**所签署版本的允许范围，且至少选择一项。
- 活动集合为签署时的快照，方案新版本不影响历史事件内容。

### 3. 版本变更对旧同意的影响

设评估时点为 T、生效版本为 T 时点已发布的最新版本：

- **非实质变更**：上一版本的有效同意继续授权**双方共有的活动**（活动同时存在于签署版本与生效版本、且在同意选择集合内）；新版本新增但未被选择的活动不授权。
- **实质变更**：旧同意立即失效，**不能再授权任何新活动**——即使活动类型在新旧版本中完全相同——参与者必须重新签署当前版本。
- 时间点语义：实质变更发布**之前**发生的活动（含事后补录）仍按当时的授权状态判定；发布之后才要求重新签署。
- 撤回后重新签署可恢复授权。

### 4. 撤回、暂停与活动记录

- 参与者可在**指定时间**（或当前时间）撤回对某研究的**全部同意**；撤回事件同样使用外部事件号、支持幂等。
- 研究负责人可因**安全事件**暂停**整个研究**或**某一类活动**：暂停决定记录外部事件号、原因与生效时间，支持研究级/活动级两种范围。
- 研究活动必须在其**发生时间点**存在有效授权才允许落库；被拒绝的活动不产生记录。
- 活动记录创建时快照授权依据（同意事件 id、生效版本号），且**不可修改、不可删除**。
- **暂停不追溯**：暂停生效时间点（含）之后若已有该范围内的合法活动落库，暂停决定被拒绝（409）；生效前已经合法发生的活动不受影响，仍可补录。暂停一经生效，对应范围内的新活动一律不得登记。
- **并发安全**：版本发布、暂停/恢复、签署、撤回、活动创建统一遵循**先研究行级写锁、再参与者行级写锁**的锁序，在同一事务内完成判定与写入，严格串行。
  - 活动先落库：若撤回/暂停生效时间 ≤ 任一已接受活动的发生时间，撤回/暂停被拒绝（409），避免追溯否定已合法接受的活动；
  - 撤回/暂停先生效：授权评估能看到该决定，该时点及之后的活动被拒绝（403）。
  - 因此"撤回/暂停生效后仍被接受的活动"不可能出现。

### 5. 恢复确认与暂停期间的版本变更

- 恢复申请必须**引用当前暂停决定**的外部事件号，并**声明恢复所依据的方案版本**（必须是研究当前版本）。
- 恢复是**参与者级**决定：一次恢复只关闭它引用的那次暂停、只对该参与者解除限制；其他参与者仍处于暂停中。
- 暂停期间若发布了**实质变更**版本，受影响参与者必须**先对当前版本重新签署**，恢复确认才能生效（否则 409）；暂停期间仅有**非实质变更**的，恢复后仍按"双方共有活动"规则授权。
- **同意已撤回**的参与者不能通过恢复确认重新获得授权，必须先重新签署当前版本。
- **旧恢复请求不得越过后来生效的暂停**：恢复时若存在比所引用暂停更新的生效暂停，请求被拒绝（409），必须引用当前暂停重新提交；重放旧恢复请求仅幂等返回原结果，不会关闭新暂停。

### 6. 授权状态、时间线与解释

- `GET …/authorization/status`：当前授权快照，按当前版本允许的每种活动逐项给出允许/拒绝及原因。
- `GET …/timeline`：版本发布、暂停/恢复决定、同意事件、活动记录合并排序的**完整时间线**，每条活动附带授权说明（时间线含该研究的全部暂停与该参与者自己的恢复确认）。
- `GET …/authorization/explain?activityType=&at=`：解释某活动在某时间点为何允许或拒绝。
- `GET /api/studies/{studyCode}/decisions`：研究暂停/恢复决定的完整、不可修改时间线。

拒绝原因码（`DenialReason`），按评估优先级（暂停与"暂停后尚未重新同意"优先于版本范围、同意状态判断）：

| 原因 | 含义 |
| --- | --- |
| `NO_VERSION_PUBLISHED` | 该时间点方案尚未发布任何版本 |
| `STUDY_SUSPENDED` | 研究整体或该类活动处于暂停中，恢复前不得登记新活动 |
| `RECONSENT_REQUIRED_AFTER_SUSPENSION` | 暂停期间发布过实质变更，尚未对当前版本重新签署 |
| `ACTIVITY_NOT_ALLOWED_IN_VERSION` | 活动不在生效版本允许范围内（如已被移除） |
| `NEVER_CONSENTED` | 尚未签署同意 |
| `CONSENT_WITHDRAWN` | 同意已撤回 |
| `ACTIVITY_NOT_SELECTED` | 同意未选择该活动 |
| `SUBSTANTIVE_VERSION_PUBLISHED` | 签署版本后（非暂停期间）发布过实质变更，须重新签署 |

### 7. 决定事件号与不可修改时间线

- 暂停与恢复决定共用同一张不可变表 `study_decision`，外部事件号在两类决定之间**全局唯一**：
  - 相同事件号 + 相同内容（类型/研究/参与者/范围/原因/引用暂停/依据版本/生效时间）→ **幂等**，返回原决定；
  - 相同事件号 + 内容不同 → **冲突 409**（数据库唯一约束兜底）。
- 所有决定只新增、不提供更新/删除入口，构成不可修改的时间线。

### 8. 时间可替换

业务代码不直接调用 `Instant.now()`，而使用 `com.chris64233.cc.studyconsent.clock.Clock`：

- 生产环境注入 `SystemClock`；
- 测试注入 `MutableClock`，可定点、可推进，用于验证历史时间点授权与并发场景。

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/studies` | 创建研究 |
| POST | `/api/participants` | 创建参与者 |
| POST | `/api/studies/{studyCode}/versions` | 发布方案版本 |
| POST | `/api/studies/{studyCode}/suspensions` | 暂停整个研究或某类活动 |
| GET | `/api/studies/{studyCode}/decisions` | 暂停/恢复决定时间线 |
| POST | `/api/studies/{studyCode}/participants/{participantCode}/consents` | 签署同意 |
| POST | `…/withdrawals` | 撤回全部同意 |
| POST | `…/resumptions` | 对当前暂停作出恢复确认 |
| POST | `…/activities` | 记录活动（授权失败返回 403） |
| GET | `…/authorization/status` | 当前授权状态 |
| GET | `…/authorization/explain` | 活动授权解释 |
| GET | `…/timeline` | 完整时间线 |

签署/撤回/活动请求均支持显式指定业务时间（`signedAt` / `withdrawAt` / `occurredAt`），
暂停/恢复支持 `effectiveAt`，缺省使用 Clock。

暂停请求体：`externalEventId`、`reason` 必填；`scopeType` 取 `STUDY`（默认）或
`ACTIVITY_TYPE`，后者须带 `activityType`。恢复请求体：`externalEventId`、
`suspensionExternalEventId`、`versionNo`（当前版本）必填。

### 快速示例

```bash
curl -X POST localhost:8080/api/studies -H 'Content-Type: application/json' \
  -d '{"studyCode":"S1","name":"睡眠研究"}'
curl -X POST localhost:8080/api/participants -H 'Content-Type: application/json' \
  -d '{"participantCode":"P1","name":"张三"}'
curl -X POST localhost:8080/api/studies/S1/versions -H 'Content-Type: application/json' \
  -d '{"changeType":"INITIAL","allowedActivityTypes":["INTERVIEW","BLOOD"]}'
curl -X POST localhost:8080/api/studies/S1/participants/P1/consents \
  -H 'Content-Type: application/json' \
  -d '{"externalEventId":"evt-1","activityTypes":["INTERVIEW"]}'
# 因安全事件暂停整个研究
curl -X POST localhost:8080/api/studies/S1/suspensions \
  -H 'Content-Type: application/json' \
  -d '{"externalEventId":"sec-1","scopeType":"STUDY","reason":"严重不良事件"}'
# 参与者基于当前版本确认恢复
curl -X POST localhost:8080/api/studies/S1/participants/P1/resumptions \
  -H 'Content-Type: application/json' \
  -d '{"externalEventId":"res-1","suspensionExternalEventId":"sec-1","versionNo":1}'
curl 'localhost:8080/api/studies/S1/participants/P1/authorization/explain?activityType=INTERVIEW'
```

## 测试

- `VersionPublishingIntegrationTest`：版本递增、变更类型校验、快照不可变、并发发布。
- `ConsentLifecycleIntegrationTest`：签署范围、幂等/冲突、实质与非实质变更、按发生时间点授权、撤回后重签。
- `WithdrawActivityConcurrencyIntegrationTest`：撤回/活动时间点规则，以及同刻并发对决（重复 20 次）不变量。
- `TimelineAndStatusQueryIntegrationTest`：时间线、状态快照、允许/拒绝解释。
- `ConsentControllerIntegrationTest`：REST 端到端（400/403/409、幂等）。
- `SuspensionLifecycleIntegrationTest`：研究级/活动级暂停、暂停不追溯、恢复须引用当前暂停并声明当前版本、暂停期间实质变更须重新签署、撤回者不得借恢复重新授权、旧恢复不得越过新暂停、决定幂等/冲突、时间线与状态解释。
- `SuspensionConcurrencyIntegrationTest`：暂停 vs 活动、恢复 vs 活动、恢复 vs 撤回的同刻并发对决（各重复 20 次），验证基于当前状态判断且无"暂停/撤回生效后仍获授权"。
- `SuspensionControllerIntegrationTest`：暂停/恢复 REST 端到端（400/403/409、幂等、决定时间线）。

## 数据存储

JPA 实体 + H2，关键唯一约束：

- `uk_study_version_no(study_id, version_no)`：同研究版本号唯一；
- `uk_consent_external_event(external_event_id)`：同意外部事件号全局唯一；
- `uk_activity_external_event(external_event_id)`：活动外部事件号全局唯一；
- `uk_decision_external_event(external_event_id)`：暂停/恢复决定的外部事件号全局唯一（两类决定共用）；
- 版本活动/同意活动集合表对（所属 id, activity_type）唯一。
