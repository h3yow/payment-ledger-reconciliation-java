# 本地支付清算与对账（payment-ledger-reconciliation-java）

一个**仅依赖本地运行**的支付清算/对账演示工程：用内存账本与内存渠道结算数据模拟
"受理 → 记账 → 渠道结算 → 对账"全链路。不接入任何外部服务、不使用真实凭据：
无数据库、无消息队列、无网络支付渠道，渠道结算数据由调用方本地构造。

技术栈：Java 21 语法级别 / Spring Boot 3.4 / JUnit 5，`mvn -o`（离线）即可构建与验证。

---

## 1. 账本模型

### 1.1 标准科目（启动时由 `DataInitializer` 建立，余额起始为 0）

| 科目代码 | 名称 | 类别 | 正常方向 |
|---|---|---|---|
| `1101` | 应收渠道清算款 | 资产 | 借方余额 |
| `1102` | 已清算备付金 | 资产 | 借方余额 |
| `2101` | 待清算客户资金 | 负债 | 贷方余额 |
| `2102` | 已清算客户资金 | 负债 | 贷方余额 |

余额按**借方发生额累计 / 贷方发生额累计**两栏分别保存，另有 `signedBalance = 借 - 贷`
（借为正、贷为负）用于守恒校验。

### 1.2 三笔业务的复式分录（每一笔内部借贷合计都相等）

**① 受理（PENDING）**——挂应收与待清算义务：

```
借 1101 应收渠道清算款   X
  贷 2101 待清算客户资金   X
```

**② 渠道确认结算（SUCCESS）**——款项落地、客户资金转已清算（一笔过账 4 条分录）：

```
借 1102 已清算备付金     X
  贷 1101 应收渠道清算款   X
借 2101 待清算客户资金   X
  贷 2102 已清算客户资金   X
```

**③ 支付失败（FAILED）**——红字冲销受理分录（方向相反、金额相等的追加分录，不删历史）：

```
借 2101 待清算客户资金   X
  贷 1101 应收渠道清算款   X
```

> 红字冲销后借贷发生额两栏各自保留历史累计（都为 X），但**净有符号余额为 0**。
> 这是复式账的标准留痕方式：流水可回溯，而不是把旧分录抹掉。

### 1.3 守恒恒等式（任意时刻成立）

```
全部分录的借方金额合计 == 全部分录的贷方金额合计
(1101 + 1102) 净借方余额 == (2101 + 2102) 净贷方余额      即 资产 == 负债
```

### 1.4 过账的原子性（`LedgerService.post`）

1. 入参校验：分录 ≥ 2 行、金额为正、账户存在、**借方合计 == 贷方合计**，否则拒绝；
2. 一把全局过账锁串行化（本地单机模型），锁内对 `postingId` 二次判重；
3. 逐行更新账户余额（桶锁内原子累加，不丢更新），记录"已做动作"；
4. 写分录流水（全局自增流水号，按 postingId / bizId 双索引可回溯）；
5. 提交后自检借贷平衡；
6. **任何一步抛异常**：先按 postingId 整笔删除本次半成品分录，再按相反顺序冲销已更新的
   余额。异常返回后系统状态等价于"这笔过账从未发生"，不会留下半条分录或只更新部分余额。

> 工程提供**故障注入点** `LedgerService.setFailureHook(...)`，在"余额已更新、分录未写完"
> 时刻抛错，专用于验证中途失败不留半成品（见 `LedgerPostingTest`）。

## 2. 金额精度与舍入口径

- 统一值对象 `Money`，内部 `BigDecimal` 固定 **2 位小数（人民币"分"）**；
- 入参超过 2 位小数**直接拒绝**（`INVALID_PARAMETER`），不做隐式截断，杜绝不同入口舍入不一致；
- 空值、非数字同样拒绝，且错误信息相互可区分；
- 仅有加减运算（不产生新小数位）；如遇除法场景，统一口径为 **HALF_UP（四舍五入）**；
- 等值判断用 `compareTo`，不受 `BigDecimal` scale 影响（`10.5` 与 `10.50` 相等）。

## 3. 幂等与状态机

### 3.1 三层幂等键

| 键 | 作用域 | 并发实现 | 重复提交结论 |
|---|---|---|---|
| `bizId` | 支付受理 | `ConcurrentHashMap.putIfAbsent` 原子占坑 | `DUPLICATE_REQUEST`（409） |
| `postingId`（=`bizId:阶段`） | 账本过账 | 全局过账锁内二次判重 | `POSTING_FAILED`，信息为"过账编号重复" |
| `batchId` | 对账批次 | `putIfAbsent` 原子占坑 | `STATE_CONFLICT`（409），历史可查 |

同一 `bizId` 的**顺序重复与并发重复都只有一笔产生有效结果**；落败请求拿到可区分的
`DUPLICATE_REQUEST`。重复时金额即使不同也判为身份冲突（信息中附"金额不一致=X"），
绝不覆盖既有单。

### 3.2 支付状态机

```
PENDING ──markSuccess──▶ SUCCESS（终态）
PENDING ──markFailed───▶ FAILED （终态，同时红字冲销受理分录）
```

- 只有 `PENDING` 可以推进；终态再推进一律 `STATE_CONFLICT`，信息中给出当前状态；
- 状态推进与账本过账绑定：**先过账、后改状态**。过账失败 → 状态保持不变，可重试；
  极小竞态下若"过账成功但 CAS 改状态落败"，立即红字冲销该笔过账并报状态冲突——
  不允许出现"状态变了账没动 / 账动了状态没变"。
