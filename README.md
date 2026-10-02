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
| `3001` | LIABILITY（负债） | 手续费收入待结算 | 0.00 |

应用启动时由 `LedgerService` 预置；可通过 `createAccount(...)`（服务层/测试）显式开户。

- **余额口径（净额）**：`balance = 借方累计发生额 − 贷方累计发生额`，固定 2 位小数。
  资产类正常在借方（非负），负债类正常在贷方（非正）。
- **守恒口径**：任意时刻全部账户余额之和恒为 `0`（有借必有贷、借贷必相等）。
  `LedgerService.totalBalanceAcrossAccounts()` 可随时校验，过账后也会强制校验，不为 0 即回滚。

### 1.2 金额精度与舍入（`domain/Money`）

- 记账精度：小数点后 **2 位（分）**，内部 `BigDecimal` 的 scale 固定为 2；
- 统一舍入：**`HALF_UP`（四舍五入，逢五进位）**，仅用于系统内部计算结果
  （`Money.of(BigDecimal)` / `Money.round2`，典型场景即**手续费处理到分**，见 1.4）；
- 外部原始输入若超过 2 位小数（如支付金额 `1.005`、对账渠道金额/手续费/净额 `0.601`），
  **直接判为参数非法**（原因带字段名，可区分），不做隐式截断/吞精度；受理、过账、对账全链路同一口径；
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

### 1.4 手续费净额结算（`service/PaymentService` + `service/FeeConfiguration`）

受理一笔支付时可以携带手续费要素，系统按**受理当时生效的费率**算出手续费，并把
手续费与扣费后净额分别记到不同账户。

- **费率单位：万分比（basis points, bp）**，整数。例如 `60` 表示万分之六十 = `0.60%`。
  合法区间 `[0, 10000)`；`10000`（=100%）及以上、负数一律 `INVALID_PARAMETER` 拒绝。
- **费率来源（请求优先，其次本地配置）**：
  - 请求体 `feeRateBp` 非空 → 用请求费率；`feeAccountNo` 缺省时用配置里的手续费账户；
  - 请求未带费率但本地配置 `enabled=true` → 用配置费率与配置账户（`PUT /api/fees/config` 可随时改）；
  - 两者都没有 → 该笔无手续费，走 1.3 的两行凭证（与历史行为完全一致）。
  - 只给手续费账户不给费率 → 参数非法拒绝。
- **计算与尾差规则（固定、可解释）**：
  - 理论手续费 = `总额 × 费率bp / 10000`（`BigDecimal` 精确除法，可能带分位以外小数）；
  - 入账手续费 = 理论手续费按 **`HALF_UP`（四舍五入，逢五进位）处理到分**，固定规则不随金额大小变化；
  - 尾差 = `入账手续费 − 理论手续费`，保留**精确小数值**，
    恒满足 `入账手续费 = 理论手续费 + 尾差`——每笔“分位以外的小数去了哪里”都能解释。
    尾差**可正可负**：
    - 理论手续费分位后一位 `≥5` 进位时尾差为正（如理论 `0.035035` → 入账 `0.04`，尾差 `+0.004965`），
      多收部分计入手续费收款账户；
    - 分位后一位 `<5` 舍去时**尾差为负**（如理论 `0.004` → 入账 `0.00`，尾差 `-0.004`），
      被舍去部分留在收款方净额里（净额按入账后的手续费计算，`净额 = 总额 − 入账手续费`），
      手续费账户不补收；
  - 净额 = `总额 − 入账手续费`，固定 2 位小数，**必须为正**，否则在记账前拒绝。
