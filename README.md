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

### 4. 撤回与活动记录

- 参与者可在**指定时间**（或当前时间）撤回对某研究的**全部同意**；撤回事件同样使用外部事件号、支持幂等。
- 研究活动必须在其**发生时间点**存在有效授权才允许落库；被拒绝的活动不产生记录。
- 活动记录创建时快照授权依据（同意事件 id、生效版本号），且**不可修改、不可删除**。
- **并发安全**：同一参与者的签署、撤回、活动创建共用一把参与者行级写锁并在同一事务内完成判定与写入，二者严格串行。
  - 活动先落库：若撤回生效时间 ≤ 任一已接受活动的发生时间，撤回被拒绝（409），避免撤回追溯否定已合法接受的活动；
  - 撤回先生效：授权评估能看到撤回事件，该时点及之后的活动被拒绝（403）。
  - 因此"撤回生效后仍被接受的活动"不可能出现。

### 5. 授权状态、时间线与解释

- `GET …/authorization/status`：当前授权快照，按当前版本允许的每种活动逐项给出允许/拒绝及原因。
- `GET …/timeline`：版本发布、同意事件、活动记录合并排序的**完整时间线**，每条活动附带授权说明。
- `GET …/authorization/explain?activityType=&at=`：解释某活动在某时间点为何允许或拒绝。

拒绝原因码（`DenialReason`）：

| 原因 | 含义 |
| --- | --- |
| `NO_VERSION_PUBLISHED` | 该时间点方案尚未发布任何版本 |
| `ACTIVITY_NOT_ALLOWED_IN_VERSION` | 活动不在生效版本允许范围内（如已被移除） |
| `NEVER_CONSENTED` | 尚未签署同意 |
| `CONSENT_WITHDRAWN` | 同意已撤回 |
| `ACTIVITY_NOT_SELECTED` | 同意未选择该活动 |
| `SUBSTANTIVE_VERSION_PUBLISHED` | 签署版本后发布过实质变更，须重新签署 |

### 6. 时间可替换

业务代码不直接调用 `Instant.now()`，而使用 `com.chris64233.cc.studyconsent.clock.Clock`：

- 生产环境注入 `SystemClock`；
- 测试注入 `MutableClock`，可定点、可推进，用于验证历史时间点授权与并发场景。

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/studies` | 创建研究 |
| POST | `/api/participants` | 创建参与者 |
| POST | `/api/studies/{studyCode}/versions` | 发布方案版本 |
| POST | `/api/studies/{studyCode}/participants/{participantCode}/consents` | 签署同意 |
| POST | `…/withdrawals` | 撤回全部同意 |
| POST | `…/activities` | 记录活动（授权失败返回 403） |
| GET | `…/authorization/status` | 当前授权状态 |
| GET | `…/authorization/explain` | 活动授权解释 |
| GET | `…/timeline` | 完整时间线 |

签署/撤回/活动请求均支持显式指定业务时间（`signedAt` / `withdrawAt` / `occurredAt`），缺省使用 Clock。

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
curl 'localhost:8080/api/studies/S1/participants/P1/authorization/explain?activityType=INTERVIEW'
```

## 测试

- `VersionPublishingIntegrationTest`：版本递增、变更类型校验、快照不可变、并发发布。
- `ConsentLifecycleIntegrationTest`：签署范围、幂等/冲突、实质与非实质变更、按发生时间点授权、撤回后重签。
- `WithdrawActivityConcurrencyIntegrationTest`：撤回/活动时间点规则，以及同刻并发对决（重复 20 次）不变量。
- `TimelineAndStatusQueryIntegrationTest`：时间线、状态快照、允许/拒绝解释。
- `ConsentControllerIntegrationTest`：REST 端到端（400/403/409、幂等）。

## 数据存储

JPA 实体 + H2，关键唯一约束：

- `uk_study_version_no(study_id, version_no)`：同研究版本号唯一；
- `uk_consent_external_event(external_event_id)`：同意外部事件号全局唯一；
- `uk_activity_external_event(external_event_id)`：活动外部事件号全局唯一；
- 版本活动/同意活动集合表对（所属 id, activity_type）唯一。
