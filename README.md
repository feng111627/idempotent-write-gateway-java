# 写接口幂等网关（Idempotent Write Gateway）

为后端写接口提供幂等保护：同一个提交在**网络重试**或**用户连点**的情况下，
最终结果与只提交一次完全一致——业务副作用只产生一次，重复请求拿到与首次相同的响应，
或得到明确的“处理中”反馈。

本轮在原有“键校验 / 并发抢占 / 结果重放 / 失败重试 / 存储故障兜底”基础上，新增三块
面向长期运行与运维的能力：

1. **分状态保留时长**：处理中 / 成功 / 失败三种记录各配各的保留窗口；
2. **状态查询入口**：看清一个键当前到底处于什么情况，处理中还能知道大概多久后再来；
3. **容量上限与安全淘汰**：内存记录不再无限增长，且淘汰绝不破坏“业务只做一次”。

## 1. 幂等键怎么传

在需要幂等保护的写接口请求头中携带 `Idempotency-Key`：

```bash
curl -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: biz-20261006-0001' \
  -d '{"productId":"prod-100","quantity":2}'
```

规则：

- 由**客户端**为“同一笔业务提交”生成，建议用业务单号或 UUID；同一笔操作的所有重试必须复用同一个键，新的业务操作必须换新键。
- 格式：以字母或数字开头，仅允许字母、数字、下划线 `_`、短横线 `-`、冒号 `:`，默认长度 ≤ 128（可配置）。
- 请求体的 SHA-256 指纹会与键绑定：**同一个键搭配不同的请求内容会被拒绝**（防止键被误用）。
- 接口接入方式：在 Controller 写方法上标注 `@IdempotencyKeyRequired` 即可，过滤器自动完成全部编排。

## 2. 拒绝原因与状态码

所有拒绝都返回 JSON，`code` 和 `rejectionReason` 能明确区分是哪种原因：

| 场景 | HTTP 状态码 | code | rejectionReason |
| --- | --- | --- | --- |
| 没传幂等键 / 空串 | 400 | `IDEMPOTENCY_MISSING_KEY` | `MISSING_KEY` |
| 键格式非法（字符/长度） | 400 | `IDEMPOTENCY_INVALID_KEY_FORMAT` | `INVALID_KEY_FORMAT` |
| 同键配了不同请求内容 | 400 | `IDEMPOTENCY_REQUEST_MISMATCH` | `REQUEST_MISMATCH` |
| 首次请求仍在执行 | 409 | `IDEMPOTENCY_PROCESSING` | —（含动态 `Retry-After`） |
| 抢占阶段存储不可用 | 503 | `IDEMPOTENCY_STORAGE_UNAVAILABLE` | `STORAGE_UNAVAILABLE` |
| 容量已满且无法安全淘汰 | 503 | `IDEMPOTENCY_CAPACITY_FULL` | `CAPACITY_FULL` |

成功的重放响应带 `Idempotency-Replayed: true` 响应头，响应状态码、Content-Type、body 与首次完全一致。

## 3. 状态怎么流转

```
                 claim（原子抢占）
   不存在/已过期 ───────────────►  PROCESSING（唯一请求获得执行权）
                                     │
              业务 2xx，固化结果成功  │  业务异常/非 2xx
                          ┌──────────┴──────────┐
                          ▼                      ▼
                      SUCCEEDED               FAILED
                    （重放同一响应）      （失败窗口内允许同内容重试）
                          │                      │
                          └──── 超过各自保留窗口 ──┘
                                     ▼
                              记录失效，同键可重新处理
```

- **PROCESSING（处理中）**：同键的并发请求只能得到 409 + `Retry-After`，不会重复执行业务。
- **SUCCEEDED（已成功）**：成功窗口内同键同内容请求直接重放首次响应，业务不再执行。
- **FAILED（已失败）**：业务抛异常或返回非 2xx 时进入。失败窗口内**允许且只允许相同请求内容重试**；换内容仍是 `REQUEST_MISMATCH`。重试成功后转为 SUCCEEDED。

并发互斥由存储层的**原子 claim** 保证：同一幂等键的 N 个并发请求，最多 1 个拿到执行权，
其余要么在执行结束后拿到与首次一致的重放，要么在执行期间拿到 409“处理中”。

## 4. 分状态保留时长（本轮新增）

三种状态各有独立的保留窗口，默认值如下：

