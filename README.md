# 写接口幂等网关（Idempotent Write Gateway）

为后端写接口提供幂等保护：同一个提交在**网络重试**或**用户连点**的情况下，
最终结果与只提交一次完全一致——业务副作用只产生一次，重复请求拿到与首次相同的响应，
或得到明确的“处理中”反馈。

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
| 首次请求仍在执行 | 409 | `IDEMPOTENCY_PROCESSING` | —（含 `Retry-After: 2`） |
| 抢占阶段存储不可用 | 503 | `IDEMPOTENCY_STORAGE_UNAVAILABLE` | `STORAGE_UNAVAILABLE` |

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
                    （重放同一响应）      （TTL 内允许同内容重试）
                          │                      │
                          └────── 超过 TTL ──────┘
                                     ▼
                              记录失效，同键可重新处理
```

- **PROCESSING（处理中）**：同键的并发请求只能得到 409 + `Retry-After`，不会重复执行业务。
- **SUCCEEDED（已成功）**：TTL 内同键同内容请求直接重放首次响应，业务不再执行。
- **FAILED（失败）**：业务抛异常或返回非 2xx 时进入。TTL 内**允许且只允许相同请求内容重试**；换内容仍是 `REQUEST_MISMATCH`。重试成功后转为 SUCCEEDED。

并发互斥由存储层的**原子 claim** 保证：同一幂等键的 N 个并发请求，最多 1 个拿到执行权，
其余要么在执行结束后拿到与首次一致的重放，要么在执行期间拿到 409“处理中”。

## 4. 过期（TTL）规则

- 默认 TTL = **24 小时**，配置项 `idempotency.ttl`（如 `1s`、`30m`、`24h`）。
- 记录从抢占成功时刻起算；超过 TTL 后，同一个键可以重新处理，旧响应不再重放（过期后同键也允许绑定新内容）。
- `idempotency.max-key-length` 控制键的最大长度。

## 5. 失败重试规则（说清楚边界）

- **业务执行失败**（抛异常或返回非 2xx）：记录标记为 `FAILED`，客户端可用**同一个幂等键、同一份请求体**重试；重试是安全的，因为失败时业务不应被视为已完成。
- 若业务实际“部分成功”但返回了非 2xx，重试是否安全取决于业务自身的补偿语义——幂等层保证的是“不会因为网关自身而重复”，业务侧仍应保证失败可重试。
- **业务成功后但结果固化到存储失败**（见下节）：记录停留在 `PROCESSING`，TTL 内重试一律 409，直到 TTL 过期键才恢复可用。

## 6. 存储不可用 / 写失败时的兜底（fail-closed）

核心原则：**绝不能一边对客户端报错、一边业务其实已经做完；也不能在没有幂等兜底时放行业务。**

1. **抢占（claim）阶段存储不可用**：直接返回 **503**，业务**一次都不执行**（fail-closed）。客户端稍后用同一个键重试即可，不会产生重复副作用。
2. **业务执行成功、固化（complete）结果时写失败**：
   - 业务已成功且响应已送达客户端——**不向客户端报错、不返回伪造的重放结果**；
   - 记录保持 `PROCESSING`：TTL 内同键重试统一返回 **409 处理中**，防止业务被重复执行；
   - 同时输出 ERROR 日志（`[幂等][兜底]`）供监控告警、人工介入；
   - 超过 TTL 后键重新可用。
   - 这是“至少一次（at-least-once）”语义下的标准取舍：宁可让客户端在窗口内查不到结果，也不制造重复订单。

> 当前默认实现是**单实例内存存储**（`InMemoryIdempotencyStore`，基于 `ConcurrentHashMap` + 同步 claim），
> 重启后记录丢失、多实例不共享。生产环境请实现 `IdempotencyStore` 替换为
> Redis（`SET key value NX EX ttl` + Lua 原子脚本）或带唯一约束的数据库；过滤器、状态机无需改动。

## 7. 本地怎么验证

### 跑单元测试 / 集成测试

```bash
mvn test
```

共 25 个用例，分布在 4 个测试类，覆盖需求要求的四类场景：

| 测试类 | 覆盖场景 |
| --- | --- |
| `InMemoryIdempotencyStoreTest` | 原子抢占、并发互斥语义、成功重放、失败可重试、FAILED 换内容拒绝、**过期重放**、存储异常（缺失记录固化 / 重复固化） |
| `IdempotencyServiceTest` | 键缺失、键格式非法/合法、请求指纹稳定性与区分度、TTL 默认值 |
| `IdempotencyWebIntegrationTest` | **重复提交**（响应一致、业务只执行一次）、缺键/格式/内容冲突、**20 并发抢占**（恰好 1 次真实执行）、**TTL 过期重放**、业务失败重试、**结果固化写失败兜底** |
| `StorageClaimFailureIntegrationTest` | **抢占阶段存储不可用** → 503 fail-closed、业务零执行 |

测试日志会打印每个用例覆盖的业务分支与通过结论，例如：

```
====== 场景: 并发抢占（同一幂等键同时打进来）======
并发结果分布: {200=1, 409=19}
场景通过: 20 个并发请求中 200=1（含重放），409处理中=19，业务真实执行=1
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

# 同键换内容：400 IDEMPOTENCY_REQUEST_MISMATCH
curl -i -X POST http://localhost:8080/api/orders \
  -H 'Idempotency-Key: k1' -H 'Content-Type: application/json' \
  -d '{"productId":"p1","quantity":3}'
```

## 8. 模块结构

```
idempotency/
  IdempotencyKeyRequired        写接口接入注解
  IdempotencyFilter             入口编排：校验 → 抢占 → 重放/处理中/执行/固化/兜底
  IdempotencyService            键校验、请求体 SHA-256 指纹
  IdempotencyStore              存储接口（claim/complete/fail）
  InMemoryIdempotencyStore      内存实现（原子 claim、TTL、过期清理）
  IdempotencyRecord             幂等记录（PROCESSING/SUCCEEDED/FAILED）
  ClaimResult                   抢占结果（Granted/Retryable/Replaying/Processing/Rejected）
  RejectionReason               拒绝原因枚举
  IdempotencyProperties         idempotency.ttl / idempotency.max-key-length
  CachedBodyHttpServlet*        请求体可重复读、响应体缓存包装
order/                          示例业务（创建订单），OrderService 带真实执行计数
web/GlobalExceptionHandler      统一错误响应
```
