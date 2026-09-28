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
| `4001` | REVENUE（收入） | 结算方手续费收入（手续费净额结算入账目标） | 0.00 |

应用启动时由 `LedgerService` 预置；可通过 `createAccount(...)`（服务层/测试）显式开户。

- **余额口径（净额）**：`balance = 借方累计发生额 − 贷方累计发生额`，固定 2 位小数。
  资产类正常在借方（非负），负债类正常在贷方（非正）。
- **守恒口径**：任意时刻全部账户余额之和恒为 `0`（有借必有贷、借贷必相等）。
  `LedgerService.totalBalanceAcrossAccounts()` 可随时校验，过账后也会强制校验，不为 0 即回滚。

### 1.2 金额精度与舍入（`domain/Money`）

- 记账精度：小数点后 **2 位（分）**，内部 `BigDecimal` 的 scale 固定为 2；
- 统一舍入：**`HALF_UP`（四舍五入，逢五进位）**，仅用于系统内部计算结果
  （`Money.of(BigDecimal)` / `Money.round2`，如未来费用分摊）；
- 外部原始输入若真实精度超过 2 位小数（如 `1.005`、`9.971`），**直接判为参数非法**，
  不做隐式截断/吞精度；尾随零写法（`9.970`）与规范写法等价、接受（不含分位以下信息）。
  该口径在受理、过账、对账全链路统一，详见 3.1；
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

## 2A. 手续费净额结算口径（费率快照 + 分账入账）

一笔支付可以带手续费：受理时按**受理当时生效的万分比费率**算出手续费，并把
**手续费与扣费后净额分别记到不同账户**。实际使用的费率、手续费、净额、原始费额、尾差
组成不可变的 `FeeSnapshot` 随支付保存，`GET /api/payments/{businessId}` 可单独查出。

### 2A.1 费率怎么给（请求优先，本地默认策略可改）

两种等价方式，二选一：

1. **请求携带**：`PaymentCommand` 增加 `feeRateText`（万分比 bp 字符串）与 `feeAccountNo`
   （手续费收款账户）。两者必须成对出现，只给一个按 `INVALID_PARAMETER` 拒绝。
2. **本地默认策略**：两者都不给时，受理侧回落到 `FeePolicyService` 的默认策略；
   可通过 `GET/PUT/DELETE /api/fee-policy` 在线查看、调整、清空（只影响调整**之后**受理的支付）。
   默认策略也没有时，该笔按无手续费的老口径处理（与扩展前完全一致）。

费率口径（`domain/FeeRate`）：

- 单位 **bp（basis points，万分比）**：`手续费 = 本金 × 费率bp / 10000`。
  例：`60 bp = 0.60%`，本金 `100.00` → 手续费 `0.60`；
- 费率固定 2 位小数（尾随零写法如 `30.000` 与 `30.00` 等价，接受）；
  真实精度超过 2 位（如 `30.123`）直接拒绝；
- 合法区间 `[0, 10000)`：`0` 免手续费；`10000 bp`（100%）及以上非法（扣完不可能剩正净额）。

### 2A.2 手续费怎么算、尾差去哪（全链路唯一舍入口径）

固定规则（`FeeSnapshot.calculate`），全程 `BigDecimal` 精确运算：

1. `原始手续费 rawFee = 本金 × 费率bp / 10000`（数学精确，不做舍入，最多 8 位小数）；
2. `手续费 fee（到分）= round2(rawFee)`，统一 **HALF_UP**；
3. `净额 netAmount（到分）= 本金 − fee`；
4. **舍入尾差由收款方净额承担**：`roundingDifference = fee − rawFee`（带符号、保留原始精度、
   仅用于解释、不参与记账）。为正=手续费进位（收款方少拿），为负=舍去（收款方多拿）。
   到分口径下恒等式 **`本金 = 净额 + 手续费` 精确成立**。

例：`10.00 × 6bp`：`rawFee=0.0060 → fee=0.01（进位），net=9.99，尾差=+0.0040`；
`10.00 × 4bp`：`rawFee=0.0040 → fee=0.00（舍去），net=10.00，尾差=−0.0040`。
每笔的 `rawFee` 与 `roundingDifference` 都随快照返回，尾差去向可逐笔解释。

### 2A.3 怎么入账（三行平衡凭证）

