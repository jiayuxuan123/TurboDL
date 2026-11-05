#!/usr/bin/env python3
"""TurboDL homepage: a packet route, its operating defaults, and the exits."""

from __future__ import annotations

from sitekit import code_block, esc, icon, md_inline, section, table


def _facts():
    import content

    return content


def _value(name: str, fallback: str) -> str:
    fact = _facts().TURBO_CONFIG.get(name, {})
    return str(fact.get("default", fallback))


def _route_board(lang: str) -> str:
    en = lang == "en"
    stages = [
        (
            "01",
            "Request" if en else "请求",
            "DownloadRequest" if en else "DownloadRequest",
            "The host submits a URL and destination. The task enters a bounded queue."
            if en
            else "宿主提交 URL 与目标路径，任务进入有界队列。",
            "accepted" if en else "已接收",
        ),
        (
            "02",
            "Probe" if en else "探测",
            f'{_value("probeTimeoutMs", "6_000")} ms',
            "One range request establishes size, byte-range support and validators. Unsupported Range falls back to a whole-file stream."
            if en
            else "一次 Range 请求确认大小、字节范围支持与校验器；不支持 Range 时退回整文件单流。",
            "range ready" if en else "范围就绪",
        ),
        (
            "03",
            "Partition" if en else "分片",
            f'{_value("segmentsPerConnection", "4")}x',
            "Blocks are sized for work stealing: about four blocks per connection, capped by block size."
            if en
            else "为工作窃取安排分片：每连接约四块，并受块大小上限约束。",
            "blocks queued" if en else "分片排队",
        ),
        (
            "04",
            "Concurrency" if en else "并发",
            f'{_value("maxConnectionsPerTask", "16")}',
            "Workers claim ranges independently. Adaptive control only descends on server pressure."
            if en
            else "工作单元独立领取 Range；只有服务端背压时才自适应下调并发。",
            "lanes active" if en else "线路活动",
        ),
        (
            "05",
            "Verify" if en else "校验",
            "byte exact" if en else "字节一致",
            "Returned bytes are checked against the requested range, then the merged size and terminal state are verified."
            if en
            else "返回字节先与请求的 Range 对齐，再核对合并后的大小与终态事件。",
            "checked" if en else "已核对",
        ),
    ]
    items = []
    for number, title, value, copy, state in stages:
        items.append(
            f"""<li class="route-stage">
  <span class="route-node" aria-hidden="true"></span>
  <div class="route-stage-head">
    <span class="stage-number">{number}</span>
    <span class="stage-state"><i aria-hidden="true"></i>{esc(state)}</span>
  </div>
  <h3>{esc(title)}</h3>
  <p class="stage-value">{esc(value)}</p>
  <p>{esc(copy)}</p>
</li>"""
        )
    return (
        '<div class="route-board" data-illustration>'
        '<div class="route-board-head">'
        f'<h2>{"Five checkpoints before a file exists" if en else "文件出现之前的五个检查点"}</h2>'
        '<p class="illustration-note">'
        + (
            "Illustrative route. Every value shown below is a TurboConfig.kt default, not a measured speed."
            if en
            else "路线为示意。下面的值均来自 TurboConfig.kt 的默认配置，不是实测速度。"
        )
        + "</p></div>"
        f'<ol class="route-line">{"".join(items)}</ol>'
        '<div class="route-legend">'
        '<span><i class="status-lamp is-green" aria-hidden="true"></i>'
        + ("configured state" if en else "配置状态")
        + "</span>"
        '<span><i class="status-lamp is-orange" aria-hidden="true"></i>'
        + ("failure boundary" if en else "失败边界")
        + "</span>"
        '<span class="legend-note">'
        + ("route illustration / not throughput data" if en else "路线示意 / 非吞吐数据")
        + "</span></div></div>"
    )