| 配置项 | 含义 | 默认值 |
| --- | --- | --- |
| `idempotency.processing-ttl` | 处理中记录保留多久（**留短**，避免键被长时间占着） | **2 分钟** |
| `idempotency.succeeded-ttl` | 成功记录保留多久（**留久**，方便同键重试时重放首次结果） | **24 小时** |
| `idempotency.failed-ttl` | 失败记录保留多久（一个**够用的重试窗口**） | **30 分钟** |
| `idempotency.max-records` | 内存中最多保留多少条记录（见第 6 节） | **10000** |
| `idempotency.max-key-length` | 幂等键最大长度 | 128 |

时长写法支持 `30s`、`5m`、`2h`、`24h` 等标准 Spring duration 格式。

行为要点：

- **抢占成功**时按 `processing-ttl` 起算；业务**成功 / 失败**后，记录的过期时刻会分别
  按 `succeeded-ttl` / `failed-ttl` **重新锚定**（从状态流转时刻起算），因此三段窗口各司其职。
- **无论哪一段窗口过期，同一个键都可以重新提交**：过期后旧结果不再重放、旧失败不再约束，
  同键甚至允许绑定新的请求内容。

### 4.1 关键边界：处理中窗口到期 ≠ 首请已经结束

处理中窗口故意比较短，可能出现“**窗口到了，但第一个请求的业务其实还在正常跑**”的情况。
如果这时简单地让同键重新抢占，就可能有两个请求同时真正把业务做两遍。

工程取舍（**在途执行护栏，in-flight guard**）：

- 除了“记录”，存储还单独维护一个**在途集合**：键在业务真实执行期间，无论记录是否到期都受保护；
- 因此即使处理中窗口已过，只要首请还在跑，同键新请求仍然只拿到 **409 PROCESSING**
  （`Retry-After` 给一个固定的轮询建议，默认 2 秒级），**绝不会第二次执行业务**；
- 首请执行结束（成功固化 / 失败标记 / 兜底放弃）后由过滤器释放护栏：
  - 首请**成功**（哪怕跨过了处理中窗口）：结果被（重新）固化为 SUCCEEDED，后续请求**重放**而不是重做；
  - 首请**失败**：记录按失败窗口标记 FAILED，可同内容重试；
  - 首请成功但**结果固化也失败**（存储兜底）：记录停在 PROCESSING，护栏释放后到处理中窗口自然到期为止仍然 409，
    **窗口到期后键才重新可用**——维持 at-least-once 的明确取舍，绝不凭空重放一个没存下来的结果。

> 一句话：**窗口到期只决定“能不能忘掉这条记录”，不决定“首请是否结束”；在途护栏只认真实执行是否结束。**

## 5. 状态查询入口（本轮新增）

看清一个键现在到底处于什么情况：

```bash
curl -i 'http://localhost:8080/api/idempotency/keys/status' \
  -H 'Idempotency-Key: biz-20261006-0001'
```

- 方法/路径：`GET /api/idempotency/keys/status`，通过 `Idempotency-Key` 请求头指定键；
- 键缺失 / 格式非法与写接口一致返回 400；查询**只读**，不改变任何记录。

返回 JSON 字段：

| 字段 | 说明 |
| --- | --- |
| `key` | 查询的幂等键 |
| `state` | `NOT_FOUND` / `PROCESSING` / `SUCCEEDED` / `FAILED_RETRYABLE` / `EXPIRED` |
| `retryAfterSeconds` | 仅处理中时出现，建议多少秒后再来查询/重试 |
| `expiresAtEpochMillis` | 当前记录的过期时刻（epoch 毫秒）；无记录时不出现 |

`state` 各取值含义：

| state | 含义 | 调用方应该怎么做 |
| --- | --- | --- |
| `NOT_FOUND` | 从没提交过（或记录已被容量淘汰） | 当作全新提交 |
| `PROCESSING` | 首次请求仍在处理中（含窗口已过但首请还在跑） | 按 `retryAfterSeconds` 等待后用同键重试 |
| `SUCCEEDED` | 之前已成功，窗口内会重放 | 直接用同键取首次结果即可 |
| `FAILED_RETRYABLE` | 之前失败过，仍在失败重试窗口内 | 用**相同内容** + 同键重试 |
| `EXPIRED` | 记录已超过保留窗口 | 可当作新提交重新处理 |

> 说明：`EXPIRED` 指“内存里还留着一条已到期的记录”；如果它随后被容量淘汰，
> 再查就会是 `NOT_FOUND`。两者对提交方的含义一致——**都可以安全地重新提交**。

## 6. 容量上限与淘汰规则（本轮新增）

内存记录受 `idempotency.max-records` 约束，长期运行不会无限增长。需要插入新记录且容量已满时，按顺序处理：