带手续费时，凭证由两行变为三行，借贷仍相等、余额仍守恒：

```
借：1001 付款方在途资金   100.00   （本金）
贷：2001 收款方待结算款    99.40   （扣费后净额）
贷：4001 结算方手续费收入   0.60   （手续费）
```

- 手续费与净额分别落到手续费账户与收款方账户，互不混用；手续费账户必须存在，
  且与付款方、收款方都不同（否则记账前以 `INVALID_PARAMETER` 拒绝）。
- 费率合法但手续费舍入后为 `0.00`（如 0bp 或极小费率被舍去）时，不产生 0 金额分录行
  （账本不允许借贷同为零的行），按两行过账；费率快照仍保留以便回溯。

### 2A.4 记账前的可区分拒绝

下列情况在**产生任何账务之前**拒绝，均为 `INVALID_PARAMETER`，原因串分别可区分：
费率为空/不可解析/超精度/为负/≥10000bp（“费率非法”）；只带费率或只带账户（“必须同时提供”）；
手续费账户不存在（“账户不存在”）；手续费账户与收/付款方相同（“必须与付款方、收款方均不同”）；
扣费后净额不为正（“扣费后净额不为正”）。

### 2A.5 费率快照锁定（后续改费率不影响历史）

受理瞬间解析出的费率被**复制**进 `FeeSnapshot`，与当时的手续费、净额、凭证一起固化。
之后无论通过请求还是 `PUT /api/fee-policy` 怎么改费率：历史支付的手续费、净额、三行凭证
以及**已经出具的对账结论都不变**（见 `FeeNetSettlementTest.feeSnapshotLocked...`
与 `FeeNetReconciliationTest.historicalBatchConclusion...`）。幂等内容比较也纳入
费率值与手续费账户：同 `businessId` 改费率/改手续费账户均判 `STATUS_CONFLICT`。

---

## 3. 对账口径（`service/ReconciliationService`）

渠道数据 `ChannelSettlementRecord(businessId, amount, status, settledAt, channelFee, channelNetAmount)`
由调用方传入，本地不发起任何渠道调用。`channelFee/channelNetAmount` 可空：**老的对账请求
不带这两个字段时，行为与扩展前完全一致**，只比状态与总额，历史批次不受影响。

对每个 `businessId` **恰好产出一条结论**，结论**互斥、可区分**，差异不静默忽略、不笼统合并。
双方都有且状态一致时，按固定优先级逐项比较，命中即出结论（`basis` 依据里同时列出
**总额、手续费、净额三项的双方实际值**）：

| 结论 `DifferenceType` | 判定口径 |
|-----------------------|----------|
| `MATCHED` | 双方都有，状态一致，且总额/手续费/净额均一致 |
| `FEE_MISMATCH` | 状态一致、渠道单携带费用明细，但**手续费**双方对不上（最先判定，绝不并入金额不符） |
| `NET_MISMATCH` | 状态一致、手续费一致，但**扣费后净额**双方对不上 |
| `AMOUNT_MISMATCH` | 老五类结论：老口径（渠道无费用明细）下总额不符；或费用明细存在但手续费、净额都一致而总额不符 |
| `STATUS_MISMATCH` | 双方都有，但成功/失败口径不一致 |
| `LOCAL_MISSING` | 渠道有单，本地无流水 |
| `CHANNEL_MISSING` | 本地核对范围内有流水，渠道无单 |

- **费用信息兼容**：渠道单**未携带**费用明细 → 完全走老口径（只比状态/总额，不出 FEE/NET 结论）；
  渠道单**携带**费用明细（手续费、净额必须成对）→ 三项分别精确比较。本地无手续费快照的老流水
  以“手续费 `0.00`、净额=本金”作为本地实际值参与比较，依据中注明该口径，不会被静默当成一致。
- **判定优先级固定为 手续费 → 净额 → 总额**：因此“总额不同但手续费一致、净额不同”会被
  明确识别为 `NET_MISMATCH`，手续费对不上永远不会被笼统归为 `AMOUNT_MISMATCH`。
- **不因精度处理方式不同而放过真实差异**：三项都规范到 2 位小数后用 `compareTo` 精确比较，
  表示法差异（`1.00` 与 `1.0`、`9.970` 与 `9.97`）判一致，真实差 1 分（`0.02` vs `0.03`）
  即使源于“对方用了另一种舍入”也判 `FEE_MISMATCH`，不会归成一致。