def _hero(lang: str, href) -> str:
    en = lang == "en"
    title = (
        "One request,<br><span>laid onto rails.</span>"
        if en
        else "一次请求，<br><span>送上受控线路。</span>"
    )
    lead = (
        "TurboDL is a Kotlin/JVM download engine with explicit probe, partition, "
        "concurrency and verification stages. The host keeps control; the engine makes the route observable."
        if en
        else "TurboDL 是一个 Kotlin/JVM 下载引擎，把探测、分片、并发与校验拆成清楚的阶段。"
        "控制权留在宿主，引擎负责让整条路线可观察、可恢复。"
    )
    return f"""<section class="home-hero">
  <div class="hero-lead">
    <h1 class="display">{title}</h1>
    <div class="hero-side">
      <p class="lead">{esc(lead)}</p>
      <div class="hero-actions">
        <a class="btn btn-primary" href="{href("download")}">
          {icon("download")}<span>{"Get TurboDL" if en else "获取 TurboDL"}</span>
        </a>
        <a class="btn btn-secondary" href="{href("docs")}">
          {icon("book")}<span>{"Read the manual" if en else "阅读工程手册"}</span>
        </a>
      </div>
    </div>
  </div>
  {_route_board(lang)}
</section>"""


def _operating_defaults(lang: str) -> str:
    en = lang == "en"
    rows = [
        [
            '<span class="mono">maxConnectionsPerTask</span>',
            '<span class="data-value">16</span>',
            "1..256" if en else "1..256",
            "Default lanes for one task." if en else "单任务默认并发线路数。",
        ],
        [
            '<span class="mono">maxConcurrentTasks</span>',
            '<span class="data-value">3</span>',
            "1..64" if en else "1..64",
            "Bounded task queue." if en else "有界的任务队列。",
        ],
        [
            '<span class="mono">segmentsPerConnection</span>',
            '<span class="data-value">4</span>',
            "1..64" if en else "1..64",
            "Work-stealing reserve per lane." if en else "每条线路的工作窃取余量。",
        ],
        [
            '<span class="mono">blockSize</span>',
            '<span class="data-value">16 MiB</span>',
            "upper bound" if en else "单块上限",
            "Coarser blocks reduce request fan-out." if en else "更粗的分块降低请求扇出。",
        ],
        [
            '<span class="mono">tailAssistMinBytes</span>',
            '<span class="data-value">8 KiB</span>',
            "tail threshold" if en else "收尾阈值",
            "Below this, splitting costs more than it saves." if en else "低于此值继续拆分得不偿失。",
        ],
        [
            '<span class="mono">maxRetries</span>',
            '<span class="data-value">5</span>',
            "0..50" if en else "0..50",
            "Per-segment retry budget." if en else "单个分片的重试预算。",
        ],
        [
            '<span class="mono">probeTimeoutMs</span>',
            '<span class="data-value">6,000 ms</span>',
            "minimum 1,000" if en else "最小 1,000",
            "Probe is bounded; it does not carry file bytes." if en else "探测有界，且不传输文件正文字节。",
        ],
        [
            '<span class="mono">stallTimeoutMs</span>',
            '<span class="data-value">45,000 ms</span>',
            "0 = off" if en else "0 = 关闭",
            "Detects a half-dead connection, not normal jitter." if en else "识别半死连接，不把正常抖动当故障。",
        ],
    ]
    return section(
        "Defaults that shape the route" if en else "塑造路线的默认值",
        '<div class="split-ledger"><div class="split-copy">'
        '<p class="lede">'
        + (
            "These are operating constraints, not a speed promise. Each row is a public field in "
            '<span class="mono">TurboConfig.kt</span>; the host can override it per client.'
            if en
            else "这些是运行约束，不是速度承诺。每一行都是 "
            '<span class="mono">TurboConfig.kt</span> 的公开字段，宿主可按 client 覆盖。'
        )
        + "</p>"
        '<p class="small">'
        + (
            "The route diagram is illustrative. The defaults below are source-verifiable."
            if en
            else "路线图是示意；下表默认值可回到源码逐项核对。"
        )
        + "</p></div>"
        + table(
            ["Config field" if en else "配置字段", "Default" if en else "默认值", "Range" if en else "范围", "Operating effect" if en else "运行影响"],
            rows,
            compact=True,
        )
        + "</div>",
    )


