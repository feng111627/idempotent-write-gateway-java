# 写接口幂等网关（Idempotent Write Gateway）

为后端写接口提供幂等保护：同一个提交在**网络重试**或**用户连点**的情况下，
最终结果与只提交一次完全一致——业务副作用只产生一次，重复请求拿到与首次相同的响应，
或得到明确的“处理中”反馈。

面向长期运行与运维，本版本进一步提供：

- **分状态保留时长**：处理中 / 成功 / 失败三种记录各配各的保留窗口；
- **状态查询入口**：一个键现在处于什么情况一目了然，处理中还能知道多久后再来；
- **内存容量上限与安全淘汰**：长期运行内存不无限增长，且淘汰绝不破坏“只执行一次”。

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
| 抢占阶段存储不可用 / 容量已满无法安全腾位 | 503 | `IDEMPOTENCY_STORAGE_UNAVAILABLE` | `STORAGE_UNAVAILABLE` |

成功的重放响应带 `Idempotency-Replayed: true` 响应头，响应状态码、Content-Type、body 与首次完全一致。

## 3. 状态怎么流转

```
                 claim（原子抢占）
   不存在/已过期/已淘汰 ───────────►  PROCESSING（唯一请求获得执行权）
                                     │
              业务 2xx，固化结果成功  │  业务异常/非 2xx
                          ┌──────────┴──────────┐
                          ▼                      ▼
                      SUCCEEDED               FAILED
                    （重放同一响应）      （失败窗口内允许同内容重试）
                          │                      │
                          └──── 各自窗口过期 ────┘
                                     ▼
                              记录失效，同键可重新处理
```

- **PROCESSING（处理中）**：同键的并发请求只能得到 409 + `Retry-After`，不会重复执行业务。
- **SUCCEEDED（已成功）**：成功窗口内同键同内容请求直接重放首次响应，业务不再执行。
- **FAILED（失败）**：业务抛异常或返回非 2xx 时进入。失败窗口内**允许且只允许相同请求内容重试**；换内容仍是 `REQUEST_MISMATCH`。重试成功后转为 SUCCEEDED。

并发互斥由存储层的**原子 claim** 保证：同一幂等键的 N 个并发请求，最多 1 个拿到执行权。

## 4. 分状态保留时长（本轮新增）

三种状态各有独立窗口，状态切换时**按新状态从切换时刻重新起算**：

| 配置项 | 含义 | 默认值 |
| --- | --- | --- |
| `idempotency.processing-ttl` | 处理中记录保留时长：短一些，别长时间占着键 | **2 分钟** |
| `idempotency.failed-ttl` | 失败记录保留时长：一个够用的重试窗口 | **30 分钟** |
| `idempotency.succeeded-ttl` | 成功记录保留时长：留久一点方便窗口内重放 | **24 小时** |

```properties
idempotency.processing-ttl=2m
idempotency.failed-ttl=30m
idempotency.succeeded-ttl=24h
```

**任何窗口过期后，同一个键都可以重新提交**（处理中、成功、失败一视同仁），旧结果不再重放，
并且过期后同键允许绑定新的请求内容。

### 4.1 关键边界：处理窗口到期 ≠ 原请求已经结束

处理窗口设得比较短，但一个**正在本实例内正常执行**的请求可能跑得比它久
（慢依赖、长事务等）。这里的工程取舍是：

- 处理窗口到期只代表“**首请疑似卡死**（最典型是实例宕机、重启后记录成为孤儿），
  键最终应当能恢复可用”，**不代表可以强杀或忽略还在跑的首请**。
- 存储为每个抢到执行权、业务尚未走完的键维护一个 **in-flight（执行中）护栏**。
  在首请真正结束（成功固化或失败标记落库）之前：
  - 同键的并发提交，**无论同内容还是换内容，一律得到 409 PROCESSING**，业务绝不会被做两遍；
  - 状态查询返回 `PROCESSING_TIMEOUT`（见第 5 节），明确告诉调用方“窗口虽过、首请还在跑”。
- 首请结束后：成功则按成功窗口重新起算并提供重放；失败则进入失败窗口允许重试；
  若实例在业务执行期间宕机，内存记录连同护栏一起消失（分布式实现则等处理窗口到期），键恢复可用。

> 边界说明：in-flight 护栏保证的是**本实例内**“同一时刻只有一个请求真正执行业务”。
> 多实例部署请使用共享存储实现（Redis 处理锁 + fencing token，见第 8 节），
> 跨实例的严格互斥由共享存储承担。

### 4.2 配置不合法：服务直接起不来

- 窗口时长为 **0、负数**（如 `0s`、`-1m`）：启动失败；
- 时长**格式不对**（如 `abc`）：Spring Boot 属性绑定阶段启动失败；
- `idempotency.max-records` / `idempotency.max-key-length` 为非正整数：启动失败。

