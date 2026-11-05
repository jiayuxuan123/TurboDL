# P01b 持久流量预算

`traffic_budget.py` 为后续公网 benchmark runner 提供 8 GiB 持久流量预算。
它不是下载模拟器，也不访问网络；调用方在开跑前预留字节数，在完成或失败后
用实际响应体字节数对账。

## 默认值与持久化

- 默认总上限：`8192 MiB`。
- 默认账本目录：`_bench/results/traffic-budget/`。它是所有公网实验唯一账本；不要用新的空目录重置预算。
- 可用 `--data-dir` 或环境变量 `TURBODL_BENCH_TRAFFIC_BUDGET_DIR` 覆盖。
- 账本文件：`traffic-ledger.jsonl`；锁文件：`traffic-ledger.lock`。
- 每个 JSONL 记录包含 `payload` 和 `checksum`。读取时校验 SHA-256；校验失败
  直接拒绝操作，不把损坏记录解释成新的扣账。
- 进程异常退出后，预留仍保存在账本里。下次用同一数据目录打开即可在 `report`
  中看到并继续 `reconcile`。

不要提交 `_bench/results/`。账本元数据也不应写入真实 URL、Cookie、令牌或其他秘密。

## 计费规则

`used_bytes = consumed_bytes + reserved_bytes`

- `reserved_bytes`：尚未 reconcile 的预留估算之和。
- `consumed_bytes`：已 reconcile 的实际响应体字节之和。
- `remaining_bytes = total_bytes - used_bytes`。
- 预留时若 `estimated_bytes > remaining_bytes`，原子拒绝并抛出
  `BudgetExceeded`。
- reconcile 后归还 `estimated_bytes - actual_response_bytes`；实际值更大时
  会减少余额，允许余额变成负数，因为流量已经发生。
- overrun 判据是 `actual_response_bytes > estimated_bytes * 1.20`。恰好
  20% 不算 overrun；CLI 在 overrun 时返回退出码 `3`。

## Python 调用

```python
from traffic_budget import TrafficBudget

budget = TrafficBudget("path/to/runtime", total_bytes=8192 * 1024 * 1024)
reservation = budget.reserve(
    "run-20261104-001",
    512 * 1024 * 1024,
    {"case": "public-network"},
)

# 下载/请求结束后，actual_response_bytes 应来自实际收到的响应体计数。
result = budget.reconcile(
    reservation.run_id,
    actual_response_bytes=500 * 1024 * 1024,
    status="completed",  # completed | failed | aborted
)
if result.overrun:
    print("actual traffic exceeded the reservation by >20%")
```

`reserve()` 和 `reconcile()` 都在 OS 文件锁内完成读取、判断和追加。相同
`run_id`、相同估算与元数据的重复 `reserve` 是幂等的；相同实际字节与状态的
重复 `reconcile` 也是幂等的，不会重复扣账。不同值则会抛出冲突异常。

### 字段

`Reservation`：

| 字段 | 含义 |
|---|---|
| `run_id` | 调用方提供的唯一运行标识 |
| `estimated_bytes` | 该 run 的预留字节数 |
| `metadata` | 调用方附带的 JSON 对象 |
| `reserved_at` | UTC 预留时间 |
| `remaining_bytes` | 本次预留后的剩余字节数 |
| `duplicate` | 是否为相同输入的幂等重复调用 |

`ReconcileResult`：

| 字段 | 含义 |
|---|---|
| `run_id` | 被对账的 run |
| `estimated_bytes` | 原预留估算 |
| `actual_response_bytes` | 实际响应体字节数 |
| `status` | `completed`、`failed` 或 `aborted` |
| `reconciled_at` | UTC 对账时间 |
| `overrun` | 实际值是否超过估算的 120% |
| `overrun_bytes` | `max(0, actual - estimated)` |
| `overrun_ratio` | `actual / estimated`；估算为 0 且实际大于 0 时为 `null` |
| `duplicate` | 是否为相同输入的幂等重复对账 |
| `total_bytes` | 总预算 |
| `consumed_bytes` | 全部已对账实际字节 |
| `reserved_bytes` | 全部仍开放的预留字节 |
| `remaining_bytes` | 当前余额，超出总预算时可为负数 |
| `over_budget` | 当前 `remaining_bytes < 0` |