def _controls(lang: str) -> str:
    en = lang == "en"
    controls = [
        (
            "dynamicSegmentation",
            True,
            "Dynamic segmentation" if en else "动态分片",
            "Historical switch for tail assist; both it and tailAssist must be true."
            if en
            else "收尾托管的历史开关；它与 tailAssist 同时为 true 才生效。",
        ),
        (
            "tailAssist",
            True,
            "Tail assist" if en else "收尾托管",
            "Idle workers can take the end of in-flight segments after the work queue empties."
            if en
            else "工作队列空后，空闲工作单元可以接管在飞分片的尾部。",
        ),
        (
            "adaptiveConcurrency",
            True,
            "Adaptive concurrency" if en else "自适应并发",
            "Downward adaptation. Ordinary throughput jitter does not reduce the lane count."
            if en
            else "只向下收敛。普通吞吐抖动不会减少线路数。",
        ),
        (
            "slowStart",
            True,
            "Slow start" if en else "慢启动",
            "Ramps from the configured initial lane count instead of opening everything at once."
            if en
            else "从配置的初始线路数逐步升速，而不是一次打开全部连接。",
        ),
        (
            "warmUpConnections",
            True,
            "Connection warm-up" if en else "连接预热",
            "Optional warm-up is bounded and never delays the transfer indefinitely."
            if en
            else "可选的预热有超时上限，不会无限拖延正式传输。",
        ),
        (
            "skipProbeWhenSizeKnown",
            True,
            "Skip known-size probe" if en else "已知大小则跳过探测",
            "When the host already knows the size, the extra metadata request is skipped."
            if en
            else "宿主已知大小时，跳过额外的元数据请求。",
        ),
    ]
    rows = []
    for field, on, label, copy in controls:
        checked = "true" if on else "false"
        rows.append(
            f"""<div class="control-row">
  <div class="control-name">
    <span class="mono">{field}</span>
    <strong>{esc(label)}</strong>
  </div>
  <p>{esc(copy)}</p>
  <button class="switch" type="button" role="switch" aria-checked="{checked}"
    data-on-label="{"Default on" if en else "默认开启"}"
    data-off-label="{"Default off" if en else "默认关闭"}">
    <span class="switch-track" aria-hidden="true"><i></i></span>
    <span class="switch-label">{"Default on" if en else "默认开启"}</span>
  </button>
</div>"""
        )
    return section(
        "Controls with runtime authority" if en else "真正有运行效力的控制项",
        '<p class="section-lead">'
        + (
            "Only current, non-deprecated switches are shown. Toggling here demonstrates state; "
            "it does not modify an engine instance."
            if en
            else "这里只列当前有效、未废弃的开关。拨动仅演示状态，不会修改真实引擎实例。"
        )
        + "</p>"
        + '<div class="control-ledger">'
        + "".join(rows)
        + "</div>"
        + '<p class="section-foot mono">TurboConfig.kt · current defaults</p>',
    )


