# turbo-plugin-js 架构说明

本报告回答三个问题：改了什么、为什么可以这么改、对现有生态有什么影响。
模块使用文档见 [README.md](README.md)。

## 1. 最终形态与依赖方向

```
turbodl-core  ←  turbo-plugin-runtime  ←  turbo-plugin-js  ←  JS Plugin（脚本，进程外知识）
                          ↑
        turbo-plugin-bootstrap / turbo-plugin-hls / demo（同级或上层消费者）
```

- `turbo-plugin-js` 是系统插件 `loader.js`（category `turbodl-loader`），与 `turbo-plugin-hls`
  同级、互为 peer；它同时是 `Plugin`（拥有生命周期与 disposer）和 `PluginLoaderProvider`
  的注册者（`loaderId = "js"`）。
- 引擎（QuickJS / `io.github.dokar3:quickjs-kt-jvm:1.0.15`）是 `implementation` 依赖，
  不出现在本模块的编译期 API 上；JS 知识止步于本模块。
- **非污染约束已验证**：`turbodl-core` 全量 grep 无任何 QuickJS/JavaScript 引用，
  `turbo-plugin-runtime` 无任何 JS 引擎依赖，`turbo-plugin-bootstrap` 默认装配不含 JS。

## 2. 对 turbo-plugin-runtime 的改动（generic，非 JS 专属）

本次工作对 runtime 的全部改动只有一个主题：**把「加载器生产插件、并拥有它们」做成内核的
通用机制**，任何语言的 provider 都受益：

1. `PluginHost.loadSource(source)` — 把 `PluginSource` 路由给已注册的
   `PluginLoaderProvider`（按 priority 降序、首个 `canLoad` 胜出），安装产出的插件并返回 id。
   内核不解释 source，只路由；loader 抛错被隔离为诊断（空列表），不腐蚀宿主。
2. `producedBy` 归属边 + `uninstall` 级联 — 产出的插件记在**注册该 provider 的插件 id** 名下；
   卸载 loader 时先按逆安装序卸载其子插件。这关上了 `PluginSource → loader → Plugin → host`
   的所有权环，是「unload loader.js 安全」的内核保证（JS 侧的 drain 序列在模块自己的
   `JsScriptPlugin.dispose` 里）。归属只记**本次真正新装**的 id：重复 id 被安装环节忽略时，
   所有权也不回填，否则 `uninstall(loader)` 会级联掉一个它从未装过的插件（见 §5 第 11 条）。
3. `installAll` + 依赖门控循环（既有能力的补强）— 批量安装后统一解析 WAITING。
4. 注释面：`Plugin` / `PluginContext` / `ServiceRegistry` / `EventBus` / `ExtensionPoint` /
   `ApiVersion` / `DownloadBackend` / `ExtensionPoints` / HLS 与 bootstrap 的
   「future JS provider（未来 JS 提供者）」全部改为对既成事实的准确描述（JS 提供者即
   `turbo-plugin-js`，且 backend/service 面**刻意不**向脚本开放）。仅注释，无行为。
5. 卸载并发语义收敛（后续补强）：`onUnload` + disposer 排到 `lock` 之外（慢卸载不再拖住
   无关 install/uninstall），用 `unloading` 领取集维持「同 id 至多一个实例」，`shutdown` 后置终态
   拒装；`Managed` 增加单调 `seq`，让「逆安装序」第一次有了真正的数据依据（见 §5 第 18、19 条）。

### Breaking Change 评估：**无**

- 全部为新增 API（`loadSource` / `producedBy` / `installAll`）或纯注释；
  既有签名、语义、状态机、握手规则零改动。
- `PluginLoaderProvider`/`PluginSource` 的内核形状未变（只有文档从 "future iteration" 改为
  实然描述）。

### Migration 评估：**无需迁移**

- 现有 Kotlin 插件、HLS 插件、Bootstrap 装配不依赖这些新成员即可照常编译运行。
- 想要 JS 能力的宿主只加一个构造参数：
  `TurboBootstrap.create(extraPlugins = listOf(JsPluginLoaderPlugin(...)))` —— **bootstrap 无 API 变更**。

## 3. 兼容性证据（实测）

- 全仓 `./gradlew test`：**258 通过 / 0 失败 / 0 错误 / 0 跳过**（turbodl-core 112、
  turbo-plugin-runtime 22、turbo-plugin-hls 36、turbo-plugin-bootstrap 2、
  turbo-plugin-js 86）。上面的数字由 `*/build/test-results/test/*.xml` 直接汇总而来 ——
  想自己复核就在仓库根目录跑一遍 `./gradlew test`，再看那些 XML，不必采信本文的说法。
