# cc-study-consent

管理研究方案版本、参与者同意和活动授权。

## 开发环境

- JDK 21
- Spring Boot 4.1.1
- Maven Wrapper 3.9.9
- H2

## 本地运行

启动服务：

    ./mvnw spring-boot:run

运行测试：

    ./mvnw clean test

## 主要业务规则

### 方案版本

- 版本按严格递增的序号发布（在研究行悲观锁内取当前最大版本号 +1），同一研究任意时刻只有一个当前版本（最大版本号）。
- 每个版本声明允许的活动类型集合；首个版本无上一版本，不声明变更性质；后续每个版本必须标记相对上一版本是**实质变更（SUBSTANTIVE）**还是**非实质变更（NON_SUBSTANTIVE）**。
- 已发布版本不可修改：实体不提供修改入口，也不存在更新/删除接口。

### 同意签署与幂等

- 参与者只能对**当前版本**签署同意，并在方案允许范围内选择活动类型子集；选择超出范围即拒绝。
- 同意事件（签署与撤回）记录全局唯一的外部事件号（数据库唯一约束）与签署时间。
- 相同外部事件号且内容相同 → 幂等重放，返回已存在事件；内容不同 → 409 冲突。

### 版本演进下的授权迁移

判定某活动在某时间点是否被授权时，以该时间点之前最近一次有效签署为准：

- **非实质新版本**发布后，旧版本同意继续有效，但只能授权签署版本与之后各非实质版本**共有**的活动类型（取交集）。
- **实质新版本**发布后，旧同意立即不能授权任何新活动，必须重新签署当前版本。
- 撤回后重新签署的，以最新签署为准。

### 撤回与活动记录

- 参与者可在指定生效时间撤回全部同意；生效时间点及之后发生的活动一律被拒绝，之前的不受影响。
- 活动记录必须在**发生时间点**存在有效授权才会被接受，并记录授权依据的同意事件。
- 撤回与活动记录共用“参与者 × 研究”维度的悲观锁（`enrollments` 行），在同一事务内先锁后判定：撤回先提交时，阻塞中的活动记录事务在锁释放后重新评估，必然看到撤回，从而保证不会出现撤回生效后仍被接受的活动。
- 活动记录与同意事件均不可修改、不可删除（无更新/删除入口）。

### 查询与时间

- `GET .../authorization`：参与者在当前（或指定）时刻对当前版本各活动类型的授权状态及原因。
- `GET .../authorization/explain`：解释某活动类型在某时刻为何允许或拒绝。
- `GET .../timeline`：版本发布、签署、撤回、活动记录的完整时间线。
- 所有时间取自可替换的 `Clock` Bean（生产为系统 UTC 时钟，测试注入可变时钟）。

## API 概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/studies` | 创建研究 |
| POST | `/api/studies/{code}/versions` | 发布新版本 |
| GET | `/api/studies/{code}/versions` | 版本列表 |
| POST | `/api/studies/{code}/participants/{pid}/consents` | 签署当前版本同意 |
| POST | `/api/studies/{code}/participants/{pid}/withdrawals` | 指定时间撤回全部同意 |
| POST | `/api/studies/{code}/participants/{pid}/activities` | 记录研究活动（无有效授权返回 422 及原因） |
| GET | `/api/studies/{code}/participants/{pid}/authorization` | 授权状态 |
| GET | `/api/studies/{code}/participants/{pid}/authorization/explain` | 授权判定解释 |
| GET | `/api/studies/{code}/participants/{pid}/timeline` | 完整时间线 |