def _modules(lang: str) -> str:
    en = lang == "en"
    modules = [
        ("turbodl-core", "Required" if en else "必需", "HTTP range engine, resume, retry, queueing, DNS/proxy." if en else "HTTP Range 引擎、续传、重试、队列、DNS/代理。"),
        ("turbo-plugin-runtime", "Optional" if en else "可选", "Lifecycle, disposal, services, events and extension points." if en else "生命周期、清理、服务、事件与扩展点。"),
        ("turbo-plugin-bootstrap", "Optional" if en else "可选", "One-call assembly for the base runtime and HTTP backend." if en else "一次调用装配基础运行时与 HTTP 后端。"),
        ("turbo-plugin-hls", "Optional" if en else "可选", "HLS VOD playlists, concurrent segments and AES-128." if en else "HLS 点播清单、分片并发与 AES-128。"),
        ("turbo-plugin-js", "Optional" if en else "可选", "QuickJS loader for plugins written in JavaScript." if en else "加载 JavaScript 插件的 QuickJS 加载器。"),
        ("turbo-plugin-parser", "Optional" if en else "可选", "Share-link parsing for Weibo, Xiaohongshu and Pixiv." if en else "微博、小红书与 Pixiv 分享链接解析。"),
        ("turbodl-cli", "Separate" if en else "独立", "A scriptable JAR over the SDK, with NDJSON output." if en else "基于 SDK 的可脚本化 JAR，支持 NDJSON 输出。"),
    ]
    rows = "".join(
        f"""<li class="module-row">
  <span class="module-node" aria-hidden="true"></span>
  <code>{esc(name)}</code>
  <span class="module-state{' is-required' if state in ("Required", "必需") else ''}">{esc(state)}</span>
  <p>{esc(copy)}</p>
</li>"""
        for name, state, copy in modules
    )
    return section(
        "One repository, seven separate stops" if en else "一个仓库，七个独立站点",
        '<p class="section-lead">'
        + (
            "The engine is complete without the plugin framework. Optional modules add protocol and host integration; dependency direction never reverses."
            if en
            else "不安装插件框架，引擎也能完整工作。可选模块增加协议与宿主集成能力；依赖方向始终不反转。"
        )
        + "</p>"
        + f'<ol class="module-route">{rows}</ol>',
    )


def _refusals(lang: str) -> str:
    en = lang == "en"
    F = _facts()
    rows = []
    for plugin in F.OFFICIAL_PLUGINS:
        refused = "".join(
            f'<span class="tag">{esc(reason)}</span>' for reason in plugin["refuses"][lang]
        )
        rows.append(
            [
                f'<strong>{esc(plugin["name"])}</strong><br><span class="mono small">{esc(plugin["id"])}</span>',
                refused,
                md_inline(plugin["refuses_note"][lang]),
            ]
        )
    return section(
        "Fail closed, not almost right" if en else "失败即拒绝，而不是“差不多对”",
        '<p class="section-lead">'
        + (
            "The official plugins state their unsupported cases before a corrupt file can be produced. "
            "This is part of the product surface, not a footnote."
            if en
            else "官方插件会先写明不支持的输入，避免先产出一个损坏文件。"
            "这是产品边界，不是脚注。"
        )
        + "</p>"
        + table(
            ["Plugin" if en else "插件", "Refused explicitly" if en else "明确拒绝", "Why" if en else "为什么"],
            rows,
        ),
    )


def _events(lang: str) -> str:
    en = lang == "en"
    sample = """{"type":"start","url":"<url>","output":"out.bin"}
{"type":"timing","stage":"metadata","t_ms":184,"probe_ms":96,"resume":""}
{"type":"progress","state":"downloading","downloaded":48234496,"total":167772160,"percent":28,"connections":16,"eta_ms":4000}
{"type":"completed","url":"<url>","file":"out.bin","bytes":167772160,"sha256":"<digest>"}
{"type":"summary","total":1,"failed":0}"""
    return section(
        "The event stream is the host interface" if en else "事件流就是宿主接口",
        '<div class="event-layout"><div class="event-copy">'
        '<p class="lede">'
        + (
            "The engine renders no UI. It reports state transitions and byte counters; the host decides how to present them."
            if en
            else "引擎不画界面。它报告状态切换与字节计数，怎么呈现由宿主决定。"
        )
        + "</p>"
        '<p class="illustration-note">'
        + (
            "The event names come from the current CLI. The numeric values in this block are illustrative only."
            if en
            else "下面的事件名来自当前 CLI；数值仅用于说明格式，是示意数据。"
        )
        + "</p></div>"
        + code_block(sample, "turbodl --json · sample output" if en else "turbodl --json · 示例输出")
        + "</div>",
    )