- JS 与 Kotlin 混排路由：`JsRoutingTest` 证明 Kotlin(priority 500) 与 JS(默认 200) 在同一
  `LINK_PARSER` 列表里只按 priority 排序；`uninstall("loader.js")` 后 Kotlin parser 完好、
  JS 不再路由、零 runtime 泄漏。
- 并发卸载：路由流量中卸载实例，调用方零异常（gone → 干净 no-op）。
- 数据面隔离：`backend`/`service` 注册被 `unsupported` 拒绝（closed-by-design），
  脚本无法注册 `DownloadBackend`，无法发布宿主 service。

## 4. 关键设计决定（一览）

| 决定 | 理由摘要 |
|---|---|
| 每脚本一个 QuickJS runtime+context | 绑定 API 不提供独立 context 句柄；实例隔离是唯一能同时隔离 globals/heap/limits/中断 的形态；成本是多一点原生内存，对一个下载器的插件数量无所谓 |
| 一实例一线程 + 显式再入拒绝 | QuickJS 不可并发求值；再入 = 提交给自己阻塞的队列 = 死锁，所以 fail fast `unsupported` |
| 粗粒度能力（无流/无 per-chunk 回调） | JS 永不站数据面；`host.http` 返回完整响应/完整文件，错误面与权限面因此都可穷举 |
| 三原语 ABI（`__turbodlCall/Register/Log`）+ JS 侧回调注册表 | QuickJS 的函数句柄在 Kotlin 侧不可调用；id 派发是唯一稳健方向，也使边界面可审计 |
| 信封而非异常（`{ok,data}`/`{ok:false,error}`） | 宿主故障与插件故障两条通道必须可区分；`code` 是可演进的契约，message 不是 |
| 封闭值域 + 手写严格 JSON | 「无 Kotlin 内部对象进 JS」由 codec 强制而非 review；边界两侧都不可信，且不给发布产物加传递依赖 |
| ACTIVE→STOPPING→DRAINING→DISPOSED + 拒绝式 close/drain | 计数是 `inFlight`（调用方）+ `engineBusy`（引擎 worker 任务）两条生命周期之和：超时返回的调用交还前者，但 JS 仍在跑，只看前者会让 drain 误判静默、close 变 use-after-free。宁可泄漏一个 runtime 也不释放 JS 正在读的内存，且泄漏计数可观测 |
| 宿主持有定时器 + watchdog，**分两个池**（timers 2 线程 / watchdog 1 线程） | unload 必须能取消一切 JS 后续触发源；定时器回调本身就是一次 JS 调用（可合法卡死），与打断路径共池时，两个卡住的回调就能让「要杀这段 JS 的中断」排队到 JS 之后。分池后「要杀 JS 的那一刀」永远排在 JS 之前 |
| loader 默认收紧、source 只能交集 | 权限与上界是宿主决定；脚本永远不能抬高字节/时间/内存上限 |
| 内核卸载「锁内改簿记、锁外跑 onUnload」 | 卸载可能阻塞数秒（drain/IO），持锁会拖住无关的 install/uninstall；用 `unloading` 领取集替代长期持锁，保持「同 id 至多一个实例」 |
| 安装序用单调 `seq`，不用 map 迭代序 | `ConcurrentHashMap.values.reversed()` 只是哈希序倒置，不是「逆安装序」；LIFO 必须由显式序号保证 |
| storage key 可逆编码，不做有损替换 | 有损替换会让 `a:b`/`a/b` 折叠成一个文件、互相覆盖；百分号编码注入，`keys()` 反向解码回逻辑 key |
| 不进 bootstrap 默认装配 | JNI 原生依赖（含 Android ABI 验证）必须由宿主选择；纯 Kotlin 发行版保持零变化 |

## 5. 测试期间发现并修复的生产缺陷（累计）

