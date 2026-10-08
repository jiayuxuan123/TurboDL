# turbo-plugin-js — JavaScript 插件加载器

TurboDL 的**系统插件**，与 `turbo-plugin-hls` 同级：实现 `PluginLoaderProvider`（category
`turbodl-loader`，id `loader.js`），把用 JavaScript 编写的 TurboDL 插件加载成一流的 `Plugin`。
内核与 Kotlin 加载器始终不知道 JS 的存在；JS 引擎只作为本模块的实现细节存在。

```
turbodl-core  ←  turbo-plugin-runtime  ←  turbo-plugin-js  ←  JS Plugin（脚本）
```

依赖方向严格单向，`turbodl-core` 里没有一处 JS/QuickJS 引用（这是本模块的硬性约束，已由测试与
全仓 grep 验证）。设计决定、runtime 改动理由与 Breaking Change/Migration 评估见
[ARCHITECTURE.md](ARCHITECTURE.md)。

---

## 定位

1. **它是加载器，不是业务插件。** 本模块不实现任何网盘/HLS/解析插件，只负责「把一段 JS 变成
   一个 TurboDL 插件」：解释脚本、注入宿主能力、把脚本的注册翻译成真实扩展点、按状态机卸载。
2. **它是 HLS 插件的同级 peer。** 消费者（`TurboClient` 的提交路径、事件泵、`LINK_PARSER` 路由）
   拿到的是普通的 `LinkParser` / `TaskPreHook` / `TaskPostHook`，看不出「这是一个 JS 的东西」——
   Kotlin 插件与 JS 插件在同一个扩展点里按同一套 priority 规则共存。