def _faq(lang: str) -> str:
    en = lang == "en"
    F = _facts()
    qa = [
        (
            "Does this require Android?",
            "No. The core is pure Kotlin/JVM and requires JVM 17+. Android is one possible host.",
            "必须用 Android 吗？",
            "不必。核心是纯 Kotlin/JVM，要求 JVM 17+。Android 只是可能的宿主之一。",
        ),
        (
            "Is the plugin framework mandatory?",
            'No. <span class="mono">turbodl-core</span> has no plugin-framework dependency. Optional modules only add capability.',
            "必须安装插件框架吗？",
            '不必。<span class="mono">turbodl-core</span> 不依赖插件框架。可选模块只增加能力。',
        ),
        (
            "Are the coordinates on Maven Central?",
            f'Not yet. The supported path is the offline Maven bundle attached to <a href="{F.LATEST["url"]}">{F.LATEST["tag"]}</a>.',
            "坐标已经上 Maven Central 了吗？",
            f'还没有。当前支持的用法是 <a href="{F.LATEST["url"]}">{F.LATEST["tag"]}</a> 附件里的离线 Maven 包。',
        ),
        (
            "What happens when a server ignores Range?",
            "The returned bytes are checked against the requested range. An unsupported or dishonest server cannot silently place bytes at the wrong offset.",
            "服务端忽略 Range 会怎样？",
            "返回字节会与请求的 Range 核对。不支持或撒谎的服务端不能悄悄把字节写到错误偏移。",
        ),
        (
            "Does it download HLS?",
            "VOD HLS is available through the official HLS plugin. Live playlists, DRM, fMP4 and discontinuities are refused.",
            "能下载 HLS 吗？",
            "点播 HLS 由官方 HLS 插件提供。直播清单、DRM、fMP4 与不连续序列会被明确拒绝。",
        ),
        (
            "Where is the GUI?",
            "This repository ships an engine, plugin framework and CLI. It deliberately does not impose a GUI.",
            "图形界面在哪里？",
            "这个仓库提供引擎、插件框架与命令行。它有意不把界面强加给宿主。",
        ),
    ]
    items = []
    for index, (q_en, a_en, q_zh, a_zh) in enumerate(qa):
        opened = " open" if index == 0 else ""
        items.append(
            f'<details class="faq-item"{opened}><summary>'
            f'<span>{esc(q_en if en else q_zh)}</span>{icon("chevron")}'
            f'</summary><div class="answer"><p>{a_en if en else a_zh}</p></div></details>'
        )
    return section(
        "Before the first download" if en else "第一次下载之前",
        '<div class="faq">' + "".join(items) + "</div>",
    )


def _closing(lang: str, href) -> str:
    en = lang == "en"
    F = _facts()
    coordinate = f'implementation("dev.turbodl:turbodl-core:{F.LATEST_VERSION}")'
    return section(
        "Start at the core coordinate" if en else "从核心坐标开始",
        '<div class="closing-board">'
        '<div class="closing-copy">'
        f'<h3>{"The smallest useful dependency" if en else "最小可用依赖"}</h3>'
        '<p>'
        + (
            "Add the core, submit a request, and keep the lifecycle in the host. The offline bundle is the current supported distribution path."
            if en
            else "加入核心依赖、提交请求，并由宿主掌握生命周期。当前官方分发方式是离线 Maven 包。"
        )
        + "</p></div>"
        '<div class="coordinate-row"><code id="core-coordinate">'
        + esc(coordinate)
        + '</code><button class="copy-button" type="button" data-copy="#core-coordinate" aria-label="Copy dependency">'
        + icon("copy")
        + f'<span>{"Copy" if en else "复制"}</span></button></div>'
        '<div class="btn-row">'
        f'<a class="btn btn-primary" href="{href("download")}">{icon("download")}<span>{"Download artifacts" if en else "下载构件"}</span></a>'
        f'<a class="btn btn-secondary" href="{href("plugins")}">{icon("plug")}<span>{"Plugin handbook" if en else "插件手册"}</span></a>'
        f'<a class="btn btn-secondary" href="{esc(F.REPO)}">{icon("external")}<span>GitHub</span></a>'
        "</div></div>",
    )


def page(lang: str, href) -> str:
    return "".join(
        [
            _hero(lang, href),
            _operating_defaults(lang),
            _controls(lang),
            _modules(lang),
            _refusals(lang),
            _events(lang),
            _faq(lang),
            _closing(lang, href),
        ]
    )