1. **先淘汰所有“已过期、且不在业务执行中”的记录**；
2. 仍不够时，按过期时刻**从早到晚**淘汰“可以忘”的记录：
   `SUCCEEDED` / `FAILED` 终态记录，以及业务已结束（护栏已释放）但仍停在 PROCESSING 的兜底记录；
3. 还不够（剩余全是**在途执行中、受护栏保护**的记录）时：返回 **503 `IDEMPOTENCY_CAPACITY_FULL`**，
   **fail-closed**——绝不靠淘汰正在执行的键来腾位。

正确性保证：

- **还在处理中、没结束的键永远不会被淘汰**，因此不会因淘汰导致同一个键的业务被做两遍；
- **被淘汰的键之后再用同一个键提交，表现为全新提交（正常受理）**：不报错，也不会重放一个早该忘掉的旧结果；
- 多个请求同时打进来、又正好撞上容量满和窗口边界时，所有判定都在同一把锁内原子完成，
  同一时刻同一个键**最多只有一个请求真正执行业务**。

## 7. 失败重试规则（边界说清楚）

- **业务执行失败**（抛异常或返回非 2xx）：记录标记为 `FAILED`，客户端可用**同一个幂等键、同一份请求体**重试；重试是安全的，因为失败时业务不应被视为已完成。
- 若业务实际“部分成功”但返回了非 2xx，重试是否安全取决于业务自身的补偿语义——幂等层保证的是“不会因为网关自身而重复”，业务侧仍应保证失败可重试。
- **业务成功后但结果固化到存储失败**（见下节）：记录停留在 `PROCESSING`，处理中窗口内重试一律 409，直到窗口过期键才恢复可用。

## 8. 存储不可用 / 写失败时的兜底（fail-closed）

核心原则：**绝不能一边对客户端报错、一边业务其实已经做完；也不能在没有幂等兜底时放行业务。**

1. **抢占（claim）阶段存储不可用**：直接返回 **503**，业务**一次都不执行**（fail-closed）。客户端稍后用同一个键重试即可，不会产生重复副作用。
2. **业务执行成功、固化（complete）结果时写失败**：
   - 业务已成功且响应已送达客户端——**不向客户端报错、不返回伪造的重放结果**；
   - 记录保持 `PROCESSING`：处理中窗口内同键重试统一返回 **409 处理中**，防止业务被重复执行；
   - 同时输出 ERROR 日志（`[幂等][兜底]`）供监控告警、人工介入；
   - 窗口到期后键重新可用。
   - 这是“至少一次（at-least-once）”语义下的标准取舍：宁可让客户端在窗口内查不到结果，也不制造重复订单。

> 当前默认实现是**单实例内存存储**（`InMemoryIdempotencyStore`，基于 `ConcurrentHashMap` + 同一把锁编排状态机），
> 重启后记录丢失、多实例不共享。生产环境请实现 `IdempotencyStore` 替换为
> Redis（`SET key value NX EX ttl` + Lua 原子脚本，在途护栏可用独立的执行标记键实现）或带唯一约束的数据库；
> 过滤器、状态机无需改动。

## 9. 老配置兼容范围

- **老用法仍然可用**：只配置 `idempotency.ttl`（如 `idempotency.ttl=24h`）时，
  processing/succeeded/failed 三段保留时长**都等于该值**，并且状态流转时**不重新锚定过期时刻**，
  即升级前“抢占起算、统一 TTL、到期可重新处理”的含义**完全不变**（内部标记为 legacy 模式）。
- **不能混用**：`idempotency.ttl` 与任何新配置项
  （`processing-ttl` / `succeeded-ttl` / `failed-ttl` / `max-records`）同时出现时，
  **应用启动失败**并提示二选一，避免语义歧义。
- 新配置项都有内置默认值，因此既不配老项也不配新项也能正常启动（走默认分状态策略）。

## 10. 配置校验：不合法就起不来

以下情况会让服务**启动失败**，错误信息明确指出是哪一项：

- 任一保留时长为 **0、负数**（如 `processing-ttl=0s`、`failed-ttl=-1s`）；
- 时长**格式无法解析**（如 `processing-ttl=abc`，由配置绑定阶段报错）；
- `max-records` / `max-key-length` 不是正整数；
- 上面说的新旧配置混用。

## 11. 本地怎么验证

### 跑单元测试 / 集成测试

```bash
mvn test
```

共 54 个用例，分布在 8 个测试类。本轮新增/扩展覆盖：