- **费率生效但手续费算成 0.00（零费率或费率极小被舍入为 0.00）时的留痕规则**：
  - 只要费率实际生效（请求显式带了 `feeRateBp`（含 `0`），或本地默认费率 `enabled=true`），
    即使按费率算出的理论手续费 HALF_UP 到分后正好是 `0.00`（例如 `1.00 × 4bp = 0.0004 → 0.00`），
    **实际费率、入账手续费 `0.00`、理论手续费、负尾差、扣费后净额（=总额）与手续费账户
    仍以 `FeeSettlement` 快照随支付一起保留**，之后单独查这笔支付的手续费信息能查到费率与净额，
    **不会因为手续费是 0 就被当成“没有手续费”而查无此记录**；
  - 账务上按**净额 = 总额走两行凭证**入账（借付款方总额、贷收款方总额），
    **不产生 0 金额分录行**、手续费账户无发生额（账本禁止借贷同时为零的分录行）；
  - 这与“请求完全不带费率要素且本地配置关闭”的支付有本质区别：后者没有 `FeeSettlement`，
    单独查询返回 `404 FEE_SETTLEMENT_NOT_FOUND`。
- **入账（三行凭证，天然借贷平衡）**，以总额 100.00、费率 60bp（手续费 0.60、净额 99.40）为例：

  ```
  借：1001 付款方在途资金   100.00
  贷：2001 收款方待结算款    99.40   （扣费后净额）
  贷：3001 手续费收入待结算   0.60   （手续费）
  ```

  恒等式 `总额 = 净额 + 手续费`，借方合计 = 贷方合计；余额同样满足全账户合计恒为 0。
- **记账前校验、原因可区分**：费率非法、扣费后净额非正、手续费账户不存在、
  手续费账户与付款方/收款方相同、只给账户不给费率等，全部在产生任何指令/分录之前拒绝，
  错误原因文案彼此区分（统一错误码 `INVALID_PARAMETER`，HTTP 400）。
- **费率快照锁定**：每笔支付实际使用的费率、手续费账户、理论手续费、入账手续费、
  尾差、净额以 `FeeSettlement` 固化在支付指令上。之后无论怎样 `PUT /api/fees/config`
  调整费率，已受理支付的手续费/净额/分录/对账结论都不变。
