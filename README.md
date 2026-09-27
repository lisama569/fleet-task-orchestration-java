# 机器人车队任务分配与执行编排

纯本地模拟的机器人车队任务编排服务：机器人与任务全部保存在内存 + 本地 JSON 快照中，
不接真实设备、不依赖外部服务，`mvn test` 或启动应用后即可自行验证。

## 领域模型与状态流转

### 任务状态机

```
                 submit
                   │
                   ▼
        ┌────── PENDING ────────────────┐
        │          │ schedule           │ cancel
        │          ▼                    ▼
        │       ASSIGNED ──(模拟即时确认)──▶ RUNNING ──┬─ reportResult(success) ─▶ SUCCEEDED
        │          │                       │  ├─ reportResult(failure) ─▶ FAILED
        │          │                       │  ├─ scanTimeouts 超时 ──────▶ TIMED_OUT
        │          │                       │  └─ cancel ────────────────▶ CANCELLED
        │          │                       │
        │          ◀── requeue ────────────┘  (机器人掉线/被替换时，任务交还队列)
        │
        └── 服务重启恢复：ASSIGNED / RUNNING ──▶ PENDING（占用全部释放，重新排队）
```

- 终结态：`SUCCEEDED / FAILED / TIMED_OUT / CANCELLED`，终结后不可逆、不会被重启"复活"。
- 所有状态推进幂等：重复取消、重复回报同一结果、迟到的过期回报（机器人或 attemptId 不匹配）
  都只返回当前状态，不产生二次副作用。
- 每次分配生成单调递增的 `attemptId`，回报必须携带匹配的 `robotId + attemptId` 才被接受，
  因此旧尝试的迟到回报不会污染新尝试。

### 机器人状态机

```
 register/replace        分配任务             任务终结/取消/超时/掉线
        │                   │                        │
        ▼                   ▼                        ▼
      IDLE ─────────────▶ BUSY ──────────────────▶ IDLE
        │                   │
        │ offline           │ offline（占用任务被 requeue）
        ▼                   ▼
                    OFFLINE（不参与分配；replace 可重新上线）
```

机器人同一时刻最多持有一个 `currentTaskId`（互斥占用）。所有涉及"机器人占用 ↔ 任务归属"
的变更都在同一把全局调度锁（`FleetLock`）内完成，因此并发提交/取消/回报混杂时两者不会矛盾。

## 分配与优先级规则

### 排队规则（`Scheduler.scheduleOnce`）

PENDING 任务按以下顺序排序，依次尝试分配：

1. **有效优先级**降序：`有效优先级 = 基础优先级 + 老化提优次数`；
2. **截止时间**升序（无截止时间的排最后）；
3. **提交时间**升序（先提交先服务）。

### 选机器人规则

在"空闲（IDLE）+ 能力匹配 + maxLoad ≥ 任务负载"的候选机器人中：

1. **best-fit**：`maxLoad` 最小者优先（把大机器人留给重任务）；
2. 并列时按 `robotId` 字典序（保证确定性）。

每次分配都会落一条 `Assignment` 记录，`explanation` 字段说明候选集与选择依据，
可通过 `GET /api/assignments` 查看，例如：

```
chosen=R1 among candidates=[R1(maxLoad=10), R2(maxLoad=20)] by best-fit(smallest maxLoad>=3,
tie-break robotId); capability=lift matched; effectivePriority=7(base=5+boost=2); deadline=null
```

### 老化提优（防止饿死）

PENDING 任务排队超过 `fleet.aging-threshold-millis`（默认 5000ms）即提升 1 级有效优先级，
最多提升 10 级。提优单调、幂等，由后台 `housekeeping` 周期（默认 1s）触发，
也可在测试中直接调用 `scheduler.boostAgedTasks()`。

### 拒绝原因（提交时区分）

提交任务时先做"是否可能被满足"的校验，不满足立即拒绝并抛出 `TaskRejectedException`，
REST 层返回 `409` + 可区分的 `rejectReason`：