`BudgetReport` 包含 `total_bytes`、`consumed_bytes`、`reserved_bytes`、
`used_bytes`、`remaining_bytes`、`open_reservation_count`、
`reconciled_run_count` 和 `open_reservations`。每个开放预留包含
`run_id`、`estimated_bytes`、`metadata`、`reserved_at`。

## CLI

```text
python traffic_budget.py remaining
python traffic_budget.py report
python traffic_budget.py report --json
python traffic_budget.py --data-dir D:\bench-budget reserve \
  --run-id run-001 --estimated-bytes 104857600 \
  --metadata "{\"case\":\"public-network\"}"
python traffic_budget.py --data-dir D:\bench-budget reconcile \
  --run-id run-001 --actual-response-bytes 99614720 --status completed
```

全局参数放在子命令前：`--data-dir`、`--total-mib`、`--total-bytes`、`--json`。
`remaining` 默认只输出一个整数；`report` 默认输出人类可读摘要。`--json`
输出单行 JSON，便于 runner 解析。

退出码：

| 退出码 | 含义 |
|---|---|
| `0` | 成功 |
| `1` | 参数、I/O、账本损坏或配置错误 |
| `2` | 预算不足，预留被拒绝 |
| `3` | reconcile 成功，但实际流量超过估算 20% |
| `4` | run 不存在或重复调用值冲突 |

## 测试

测试只使用 Python 标准库和临时目录：

```bash
cd _bench/industrial
python -m unittest -v test_traffic_budget.py
```

覆盖并发跨进程 reserve、预算不足、重复 reconcile、异常退出恢复、损坏/尾部
未完成记录、20% overrun 边界，以及 `remaining`/`report` CLI。

## 统一真实网络 runner

`run_engine_compare.py` 在同一个 URL、代理和 HTTP/1.1 条件下交错运行
`curl`、TurboDL fat JAR 与 aria2。每个 `引擎 × 连接数 × repeat` 使用独立
输出和日志；失败输出保留。原始 URL 只参与请求，不写入 raw JSONL、summary
或日志，公开结果只保存 URL SHA-256。

```powershell
cd _bench/industrial
python run_engine_compare.py `
  --url $env:TURBODL_BENCH_URL `
  --size 33554432 `
  --sha256 $env:TURBODL_BENCH_SHA256 `
  --engines curl,turbo,aria2 `
  --connections 1,8,16 `
  --repeats 5 `
  --seed 20261104 `
  --proxy http://127.0.0.1:7897 `
  --prewarm `
  --aria2-exe 'C:\path\to\aria2c.exe'
```

先加 `--dry-run` 可只打印总预算估算，不下载。估算为
`size × repeats × (engines × connections)`，启用 prewarm 时默认每个所选
引擎各预热一次（`size × engines`）；`--prewarm-engine` 可显式退回到单引擎
预热。正式运行在下载前一次性 reserve 全部 lease，随后逐 cell 按实际
响应体 reconcile；异常退出时开放 lease 会以 aborted 关闭。

退出码：

| 退出码 | 含义 |
|---|---|
| `0` | 所有比较样本通过 |
| `2` | 参数、引擎配置或账本配置错误 |
| `3` | 至少一个样本退出码/大小/SHA-256 校验失败 |
| `4` | 预算不足或实际流量 overrun |

测试：

```bash
python -m unittest -v test_run_engine_compare.py test_traffic_budget.py
```

测试不访问公网；网络命令构造、预算预留/回滚/对账、固定种子交错顺序、
速度排序、失败文件保留与 URL 脱敏均由离线 unittest 覆盖。
