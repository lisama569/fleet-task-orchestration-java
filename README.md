# 机器人车队任务分配与执行编排（本地模拟，无外部依赖）

一套纯本地的机器人车队任务编排能力：机器人和任务都用**内存 + 本地 JSON 文件**模拟，不接真实设备、不依赖外部服务。
启动即可自检，`mvn test` 即可验证全部关键场景。

- 任务按**优先级 + 截止时间 + 提交时间**排队，自动分配给**能力/负载匹配**的机器人
- 同一机器人同一时刻只能执行一个互斥任务；重复提交（相同 `taskId`）不产生重复分配
- 没有可用机器人或能力/负载不满足时**拒绝并入队**，原因码可区分
- 执行中支持**取消、超时、失败**；状态推进全部**幂等**，重复上报无重复副作用
- 机器人完成 / 掉线 / 被替换后释放并重新参与分配；长期排队任务会**老化提升优先级**
- 任务与机器人都持久化到本地 JSON，**重启恢复**：未完成任务退回可调度状态并重新下发，不卡死、不重复下发

---

## 1. 快速开始

要求 JDK 21+、Maven 3.6+。

```bash
# 启动应用：自动注册模拟机器人并跑一遍自检场景，日志搜索 FLEET-DEMO 即可看到全过程
mvn spring-boot:run

# 运行全部单测（34 个）
mvn test
```

启动日志里会依次演示：正常分配与分配依据、`ALL_ROBOTS_BUSY`/`CAPABILITY_MISMATCH`/`LOAD_UNSUPPORT`、
重复提交去重、排队任务在机器人空闲后自动补位、取消排队/执行中任务、执行超时、模拟失败、长期排队老化提升、掉线/替换释放。

数据文件默认写到 `./data/fleet-tasks.json` 与 `./data/fleet-robots.json`（原子写：临时文件 + rename）。
想反复看全新演示，先删掉 `data/` 目录。

关键配置（`src/main/resources/application.properties`）：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `fleet.task-store-path` | `data/fleet-tasks.json` | 任务 JSON 路径（机器人文件自动派生为 `fleet-robots.json`） |
| `fleet.scheduler-enabled` | `true` | 是否启用后台调度循环 |
| `fleet.schedule-interval-ms` | `100` | 调度轮询间隔（排队分配 / 超时扫描 / 老化提升） |
| `fleet.aging-after-ms` | `4000` | 排队超过该毫秒数后开始老化提升，`0` 关闭 |
| `fleet.aging-boost` | `10` | 每次老化提升的优先级幅度 |
| `fleet.aging-interval-ms` | `1000` | 老化后每再多等该时长再提升一次 |
| `fleet.demo-enabled` | `true` | 启动时是否运行自检演示 |

---

## 2. 任务状态流转

```
                提交(去重)
                   │
                   ▼
                PENDING（排队中，可被调度）
                   │ 调度命中机器人：robot=IDLE、能力/负载满足
                   ▼
               ALLOCATED（已分配、已下发模拟引擎）
                   │ 引擎回报 onStarted
                   ▼
              IN_PROGRESS（执行中，进度单调递增 0..100）
       ┌───────────┼───────────────────────┐
       │           │                       │
  取消(cancel)  成功 reportSuccess      失败 reportFailure / 超时 timeout
       │           │                       │
       ▼           ▼                       ▼
   CANCELLED    COMPLETED               FAILED / TIMED_OUT
   （以上四个均为终态，重复上报/重复取消一律幂等忽略，不计数、不重复释放）
```

特殊回退（释放归属、可继续调度）：

- 机器人**掉线**或**被替换**：其持有的活动任务 → `PENDING`，进度清零，等待重新分配
- **服务重启 / 调度中断**：持久化中 `ALLOCATED` / `IN_PROGRESS` 的任务 → 恢复为 `PENDING`；
  内存中残留的 `BUSY` 机器人复位为 `IDLE`；恢复完成后立即重新调度，每个任务**只重新下发一次**
- 终态任务（`COMPLETED/FAILED/CANCELLED/TIMED_OUT`）重启后保持终态；终态任务保留“最后归属机器人”作为历史信息，但不构成占用

幂等规则（`revision` 修订号只在真实推进时 +1）：

| 操作 | 重复 / 过期输入的结果 |
| --- | --- |
| 相同 `taskId` 再提交 | `DUPLICATE_SUBMISSION`，返回原任务，引擎不重复下发 |
| 终态后再报成功/失败 | `DUPLICATE_IGNORED`，状态、机器人释放计数、`revision` 均不变 |
| 进度不前进（更小/相同百分比） | `DUPLICATE_IGNORED`，进度不回退 |
| 重复取消 | `ALREADY_TERMINAL`，不再调引擎取消、不重复释放 |
| 非归属机器人回报 | `WRONG_OWNER`，拒绝且不推进（防止掉线重派后旧机器人的迟到回报污染新执行） |
| 引擎超时事件晚于完成 | CAS 抢占失败 + 终态判定，事件被忽略，不会把 COMPLETED 改回 TIMED_OUT |