失败信息会明确指出**具体配置项名与非法值**，例如：

```
配置项 idempotency.processing-ttl 非法：必须为正数时长（如 2m / 30m / 24h），当前值=PT0S
Failed to bind properties under 'idempotency.processing-ttl' to java.time.Duration ... 'abc' is not a valid duration
```

### 4.3 老配置 `idempotency.ttl` 的兼容范围

老的单一配置**继续可用，含义明确且不变**：

- 只配 `idempotency.ttl=24h`（不配任何分段时长）时，三段窗口都取 24h，
  且**沿用旧语义——从抢占成功时刻起算的固定截止时间，状态切换不重算窗口**。
- 一旦显式配置了任意一个分段时长，即切换为“分状态、状态切换时重算窗口”的新语义；
  未显式配置的分段回退取 `idempotency.ttl` 的值（再缺省取默认值）。
- 建议新部署直接使用三个分段配置；`idempotency.ttl` 仅为平滑升级保留。

## 5. 状态查询入口（本轮新增）

```bash
curl -i http://localhost:8080/api/idempotency/status \
  -H 'Idempotency-Key: biz-20261006-0001'
```

只读查询：不抢占、不续期、不改变记录生命周期；键的校验规则与写接口完全一致。
HTTP 恒为 200（键缺失/非法仍为 400），用 `state` 区分五种情况：

| state | 含义 | 调用方应该怎么做 |
| --- | --- | --- |
| `NOT_FOUND` | 从未提交过、窗口已过期或已被容量淘汰 | 直接提交（按全新请求） |
| `PROCESSING` | 首次请求仍在处理中 | 按 `retryAfterSeconds` 提示稍后再来 |
| `SUCCEEDED` | 之前已成功 | 同键同内容提交会直接重放首次响应 |
| `FAILED_RETRYABLE` | 之前失败过、仍在失败窗口内 | 用同一个键、同一份请求体重试 |
| `PROCESSING_TIMEOUT` | 处理窗口已过，但首请在本实例仍未结束 | 仍不能并发提交，稍后再来（避免业务做两遍） |

响应示例（处理中）：

```json
{
  "key": "biz-20261006-0001",
  "state": "PROCESSING",
  "retryAfterSeconds": 117,
  "expiresAtEpochMillis": 1791506981468,
  "hint": "首次请求仍在处理中，请按 retryAfterSeconds 提示稍后再来查询或提交"
}
```

写接口返回 409 时同样带动态的 `Retry-After` 响应头（取处理窗口剩余秒数、向上取整，最小 1 秒），
响应体里也有 `retryAfterSeconds` 字段；调用方不用盲目固定间隔重试。
当处理窗口已过但首请仍在跑时，`Retry-After` 取配置项
`idempotency.processing-retry-after`（默认 **2 秒**）作为兜底提示。

## 6. 内存容量上限与淘汰规则（本轮新增）

内存存储有记录条数硬上限 `idempotency.max-records`（默认 **10000**），
在新键抢占、需要插入记录时按以下顺序腾位（整个过程在存储临界区内原子完成）：

1. **先清理已过期、且首请不在执行中的记录**；
2. 仍然满：**强制淘汰最老的终态记录**（SUCCEEDED / FAILED，按记录创建时间取最老）；
3. 还腾不出位（现存记录全是 PROCESSING）：按 **fail-closed** 返回
   **503 `IDEMPOTENCY_STORAGE_UNAVAILABLE`**，宁可暂不接新单，也不破坏并发互斥。

正确性保证：

- **处理中、首请尚未结束的键永远不会被淘汰**（既不会被过期清理，也不会被强制淘汰），
  从根上杜绝“键被淘汰 → 同键新请求再跑一遍业务”的重复执行。
- **被淘汰的键之后再用同一个键提交，表现为一次全新提交**：不报错、也不回放早该忘掉的结果；
  状态查询为 `NOT_FOUND`，并允许绑定新的请求内容。
- 容量满、窗口边界与多个请求同时到达叠加时，claim 临界区 + in-flight 护栏共同保证：
  **同一时刻同一个键仍然最多只有一个请求真正执行业务**（见
  `CapacityEvictionIntegrationTest` 的 20 并发边界用例）。

## 7. 失败重试与存储兜底（原有能力，保留不变）

- **业务执行失败**（抛异常或返回非 2xx）：记录标记为 `FAILED`，客户端可用**同一个幂等键、同一份请求体**在失败窗口内重试；业务侧仍应保证失败可重试。
- **抢占（claim）阶段存储不可用 / 容量满无法安全腾位**：直接返回 **503**，业务**一次都不执行**（fail-closed）。
- **业务执行成功、固化（complete）结果时写失败**：
  - 业务已成功且响应已送达客户端——**不向客户端报错、不返回伪造的重放结果**；
  - 记录保持 `PROCESSING`，处理窗口内同键重试统一 **409**，防止业务被重复执行；
  - 同时输出 ERROR 日志（`[幂等][兜底]`）供监控告警、人工介入；
  - 处理窗口过后键重新可用。