1. `loaderDefaultPriority` 被声明但从未生效（注册永远落 200）。
2. `onDestroy` 信封未解码——失败的 onDestroy 被当成成功。
3. 堆越限错误信息空白（`"null"` / `<NO_MESSAGE>`）→ 改为用引擎自身 `memoryUsage` 判定并报 `limit`。
4. pre-hook 回显 destination 的往返被沙箱误拒 → `fromMap(roundTrip)` 只信任未被改动的宿主路径。
5. `as:'none'` 不报 `bytes` 且按字符而非字节计上限 → 补真实字节数与编码/解码两侧计数。
6. `as:'none'` 可以无界读取（绕过 `maxBytes`）→ 丢弃 body 前仍有界读取。
7. 加载日志整段回显内联脚本（源码/token 进日志）→ `describeSourceUri` 截断。
8. `host.clearTimeout(setTimeout(...))` 句柄形状不匹配（`{id}` vs number → NaN，永远清不掉）→ shim 展开为数字句柄。
9. `JsJson` 接受 `01` 这类非法前导零数字（与所有真实 JS 引擎的 `JSON.parse` 分歧）→ 按 RFC 8259 拒绝。
10. 声明了但越界的 `priority`/`connections` 被**静默回落到默认值**（插件按作者没选过的数字路由）→
    新增严格 `asIntInRange`：未提供 = 用默认；提供了但非法 = `validation` 失败。

第 1–10 条为自测期间发现。第 11–16 条由**外部评审**提出（对照另一份更精简的实现变体），
每条都有对应的回归测试，不是文档式承认：

11. `PluginHost.loadSource()` 把本次产出的**全部** id 记入 `producedBy`，包括因重复而被跳过的那些
    → `uninstall(loader)` 能级联掉一个它从未安装的插件（越权卸载）。修复：`installNew` 只返回
    真正新增的 id，归属记账以其为准。测试 2 例（runtime）：外部直装的插件不被 loader 认领；
    部分重复时 loader 只认领 fresh id，级联命中 fresh、放过 foreign。
12. `JsRuntime.submit()` 的超时路径只 `future.cancel(true)`——worker 停不下来，而调用方的
    `guard.end()` 把 `inFlight` 归零，drain 于是误判静默，`QuickJs.close()` 释放 JS 仍在读的内存
    （use-after-free）。修复：guard 增加第二条 `engineBusy` 计数，包裹引擎 worker 任务的整个生命
    周期；`busyCount = inFlight + engineBusy` 是 drain 唯一可等的量，close 的拒绝诊断把两个数都
    点名。测试 2 例：guard 层面证明「被放弃的 worker 仍算忙」；端到端证明一个真会睡过超时的宿主
    调用无法被 drain 绕过、无法被 close 覆盖（含拒绝文案与线程存活断言）。
13. 定时器回调与 watchdog 中断共用一个 2 线程调度器 → 两个卡住的定时器回调就能把「要杀这段 JS 的
    中断」排到自己后面，看门狗失效。修复：拆池（timers 2 线程、watchdog 单线程且只跑
    `interruptEvaluation()`），disposer 顺序固化为实例 → timers → watchdog。测试 1 例：灌满 timer
    池后 watchdog 任务仍在 5 ms 标称延迟内、亚 500 ms 送达。
14. HTTP 的重定向安全只有文档没有接线：`redirectTarget()` 是死代码，逐跳 origin 复检与跨源凭据
    剥离实际未发生；per-call 的 `followRedirects` ABI 参数被接受但被忽略（只有 loader 级配置生效）。
    修复：OkHttp 无条件关闭自动跟随，宿主自己走 hop 循环——每跳重跑
    `JsRequestPolicy.redirectTarget`（scheme 白名单）与 `headersFor`（对照 credentialOrigin 重判
    凭据），307/308 保留方法+body、301/302/303 降级 GET，超过 5 跳报 `limit`，不跟随时 3xx 作为
    数据返回且 `Location` 原样保留，per-call 只做收窄（`config.followRedirects && perCall != false`），
    结果新增 `redirects` 跳数。测试 4 例，用两个不同端口的真实 origin 证明被跟随后 Authorization
    确实消失、`Location` 确实可达、循环与 `file://` 分别落 `limit`/`permission`。
15. 能力语义不一致：`timer.clear` 派发路径不查 `TIMER`（`timer.set` 查），`host.log` 无条件可调，
    而 `LOG` 是声明式能力清单里的一员。修复：`timer.clear` 走同一个 `requireCapability`；JS 侧门口
    `logFromJs` 做 grant 检查，未授予则丢弃并**只**由宿主发一条诊断（一次日志调用不能把插件打断，
    所以这条路径不抛错），宿主对自身的诊断走独立的 `log`、不受 grant 影响——否则缺 `LOG` 的插件
    可以反过来让宿主闭嘴。测试 2 例。
16. 测试诚实度：46 处 `if (!engineAvailable()) return`（loader 测试另有 12 处等价的私有守卫，合计
    58 处）会在原生库缺失的平台上伪装成 PASS —— 「全部通过」于是成了一句没有依据的自述。
    修复：58 处全部改为 JUnit 5 `Assumptions.assumeTrue`（引擎不可用时报 SKIPPED，不谎称 PASS）；
    复核方式是跑一遍 `./gradlew test` 再看 `*/build/test-results/test/*.xml`。