3. **它是可选依赖。** `turbo-plugin-bootstrap` 的默认装配保持纯 Kotlin、字节级不变；引入 JS 能力
   由宿主显式决定（见 [Bootstrap](#bootstrap)），因为 QuickJS 是 JNI 原生依赖，给每个下游构建
   （含 YunGet/Android）加一个 `.so` 必须是宿主的决定，而不是加载器的。
4. **它不改核心。** 对 `turbo-plugin-runtime` 的改动是**通用机制**（`PluginHost.loadSource` +
   `producedBy` 归属级联），不是 JS 专属钩子；对 `turbodl-core` 零改动。

### 为什么选 QuickJS（12 个维度）

| # | 维度 | QuickJS（经 `io.github.dokar3:quickjs-kt-jvm:1.0.15`） | 备选与之比较 |
|---|---|---|---|
| 1 | 体积 | 核心库数百 KB 量级，随 jar 分发预编译原生库 | V8（数十 MB）、J2V8/Node 嵌入包体不可接受 |
| 2 | 许可 | QuickJS 为 MIT，绑定为 Apache-2.0，与 TurboDL 的 MIT + 补充条款兼容 | GraalJS 依赖 GraalVM 工具链许可/体积；Rhino 许可友好但能力老 |
| 3 | 无运行时前提 | 纯 JNI `.so`/`.dylib`/`.dll`，不需要 JVM 上的额外引擎发行版 | Nashorn 已在 JDK 15 移除；GraalJS 需匹配 JDK/引擎版本 |
| 4 | 启动成本 | 每个实例创建即求值，毫秒级，适合「按需加载脚本」 | Node 进程/embedder 启动与守护成本明显更高 |
| 5 | 隔离粒度 | `QuickJs.create()` 同时创建 runtime + context，天然做到**每脚本一实例**：globals/heap/上限/中断互不可见 | JVM classloader 隔离无法约束 CPU/内存，且会暴露对象图 |
| 6 | 资源上限 | 提供 `memoryLimit`、`maxStackSize`、`evaluationTimeoutMillis`、`interruptEvaluation()`、`memoryUsage`，即本模块安全模型的支点 | 纯 JVM 沙箱（SecurityManager 已废弃）拿不到等价的原语 |
| 7 | 确定性/可预测错误 | 堆越限抛 `QuickJsException`、栈溢出是可捕获的 `InternalError`，行为已在本仓库实测固定 | 自研解释器成本与风险都过高 |
| 8 | 语言标准 | 覆盖 ES2020+ 常用子集（`let/const`、箭头函数、`Map`/`Set`、`JSON`、模板串），够写解析器/签名器 | Rhino 的 ES 版本更老，`JSON.parse`/typed array 生态弱 |
| 9 | 跨平台 | 绑定自带 linux/mac/windows 原生库，与 CI/桌面/服务器一致；Android 需宿主另行验证 ABI | Jsii/V8 桥在 Android 上体积与调试成本更高 |
| 10 | 与协程/JVM 集成 | API 是 `suspend`（`evaluate`、`invokeAsyncFunction`），可在单线程 executor 上以 `runBlocking` 驱动，天然匹配「一实例一线程」模型 | 外部进程方案（Node）需自建 IPC 与背压协议 |
| 11 | 边界面 | 只有 3 个绑定函数穿越边界（见 ABI），攻击面小且可审计；JS 函数不跨边界（以 id 派发） | 反射式桥（把 JVM 对象直接暴露给 JS）与本模块第 1 条铁律冲突 |
| 12 | 生态与维护 | 上游活跃、被多个 Kotlin 嵌入式项目使用；版本可 pin，无传递依赖爆炸 | 自建 QuickJS fork 维护成本不可持续 |

结论：需要一个**能设内存上限、能设栈上限、能中断、能按实例隔离、体积极小、许可干净**的引擎，
QuickJS 是当前唯一同时满足这些条件的选择。若日后要换引擎，只需替换 `JsRuntime.kt`（全模块唯一
触碰原生运行时的文件），ABI 与宿主能力契约不变。

### 关于 `-Xskip-metadata-version-check`

`quickjs-kt-jvm:1.0.15` 由 Kotlin 2.4.x 编译（metadata 2.4.0），本仓库工具链为 2.0.21，因此本模块
单独加了这个编译标志（见 `build.gradle.kts` 注释）。已实测跳过检查后的编译与运行行为均正确
（evaluate / 绑定 / 超时 / 内存上限全部符合预期）。刻意不做全仓库工具链升级：那会强制下游
（YunGet，Kotlin 2.1.0）一起升级，代价远大于本标志的收益。作用域仅限本模块。

---

## Loader 行为

`JsPluginLoaderPlugin`（id `loader.js`）在 `onLoad` 里注册：

- 扩展点 `PluginLoaderProvider.KEY`（`loaderId = "js"`，`canLoad = source.kind == "js"`）；
- 服务 `loader.js`（service id == plugin id，符合 CONVENTION §4），供其它插件做依赖门控；
- 两个 `ScheduledExecutorService`：`turbo-js-<loader>-timers`（2 线程，只跑宿主定时器）与
  `turbo-js-<loader>-watchdog`（1 线程，只跑 `interruptEvaluation()`），加上存活实例表。disposer
  里**先拆实例、再停 timers、最后停 watchdog**，因此定时器绝不会被投递给已释放的 runtime。
  两个池必须分开：定时器回调**就是一次 JS 调用**，可以名正言顺地占满线程到整个预算；而中断是唯一
  一件绝不能排到「它要杀的那段 JS」后面的任务。合成一个池时，两个卡住的定时器回调就能让 watchdog
  排队——caller 照样拿到 `timeout`，跑飞的 JS 却继续占着插件的引擎线程，而那正是 drain 在等的东西。

### 支持的 source uri

| 形态 | 说明 |
|---|---|
| `/abs/path/plugin.js`、`file:/abs/path/plugin.js` | 按 `encoding`（默认 UTF-8）读文本 |
| `inline:<javascript source>` | 直接用作脚本源码（测试、宿主内嵌片段） |
| `data:<percent-encoded text>` | 百分号解码为 UTF-8；非 ASCII 必须转义，否则拒绝而不是静默丢字符 |

`jar:` / classpath / http URL 源码**暂不支持**（见 [TODO](#todo)）：读不到就返回空列表并留一行
明确日志，而不是猜测——`loadSource` 对加载器失败是隔离的，一个坏路径是诊断而非崩溃。脚本体积上限
512 KiB：超过即不是插件而是应用。

### source attributes（全部由宿主提供，脚本无权指定）

| 属性 | 含义 |
|---|---|
| `pluginId` | 覆盖派生 id。脚本永远不能自选 id，所以 `loader.`/`core.`/`backend.` 无法被伪造 |
| `pluginName` | 覆盖展示名 |
| `permissions` | 与加载器授予集**取交集**（绝不是并集）；要求未授予的能力 → 加载期握手失败 |
| `entry` | 诊断用的脚本文件名，同时作为 id slug |
| `category` | id 前缀（限定在 manifest 允许的集合内） |
| `encoding` | 脚本字符集 |

id 派生：`acme-parser.js` → `parser.acme-parser`（按 CONVENTION §4 的 `<category>.<name>` 形状，
不发明前缀）；类别无法从 uri 判断时落到 `plugin.`。

### 加载与握手

`onLoad`：sandbox → host → bridge → runtime（创建实例、**先设上限再执行第一行脚本**、装 3 个绑定、
求值 shim → identity → 脚本）→ `__turboSeal()` → 握手 → 跟踪。握手三项检查各有独立错误码：

1. **ABI major**（`unsupported`）：脚本声明与 shim 报告都要等于加载器实现的主版本；
2. **onInit**（`plugin`）：脚本自己的 init 抛了，声明面不完整；
3. **capabilities**（`permission`）：脚本要求了加载器未授予的能力。

握手放在 seal 之后是刻意的：那是「尚无消费者可见」的最后时刻，失配只损失一次加载，而不会留下
一个已被路由的半成品插件。加载失败时 `onLoad` 抛出，宿主回滚已注册的扩展，本模块负责关掉
runtime、释放 HTTP client、删除沙箱目录——不留原生内存和目录。

### 卸载级联

`PluginHost.loadSource` 把产出的插件记在**注册该 provider 的插件 id** 名下，且只记**本次真正新装**
的 id：撞上已存在的同 id 插件时安装被忽略，所有权也不回填——`uninstall("loader.js")` 绝不会级联
掉一个它从未装过的插件。命中归属的每个 JS 插件按逆安装序卸载（各自跑完自己的 drain），再执行
loader 自己的 disposer（停调度器）。这是「卸载 JS 提供器」是安全操作而非内存腐蚀赌博的原因。

---

## JS Plugin ABI

`abi.js`（随 jar 分发，位于 `dev/turbodl/plugin/js/abi.js`）**就是** ABI 本身。脚本只看到两个普通
JS 对象——`plugin`（注册）与 `host`（能力），它们建立在恰好三个宿主绑定原语之上：

```
__turbodlCall(path, payloadJson)     -> envelopeJson   // JS → 宿主能力（粗粒度）
__turbodlRegister(kind, cbId, opts)  -> envelopeJson   // JS → 扩展/服务注册
__turbodlLog(level, text)            -> void           // 日志快路径（不走 JSON 往返）
```

跨边界的一切都是 **JSON 文本**，回调以整数 id 传递。原因很实际：QuickJS 把 JS 函数交给 Kotlin 时
是不可调用的不透明句柄，唯一稳健的做法是让函数留在 JS 侧、由 shim 按 id 派发
（`__turboDispatchCallback` / `__turboReleaseCallback` / `__turboHasCallback` / `__turboSeal` /
`__turboAbiVersion` 是宿主调用的 shim 全局）。

### 信封协议与错误码

```js
{ ok: true,  data: <value> }
{ ok: false, error: { code: "permission", message: "..." } }
```

两个失败通道刻意不混同：**宿主故障**（能力本身被拒/超限/网络错误）由绑定层转成信封，shim 还原成
带稳定 `code` 的 `Error`；**插件故障**（脚本抛错）由 shim 捕获后放进回调返回的信封里，宿主据此
区分「插件的 bug」与「引擎的故障」。`code` 是跨版本契约，不依赖 message 文本：

`validation` · `permission` · `limit` · `not_found` · `gone` · `unsupported` · `network` ·
`timeout` · `interrupted` · `internal` · `plugin`

> 原语 `__turbodlCall` / `__turbodlRegister` **返回信封、绝不抛异常**；请用 `plugin.*` / `host.*`
> 它们把失败转成可 `catch` 的 `Error`。直接调原语而忘了读 `ok`，是本项目文档里最常见的坑。

### `plugin`（注册面）

| API | 契约 |
|---|---|
| `plugin.id` / `plugin.manifest` / `plugin.sourceUri` | 加载器写入的**纯数据**；脚本只读到事实，读不到宿主句柄 |
| `plugin.defineMeta({name, version, apiMajor})` | 声明展示元信息 |
| `plugin.requires({permissions, abiMajor})` | 声明所需能力；与授予集不满足即加载失败（好于下载到一半被拒） |
| `plugin.registerParser({ parse(input) {...}, priority? })` | `(string) -> 单个描述符 / 描述符数组 / null` → `LinkParser` |
| `plugin.registerTaskPreHook({ beforeSubmit(request) {...}, priority? })` | 描述符 → 描述符（返回 `null`/`undefined` 即不改） → `TaskPreHook` |
| `plugin.registerTaskPostHook({ afterFinish({request, success, detail}) {...}, priority? })` | 只观察 → `TaskPostHook` |
| `plugin.onEvent(fn)` | `({type, taskId, ...}) -> void`，只读、不得抛 |
| `plugin.onInit(fn)` / `plugin.onDestroy(fn)` | 各只能注册一次；`onDestroy` 是唯一允许在 STOPPING 之后进入 JS 的调用 |

请求描述符（JS 侧唯一可见的「下载」形状，字段全为 string/number/字符串表）：

```js
{ url, destination?, fileName?, headers?, knownSize?, connections?, stableKey?, tags? }
```

`destination` 出去时是宿主给的绝对路径字符串，回来时**默认只允许裸文件名**（落进该插件的
`downloads/`）；含分隔符或绝对路径仅在宿主打开 `allowExternalDestination` 时生效。pre-hook 原样
回显宿主给的路径是被信任的（那是宿主的选择），其余一律重新过沙箱校验——所以 hook 无法「洗白」
一个沙箱外路径。一条 parse 最多 256 个描述符，超出整批失败（丢条目会改变下载清单，比失败更糟）。
`connections` 的合法区间是 `1..256`（与引擎的 `maxConnectionsPerTask` 同界）。数值字段一律
**严格**：写了但越界/非数 = `validation` 失败，绝不静默回落到默认值——一个自报 99999 线程却被
按 8 线程跑的插件，从日志里根本看不出来。

失败语义按扩展点各自的契约映射，而不是全局抛异常：parser 抛错/返回脏数据 → `null`（下一个 parser
继续，等同不匹配）；pre-hook 抛错 → 原请求不变；post-hook / 事件监听 → 记日志并吞掉。每条失败都
带插件 id 与 `code`，「我的 parser 不再命中」是可调试的而非静默的。事件监听是**有界同步观察者**：
调用同步执行、上界为一次 invocation 预算——慢监听器最多让事件泵停这么久；抛错被隔离，
绝不会杀死事件泵。

优先级取序：`impl.priority`（脚本自报）→ `defaultExtensionPriority`（loader 一次性压低全部 JS）→
`200`（CONVENTION §6 的 adapter 档）。范围 −10000..10000，越界即 `validation`。同优先级按注册序稳定
排序，因此一个脚本内部的顺序可复现；JS 的 200 低于一个主动要了更高优先级的 Kotlin 插件。

### 最小插件示例

```js
// acme-parser.js — 一个只认 acme:// 链接的解析器
plugin.defineMeta({ name: "Acme Parser", version: "1.0.0" });
plugin.requires({ permissions: ["http", "crypto"] });   // 未授予则加载期失败，不会跑到一半被拒

plugin.registerParser({
  priority: 300,
  parse(input) {
    if (!input.startsWith("acme://")) return null;      // 不匹配就 null，让下一个 parser 接手
    const share = input.slice("acme://".length);
    const sig = host.crypto.hmac("HmacSHA256", plugin.manifest.secret || "demo", share).hex;
    const res = host.http.request({                     // 粗粒度：拿到完整响应，永不接管字节流
      url: "https://api.acme.test/resolve?s=" + encodeURIComponent(share) + "&sig=" + sig,
      as: "text",
    });
    if (!res.ok) throw new Error("resolve failed: " + res.status);   // 抛错 → 记日志 + null
    const j = JSON.parse(res.body);
    return { url: j.cdn, fileName: j.name, headers: { Range: "bytes=0-" }, knownSize: j.size };
  },
});

plugin.onInit(function () { host.log.info("acme parser ready, id=" + plugin.id); });
```

加载它（宿主侧）与 `loadSource` 的 attributes 见 [Bootstrap](#bootstrap)。本模块自己的
`turbodl-plugin.json` 形状（`entry.language` 首次从「保留」变成可用）：

```json
{
  "manifestVersion": "1.0",
  "id": "loader.js",
  "name": "JavaScript Plugin Loader",
  "category": "turbodl-loader",
  "capabilities": ["turbodl-js", "turbodl-quickjs"],
  "turbodl": { "apiMajor": 1, "requiredApiVersion": "1.0.0" },
  "entry": {
    "language": "js",
    "pluginClass": "dev.turbodl.plugin.js.JsPluginLoaderPlugin"
  },
  "artifact": { "type": "maven", "coordinates": "dev.turbodl:turbo-plugin-js:0.2.0.6" },
  "extensionPoints": ["turbo.pluginLoaderProvider"],
  "services": ["loader.js"]
}
```

注意这里的 `language: "js"` 描述的是**加载器本身**由 Kotlin 装配（`pluginClass` 仍是 JVM 类），
JS 是它承载的插件语言；一个真正的 JS 插件不需要 `pluginClass`，它只需要一段脚本 +
`kind: "js"` 的 `PluginSource`。

### `host`（能力面）

见下一节。

### 值域（`JsValueCodec`，封闭集合）

`null` · `Boolean` · `Long` · `Double` · `String` · `List` · `Map<String, ?>`。
其余（函数、`Date`、`RegExp`、Kotlin `File`/`ByteArray`、QuickJS 的 `JsObject`）一律**拒绝而非强转**——
「JS 里没有 Kotlin 内部对象」这条铁律由 codec 强制，不靠 review。字符串上限 4 Mi 字符；JSON 文本
上限 4 Mi 字符、嵌套深度 64、严格遵循 RFC 8259（拒绝前导零数字、尾随垃圾、`NaN`/`Infinity`）；
`NaN`/`Infinity` 编码为 `null`。解析器为手写实现：边界两侧都不可信，且不给已发布产物增加传递依赖。

---

## Host API

所有能力穿过**同一个漏斗** `JsHostApi.dispatch(path, payloadJson)`：一处实施权限、一处审计，
未注册的路径是 `unsupported` 而不是「有人接了就能用」。授予按 loader 配置、**每次调用复检**，
「未授予」(`permission`，错误信息点名要申请哪个能力) 与「已授予但被拒」(各自的码) 是两种可区分的
错误。默认授予：`http,crypto,log,time`；`storage`、`env`、`timer` 是宿主必须显式打开的持久化/环境/
定时能力。`log` 也在同一套复检之内（见下文 `host.log`）：它是声明式能力，不是豁免通道。

### `host.http`

| 项 | 内容 |
|---|---|
| Kotlin 接口 | `JsHostHttp.request(payloadJson)` / `downloadToFile(payloadJson)`（`JsHost.kt`），阻塞、无 suspend、返回**完整**结果 |
| JS API | `host.http.request({url, method?, headers?, body?, bodyBase64?, as?, timeoutMillis?, originUrl?, followRedirects?, maxBytes?})`；`host.http.downloadToFile({url, headers?, fileName?, timeoutMillis?, maxBytes?, originUrl?, followRedirects?})` |
| 输入类型 | 一个 JSON 对象；字段与上界在 `JsHostHttp.parseRequest` |
| 输出类型 | request → `{ok, status, statusText, headers, url, redirects, body / bodyBase64 / bytes, ...}`；download → `{ok, file, bytes, status, contentType, url, redirects}`。headers 是单值表（首值胜出）；`as:'none'` 也返回 `bytes`（形状稳定，恒不为 `undefined`），文档化形态是「body 为 null、bytes 为真实读取数」；`redirects` 是**实际跟随的跳数**（直连为 0），插件因此能区分「被改写的最终 URL」和「自己走的链」 |
| 错误模型 | `validation`（参数）、`permission`（scheme/能力）、`limit`（字节上限、重定向链超过 5 跳）、`network`（传输失败/非 2xx 的下载）、`timeout`（预算耗尽）。**非 2xx 的 `request` 不是错误**：返回 `ok:false, status`，让插件像常规 HTTP 库那样分支 |
| 生命周期 | 每个插件一个懒建 `OkHttpClient`，`host.shutdown()` 释放；传输来自 `TurboHttpClients.create(engineConfig)`（代理/DNS/TLS/UA 策略是 TurboDL 的），引擎配置变化时按签名重建——宿主改代理不必重载插件 |
| 权限边界 | 需 `HTTP`。scheme 仅 `http/https`（`file://` 等是 SSRF/本地读通道）；`Authorization/Cookie/Referer/Origin/Proxy-Authorization` 在跨 origin 时被剥离，凭据归属由 `originUrl` 声明（登录域 A、CDN 域 B 的写法）。`maxBytes`/`timeoutMillis`/`followRedirects` 只能调低不能调高 |

### 重定向：逐跳策略，不是传输细节

OkHttp 自带的跟随在这里**无条件关闭**，链由 `JsHostHttp.executeWithRedirects` 自己走。原因是策略性的
而非功能性的：跟随一次重定向就是换一次 origin，而凭据剥离规则是按 origin 判定的。交给传输层跟随，
`Authorization` 就会跟着 302 坐到 CDN 上——那是这条边界的教科书级失败。因此每一跳都重新：

1. 用 `JsRequestPolicy.redirectTarget` 解析并复检 scheme（`Location: file:///etc/passwd` 是
   `permission` 拒绝，不是「顺手取一下」）；
2. 用 `JsRequestPolicy.headersFor` 对着**声明的凭据 origin** 重算要发哪些头；
3. 记一跳，超过 `MAX_REDIRECT_HOPS = 5` 报 `limit`（环检测的廉价形式）。

`307/308` 原样重发方法与 body（方法保持），`301/302/303` **一律**降为无 body 的 `GET`——不管原方法是什么，
`303 + PUT` 也变 `GET`，绝不把 `PUT/DELETE/PATCH` 重放到一个可能是别的 origin 的 `Location` 上（那里凭据
刚被剥掉）。不跟随时（默认，且凡是 loader 没打开的调用永远如此）3xx **作为数据返回**，`Location` 原样
保留，由插件决定这个重定向意味着什么。per-call 的 `followRedirects` 只做收窄：`false` 能关掉 loader 允许
的跟随，`true` 永远打不开 loader 关掉的跟随。

刻意**没有**流式 API、没有 body 句柄、没有分块回调：JS 永远不站在数据面上。需要字节就拿有界文本
/base64，或者拿一个写完的文件。

### `host.crypto`

| 项 | 内容 |
|---|---|
| Kotlin 接口 | `JsHostCrypto.digest/hmac/randomBytes/base64/hex`，JDK `MessageDigest` / `Mac` / `SecureRandom` |
| JS API | `digest(algorithm, data)` → 小写 hex；`hmac(algorithm, key, data)` → hex；`randomBytes(length)` → `{base64, hex}`（1..512）；`base64Encode/base64Decode/hexEncode/hexDecode` |
| 输入类型 | `data`/`key` 为 `{kind:'utf8' 或 'base64', text}`（由 shim 生成，裸字符串按 utf8 容忍），字节数组从不过界 |
| 输出类型 | `{algorithm, hex}` / `{base64}` / `{text 或 base64 或 hex}` |
| 错误模型 | `validation`（算法不在白名单、编码畸形——绝不泄漏 provider 名）、`permission`（未授予 `CRYPTO`）、`limit`（单次输入超 `min(maxResponseBytes, 32MiB)`） |
| 生命周期 | 无持有资源 |
| 权限边界 | 需 `CRYPTO`。算法白名单：`MD5/SHA-1/SHA-256/SHA-512`、`HmacSHA1/256/512`。密钥不记录不返回。**范围即立场**：下载插件要的是签名与校验和，不是密码套件/密钥协商/非对称——那些应当由 Kotlin 后端承担 |

### `host.log`

`host.log.info/warn/error/debug(...)` → `[<pluginId>] [<level>] <text>`，单条上限 8 Ki 字符，
非字符串参数尽力 `JSON.stringify`。日志永远不能把插件打断（`__turbodlLog` 侧 `runCatching`）。
前缀由 `PluginContext.log` 统一渲染，JS 与 Kotlin 插件的日志格式因此一致。

`log` 是一项**真实的能力**（`LOG`，默认授予），因此和其它能力一样在 JS 侧门口做 grant 检查：
loader 撤掉 `log` 后，脚本的日志行会被丢弃而不是照常输出。丢弃不会抛错（一次日志调用不能让下载失败），
取而代之的是宿主自己发出**一条**诊断（每实例一次，不是每行一次）。检查只加在 JS 侧门口
（`logFromJs`）：宿主对自身实例的诊断（定时器失败、拒绝通知）走 `log`，永远不受 grant 影响——否则
一个缺少 `LOG` 授权的插件就能反过来让宿主闭嘴。

### `host.storage` / `host.env` / `host.time`

| 能力 | JS API | 边界 |
|---|---|---|
| `storage`（需 `STORAGE`） | `get(key)` / `set(key, value)` → `{ok, bytes}` / `remove(key)` → `{removed}` / `keys()` | 插件沙箱内的小 KV，值是 JSON 域数据（存状态不存字节）；key 做**可逆**的文件名编码（百分号编码，`user:token` 可用，且 `user:token` / `user/token` / `user token` 是三个不同的 key，绝不互相覆盖）、≤64 字符、拒绝点开头；单值 ≤256 Ki，总量 ≤ `maxStorageBytes`（默认 4 Mi） |
| `env`（需 `ENV`） | `get(name)` → `{value}` / `has(name)` → `{allowed, present}` | 只读 + 白名单，默认白名单为空——环境对插件不可见，除非宿主点名 |
| `time`（需 `TIME`） | `now()` → `{millis}` / `iso(millis?)` → `{iso, millis}` / `sleep(millis)` → `{sleptMillis}` | 用宿主时钟而非 `Date.now()`，时间戳可与引擎日志对齐；`sleep` 上限 5 s 且受调用预算约束，不能把 drain 拖死 |

### `host.setTimeout` / `setInterval` / `clearTimeout`（需 `TIMER`）

定时器**由宿主持有**（loader 级 `turbo-js-<loader>-timers` 调度器，2 线程），所以 unload 一定能
取消它们；定时器回调本身就是一次 JS 调用，可能合法地卡住，这也是它和 watchdog 分池的原因——
打断路径（`turbo-js-<loader>-watchdog`，1 线程）绝不能排到「它要杀的那段 JS」后面（见上文
`JsPluginManager`）。handler 以回调 id 传递，句柄是 `clearTimeout` 可直接使用的**数字**（返回
`{id}` 对象会让 `clearTimeout(setTimeout(...))` 静默打中 `NaN`——这是测试期间发现并已修的真实
缺陷）。delay ≤ 24 h。进入 DRAINING 时全部取消；STOPPING 之后触发被静默丢弃；回调抛错只记
`warn`，既不杀插件也不阻塞卸载；`timer.clear` 在调度器已停时返回 `gone`，且和 `timer.set` 一样
先过 `TIMER` grant 复检——能力检查不因「这只是取消」而豁免。

### 宿主能力上限一览（`JsPluginConfig`）

`permissions`（默认 `http,crypto,log,time`）、`evaluationTimeoutMillis` 5 s、
`invocationTimeoutMillis` 20 s（必须 ≥ 前者，否则外层先杀、引擎超时永远轮不到）、
`drainTimeoutMillis` 5 s、`memoryLimitBytes` 32 Mi、`maxStackSizeBytes` 512 Ki、
`maxResponseBytes` 8 Mi、`maxDownloadBytes` 512 Mi、`maxStorageBytes` 4 Mi、
`httpTimeoutMillis` 30 s、`followRedirects` false、`sandboxRoot` 默认
`<workDir>/../turbodl-js`、`allowedEnvVars` 空、`allowExternalDestination` false。
每一条都是**安全或资源上界**而非功能开关：默认值按「一个抓页面+清单的解析器」定尺，不是按
「内嵌应用服务器」。

沙箱：`<root>/<pluginId>/{storage,downloads}`。`resolve()` 是唯一收口——拒分隔符、拒 `..`、
拒绝对路径，并复核 canonical path 仍在沙箱内（对符号链接/后缀把戏的纵深防御）。卸载时
`downloads/` 一定清（scratch），`storage/` 是插件自己的状态、默认保留（会话 token、续传游标），
只有宿主设 `purgeSandboxOnUnload = true` 才整目录删除（kiosk 型宿主）。

---

## 生命周期

```
ACTIVE ──stop()──▶ STOPPING ──(拒绝新调用)──▶ DRAINING ──(busy == 0)──▶ DISPOSED
```

- **ACTIVE**：接受调用、服务能力、可排定时器。
- **STOPPING**：决策点。每个**新**调用立即拒绝（快速失败，绝不排队），drain 才有真实截止线；
  已在跑的 JS 保留宿主访问权，好让它干净收尾而不是留下半个副作用。
- **DRAINING**：等待 busy 归零。这里的 busy 是 `inFlight + engineBusy`——调用方计数与引擎
  worker 任务计数之和（见下文两条规则）。定时器与监听在入口即取消，计数不会再被回填。
- **DISPOSED**：runtime/context 已关、executor 已停。终态。

`dispose()` 的实际顺序（`JsScriptPlugin.dispose`）：1) `markStopping()`（并 `interruptEvaluation()`
打断卡住的 JS）；2) `deactivateAll()` 中和注册（parser→`null`、hook→原值，抢在 unload 路上的消费者
拿到干净 no-op）；3) 取消定时器 + 关 HTTP client（**必须在 drain 之前**，否则定时器会在 drain
期间加活、截止线每次看都在动）；4) `drainInFlight()`；5) `onDestroy`（唯一允许 STOPPING 后进入）；
6) 释放回调；7) `close()`（先关 context 再关线程）；8) 磁盘清理。