---

## 3. 机器人状态流转

```
IDLE ──分配任务──▶ BUSY ──完成/失败/超时/取消──▶ IDLE（完成计数 +1，重新参与分配）
  │                  │
  │                  └──掉线/被替换──▶ OFFLINE（在执任务退回 PENDING）
  └──────────────上线──────────────┘
```

`BUSY` 时 `currentTaskId` 唯一；服务用单一可重入锁串行化全部状态变更，并在“策略判定”与“真正占用”之间二次校验机器人仍为 `IDLE`，
因此并发提交/取消/回报混合时不会出现一台机器人被派两个任务、或活动任务找不到归属的矛盾。

---

## 4. 排队、分配与优先级规则

### 排队顺序
`PENDING` 任务依次按：
1. **有效优先级降序**（有效优先级 = 基础优先级 0..100 + 老化提升量）
2. **截止时间升序**（`deadlineEpochMs=0` 视为无截止时间，排最后）
3. **提交时间升序**，再按 `taskId` 升序（稳定、可复现）

### 单任务选机器人（为什么是它）
1. 只考虑 `IDLE` 机器人（互斥约束）
2. 必须具备任务要求的能力 `capability`
3. 容量 `capacity >= 任务负载 load`
4. 可行候选中选**剩余容量最小**（最贴近负载、避免大材小用），容量相同按 `robotId` 升序

每次分配都会在日志和任务的 `lastDecisionReason` 中写明依据，例如：

```
任务 T-OK 分配给机器人 R1 | 依据: 选择 R1：在 2 台可行机器人中剩余容量最小(2)，容量相同时按 id 升序
```
策略还会记录每个候选被排除的依据（缺什么能力、容量差多少），见 `AllocationDecision.candidateEvals`。

### 老化提升
排队超过 `aging-after-ms` 后首次提升；超出阈值的时间每满 `aging-interval-ms` 再提升一次。
长期得不到调度的低优先级任务会因此逐步超过高优先级任务，在资源释放时“插队”，避免饥饿。

### 释放即补位
任务完成/失败/超时/取消、机器人上线、机器人替换的**同一把锁内**立即触发一轮排队分配，无需等待下一次轮询。

---

## 5. 拒绝/延后原因码怎么区分

提交接口 `SubmitResult` 返回 `allocated / duplicate / reason / message / task`。任务没能**立即**分配时会进入排队（`PENDING`），原因码：

| 原因码 | 触发条件 | 任务去向 |
| --- | --- | --- |
| `NONE` | 立即分配成功 | `ALLOCATED`，已下发引擎 |
| `DUPLICATE_SUBMISSION` | 相同 `taskId` 已存在 | 幂等返回原任务，无新分配 |
| `ALL_ROBOTS_OFFLINE` | 没有注册机器人，或在线机器人数量为 0 | `PENDING`，机器人上线后自动可分配 |
| `ALL_ROBOTS_BUSY` | 有在线机器人但全部 `BUSY`（互斥） | `PENDING`，机器人释放后自动补位 |
| `CAPABILITY_MISMATCH` | 有**空闲**机器人，但没有一台具备所需能力 | `PENDING`（可能等新机器人/更合适机器人） |
| `LOAD_UNSUPPORTED` | 有空闲且具备能力的机器人，但容量都小于负载 | `PENDING`（可能等大容量机器人） |
| `INVALID_REQUEST` | 参数非法（空 taskId/能力、优先级越界、负载/时长非正数等），或 null 请求 | 不入系统、不落盘 |

区分判定优先级：**全离线 → 全忙 → 能力 → 负载**。即“能力/负载”原因只会在确实存在空闲机器人时给出，
这样调用方可以据原因码决定是等待、加机器人还是改参数。

取消结果 `CancelOutcome`：`CANCELLED_PENDING`（排队任务被取消）、`CANCELLED_ACTIVE`（执行中被取消且机器人释放）、
`ALREADY_TERMINAL`（幂等忽略）、`NOT_FOUND`。
回报结果 `ReportOutcome`：`PROGRESS_ACCEPTED / SUCCEEDED / FAILED_ACCEPTED / NOT_FOUND / WRONG_OWNER / DUPLICATE_IGNORED / NOT_ACTIVE`。

---

## 6. 并发一致性与恢复保证