- 渠道费用字段**必须成对**（只给一个 → `INVALID_PARAMETER`），且渠道单自身必须满足
  `总额 = 手续费 + 净额`（到分精确），不自洽直接拒绝。
- **状态映射**：本地 `POSTED ↔ 渠道 SUCCESS`，本地 `FAILED ↔ 渠道 FAILED`；
  本地 `ACCEPTED`（未完成）与任何渠道结算状态都不一致。
- **核对范围**：请求中的 `scopeBusinessIds` 为本地范围（决定 `CHANNEL_MISSING` 的检测口径）；
  留空则以全部本地流水为范围。渠道侧始终逐单检查，故 `LOCAL_MISSING` 不会被漏掉。
- **批次可复现**：结果按 `businessId` 排序后生成不可变 `ReconciliationBatch`，并记录输入指纹
  （指纹已含渠道手续费/净额，`null` 显式区分）。相同 `batchId` + 相同输入重复执行 →
  **直接返回同一历史结论，不重复处理**；相同 `batchId` + 不同输入 → `STATUS_CONFLICT` 拒绝。
  已受理支付的费率被快照锁定，所以即使之后调整费率，老批次结论仍原样复现。
- 渠道数据中同一 `businessId` 出现多条、金额非正/超精度、状态为空、`batchId` 为空等，
  一律 `INVALID_PARAMETER` 拒绝，不做静默取舍。
- 每条结论带 `detail` 与 `basis`（判定依据，含双方状态与三项金额），保证结论可解释。

### 3.1 金额精度契约（受理、过账、对账全链路统一）

- 记账精度固定 **2 位小数（分）**，内部 `BigDecimal` scale=2；系统内部计算结果统一
  **HALF_UP** 到分（手续费到分是唯一的费用舍入点，尾差规则见 2A.2）。
- **一切外部金额**——支付金额、渠道总额/手续费/渠道净额、费率——都按同一真实精度口径校验：
  按去尾零后的数值判断，尾随零写法（`9.970`）与规范写法等价、接受；
  真实含分位以下小数（`9.971`、`1.005`）**一律 `INVALID_PARAMETER` 拒绝且原因字段可区分**，
  绝不被自动舍入后继续参与记账或对账。
- 适用范围：`Money.of(String)`（受理）、`FeeRate`（费率）、`ReconciliationService.strictScale2`
  （对账渠道总额/手续费/净额）三处共用该口径。

---

## 4. HTTP 接口（可选的演示入口）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/payments` | 受理并过账；请求体 `PaymentCommand`（可选 `feeRateText`、`feeAccountNo`） |
| GET  | `/api/payments/{businessId}` | 查询单笔指令（含 `feeSnapshot`、`fee`、`netAmount`） |
| GET  | `/api/payments` | 全部指令 |
| GET  | `/api/ledger/accounts` | 账户余额（含手续费收入账户 4001） |
| GET  | `/api/ledger/entries` | 全部分录流水 |
| GET  | `/api/fee-policy` | 查看本地默认费率策略 |
| PUT  | `/api/fee-policy` | 更新默认费率策略 `{feeRateText, feeAccountNo}`（只影响之后受理的支付） |
| DELETE | `/api/fee-policy` | 清空默认费率策略 |
| POST | `/api/reconciliations` | 执行/复用对账批次；请求体 `ReconciliationCommand` |
| GET  | `/api/reconciliations/{batchId}` | 取回历史批次（结论复现） |
| GET  | `/api/reconciliations` | 全部批次 |

示例：