两条不可协商的规则：

- **引擎还在被使用时直接 `close()` 是被禁止的**。计数是两条生命周期的和：`inFlight`（调用方还
  在等结果）与 `engineBusy`（引擎 worker 任务还活着）。超时返回的调用会立刻交还 `inFlight`，
  但 JS 仍在专属线程上跑——只看调用方计数的 drain 会误判为静默，随后 `QuickJs.close()` 就是
  use-after-free。`JsRuntime.close()` 在 `busyCount > 0` 时抛异常，且诊断把两个数都点名，把
  「记得先 drain」从习惯升级为强制前置条件。
- **drain 超时就不许 dispose**。宿主拒绝关引擎、记录一次 leak（`JsPluginManager.leakedCount()`
  可见）、留下明确诊断：漏掉一个 QuickJS runtime 是可恢复的，释放 JS 正在读的内存不是。

其它不变量：每插件一个专属线程 `turbo-js-<id>`（QuickJS 不可并发求值；插件互不阻塞；`close()`
不会与求值竞争；线程名让卡住的插件在 thread dump 里一眼可辨）；从宿主回调内部再入本 runtime 被
显式拒绝（`unsupported`）而不是死锁；两层上界——引擎自身 `evaluationTimeoutMillis` 只计 JS 时间，
watchdog 线程（独立 1 线程池，见 `JsPluginManager`）从另一线程 `interruptEvaluation()` 覆盖总预算
（JS + 宿主时间，排队等待也计入）。deadline 在提交时刻固定，但 watchdog 由 worker 任务自己在真正
拿到引擎后挂载——排队中的调用绝无可能打断当前占用引擎的调用，调用方超时也只是放弃自己的 future
（见 `JsLifecycleTest` 的两条 queued-invocation 回归测试）。`while(true){}` 被杀后实例仍可用（已实测）。
诊断快照含 `state` / `abi` / `registrations` / `inFlight` / `engineBusy` / `jsHeapBytes` / `grants`。

