# TurboDL 工业级建设入口

本目录是 TurboDL 后续全范围升级的执行起点。规划完成不等于能力完成，所有结论以实际测试、
原始证据和本目录台账为准。

## 先读什么

| 文档 | 用途 |
| --- | --- |
| [ROADMAP.md](ROADMAP.md) | 全部任务卡、依赖、完成门槛和追加子卡 |
| [EXECUTION_BOARD.md](EXECUTION_BOARD.md) | 批次顺序、并行写集、8 GiB 公网预算、弱模型子卡 |
| [HANDOFF.md](HANDOFF.md) | 给执行模型的最小阅读量、任务模板和交付格式 |
| [API_CONTRACTS.md](API_CONTRACTS.md) | 46 个配置、9 个请求字段、55 个 CLI 标记、事件/SPI 契约 |
| [CONFIG_CONSUMPTION_MATRIX.md](CONFIG_CONSUMPTION_MATRIX.md) | 配置字段到真实消费点/测试/缺口的矩阵 |
| [CLI_API_CONSUMPTION_MATRIX.md](CLI_API_CONSUMPTION_MATRIX.md) | CLI、客户端方法、事件、状态、SPI 的消费矩阵 |
| [COMPETITOR_GAP_MATRIX.md](COMPETITOR_GAP_MATRIX.md) | aria2/Gopeed/axel/XDM 的能力、架构和许可证边界 |
| [NETWORK_ACCEPTANCE.md](NETWORK_ACCEPTANCE.md) | 本地、公网、断点、竞品、资源和 soak 验收协议 |
| [RISK_REGISTER.md](RISK_REGISTER.md) | 已发现的待复现数据/资源/兼容风险 |
| [RELEASE_GATE.md](RELEASE_GATE.md) | 发布候选必须执行的全量、网络、断点、APK 和附件门禁 |

## 第一张可领取卡

`P00 + P00c`：冻结基线、竞品版本、分片台账和任务租约。随后并行领取
`P01/P01b`（实验台 + 8 GiB 流量门禁）和 `P02/P02a + Q00`（契约消费地图 + 测试真实性）。
在这些门禁完成前，不得用临时公网结果或 JUnit 数字宣称工业级完成。

## 不可省略的规则

- 一张卡一个主要行为、明确写集和一个可复现交付。
- 冷门参数/CLI 标记必须证明真实消费，否则进入 no-op 白名单或 `MISSING`。
- 公共协议后端没有 CLI/SDK 路由、断点、取消、预算和真实服务证据时，不能算完成。
- 所有公网流量计入 8 GiB 账本，竞品和重试也算。
- GPL 竞品源码不复制进 MIT core；参考行为也要记录许可证边界。
- 已发布版本不覆盖，新修复进入下一版本。