```bash
# 无手续费（老口径）
curl -s -X POST localhost:8080/api/payments -H 'Content-Type: application/json' -d '{
  "businessId":"B-1001","payerAccountNo":"1001","payeeAccountNo":"2001","amountText":"100.00"}'

# 带手续费：100.00 × 60bp = 手续费 0.60、净额 99.40
curl -s -X POST localhost:8080/api/payments -H 'Content-Type: application/json' -d '{
  "businessId":"B-1002","payerAccountNo":"1001","payeeAccountNo":"2001","amountText":"100.00",
  "feeRateText":"60","feeAccountNo":"4001"}'

# 等价方式：配置本地默认费率后，请求不带费率即按默认策略受理（只影响之后的支付）
curl -s -X PUT localhost:8080/api/fee-policy -H 'Content-Type: application/json' -d '{"feeRateText":"60","feeAccountNo":"4001"}'

# 查单笔实际费率/手续费/净额
curl -s localhost:8080/api/payments/B-1002

# 带渠道手续费/净额的对账（三项分别比对）
curl -s -X POST localhost:8080/api/reconciliations -H 'Content-Type: application/json' -d '{
  "batchId":"BATCH-FEE-1",
  "channelRecords":[{"businessId":"B-1002","amount":100.00,"status":"SUCCESS",
    "channelFee":0.60,"channelNetAmount":99.40,"settledAt":"2026-09-29T10:00:00Z"}],
  "scopeBusinessIds":["B-1002"]}'

# 老的对账请求（不带 channelFee/channelNetAmount）行为不变
curl -s -X POST localhost:8080/api/reconciliations -H 'Content-Type: application/json' -d '{
  "batchId":"BATCH-1",
  "channelRecords":[{"businessId":"B-1001","amount":100.00,"status":"SUCCESS","settledAt":"2026-09-29T10:00:00Z"}],
  "scopeBusinessIds":["B-1001"]}'
```

---

## 5. 本地验证方法

```bash
# 离线编译
mvn -o compile

# 全量测试（31 个），无需任何外部服务
mvn -o test

# 只跑关键场景
mvn -o test -Dtest=PaymentIdempotencyConcurrencyTest   # 20 线程并发重复提交、拒绝原因区分
mvn -o test -Dtest=LedgerAtomicPostingTest             # 过账中途失败、全额冲正、零半成品
mvn -o test -Dtest=ReconciliationDifferenceTest        # 五类老对账差异、批次可复现/冲突
mvn -o test -Dtest=FeeNetSettlementTest                # 手续费净额计算、三行平衡、尾差、记账前拒绝、费率快照锁定
mvn -o test -Dtest=FeeIdempotencyConcurrencyTest       # 带手续费并发仅一笔、篡改费率冲突、失败零半成品
mvn -o test -Dtest=FeeNetReconciliationTest            # 净额/手续费差异分别识别、老请求不变、超精度拒绝
mvn -o test -Dtest=FeeApiIntegrationTest               # HTTP 费用受理/快照查询/对账/费率策略/超精度
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
| `FeeNetSettlementTest` | 手续费与净额分账（三行平衡、守恒）；HALF_UP 到分尾差由净额承担且可解释；费率/净额/账户类记账前拒绝原因区分；**调费率后历史快照/凭证不变**；费率净额随支付可查；0bp |
| `FeeIdempotencyConcurrencyTest` | 20 线程带手续费并发仅 1 笔且手续费/净额/三行凭证完全一致；改费率/改手续费账户=>CONFLICT；费用凭证中途失败全额冲正、零残留、FAILED 后重复只见终态 |
| `FeeNetReconciliationTest` | FEE_MISMATCH/NET_MISMATCH 分别识别且依据列三项实际值；老渠道请求行为不变；超精度（总额/费/净额）拒绝原因区分；表示法差异判一致但 1 分真实差异不放过；**调费率后历史批次结论复现** |
| `FeeApiIntegrationTest` | HTTP 费用受理与快照查询、三账户守恒、手续费/净额对账分类、费率策略查/改/清、超精度与非法费率 400 |
| `PaymentApiIntegrationTest` | HTTP 端到端、三类错误码、余额守恒、对账批次取回（老能力不回归） |

## 6. 代码结构

```
domain/        Money、FeeRate（万分比费率）、FeeSnapshot（费率/手续费/净额/尾差快照）、
               Account、LedgerEntry、PaymentInstruction、
               ChannelSettlementRecord（可带渠道手续费/净额）、ReconciliationResultItem/Batch、枚举
repository/    四个仓储接口 + ConcurrentHashMap 内存实现
service/       LedgerService（原子过账/守恒）、PaymentService（受理幂等/费用解析与分账）、
               FeePolicyService（可在线调整的本地默认费率策略）、
               ReconciliationService（对账分类：手续费/净额/总额分别判定）、
               命令对象、三类可区分异常
web/           PaymentController、ReconciliationController、FeeController、GlobalExceptionHandler
```