---

## Bootstrap

`TurboBootstrap.create(...)` 已有 `extraPlugins` 形参，因此接入 JS 加载器**不需要任何 bootstrap
API 改动**：

```kotlin
val boot = TurboBootstrap.create(
    config = TurboConfig(userAgent = "YunGet/1.0"),
    extraPlugins = listOf(
        JsPluginLoaderPlugin(
            engineConfig = TurboConfig(),
            jsConfig = JsPluginConfig(),            // 默认即紧的一档
            // defaultExtensionPriority = 150,      // 一次性压低所有 JS 注册（脚本自报的仍优先）
            // purgeSandboxOnUnload = true,         // kiosk：不留任何文件
        ),
    ),
)

// 由宿主决定加载哪些脚本，以及用哪个 id/名字/能力集
val ids = boot.host.loadSource(
    PluginSource(
        kind = "js",
        uri = "/opt/turbodl-plugins/acme-parser.js",
        attributes = mapOf("permissions" to "http,crypto,log", "category" to "parser"),
    ),
)
```

不使用本模块时，Kotlin/HLS/Bootstrap 插件的行为与依赖树完全不变；`turbodl-core` 不会因为存在
`turbo-plugin-js` 而引用 JS。卸载：`boot.host.uninstall("loader.js")`（级联卸掉它产出的每个脚本，
各自跑完 drain）。热替换路径 = `uninstall(oldId)` 后重新 `loadSource(...)`；同一 id 重复安装会被
`install` 忽略，这是刻意的（避免半新半旧的两个实例同时路由）。

