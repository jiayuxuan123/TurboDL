# TurboDL 官网重构 — 设计笔记（taste-skill v2 流程）

日期 2026-10-15 · 方向 `sentence-as-layout`（seed 2039775358）· sector dev-tool
首屏形制 `headline-across-two-fields` · 字体 `sentient-light` · 调色板 `black-crimson`

## 1. 素材清单（只有这个客户才有的东西）

1. **分片**：文件被切成块，块数约为连接数的四倍，收尾阶段还有工作窃取。
2. **真实配置字段与默认值**：`maxConnectionsPerTask = 16`、`maxConcurrentTasks = 3`、
   `dynamicSegmentation = true`、`tailAssist = true`、`overlapMerge = false`、
   `bufferedSegmentWrite = false`、`minSegmentSize = 64 KiB`、`segmentsPerConnection = 4`。
3. **实测数字**（源码注释里的 A/B，不是估计）：预合并 512 MB / 64 连接
   11.08 s → 10.39 s（约 6%）；缓冲写 16 MB / 128 连接 / 64 KB 分片
   2576 ms 直写 vs 2584 ms 缓冲（0.3%，噪声内）。
4. **NDJSON 事件**：`start` / `timing` / `progress` / `completed` / `failed` / `summary`，
   字段名与取值都可以在 `Main.kt` 里核对。
5. **六个构件**：`turbodl-core`、`turbo-plugin-runtime`、`turbo-plugin-bootstrap`、
   `turbo-plugin-hls`、`turbo-plugin-js`、`turbodl-cli`；只有第一个是必需的。
6. **拒绝清单**：直播清单 / DRM / fMP4 / 不连续（HLS 插件）；注册后端 / 发布服务 /
   `require`·npm·fetch / `jar:`·远程脚本（JS 加载器）。
7. **真实版本**：`v0.2.2.0`、MIT、JVM 17+、258 个用例 0 跳过 0 失败。
8. **一行坐标**：`implementation("dev.turbodl:turbodl-core:0.2.2.0")`（尚未上 Maven Central）。

## 2. 页面主张（claims + proof，每节一条）

| # | 主张 | 证据 | 章节 |
|---|---|---|---|
| 1 | 十六只手处理同一个文件 | 首屏两场 + 两句标题 + 支撑句 | hero |
| 2 | 一次转移只有三段 | 被放大成整幅的进度条（真段宽：1 / 16 / 1）+ 三行判断依据 | how-it-works |
| 3 | 它真的对字节做了六件事 | README 能力表（含两条"实测否掉了显然做法"） | proof |
| 4 | 每个旋钮都是公开字段 | 五个**能按**的真开关（默认值与代码一致）+ 九项字段表 | features |
| 5 | 箱子里有六件东西 | 一句话里轮流点亮 + 构件表（必需/可选） | list |
| 6 | 它明确拒绝什么 | 插件 `refuses` 清单原样（纸场翻面） | list（纸场） |
| 7 | 它报告什么 | 面板（progress 字段）+ NDJSON 控制台 + CLI 三行命令 | proof |
| 8 | 六个最常被问的问题 | `<details>` 六问 | faq |
| 9 | 一行依赖就能接上 | 句子中间空出来的坐标块 + 复制按钮 | cta |

## 3. 点子句

> 在这张页面上，**「引擎把文件切成段」**变成**页面自己的版式**：首屏两块场之间的
> 那条硬接缝（同一句话在两块场上换墨色）、章节之间的分段线、以及被放大到整幅宽度的
> 进度条 —— 三种尺寸说的是同一件事。

竞争对手检验：任何下载引擎都能说"多线程、可续传"。但**把"句子被切开的接缝"当作
首屏的装置、把产品自己的进度条当整页的图**，而且段宽、开关默认值、结论里的数字
全部来自本仓库的代码与实测注释 —— 换个金融仪表盘或玩具站都不会长成这样。通过。

## 4. 那个"活着的东西"（live thing）

- **首屏那句标题里的分片带**：16 根柱子，每根的推进由固定序列（不是 `Math.random()`）
  驱动，所以每次刷新长得一样；
- **五个真开关**：状态与引擎默认值一致（`dynamicSegmentation`、`tailAssist` 默认开），
  按下去会翻开该字段的真实结论，键盘 Space / Enter 等效；
- **一句话里的名字轮流点亮**：每两秒换一个（尊重 `prefers-reduced-motion`）；
- **坐标一键复制**。

## 5. 文案（copy deck）

- H1（两行，接缝切在第二行上三分之一）：`Many hands,` / `one file.`（中文：`各下一段，` / `合成一份。`）
- 支撑句：十六条连接同时处理同一个文件；暂停后从磁盘上真实写完的字节接着下。
- 主按钮：Download v0.2.2.0（红）/ Read the docs（一档色差）
- 页脚：整幅红场，大字是**取用地址本身**（`github.com/jiayuxuan123/TurboDL`），
  不是"联系我们"这类空话。

## 6. 与旧版的关系（需要说明的取舍）

- 旧站是"浅色 + 等宽数字 + 左侧栏 + Pico"；新版**去掉 Pico**（70 KB 第三方样式表，
  为了覆盖它曾经写了 5 条 hack），规则全在 `site.css` 里。
- 左侧栏保留（开发站的目录常驻是真的有用），但改为深底、无边框、无阴影；
  导航项的当前页用一小段红记号标出。
- 等宽字体取消（房屋规则禁令），机器数据改用文本字体 + `tabular-nums`；
  只有真代码块与那行依赖坐标是等宽。
- **首页正文拆到 `website/home.py`**：九节的版式代码与"事实表 + 页面清单"不是同一种
  东西；事实仍全部由 `content.py` 拥有，`home.py` 延迟读取（否则循环导入）。
- **不放任何装饰性图片**（用户 2026-10-15 明确指示：引擎站与这些素材没关系）。
  曾下载过 ARPANET 逻辑图与一张金门大桥黑白照，已删除、`credits.json` 一并撤掉。
  代价是两条 WARN（`no-real-pictures` / `no-footage`），在报告里如实说明并辩护。
- **"图"由产品自己的界面碎片担任**：`website/fragments.py` 提供四个构造器
  （控制台 / 传输面板 / 字段台账 / 清单片段），首页放 4 块，下载页与文档页各 4 块 ——
  内页因此从"只有文字与表格"（门禁 4 个 FAIL）变成 0 FAIL。碎片的字段名与取值
  全部来自 `Main.kt` 与 `TurboConfig.kt`，不是编的。
- **内页三处硬伤一起修**：页标题加大一档（内页首屏没有主宰元素）、
  段落行宽 68ch（原来 160 字一行）、`--t-small` 提到 15px（原来正文档位是 14px）；
  窄屏表格改为卡片式堆叠，列名靠 `data-label` 贴回值上方。
- **两处有意的偏离**：`--muted` 由 #8c8c8c 提到 #a5a5a5（前者对黑底只有 6.1:1，
  达不到正文灰 ≥7:1）；定向单的"固定元素跟着章节换墨"只实现了章节色场那一半，
  侧栏不压进色场（压进去会被探针读成深墨压黑底）。
- **删掉一个切了没反应的开关**：原来有深色/浅色主题按钮，但这一版只有一种底色 ——
  按钮、CSS、JS 与 `theme-color` 一并清掉。