- **单一锁模型**：`FleetService` 内一把公平 `ReentrantLock` 保护任务表与机器人状态；引擎回调、HTTP、调度线程全部走同一把锁。
- **不变量**（单测中全量校验）：
  - `BUSY` 机器人的 `currentTaskId` 必然指向一个 `ALLOCATED/IN_PROGRESS` 且反向归属它的任务
  - 每个活动任务有且仅有一个 `BUSY` 归属机器人；非活动任务不残留运行归属
  - 不会有两个活动任务共享同一机器人
- **引擎幂等**：`dispatch` 同一 taskId 已在跑时直接忽略；完成/失败/超时用 CAS 保证只有第一个生效；`cancel` 未在跑时无操作。
- **崩溃/重启**：每次状态变更同步落盘（任务 + 机器人）。启动时先恢复机器人名录，再把活动任务退回 `PENDING`、释放残留占用，然后重新调度；
  旧引擎随旧进程一起消失，新进程为每个任务只下发一条新执行，做到“不卡死、不重复下发”。

---

## 7. 本地 REST 接口（可选，用于手工验证）

应用启动后端口随机（见启动日志 `Tomcat started on port(s)`）。例：

```bash
BASE=http://localhost:<port>/fleet

# 注册机器人
curl -s -XPOST $BASE/robots -H 'Content-Type: application/json' \
  -d '{"robotId":"R1","capabilities":["LIFT"],"capacity":5}'

# 提交任务（timeoutMs=300 < durationMs=2000 会超时；failBeforeComplete=true 会模拟失败）
curl -s -XPOST $BASE/tasks -H 'Content-Type: application/json' \
  -d '{"taskId":"T1","capability":"LIFT","priority":50,"deadlineEpochMs":0,"load":3,
        "durationMs":1000,"failBeforeComplete":false,"timeoutMs":0}'

curl -s $BASE/tasks                       # 查看全部任务状态
curl -s -XPOST $BASE/tasks/T1/cancel     # 取消
curl -s -XPOST $BASE/tasks/T1/success -H 'Content-Type: application/json' -d '{"robotId":"R1"}'
curl -s -XPOST $BASE/robots/R1/offline    # 模拟掉线（在执任务自动退回排队）
curl -s -XPOST $BASE/robots/R1/online     # 恢复上线
curl -s -XPOST $BASE/robots/replace -H 'Content-Type: application/json' \
  -d '{"oldRobotId":"R1","newRobotId":"R3","capabilities":["LIFT"],"capacity":9}'
```

---

## 8. 测试与日志

单测位于 `src/test/java/.../tests/fleet/`，日志会打印**用例输入**与**关键判定依据**（前缀形如 `[用例输入]` / `[判定]`）：

| 测试类 | 覆盖内容 |
| --- | --- |
| `FleetAllocationTest` | 5 种拒绝原因、最贴近负载选择、互斥、优先级/截止时间排序、重复提交去重、完成后补位 |
| `FleetLifecycleTest` | 取消（排队/执行中/幂等）、可控时钟下的超时、失败、错归属拒绝、旧进度拒绝、掉线退回重派、机器人替换 |
| `FleetAgingTest` | 老化提升次数与插队、关闭老化不提升 |
| `FleetConcurrencyTest` | 32 线程重复提交同一任务只产生一个任务/一次下发；64 并发任务不重复占用机器人；提交+取消+回报混合下的双向归属不变量 |
| `FleetRecoveryTest` | 重启后活动任务回退重派、终态保持、排队任务后续可调度、机器人名录本地恢复 |
| `FleetAsyncEngineTest` | 真实异步模拟引擎：自动进度、成功/注入失败/超时事件竞争、终态不被晚到事件翻转 |

```bash
mvn test                      # 全部
mvn test -Dtest=FleetConcurrencyTest   # 只跑并发场景
cat target/surefire-reports/*.txt       # 查看含输入与判定依据的详细日志
```

---

## 9. 代码结构

```
fleet/
├── model/       枚举与领域模型（TaskStatus/RobotStatus/RejectReason、Task、Robot、提交/回报/取消结果）
├── store/       本地存储（TaskStore/RobotStore 的 JSON 文件实现，原子写）
├── registry/    机器人内存注册表
├── engine/      派发引擎抽象 + 异步模拟实现（进度/完成/失败/超时，幂等 + CAS）
├── scheduler/    分配策略（能力+负载+最贴近选择，记录判定依据）与后台调度循环
├── service/      FleetService：锁、幂等状态机、排队分配、超时扫描、老化、掉线/替换、启动恢复
├── web/         本地验证 REST 控制器
├── demo/        启动自检场景
└── config/      Spring 装配与配置项
```