第 10 条由这次新增的 2 个路由测试钉死（越界 priority → 加载 FAILED 且零路由；越界 connections →
描述符作废并 fall through）。第 11–15 条各新增回归测试：ownership 2（runtime）、guard/lifecycle/
watchdog 3（js）、redirect 策略 4（js）、capability 语义 2（js）。

第 17–20 条为**第二轮外部评审**提出（第 4 条留了一个语义缺口，另有三条更早的历史问题）：

17. 重定向的方法改写规则不完整：`301/302/303` 只在「有 body 或方法为 POST」时才降为 GET，
    于是 `303 + PUT`（无 body）和 `301 + DELETE` 会被原方法重放。修复：`301/302/303` **一律**降为
    无 body 的 GET（`307/308` 仍原样保留方法+body）。测试 1 例：在**远端 origin** 观测实际到达的
    方法，`303+PUT`→`GET|`、`307+PUT`→`PUT|payload`、`303+DELETE`→`GET|`。
18. `PluginHost.shutdown()`/`uninstall()` 把阻塞式卸载放在 `lock` 里：onUnload 可能阻塞数秒
    （JS drain、宿主 I/O、同步 close），期间其它线程的 install/uninstall 全被卡住。修复：注册表
    变更在锁内，`onUnload` + disposer 排在锁外；用 `unloading` 领取集保证「同 id 至多一个实例」，
    并在 shutdown 后置终态拒绝新装。测试 2 例：慢卸载期间无关 install 不被拖住；shutdown 后 install 被拒。
19. `shutdown()` 的「reverse install order」名不副实：`ConcurrentHashMap.values.reversed()` 只是把
    哈希迭代序倒过来，并非安装序。修复：`Managed` 记录单调 `seq`，卸载按 `seq` 降序；`uninstall`
    对子插件同理。测试 1 例：四个插件按装机序断言严格 LIFO。
20. `host.storage` 的 key 变换是有损替换（`:`/`/`/空格 → `_`），`user:token` 与 `user/token` 会
    折叠到同一个文件、互相覆盖状态；注释却写着「keeps them unique」。修复：改为**可逆的百分号编码**
    （安全字符原样，其余 `%XX`，`%` 自身总被转义），注入性由构造保证；`keys()` 反向解码回逻辑 key。
    测试 1 例：三个仅差一个非安全字符的 key 读回各自的值、`keys().length == 3`。

全量回归数字见 §3。

> 更理想的形态是把这套「在飞计数 + 拒绝式关闭」抽象成 `turbo-plugin-runtime` 里的
> 通用 `InvocationGate`，让 HLS 等任何有后端线程的插件复用，而不是留在 JS 模块内。当前实现是朝
> 那个方向的**第一步**——`JsInvocationGuard` 已不含任何 QuickJS 知识（两个原子计数 + 一个
> quiescence 回调），上提为内核类型只剩改名与去 `Js` 前缀。刻意没做：动内核要同时验证 HLS
> 侧的等价位，那是另一件事，不适合混在修缺陷的改动里。

## 6. 这个模块里有什么

- `turbo-plugin-js/`（源码 13 个 Kotlin 文件 + `abi.js` + 资源清单 `turbodl-plugin.json`
  + HLS 同风格 `build.gradle.kts` + `README.md`）。
- 测试 7 个文件 / 86 例（loader、隔离、生命周期、宿主能力、codec、路由、沙箱策略）；引擎不可用
  平台上以 JUnit assumption 报 SKIPPED，不伪 PASS —— 在 Windows x64 实测 0 跳过。
- 复核方式：仓库根目录 `./gradlew test`，逐类结果在 `*/build/test-results/test/*.xml`。
- 文档更新：`docs/plugins/README.md`（"A note on JS" 改写为已交付 + `loadSource` 示例）、
  `docs/plugins/MARKET.md`（`entry.language` 字段说明）、`turbodl-plugin.schema.json`（描述文本）、
  根 `README.md` 与五份本地化 README 的模块清单、新增 `docs/legal/`（隐私 / 条款 / 第三方组件）。
- 本架构报告。

## 7. 遗留（与 README TODO 一致）

`jar:`/URL 源码与校验和分发；`host.http` 并发原语；更多扩展点；
runtime 的通用「单注册撤销 handle」API；Android ABI/体积核算；沙箱存储版本迁移。