- **单独查询**：`GET /api/payments/{businessId}/fee-settlement` 返回该笔快照；
  费率生效但手续费为 `0.00` 的支付同样返回 `200`（快照中 `feeAmount=0.00`、`netAmount=总额`，
  `roundingAdjustment` 为负尾差）；只有支付存在但受理时确实不含任何费率要素时
  才返回 `404 FEE_SETTLEMENT_NOT_FOUND`（与“支付不存在”的 400 区分）。

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
| 字段缺失/金额非法/账户不存在/收付相同/费率非法/净额非正 | `InvalidParameterException` | `INVALID_PARAMETER` | 400 |
| 同 `businessId`、**内容完全相同**（含费率与手续费账户）的重复提交（串行或并发的输家；含已 FAILED 的重试） | `DuplicateRequestException` | `DUPLICATE_REQUEST` | 409 |
| 同 `businessId`、**内容不同**（金额/账户/**费率/手续费账户**被篡改） | `ConflictException` | `STATUS_CONFLICT` | 409 |

并发与重复提交对手续费要素同等生效：同一 `businessId` 不管串行重复还是并发提交，
都只有一笔有效结果，赢家与输家观察到的**费率、手续费、净额、分录金额完全一致**；
失败时账本全额冲正，不留半成品分录、不留半改余额。

对“费率生效但手续费算成 0.00”的支付同样按此强度判定：**两次请求内容完全相同
（含费率与手续费账户）才算重复请求**；哪怕两次算出来的手续费都是 `0.00`，
只要费率不同（如 4bp vs 9bp 对 `1.00` 都是 `0.00`）、手续费账户不同或费率要素有无不同，
一律判 `STATUS_CONFLICT`，而不是重复请求。并发提交同一业务标识时最终只有一笔有效结果，
`0.00` 手续费、净额=总额、两行分录完全一致。

`FAILED` 是终态：同一 `businessId` 相同请求得到 `DUPLICATE_REQUEST`（响应带 `existingStatus=FAILED`），
篡改请求得到 `STATUS_CONFLICT`；需要重新支付请换用新的业务标识。

---

## 3. 对账口径（`service/ReconciliationService`）

渠道数据 `ChannelSettlementRecord(businessId, amount, status, settledAt, fee, netAmount)` 由调用方传入，本地不发起任何渠道调用。
其中 `fee`（渠道手续费）与 `netAmount`（渠道净额）**可选**：两者要么都给、要么都不给（只给其一按参数非法拒绝）。

对每个 `businessId` **恰好产出一条结论**，结论**互斥、可区分**，差异不静默忽略、不笼统合并：

| 结论 `DifferenceType` | 判定口径 |
|-----------------------|----------|
| `MATCHED` | 双方都有，状态一致、总额一致；当任一方带手续费/净额要素时，净额与手续费也一致 |
| `AMOUNT_MISMATCH` | 双方都有、状态一致，但**总额**不一致 |
| `NET_MISMATCH` | 状态一致、总额一致，但**扣费后净额**双方不一致 |
| `FEE_MISMATCH` | 状态一致、总额一致、净额一致，但**手续费**双方不一致 |
| `STATUS_MISMATCH` | 双方都有，但成功/失败口径不一致 |
| `LOCAL_MISSING` | 渠道有单，本地无流水 |
| `CHANNEL_MISSING` | 本地核对范围内有流水，渠道无单 |

- **净额与手续费分开识别、互不混淆**：判定顺序为 状态 → 总额 → 净额 → 手续费。
  净额先不符报 `NET_MISMATCH`（依据中仍列出双方手续费实际值）；净额一致、仅手续费不符报 `FEE_MISMATCH`。
  每条结论的 `basis` 分别写明本地/渠道的净额、手续费**实际值**，保证可解释。
- **本地侧取数**：本地有手续费快照时取快照的净额/手续费（**快照保留的手续费为 `0.00` 时
  就按 `0.00` 与净额=总额参与核对**，渠道若报非零手续费会如实判 `FEE_MISMATCH`，
  不会把这笔当成“没有手续费”而跳过）；本地无手续费快照时净额=总额、手续费=0.00。
  因此“本地无费、渠道报有费”会被如实识别为净额/手续费差异，不会静默当成一致。
- **向后兼容（老批次行为不变）**：渠道记录不带 `fee/net` 时，只比对总额与状态，
  即使本地该笔带手续费也不会产生手续费类差异（依据中标注“按总额口径核对”）。
  历史批次的输入指纹不含新字段，复用/冲突判定结论与扩展前完全一致。
- **精度契约（全链路统一，受理/过账/对账同一套规则）**：
  - 渠道总额/手续费/净额均要求**最多 2 位小数**；超过 2 位一律 `INVALID_PARAMETER` 拒绝，
    且原因带字段名（`渠道金额` / `渠道手续费` / `渠道净额`）可区分，**绝不隐式舍入后继续核对**；
  - 因此不会把“仅精度处理方式不同”（如 `100.005` vs `100.01`）的金额判成一致；
  - 支付金额沿用同一口径（见 1.2）：超精度外部输入直接拒绝，不自动调整后参与记账。
- **状态映射**：本地 `POSTED ↔ 渠道 SUCCESS`，本地 `FAILED ↔ 渠道 FAILED`；
  本地 `ACCEPTED`（未完成）与任何渠道结算状态都不一致。
- **核对范围**：请求中的 `scopeBusinessIds` 为本地范围（决定 `CHANNEL_MISSING` 的检测口径）；
  留空则以全部本地流水为范围。渠道侧始终逐单检查，故 `LOCAL_MISSING` 不会被漏掉。
- **批次可复现**：结果按 `businessId` 排序后生成不可变 `ReconciliationBatch`，并记录输入指纹
  （指纹在手续费/净额出现时额外纳入二者）。相同 `batchId` + 相同输入重复执行 → **直接返回同一历史结论，不重复处理**；
  相同 `batchId` + 不同输入 → `STATUS_CONFLICT` 拒绝，历史结论不可被覆盖。费率快照锁定进一步保证：
  受理后再改费率，已出的对账结论不会变化。
- 渠道数据中同一 `businessId` 出现多条、金额非正、状态为空、`batchId` 为空、
  手续费/净额只给其一、手续费为负、净额非正等，一律 `INVALID_PARAMETER` 拒绝，不做静默取舍。
- 每条结论带 `detail` 与 `basis`（判定依据，含双方状态/总额/净额/手续费），保证结论可解释。

---

## 4. HTTP 接口（可选的演示入口）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/payments` | 受理并过账；请求体 `PaymentCommand`（可选 `feeRateBp`/`feeAccountNo`） |
| GET  | `/api/payments/{businessId}` | 查询单笔指令（含 `feeSettlement` 快照） |
| GET  | `/api/payments/{businessId}/fee-settlement` | 单独查该笔实际费率/手续费/净额/尾差；无手续费要素返回 404 |
| GET  | `/api/payments` | 全部指令 |
| GET  | `/api/ledger/accounts` | 账户余额（含 `3001` 手续费账户） |
| GET  | `/api/ledger/entries` | 全部分录流水 |
| GET  | `/api/fees/config` | 查询当前本地费率配置 |
| PUT  | `/api/fees/config` | 修改本地费率配置（只影响之后受理的支付） |
| POST | `/api/reconciliations` | 执行/复用对账批次；请求体 `ReconciliationCommand`（渠道记录可选 `fee`/`netAmount`） |
| GET  | `/api/reconciliations/{batchId}` | 取回历史批次（结论复现） |
| GET  | `/api/reconciliations` | 全部批次 |

示例：

```bash
# 配置默认费率（可选；也可在每笔请求里直接带 feeRateBp/feeAccountNo）
curl -s -X PUT localhost:8080/api/fees/config -H 'Content-Type: application/json' -d '{
  "enabled":true,"feeRateBp":60,"feeAccountNo":"3001"}'

# 带手续费受理一笔：100.00，60bp => 手续费 0.60、净额 99.40
curl -s -X POST localhost:8080/api/payments -H 'Content-Type: application/json' -d '{
  "businessId":"B-1001","payerAccountNo":"1001","payeeAccountNo":"2001","amountText":"100.00",
  "feeRateBp":60,"feeAccountNo":"3001"}'

# 单独查这笔实际用的费率、手续费、净额与尾差
curl -s localhost:8080/api/payments/B-1001/fee-settlement

# 带费率但手续费算成 0.00：1.00 × 4bp = 0.0004，HALF_UP 到分 = 0.00（尾差 -0.0004）
# 费率/0.00 手续费/净额 1.00 仍随支付保留，查询返回 200（不是 404）
curl -s -X POST localhost:8080/api/payments -H 'Content-Type: application/json' -d '{
  "businessId":"B-1002","payerAccountNo":"1001","payeeAccountNo":"2001","amountText":"1.00",
  "feeRateBp":4,"feeAccountNo":"3001"}'
curl -s localhost:8080/api/payments/B-1002/fee-settlement
# => {"feeRateBp":4,"feeAccountNo":"3001","feeAmount":0.00,"rawFeeAmount":0.0004,
#     "roundingAdjustment":-0.0004,"netAmount":1.00}

# 渠道数据带手续费与净额做分项对账（0.00 手续费按保留值核对）
curl -s -X POST localhost:8080/api/reconciliations -H 'Content-Type: application/json' -d '{
  "batchId":"BATCH-1",
  "channelRecords":[{"businessId":"B-1001","amount":100.00,"status":"SUCCESS",
    "settledAt":"2026-09-29T10:00:00Z","fee":0.60,"netAmount":99.40}],
  "scopeBusinessIds":["B-1001"]}'
```

---

## 5. 本地验证方法

```bash
# 离线编译
mvn -o compile

# 全量测试（40 个），无需任何外部服务
mvn -o test

# 只跑关键场景
mvn -o test -Dtest=PaymentIdempotencyConcurrencyTest   # 20 线程并发重复提交、拒绝原因区分
mvn -o test -Dtest=LedgerAtomicPostingTest             # 过账中途失败、全额冲正、零半成品
mvn -o test -Dtest=ReconciliationDifferenceTest        # 五类对账差异、批次可复现/冲突
mvn -o test -Dtest=PaymentApiIntegrationTest           # HTTP 端到端、三类错误码
mvn -o test -Dtest=FeeSettlementServiceTest            # 手续费净额结算、尾差（含负尾差）、费率快照、非法拒绝
mvn -o test -Dtest=FeeIdempotencyConcurrencyTest       # 手续费并发重复、篡改费率/账户区分、改费率不影响已受理
mvn -o test -Dtest=FeeReconciliationDifferenceTest     # 净额/手续费差异分别识别、超精度拒绝、老请求兼容
mvn -o test -Dtest=FeeApiIntegrationTest               # 手续费 HTTP 端到端、0.00 手续费留痕 200、费率配置、分项对账
mvn -o test -Dtest=ZeroFeeRateRetentionTest            # 0.00 手续费费率留痕/查询、重复与篡改区分、并发唯一、改费率不变、过账失败原子性、对账

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
| `FeeSettlementServiceTest` | 三账户分账与平衡、费率快照单独可查、HALF_UP 尾差与恒等式（**新增舍去为负尾差 -0.0004 且快照保留**）、费率/净额/账户非法拒绝、改配置后快照不变、配置驱动开关 |
| `FeeIdempotencyConcurrencyTest` | 带手续费 20 线程并发仅 1 笔且结果唯一、串行重复 DUPLICATE、篡改费率/手续费账户 CONFLICT、改费率后重放仍按受理快照 |
| `FeeReconciliationDifferenceTest` | 三要素一致 MATCHED、净额/手续费差异分别识别且依据列实际值、本地无费渠道有费、老请求总额口径兼容、三类超精度金额拒绝、历史批次不被覆盖 |
| `FeeApiIntegrationTest` | 手续费 HTTP 受理/三账户守恒/单独查询/404、**0.00 手续费快照 HTTP 200 可查且两行分录无手续费账户**、费率配置 GET/PUT、HTTP 分项对账 NET_MISMATCH/FEE_MISMATCH/MATCHED |
| `ZeroFeeRateRetentionTest` | **显式费率（含 0bp）与本地默认费率算得 0.00 手续费时费率/0.00/净额留痕可查；两行凭证无 0 金额行、三账户守恒；同内容 DUPLICATE 与费率/账户篡改（手续费同为 0.00）CONFLICT 区分；20 线程并发仅 1 笔；改费率配置后快照/分录/对账不变；过账中途失败全额冲正、零分录、FAILED 快照仍保留；对账 MATCHED/NET_MISMATCH/FEE_MISMATCH 与老口径兼容** |

## 6. 代码结构

```
domain/        Money、Account、LedgerEntry、PaymentInstruction、FeeSettlement、
               ChannelSettlementRecord、ReconciliationResultItem/Batch、枚举
repository/    四个仓储接口 + ConcurrentHashMap 内存实现
service/       LedgerService（原子过账/守恒）、PaymentService（受理幂等+手续费净额）、
               FeeConfiguration（可改的本地费率配置）、ReconciliationService（对账分类）、
               命令对象、三类可区分异常
web/           PaymentController、FeeController、ReconciliationController、
               GlobalExceptionHandler、FeeSettlementNotFoundException
```
