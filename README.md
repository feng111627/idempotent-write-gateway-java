# 写接口幂等网关（Idempotent Write Gateway）

为后端写接口提供“同一提交只生效一次”的保证：网络重试、用户连点、并发重复请求，
最终结果都与只提交一次一致。基于 Spring Boot 3 / Java 21，默认使用内存存储，
可替换为 Redis / 数据库实现。

## 1. 怎么用

### 1.1 给写接口加幂等

在任意 Controller 写方法上标注 `@Idempotent`，调用方在请求头里带幂等键：

```http
POST /api/orders
Idempotency-Key: order-key-20261006-0001
Content-Type: application/json

{"productId":"P-1001","quantity":2}
```

```java
@PostMapping
@Idempotent
public CreateOrderResponse create(@RequestBody CreateOrderRequest request) { ... }
```

- 幂等键请求头默认名为 `Idempotency-Key`，可用 `idempotency.header-name` 修改。
- 键格式默认 `^[A-Za-z0-9_-]{8,128}$`（8~128 位字母/数字/`_`/`-`），
  建议调用方用“业务单号”或 UUID；可用 `idempotency.key-pattern` 修改。
- 键的**命名空间由调用方负责**：不同业务场景请用不同前缀，避免键碰撞。
- 请求指纹 = `HTTP 方法 + URI + 排序后的查询参数 + body` 的 SHA-256。
  body 字节级一致才算同一请求（JSON 字段顺序不同会被视为不同内容）。

### 1.2 拒绝原因与错误码

所有拒绝都返回统一 JSON：`{"code":"...","message":"..."}`，靠 `code` 区分原因：

| HTTP | code                              | 触发条件                                         |
|------|-----------------------------------|--------------------------------------------------|
| 400  | `IDEMPOTENCY_KEY_MISSING`         | 没带幂等键请求头                                 |
| 400  | `IDEMPOTENCY_KEY_INVALID`         | 幂等键为空或不符合格式                           |
| 422  | `IDEMPOTENCY_KEY_CONFLICT`        | 同一个键携带了**不同的请求内容**（指纹不一致）   |
| 409  | `IDEMPOTENCY_IN_PROGRESS`         | 同键首请仍在执行（并发重复提交），附 `Retry-After`（秒） |
| 503  | `IDEMPOTENCY_STORAGE_UNAVAILABLE` | 幂等存储不可用（fail-closed，业务未执行）        |

- 409 响应带 `Retry-After` 头，提示客户端建议的退避秒数。
- 命中历史结果的响应带 `Idempotency-Replayed: true`（成功重放和失败重放都会带）。
- 业务自身失败返回业务自己的状态码与错误码（演示接口为 4xx + `ORDER_*`）。

## 2. 状态怎么流转

```
                 同键同内容? ── 否 ──> 422 IDEMPOTENCY_KEY_CONFLICT
 请求进入 ──校验键                │
   │        缺失/非法 ─> 400      │
   │                              │
   ▼                              ▼
 acquire（原子）
   │
   ├─ 无记录/记录过期 ──> 写入 PROCESSING ──> 执行业务（只有这里会真正执行）
   │                                        ├─ 成功 ─> SUCCESS（缓存响应，TTL=successTtl）
   │                                        └─ 失败 ─> FAILED（缓存错误，TTL=failureTtl）
   │
   ├─ PROCESSING 在租约内 ──> 409 IDEMPOTENCY_IN_PROGRESS（不执行业务）
   │
   └─ 终态记录且指纹一致
           ├─ SUCCESS ─> 200 + 与首次完全一致的响应体 + Idempotency-Replayed: true
           └─ FAILED  ─> 与首次一致的错误状态/错误码 + Idempotency-Replayed: true
```

并发互斥实现要点：存储层为**每个幂等键使用一把短锁**，锁内只做内存读写，
从不在持锁期间执行业务——并发请求会立刻拿到 409，而不是排队等业务结束。

## 3. 过期与失败重试规则

| 记录状态   | 默认 TTL   | TTL 内再次提交（同键同内容）              | TTL 过期后            |
|------------|-----------|-------------------------------------------|-----------------------|
| PROCESSING | 10 分钟    | 409 处理中                                | 视为崩溃租约，可重新抢占 |
| SUCCESS    | 24 小时    | 直接重放首次结果，不再执行业务            | 同键可重新处理（新业务） |
| FAILED     | 5 分钟     | 原样重放同一失败，不再执行业务            | 允许真正重试           |

失败重试策略说明（有意为之）：
- 业务**失败后保留一个较短的失败 TTL**。TTL 内重试一律返回首次的同一失败，
  避免对“未知是否已产生部分副作用”的失败请求盲目重放造成重复副作用；
  这对支付、下单这类写尤其重要。
- 超过失败 TTL 后，客户端可以用**同一个键**安全重试，此时会真正再执行业务。
- PROCESSING 的 TTL 是“崩溃兜底租约”：正常情况下首请会很快写入终态；
  若进程在处理中途崩溃，租约到期后该键可被接管。迟到的终态写入因
  `createdAt` 对不上会被忽略，不会覆盖新首请的结果。