---

## 已知限制

1. **没有「单个注册的 cancel」。** `PluginContext` 只按插件整体注销注册，因此
   `deactivateAll()` 的做法是**中和适配器**（释放回调 + 适配器短路成各自契约的 no-op），注册表条目
   要到插件 unload 才消失。不去伸手够 `ExtensionRegistry`——那等于把 Kotlin 内部对象交给 JS 侧代码。
2. **逃逸的堆越限可能让 context 卡死。** 实测：`memoryLimit` 越限抛的 `QuickJsException` message
   恒为字面量 `"null"`，此后该 context 要么每次求值报 `InternalError: <NO_MESSAGE>`，要么（不稳定地）
   恢复。因此**把大块分配包进 try/catch**；`JsRuntime.engineFault` 用引擎自己的 `memoryUsage` 判定
   并报 `limit`（而不是一个空白错误）。不影响卸载：实例照样走完 drain，`close()` 照样成功。
3. **不支持 `jar:` / classpath / URL 源码。** 读不到返回空列表 + 一行诊断。
4. **`__turbodl*` 原语返回信封、不抛异常。** 直接用它们而忘了检查 `ok` 会把失败当成功；请一律走
   `plugin.*` / `host.*`（失败转成可 `catch` 的 `Error`，带 `code`）。