| rejectReason | 含义 |
|---|---|
| `NO_ROBOT_ONLINE` | 当前没有任何在线机器人 |
| `NO_CAPABLE_ROBOT` | 有在线机器人，但没有任何一台具备任务要求的能力 |
| `ROBOT_LOAD_INSUFFICIENT` | 有能力匹配的机器人，但它们的 maxLoad 都小于任务负载 |
| `TASK_ALREADY_TERMINAL` | 同一 taskId 已终结，拒绝重复提交 |

注意区分：**机器人忙不是拒绝理由**。只要存在"能力 + 负载"都满足的在线机器人，
任务就进入 PENDING 排队等待，而不是被拒绝。

### 幂等约定

- **重复提交**同一 taskId：任务未终结 → 返回已存在任务（不产生重复分配）；已终结 → 拒绝。
- **重复取消 / 重复回报**：返回当前状态，无副作用。
- **重启恢复**：启动时从快照加载，ASSIGNED/RUNNING 回退为 PENDING、BUSY 机器人复位 IDLE，
  随后正常重新调度——同一任务同一时刻只会占用一台机器人，不会重复下发。

## 本地验证

### 运行单测（19 个用例）

```bash
mvn test
```

- `SubmissionTest`：各类拒绝原因、重复提交幂等、忙时排队；
- `LifecycleTest`：超时/取消状态流转、重复与过期回报幂等、老化提优、掉线与替换；
- `ConcurrencyTest`：32 线程重复提交同一任务只产生一次分配；40 任务抢 4 机器人无重复占用；
  提交/取消/回报混杂时占用与归属一致；
- `RecoveryTest`：崩溃重启后未完成任务恢复调度、终结任务不复活、机器人占用复位。

单测日志会打印 `[input]`（输入）与 `[decision]`（关键判定依据），调度器日志打印每次分配的
候选集与选择理由。

### 启动服务手动验证

```bash
mvn package -DskipTests
java -jar target/fleet-task-orchestration-java-1.0-SNAPSHOT.jar --server.port=8080
```

```bash
# 注册机器人
curl -X POST localhost:8080/api/robots/R1 -H 'Content-Type: application/json' \
  -d '{"capabilities":["lift"],"maxLoad":10}'

# 提交任务（立即被调度到 R1）
curl -X POST localhost:8080/api/tasks/T1 -H 'Content-Type: application/json' \
  -d '{"priority":5,"requiredCapability":"lift","requiredLoad":3,"timeoutMillis":60000}'

# 查看分配决策（含选择理由）
curl localhost:8080/api/assignments

# 回报结果 / 取消 / 机器人掉线 / 替换
curl -X POST localhost:8080/api/tasks/T1/result -H 'Content-Type: application/json' \
  -d '{"robotId":"R1","attemptId":1,"success":true,"detail":"done"}'
curl -X POST localhost:8080/api/tasks/T2/cancel
curl -X POST localhost:8080/api/robots/R1/offline
curl -X POST localhost:8080/api/robots/R1/replace -H 'Content-Type: application/json' \
  -d '{"capabilities":["lift"],"maxLoad":10}'

# 重启恢复：直接 Ctrl+C 后重新启动（同一 fleet.snapshot-file），
# 未完成任务会从 fleet-state.json 恢复并重新进入调度。
```

### 配置项（application.properties）

| 配置 | 默认 | 说明 |
|---|---|---|
| `fleet.snapshot-file` | `fleet-state.json` | 本地快照文件路径（每次状态变更后原子写盘） |
| `fleet.aging-threshold-millis` | `5000` | 排队多久触发一次老化提优 |
| `fleet.housekeeping-interval-millis` | `1000` | 后台超时扫描 / 提优 / 补调度周期 |

## 代码结构

```
model/      Task、Robot、Assignment、TaskStatus、RobotStatus、RejectReason
store/      TaskStore、RobotStore（内存）、SnapshotStore（JSON 快照 + 启动恢复）
service/    FleetLock（全局调度锁）、Scheduler（排队/分配/超时/提优/恢复）、
            TaskService（提交/取消/上报）、RobotService（注册/掉线/替换）、
            TaskRejectedException
controller/ FleetController（REST 验证接口）
```