> 当前默认实现是**单实例内存存储**（`InMemoryIdempotencyStore`），重启后记录丢失、多实例不共享。
> 生产环境请实现 `IdempotencyStore` 替换为 Redis（`SET key value NX EX ttl` + Lua 原子脚本、
> 处理锁配 fencing token 以覆盖“处理锁到期但首请仍在跑”的跨实例等价问题）
> 或带唯一约束的数据库；过滤器、状态机与状态查询无需改动。

## 8. 本地怎么验证

### 跑单元测试 / 集成测试

```bash
mvn test
```

共 47 个用例，分布在 8 个测试类：

| 测试类 | 覆盖场景 |
| --- | --- |
| `InMemoryIdempotencyStoreTest` | 原子抢占、并发互斥、成功重放、失败可重试、内容冲突、过期重放、**分状态窗口重算**、**in-flight 护栏（窗口过期+首请在跑）**、**容量淘汰与处理中免疫**、**状态查询五分支**、存储异常 |
| `IdempotencyServiceTest` | 键缺失/格式、请求指纹、**默认三段窗口**、**老 ttl 兼容语义**、**零/负时长与非法容量拒绝** |
| `IdempotencyWebIntegrationTest` | 重复提交、缺键/格式/内容冲突、20 并发抢占、老配置过期重放、失败重试、固化写失败兜底、**状态查询各分支**、**动态 Retry-After** |
| `StorageClaimFailureIntegrationTest` | 抢占阶段存储不可用 → 503 fail-closed、业务零执行 |
| `InvalidConfigurationStartupTest` | **时长格式错误/零/负数、容量为 0 一律启动失败并指明配置项** |
| `CapacityEvictionIntegrationTest` | **容量满淘汰最老终态、被淘汰键重新提交按新请求、处理中免疫淘汰→503、容量边界 20 并发仍只执行一次** |
| `InFlightGuardIntegrationTest` | **处理窗口到期但慢业务仍在跑：同内容/换内容并发全 409、查询 PROCESSING_TIMEOUT、业务仅 1 次、完成后恢复重放** |
| `PerStateRetentionIntegrationTest` | **三段窗口各算各的端到端行为：成功窗口独立于处理窗口、失败窗口重试边界** |

测试日志会打印每个用例覆盖的业务分支与通过结论，例如：

```
====== 用例: expiredProcessingButInFlightStillExclusive（核心取舍）======
用例通过: 窗口过期 + 首请在途 → 并发请求全部 PROCESSING/查询=PROCESSING_TIMEOUT，业务零重复；...
====== 场景: 容量淘汰后被淘汰键重新提交 ======
场景通过: 被淘汰 cap-k1 查询=NOT_FOUND，重新提交真实再执行一次且结果为新订单
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

# 查询一个键当前的状态
curl -i http://localhost:8080/api/idempotency/status -H 'Idempotency-Key: k1'

# 没提交过的键：NOT_FOUND，可以直接提交
curl -i http://localhost:8080/api/idempotency/status -H 'Idempotency-Key: brand-new-key'
```

## 9. 模块结构

```
idempotency/
  IdempotencyKeyRequired        写接口接入注解
  IdempotencyFilter             入口编排：校验 → 抢占 → 重放/处理中/执行/固化/兜底/释放护栏
  IdempotencyStatusController   状态查询入口 GET /api/idempotency/status
  IdempotencyService            键校验、请求体 SHA-256 指纹
  IdempotencyStore              存储接口（claim/complete/fail/inspect/releaseInFlight）
  InMemoryIdempotencyStore      内存实现：原子 claim、分状态窗口、in-flight 护栏、容量与淘汰
  IdempotencyRecord             幂等记录（PROCESSING/SUCCEEDED/FAILED，支持窗口重算）
  RetentionWindows              三段保留窗口 + 是否在状态切换时重算（老/新语义）
  KeyState / KeyStatusView      状态查询的五种对外状态与只读快照
  ClaimResult                   抢占结果（Granted/Retryable/Replaying/Processing/Rejected）
  RejectionReason               拒绝原因枚举
  IdempotencyProperties         分段 ttl / 老 ttl 兼容 / max-key-length / max-records /
                                processing-retry-after，启动期严格校验
  CachedBodyHttpServlet*        请求体可重复读、响应体缓存包装
order/                          示例业务（创建订单），OrderService 带真实执行计数
web/GlobalExceptionHandler      统一错误响应
```