| 测试类 | 覆盖场景 |
| --- | --- |
| `InMemoryIdempotencyStoreTest` | 原有：抢占、并发互斥、成功重放、失败重试、内容冲突、过期重放、存储异常；**新增：三段 TTL 各自锚定与过期重提、处理中窗口到期但首请在跑的护栏安全、跨窗口成功/失败、容量满的过期淘汰/终态淘汰/在途不淘汰/被淘汰键重新提交/CAPACITY_FULL、容量边界并发唯一执行** |
| `KeyStateQueryTest` | **新增：状态查询五分支 NOT_FOUND/PROCESSING/SUCCEEDED/FAILED_RETRYABLE/EXPIRED、跨窗口在途仍报 PROCESSING、重试建议秒数** |
| `RetentionPolicyConfigTest` | **新增：默认三段时长、老配置 legacy 兼容、分状态绑定、0/负数/格式错误/容量非正/新旧混用均启动失败并指明配置项** |
| `IdempotencyWebIntegrationTest` | 原有：重复提交、缺键/格式/内容冲突、20 并发抢占恰好 1 次执行、TTL 过期重放、失败重试、固化写失败兜底（已适配三段 TTL） |
| `InFlightWindowIntegrationTest` | **新增（真实线程 + 可控时钟）：处理中窗口仅 1s，首请业务阻塞跨窗口；期间同键并发=409 + Retry-After，业务只做一次，首请完成后=200 重放** |
| `CapacityAndStatusIntegrationTest` | **新增：状态查询 HTTP 入口全分支、容量满靠淘汰终态继续服务、容量被在途处理中占满时 503 `IDEMPOTENCY_CAPACITY_FULL`** |
| `StorageClaimFailureIntegrationTest` | 原有：抢占阶段存储不可用 → 503 fail-closed、业务零执行 |
| `ApplicationTests` | Spring 上下文装配冒烟测试 |

测试日志会打印每个用例覆盖的业务分支与通过结论，例如：

```
====== 用例: expiredProcessingWindowButStillInFlightIsProtected ======
用例通过: 首请跨处理中窗口仍在执行期间同键请求一律 PROCESSING；完成后重放，业务零重复
====== 场景: 容量满且全部在处理中（max-records=3）======
容量被在途处理中占满时新写请求=503/IDEMPOTENCY_CAPACITY_FULL，处理中记录数保持=3
场景通过: 容量满且全在处理中时 fail-closed 拒绝新写，绝不淘汰处理中记录导致重复执行
```

### 启动服务手工验证

```bash
mvn spring-boot:run            # 默认随机端口；可用 -Dspring-boot.run.arguments="--server.port=8080"
```

```bash
# 首次提交
curl -X POST http://localhost:8080/api/orders \
  -H 'Idempotency-Key: k1' -H 'Content-Type: application/json' \
  -d '{"productId":"p1","quantity":2}'

# 立刻重试：同样的响应 + Idempotency-Replayed: true
curl -i -X POST http://localhost:8080/api/orders \
  -H 'Idempotency-Key: k1' -H 'Content-Type: application/json' \
  -d '{"productId":"p1","quantity":2}'

# 查询键当前状态（处理中会带 retryAfterSeconds）
curl -s 'http://localhost:8080/api/idempotency/keys/status' -H 'Idempotency-Key: k1'

# 同键换内容：400 IDEMPOTENCY_REQUEST_MISMATCH
curl -i -X POST http://localhost:8080/api/orders \
  -H 'Idempotency-Key: k1' -H 'Content-Type: application/json' \
  -d '{"productId":"p1","quantity":3}'
```

## 12. 模块结构

```
idempotency/
  IdempotencyKeyRequired        写接口接入注解
  IdempotencyFilter             入口编排：校验 → 抢占 → 重放/处理中/执行/固化/兜底，结束后释放护栏
  IdempotencyService            键校验、请求体 SHA-256 指纹
  IdempotencyStore              存储接口（claim/complete/fail/query/releaseInFlight）
  InMemoryIdempotencyStore      内存实现：分状态 TTL、在途护栏、容量淘汰、状态查询
  RetentionPolicy               三段保留时长 + 容量上限（含 legacy 模式标志）
  IdempotencyRecord             幂等记录（PROCESSING/SUCCEEDED/FAILED，支持过期点重新锚定）
  KeyState / KeyStateView       对外查询状态枚举与只读视图
  web/IdempotencyStatusController  GET /api/idempotency/keys/status 状态查询入口
  ClaimResult                   抢占结果（Granted/Retryable/Replaying/Processing/Rejected）
  RejectionReason               拒绝原因枚举（含 CAPACITY_FULL）
  IdempotencyProperties         idempotency.* 配置；IdempotencyConfig 负责构建策略并校验
  CachedBodyHttpServlet*        请求体可重复读、响应体缓存包装
order/                          示例业务（创建订单），OrderService 带真实执行计数
web/GlobalExceptionHandler      统一错误响应
```