相关配置（`application.properties`）：

```properties
idempotency.success-ttl=PT24H       # 成功结果保留 24 小时
idempotency.failure-ttl=PT5M       # 失败结果保留 5 分钟
idempotency.processing-ttl=PT10M   # 处理中租约 10 分钟
idempotency.cleanup-interval=PT1M  # 过期记录清理周期
idempotency.storage-fail-closed=true
```

## 4. 存储不可用时的兜底（不会“报错但业务其实做完了”）

幂等层在三个时点都定义了明确行为：

1. **抢占前（acquire）存储不可用**：业务还没执行，直接返回
   `503 IDEMPOTENCY_STORAGE_UNAVAILABLE`，**绝不执行业务**（fail-closed）。
2. **业务成功后写终态失败**：业务副作用已经真实发生，此时再报错会诱发客户端重试。
   幂等层记录 ERROR 日志，并把**本次真实结果照常返回 200**；后续在处理租约内的
   重试会收到 409，租约过期后才会重新处理。
3. **业务失败后写失败记录失败**：把业务异常原样抛给调用方（额外记录存储 ERROR），
   不让存储异常掩盖真正的业务失败原因。

> 部署提示：内置 `InMemoryIdempotencyStore` 仅适用于**单实例**。多实例部署请实现
> `IdempotencyStore`（接口只有 `acquire` / `complete` 两个方法），用 Redis
> `SET NX PX` 或数据库唯一约束 + 短事务保证 acquire 原子性。

## 5. 本地验证

### 5.1 跑单元测试 / 集成测试

```bash
mvn test
```

控制台会逐条打印“覆盖的业务场景 + 每个用例通过/失败”：

```
▶ 开始用例 [DuplicateSubmission] 同键同内容重复提交：业务只执行一次……
✅ 用例通过 [DuplicateSubmission] 同键同内容重复提交……
▶ 开始用例 [StorageFailure] acquire 阶段存储不可用：抛 503 且业务完全不执行……
```

测试矩阵（共 29 个用例）：

| 分类       | 测试类                           | 覆盖点 |
|------------|----------------------------------|--------|
| 重复提交   | IdempotencyServiceTest / 集成测试 | 同内容重放一致、不同内容 422、失败重放 |
| 并发抢占   | IdempotencyServiceTest / InMemoryStoreConcurrencyTest / 集成测试 | 5 真实线程同键只执行 1 次、409+Retry-After、8×500 压测 Acquired 恰为 1 |
| 过期重放   | IdempotencyServiceTest / 集成测试 | 成功 TTL 过期重处理、失败 TTL 过期重试、PROCESSING 租约接管 |
| 存储异常   | IdempotencyServiceTest / 集成测试 | acquire 失败 503 不执行业务、complete 失败后真实结果仍返回 |
| 键校验     | 集成测试 / RequestFingerprinterTest | 缺失 400、非法 400、指纹识别内容差异、参数顺序不敏感 |

### 5.2 手动 curl 验证

```bash
mvn spring-boot:run      # 启动（默认 8080，可在 application.properties 改端口）

# 第一次提交
curl -i -X POST http://localhost:8080/api/orders \
  -H 'Idempotency-Key: demo-key-0001' -H 'Content-Type: application/json' \
  -d '{"productId":"P-1001","quantity":2}'

# 立刻重试（连点/超时重试）：返回与第一次完全一致的订单号，且带重放头
curl -i -X POST http://localhost:8080/api/orders \
  -H 'Idempotency-Key: demo-key-0001' -H 'Content-Type: application/json' \
  -d '{"productId":"P-1001","quantity":2}'

# 同键改内容：422 IDEMPOTENCY_KEY_CONFLICT
curl -i -X POST http://localhost:8080/api/orders \
  -H 'Idempotency-Key: demo-key-0001' -H 'Content-Type: application/json' \
  -d '{"productId":"P-1001","quantity":9}'

# 不带键：400 IDEMPOTENCY_KEY_MISSING；键太短：400 IDEMPOTENCY_KEY_INVALID
```

## 6. 代码结构

```
annotation/Idempotent              注解：标注需要幂等的写方法
core/IdempotencyAspect             切面：取键、校验、算指纹、重放直写响应
core/IdempotencyService            编排：抢占 → 执行 → 落终态/重放，存储异常兜底
core/RequestFingerprinter          请求指纹（SHA-256）
store/IdempotencyStore             存储抽象（可换 Redis/DB）
store/InMemoryIdempotencyStore     内存实现：每键短锁 + PROCESSING 租约
model/IdempotencyRecord,State      记录模型与三态
error/*                            带错误码的异常体系
web/ReplayableRequestFilter        请求体可重复读（指纹+反序列化共用）
web/GlobalExceptionHandler         错误码/Retry-After/重放头统一出口
demo/OrderController               演示写接口 POST /api/orders
```
