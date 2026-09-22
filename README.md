# 支付清算与对账（纯本地内存实现）

一套**只依赖本地运行、不接入任何外部服务或真实凭据**的支付受理、复式记账与渠道对账能力。
渠道结算数据由调用方以普通对象/HTTP 请求体传入，所有状态保存在进程内内存仓储中；
重启后数据清空，适合作为清算链路的可解释参考实现与本地演练环境。

- 技术栈：Java 21、Spring Boot 3.4.1、Maven、JUnit 5、SLF4J
- 运行方式：`mvn -o spring-boot:run`（离线）；无数据库、无消息队列、无外部网络调用

---

## 1. 账本模型（复式记账）

### 1.1 账户

| 账户号 | 类型 | 含义 | 期初 |
|--------|------|------|------|
| `1001` | ASSET（资产） | 付款方在途资金 | 0.00 |
| `2001` | LIABILITY（负债） | 收款方待结算款 | 0.00 |

应用启动时由 `LedgerService` 预置；可通过 `createAccount(...)`（服务层/测试）显式开户。

- **余额口径（净额）**：`balance = 借方累计发生额 − 贷方累计发生额`，固定 2 位小数。
  资产类正常在借方（非负），负债类正常在贷方（非正）。
- **守恒口径**：任意时刻全部账户余额之和恒为 `0`（有借必有贷、借贷必相等）。
  `LedgerService.totalBalanceAcrossAccounts()` 可随时校验，过账后也会强制校验，不为 0 即回滚。

### 1.2 金额精度与舍入（`domain/Money`）

- 记账精度：小数点后 **2 位（分）**，内部 `BigDecimal` 的 scale 固定为 2；
- 统一舍入：**`HALF_UP`（四舍五入，逢五进位）**，仅用于系统内部计算结果
  （`Money.of(BigDecimal)` / `Money.round2`，如未来费用分摊）；
- 外部原始输入若超过 2 位小数（如 `1.005`），**直接判为参数非法**，不做隐式截断/吞精度；
- 支付金额必须为正；为 0、负数、空值、不可解析字符串均拒绝。

### 1.3 凭证与过账原子性（`service/LedgerService`）

一笔支付产生**一张凭证（voucher）= 两行分录**：

```
借：1001 付款方在途资金   100.00
贷：2001 收款方待结算款   100.00
```

- 凭证号复用支付 `businessId`，一一对应；`LedgerEntryRepository.saveVoucher` 以凭证号做 CAS，
  同号凭证只能成功落库一次（过账幂等的持久防线）。
- 过账全程在**单一过账锁**内串行执行，顺序为：
  **校验借贷平衡 → 逐行更新余额（可注入中途故障）→ 全局守恒校验 → 凭证整体落库**。
- 任一步抛异常：已更新余额按**相反方向全额冲正**、凭证不落库。
  因此**不会留下半成品分录，也不会只更新部分余额**（见 `LedgerAtomicPostingTest`）。

---

## 2. 支付受理与幂等口径（`service/PaymentService`）

状态机：

```
(新建) ──受理──▶ ACCEPTED ──过账成功──▶ POSTED   （终态）
                     └──────过账失败────▶ FAILED   （终态）
```

处理顺序：

1. **参数校验前置**：`businessId / payerAccountNo / payeeAccountNo / 金额` 校验，
   收付款账户必须存在且不同；不通过直接拒绝，**不产生任何指令或分录**。
2. **幂等落指令**：`PaymentRepository.insertIfAbsent` 基于 `ConcurrentMap.putIfAbsent` 原子 CAS，
   并发提交同一 `businessId` 时只有一个线程成为“赢家”。同一 `businessId` 的“受理+过账+终态落定”
   在按业务键分段的锁内串行（异键互不阻塞），输家等到终态后才被拒绝，不会看到 `ACCEPTED` 瞬时态。
3. **赢家过账**：成功标记 `POSTED`；失败由账本保证全额冲正、零分录残留，指令标记 `FAILED`。

三类**可区分**的拒绝原因（HTTP 由 `GlobalExceptionHandler` 统一映射）：

| 场景 | 异常 | 错误码 `code` | HTTP |
|------|------|---------------|------|
| 字段缺失/金额非法/账户不存在/收付相同 | `InvalidParameterException` | `INVALID_PARAMETER` | 400 |
| 同 `businessId`、**内容完全相同**的重复提交（串行或并发的输家；含已 FAILED 的重试） | `DuplicateRequestException` | `DUPLICATE_REQUEST` | 409 |
| 同 `businessId`、**内容不同**（金额/账户被篡改） | `ConflictException` | `STATUS_CONFLICT` | 409 |

`FAILED` 是终态：同一 `businessId` 相同请求得到 `DUPLICATE_REQUEST`（响应带 `existingStatus=FAILED`），
篡改请求得到 `STATUS_CONFLICT`；需要重新支付请换用新的业务标识。

---

## 3. 对账口径（`service/ReconciliationService`）

渠道数据 `ChannelSettlementRecord(businessId, amount, status, settledAt)` 由调用方传入，本地不发起任何渠道调用。

对每个 `businessId` **恰好产出一条结论**，五类结论**互斥、可区分**，差异不静默忽略、不笼统合并：

