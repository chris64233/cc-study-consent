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

### 5. 研究暂停与恢复

- 研究负责人可因**外部安全事件**暂停**整个研究**或**某类活动**：暂停决定携带本决定的外部事件号、外部安全事件号、原因、生效时间与范围（活动类型集合为空即整个研究）。
- 暂停决定生效后，对应范围内的**新活动一律不得登记**（含事后补录），授权解释以 `STUDY_SUSPENDED` 拒绝；**暂停不追溯**——若范围内已存在发生时间 ≥ 暂停生效时间的合法活动，暂停决定被拒绝（409），已发生的活动保持有效。
- 活动级暂停只拦截其声明的活动类型，范围外活动照常登记。
- **恢复申请**必须引用一条**当前仍生效的暂停决定**（按外部事件号），并声明恢复所依据的方案版本（必须是研究当前版本）；恢复只解除其引用的那一次暂停，不影响其它在效暂停，且不能重复恢复。
- **暂停期间的方案变更**：
  - 暂停期间发布了**实质变更**版本：恢复后，在该暂停覆盖范围内、仍停留在旧同意上的参与者必须**对当前版本重新签署**才能恢复授权（拒绝码 `RECONSENT_REQUIRED`）；参与者也可以在暂停期间先行重新签署，恢复后即刻恢复授权；
  - 暂停期间仅有**非实质变更**：恢复后仍按"双方共有活动"规则判断，无需重新签署；
  - 活动级暂停下，重新签署要求只适用于被暂停覆盖的活动类型，未暂停的活动按常规版本规则判定。
- 恢复本身不改变任何个人授权状态：撤回同意的参与者不会因恢复而重新获得授权。
- 暂停/恢复决定与签署、撤回、活动一样使用外部事件号：**同号同内容重放返回原决定（幂等），同号异内容冲突（409）**；决定一经写入不可修改、不可删除。
- **并发安全**：暂停/恢复决定持研究行级写锁；活动登记按"参与者锁 → 研究锁"的固定顺序加锁，与暂停决定在研究锁上互斥。因此"暂停在 T 生效"与"发生于 T 的活动被接受"不可能同时成立；旧恢复请求不能越过后来生效的新暂停。

### 6. 授权状态、时间线与解释

- `GET …/authorization/status`：当前授权快照，按当前版本允许的每种活动逐项给出允许/拒绝及原因。
- `GET …/timeline`：版本发布、暂停/恢复决定、同意事件、活动记录合并排序的**完整时间线**，每条活动附带授权说明。
- `GET …/authorization/explain?activityType=&at=`：解释某活动在某时间点为何允许或拒绝。
- `GET /api/studies/{studyCode}/suspensions`：研究暂停/恢复决定的完整、不可修改时间线。

拒绝原因码（`DenialReason`）：

| 原因 | 含义 |
| --- | --- |
| `NO_VERSION_PUBLISHED` | 该时间点方案尚未发布任何版本 |
| `ACTIVITY_NOT_ALLOWED_IN_VERSION` | 活动不在生效版本允许范围内（如已被移除）——方案范围不符 |
| `NEVER_CONSENTED` | 尚未签署同意 |
| `CONSENT_WITHDRAWN` | 同意已撤回 |
| `ACTIVITY_NOT_SELECTED` | 同意未选择该活动 |
| `SUBSTANTIVE_VERSION_PUBLISHED` | 签署版本后发布过实质变更，须重新签署 |
| `STUDY_SUSPENDED` | 活动发生时研究（或该类活动）因安全事件处于暂停中——研究暂停 |
| `RECONSENT_REQUIRED` | 覆盖该活动的暂停期间发布过实质变更版本，恢复后尚未对当前版本重新同意 |

授权判定按固定顺序给出**唯一**拒绝原因：无版本 → 范围不符 → 研究暂停 → 未签署/已撤回 → 未选择 → 实质变更失效 / 暂停期重新同意。

### 7. 时间可替换

业务代码不直接调用 `Instant.now()`，而使用 `com.chris64233.cc.studyconsent.clock.Clock`：

- 生产环境注入 `SystemClock`；
- 测试注入 `MutableClock`，可定点、可推进，用于验证历史时间点授权与并发场景。

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/studies` | 创建研究 |
| POST | `/api/participants` | 创建参与者 |
| POST | `/api/studies/{studyCode}/versions` | 发布方案版本 |
| POST | `/api/studies/{studyCode}/suspensions` | 因安全事件暂停整个研究或某类活动 |
| POST | `/api/studies/{studyCode}/resumptions` | 恢复申请（引用暂停事件号并声明方案版本） |
| GET | `/api/studies/{studyCode}/suspensions` | 暂停/恢复决定时间线 |
| POST | `/api/studies/{studyCode}/participants/{participantCode}/consents` | 签署同意 |
| POST | `…/withdrawals` | 撤回全部同意 |
| POST | `…/activities` | 记录活动（授权失败返回 403） |
| GET | `…/authorization/status` | 当前授权状态 |
| GET | `…/authorization/explain` | 活动授权解释 |
| GET | `…/timeline` | 完整时间线 |

签署/撤回/活动请求均支持显式指定业务时间（`signedAt` / `withdrawAt` / `occurredAt`），暂停/恢复支持 `effectiveAt`，缺省使用 Clock。

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
# 因安全事件暂停整个研究（activityTypes 省略或为空）；仅暂停某类活动则给出类型集合
curl -X POST localhost:8080/api/studies/S1/suspensions -H 'Content-Type: application/json' \
  -d '{"externalEventId":"sus-1","externalIncidentId":"INC-2026-042","reason":"严重不良事件调查","activityTypes":["BLOOD"]}'
# 恢复：引用暂停事件号，声明当前方案版本
curl -X POST localhost:8080/api/studies/S1/resumptions -H 'Content-Type: application/json' \
  -d '{"externalEventId":"res-1","suspendEventId":"sus-1","declaredVersionNo":1}'
curl 'localhost:8080/api/studies/S1/participants/P1/authorization/explain?activityType=INTERVIEW'
```

## 测试

- `VersionPublishingIntegrationTest`：版本递增、变更类型校验、快照不可变、并发发布。
- `ConsentLifecycleIntegrationTest`：签署范围、幂等/冲突、实质与非实质变更、按发生时间点授权、撤回后重签。
- `WithdrawActivityConcurrencyIntegrationTest`：撤回/活动时间点规则，以及同刻并发对决（重复 20 次）不变量。
- `SuspensionLifecycleIntegrationTest`：暂停范围与不追溯、恢复引用与版本声明、暂停期实质变更须重新同意、非实质变更按共有活动、旧恢复不能越过新暂停、撤回者不被恢复重新授权、幂等/冲突与时间线。
- `SuspensionConcurrencyIntegrationTest`：暂停 vs 活动、恢复 vs 新暂停的同刻并发对决（各重复 20 次）。
- `TimelineAndStatusQueryIntegrationTest`：时间线、状态快照、允许/拒绝解释。
- `ConsentControllerIntegrationTest`：REST 端到端（400/403/409、幂等）。

## 数据存储

JPA 实体 + H2，关键唯一约束：

- `uk_study_version_no(study_id, version_no)`：同研究版本号唯一；
- `uk_consent_external_event(external_event_id)`：同意外部事件号全局唯一；
- `uk_activity_external_event(external_event_id)`：活动外部事件号全局唯一；
- `uk_suspension_external_event(external_event_id)`：暂停/恢复决定外部事件号全局唯一；
- 版本活动/同意活动/暂停活动集合表对（所属 id, activity_type）唯一。