- 受理阶段若占坑成功但首次挂账失败，订单置 FAILED 并注明"受理挂账失败: 原因"，
  账本因过账回滚保持为空，结论一致可解释。

## 4. 对账口径（`ReconciliationService`）

以 `bizId` 为键，取**本地单集合与渠道结算集合的并集**，每个 bizId 恰好产出一条结论，
用 `TreeSet` 保证结论顺序稳定。

**状态映射**：本地 `SUCCESS` 应对应渠道 `SETTLED`；本地 `PENDING / FAILED` 应对应
渠道 `NOT_SETTLED`。

**分类优先级（五类互斥，不静默忽略、不笼统合并）**：

| 条件 | 分类 | 判定依据（落库在 reason，接口可见） |
|---|---|---|
| 渠道有、本地无 | `LOCAL_MISSING` | 本地缺失，附渠道金额/状态 |
| 本地有、渠道无 | `CHANNEL_MISSING` | 渠道缺失，附本地金额/状态 |
| 两侧都有、状态口径不一致 | `STATUS_MISMATCH` | 本地状态→期望渠道状态→实际渠道状态 |
| 状态一致、金额不一致 | `AMOUNT_MISMATCH` | 双方金额 + `差额(渠道-本地)` |
| 金额、状态都一致 | `MATCHED` | 双方金额/状态回显 |

- 渠道数据集内部若出现重复 bizId，直接 `INVALID_PARAMETER` 拒绝（无法形成唯一结论）；
- 结论 **append-only**：批次一旦生成不可改写；同 batchId 重新对账被拒绝；
  用同 batchId 查询得到的结论与首次逐字一致（**历史可复现**）。

## 5. 错误码（失败原因可区分）

| code | HTTP | 触发场景 |
|---|---|---|
| `INVALID_PARAMETER` | 400 | bizId/批次号为空、金额空/非数字/超 2 位小数/≤0、借贷不平衡、账户不存在、渠道入参缺字段或内部 bizId 重复 |
| `DUPLICATE_REQUEST` | 409 | 同 bizId 顺序/并发重复受理（信息区分金额一致/金额不一致/并发落败） |
| `STATE_CONFLICT` | 409 | 终态非法流转、对账批次重复处理 |
| `POSTING_FAILED` | 500 | 过账编号重复、过账中途失败（保证已回滚，无半成品） |
| `ACCOUNT_NOT_FOUND` | 400 | 过账引用了不存在的账户 |

响应体形如：`{"code":"DUPLICATE_REQUEST","message":"...可区分原因...","httpStatus":409}`。

## 6. HTTP 接口（本地手工验证）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/local/payments` | 受理，body：`{"bizId":"...","amount":"100.00"}` |
| GET  | `/local/payments/{bizId}` | 查询支付单 |
| POST | `/local/payments/{bizId}/success` | 本地模拟渠道结算成功 |
| POST | `/local/payments/{bizId}/failed` | 本地模拟失败，body：`{"reason":"..."}` |
| POST | `/local/reconciliations` | 对账，body 携带本地模拟渠道记录数组 |
| GET  | `/local/reconciliations/{batchId}` | 复现历史批次结论 |
| GET  | `/local/ledger/accounts` | 四个科目余额（守恒核验） |
| GET  | `/local/ledger/entries` | 全部分录流水（回溯） |

## 7. 本地验证方法

### 7.1 构建与全量测试（离线，无需任何外部服务）

```bash
mvn -o test                 # 编译 + 18 个测试用例
mvn -o package              # 打包（跳过 jar 检查可用 -DskipTests）
```

测试覆盖：

| 测试类 | 覆盖点 |
|---|---|
| `MoneyTest` | 2 位精度、scale 无关等值、超精度拒绝、非法格式可区分 |
| `PaymentIdempotencyTest` | **20 线程并发同一 bizId 仅 1 笔生效**、顺序重复、金额冲突、参数非法、状态冲突、失败红字冲销与净额归零 |
| `LedgerPostingTest` | **故障注入下过账中途失败不留半成品、余额回滚**、不平衡/零金额/未知账户拒绝、postingId 重复拒绝、**50 笔并发过账后资产=负债、借贷合计相等** |
| `ReconciliationTest` | 五类差异各自分类与判定依据、批次幂等、历史逐字复现、渠道入参重复拒绝 |
| `PaymentHttpE2ETest` | HTTP 全链路：受理/重复 409/非法 400/结算/终态冲突/金额不符对账/批次重复/余额流水核验 |

测试日志以 `[关键输入]`、`[判定依据]`、`[守恒判定]`、`[对账结论]` 等前缀打印输入与结论。

### 7.2 手工 curl 冒烟

```bash
mvn -o spring-boot:run &

curl -s -XPOST localhost:8080/local/payments -H 'Content-Type: application/json' \
  -d '{"bizId":"T-1","amount":"100.00"}'
curl -s -XPOST localhost:8080/local/payments/T-1/success
curl -s -XPOST localhost:8080/local/reconciliations -H 'Content-Type: application/json' \
  -d '{"batchId":"B1","records":[{"bizId":"T-1","amount":"99.00","status":"SETTLED","settleBatchId":"S1"}]}'
curl -s localhost:8080/local/ledger/accounts
curl -s localhost:8080/local/ledger/entries
```

预期对账结论为 `AMOUNT_MISMATCH`（差额 -1.00）；账户侧 1102 借方 100.00、2102 贷方 100.00，
资产 == 负债。