| 结论 `DifferenceType` | 判定口径 |
|-----------------------|----------|
| `MATCHED` | 双方都有，状态一致且金额一致（金额规范到 2 位小数后精确比较） |
| `AMOUNT_MISMATCH` | 双方都有、状态一致，但金额不一致 |
| `STATUS_MISMATCH` | 双方都有，但成功/失败口径不一致 |
| `LOCAL_MISSING` | 渠道有单，本地无流水 |
| `CHANNEL_MISSING` | 本地核对范围内有流水，渠道无单 |

- **状态映射**：本地 `POSTED ↔ 渠道 SUCCESS`，本地 `FAILED ↔ 渠道 FAILED`；
  本地 `ACCEPTED`（未完成）与任何渠道结算状态都不一致。
- **核对范围**：请求中的 `scopeBusinessIds` 为本地范围（决定 `CHANNEL_MISSING` 的检测口径）；
  留空则以全部本地流水为范围。渠道侧始终逐单检查，故 `LOCAL_MISSING` 不会被漏掉。
- **批次可复现**：结果按 `businessId` 排序后生成不可变 `ReconciliationBatch`，并记录输入指纹。
  相同 `batchId` + 相同输入重复执行 → **直接返回同一历史结论，不重复处理**；
  相同 `batchId` + 不同输入 → `STATUS_CONFLICT` 拒绝，历史结论不可被覆盖。
- 渠道数据中同一 `businessId` 出现多条、金额非正、状态为空、`batchId` 为空等，
  一律 `INVALID_PARAMETER` 拒绝，不做静默取舍。
- 每条结论带 `detail` 与 `basis`（判定依据，含双方状态/金额），保证结论可解释。

---

## 4. HTTP 接口（可选的演示入口）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/payments` | 受理并过账；请求体 `PaymentCommand` |
| GET  | `/api/payments/{businessId}` | 查询单笔指令 |
| GET  | `/api/payments` | 全部指令 |
| GET  | `/api/ledger/accounts` | 账户余额 |
| GET  | `/api/ledger/entries` | 全部分录流水 |
| POST | `/api/reconciliations` | 执行/复用对账批次；请求体 `ReconciliationCommand` |
| GET  | `/api/reconciliations/{batchId}` | 取回历史批次（结论复现） |
| GET  | `/api/reconciliations` | 全部批次 |

示例：

```bash
curl -s -X POST localhost:8080/api/payments -H 'Content-Type: application/json' -d '{
  "businessId":"B-1001","payerAccountNo":"1001","payeeAccountNo":"2001","amountText":"100.00"}'

curl -s -X POST localhost:8080/api/reconciliations -H 'Content-Type: application/json' -d '{
  "batchId":"BATCH-1",
  "channelRecords":[{"businessId":"B-1001","amount":100.00,"status":"SUCCESS","settledAt":"2026-09-23T10:00:00Z"}],
  "scopeBusinessIds":["B-1001"]}'
```

---

## 5. 本地验证方法

```bash
# 离线编译
mvn -o compile

# 全量测试（13 个），无需任何外部服务
mvn -o test

# 只跑关键场景
mvn -o test -Dtest=PaymentIdempotencyConcurrencyTest   # 20 线程并发重复提交、拒绝原因区分
mvn -o test -Dtest=LedgerAtomicPostingTest             # 过账中途失败、全额冲正、零半成品
mvn -o test -Dtest=ReconciliationDifferenceTest        # 五类对账差异、批次可复现/冲突
mvn -o test -Dtest=PaymentApiIntegrationTest           # HTTP 端到端、三类错误码

# 启动后用 curl 演练
mvn -o spring-boot:run
```

测试日志会打印**关键输入**（businessId、金额、线程数/渠道记录）与**判定依据**
（成功/拒绝分类、冲正后余额、各条对账结论 basis）。

| 测试类 | 覆盖点 |
|--------|--------|
| `PaymentIdempotencyConcurrencyTest` | 20 线程并发同键仅 1 笔成功；**故障 + 并发下输家只见终态 FAILED、不见 ACCEPTED**；串行重复=>DUPLICATE，篡改=>CONFLICT；5 种非法参数 |
| `LedgerAtomicPostingTest` | 故障钩子模拟借方已入账后失败：余额归零、零分录、FAILED 终态、同键不可再过账；不平衡凭证拒绝 |
| `ReconciliationDifferenceTest` | 金额不符/状态不一致/本地缺失/渠道缺失/一致；批次复现与冲突；渠道重复单/非法输入拒绝；空渠道范围 |
| `PaymentApiIntegrationTest` | HTTP 端到端、三类错误码、余额守恒、对账批次取回 |

## 6. 代码结构

```
domain/        Money、Account、LedgerEntry、PaymentInstruction、
               ChannelSettlementRecord、ReconciliationResultItem/Batch、枚举
repository/    四个仓储接口 + ConcurrentHashMap 内存实现
service/       LedgerService（原子过账/守恒）、PaymentService（受理幂等）、
               ReconciliationService（对账分类）、命令对象、三类可区分异常
web/           PaymentController、ReconciliationController、GlobalExceptionHandler
```