5. **JS 不能注册 `DownloadBackend`，也不能发布宿主 service。** 二者都返回 `unsupported`，这是
   设计上的封闭：chunk/字节面留在 Kotlin，`host.http.downloadToFile` 给脚本的是写完的文件；
   service 是 Kotlin 对象图。未知注册 kind 是 `validation`。
6. **没有 JS 标准库。** 不提供 `require`/npm/fetch/Web API。`host.http` 是唯一出网口（复用 TurboDL
   传输），JSON/base64/hex/digest 由 `host.crypto` 提供，其余请用 JS 语言本身。刻意不做推测性 stdlib。
7. **Android/YunGet 需自行验证原生 ABI。** 绑定自带 linux/mac/windows 库；Android 的 `.so` 由宿主
   按自身 ABI 矩阵评估，这也是本模块不进入默认装配的原因之一。
8. **`trustAllCerts`/代理等传输政策继承宿主 `TurboConfig`。** 脚本不能改，只能在自己的调用里收窄。

---

## TODO

- `jar:` / classpath 源码，以及带内容校验（sha256）的远程源码获取，供市场（MARKET.md）使用。
- 脚本级 `host.http` 并发原语（一次调用里等多个请求），避免插件用轮询或自建定时器模拟。
- 更多扩展点（进度改写、URL 探测、文件名建议）——每个都必须保持粗粒度与同样的失败映射。
- 注册句柄的「单条撤销」，前提是给 `PluginContext` 加一个通用的 registration handle API（runtime
  侧的通用改进，不是 JS 专属）。
- 沙箱存储的迁移版本化（插件升级时改 key 形状时的读写兼容）。
- 把 `JsInvocationGuard` 的「双计数 + 拒绝式关闭」上提为 `turbo-plugin-runtime` 的通用
  `InvocationGate`，让 HLS 等任何有后台线程的插件复用（当前 guard 已不含 QuickJS 知识，上提
  只剩形状迁移；需要连同 HLS 侧验证，所以没放在这次改动里）。
- Android（YunGet）ABI 矩阵验证与体积核算。
- 引擎可替换性收口实验（`JsRuntime.kt` 之外无原生引用的静态检查）。
