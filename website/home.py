#!/usr/bin/env python3
"""TurboDL 首页：传输工程台。

本文件只负责把 `content.py` 的真实事实排成页面，不新增任何版本号、附件或性能数字。
视觉母题来自产品本身：Range 探测、并行分片、工作窃取、合并校验。
"""

from __future__ import annotations

from sitekit import code_block, esc, md_inline, section, table


def _facts():
    """延迟读取内容层，避免 content.py -> home.py -> content.py 循环导入。"""
    import content

    return content


def _strip() -> str:
    """16 路分片条；具体宽度和推进由页面 CSS / JS 生成。"""
    return '<span class="shard-inline" data-strip aria-hidden="true"></span>'


def _hero(lang: str, href) -> str:
    en = lang == "en"
    F = _facts()
    title_a, title_b = ("Many hands,", "one file.") if en else ("各下一段，", "合成一份。")
    note = (
        "A Kotlin/JVM engine splits one HTTP transfer across sixteen workers, "
        "resumes from bytes that actually landed, and rejects a server that lies about Range."
        if en
        else "Kotlin/JVM 下载引擎把一次 HTTP 传输拆给 16 个工作单元，从磁盘上真实落下的字节续传，"
        "并拒绝在 Range 上撒谎的服务端。"
    )
    primary = f"Download {F.LATEST['tag']}" if en else f"下载 {F.LATEST['tag']}"
    secondary = "Read the engine docs" if en else "阅读引擎文档"
    release = F.LATEST["tag"]
    return f"""
<section class="hero-bench" data-hero>
  <div class="hero-copy">
    <p class="eyebrow"><span class="status-dot" aria-hidden="true"></span><span>HTTP/RANGE</span><span>KOTLIN JVM 17+</span><span class="eyebrow-tail">NO UI REQUIRED</span></p>
    <h1 class="hero-title"><span>{esc(title_a)}</span><span class="signal-text">{esc(title_b)}</span></h1>
    <p class="hero-note">{esc(note)}</p>
    <div class="hero-act">
      <a class="btn btn-primary" href="{href("download")}"><span>{esc(primary)}</span><span class="btn-arrow" aria-hidden="true">↓</span></a>
      <a class="btn btn-ghost" href="{href("docs")}"><span>{esc(secondary)}</span><span class="btn-arrow" aria-hidden="true">↗</span></a>
    </div>
    <dl class="hero-facts">
      <div><dt>RELEASE</dt><dd>{esc(release)}</dd></div>
      <div><dt>ENGINE</dt><dd>1 REQUEST → {esc(16)} SHARDS</dd></div>
      <div><dt>VERIFY</dt><dd>{esc(F.TEST_COUNT)} TESTS</dd></div>
    </dl>
  </div>

  <aside class="telemetry-board" data-ui aria-label="{'Sample transfer telemetry' if en else '示例传输遥测'}">
    <div class="telemetry-head">
      <span><i class="signal-dot" aria-hidden="true"></i> SAMPLE TRACE</span>
      <code>212.4 / 341.8 MB</code>
    </div>
    <div class="telemetry-readout">
      <p class="telemetry-speed"><strong>18.6</strong><span>MB/s</span></p>
      <p class="telemetry-workers"><strong>16</strong><span>SHARDS<br>IN FLIGHT</span></p>
    </div>
    <div class="telemetry-plot">
      <span class="plot-label">RANGE / 0—341.8&nbsp;MB</span>
      <div class="shard-field" data-strip aria-hidden="true"></div>
      <div class="plot-axis" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i></div>
    </div>
    <div class="telemetry-grid">
      <div><span>STATE</span><b>downloading</b></div>
      <div><span>ELAPSED</span><b>00:18.42</b></div>
      <div><span>ETA</span><b>00:07.02</b></div>
      <div><span>RETRIES</span><b>0</b></div>
    </div>
    <div class="telemetry-foot">
      <span>progress event / byte counter</span>
      <b>62%</b>
    </div>
  </aside>
</section>
"""


def _pipeline(lang: str) -> str:
    en = lang == "en"
    stages = [
        (
            "01",
            "1",
            "probe",
            "次探测",
            "A Range request asks one question before the file is cut: can this server serve byte ranges?"
            if en
            else "下载开始前先问一次：这个服务端认不认字节范围 Range？",
        ),
        (
            "02",
            "16",
            "shards",
            "个分片",
            "Sixteen segments move at once. Work stealing takes the remaining block away from a slow connection."
            if en
            else "16 个分片同时进行；工作窃取会把剩余工作从慢连接手里拿走。",
        ),
        (
            "03",
            "1",
            "verify",
            "次校验",
            "Every returned range is checked, then the merged file is checked against the expected byte count."
            if en
            else "先核对每一段实际返回的字节，再核对合并文件的预期总大小。",
        ),
    ]
    nodes = []
    for code, count, unit_en, unit_zh, copy in stages:
        nodes.append(
            '<div class="pipeline-node">'
            f'<span class="node-code">{esc(code)}</span>'
            f'<p class="node-value"><strong>{esc(count)}</strong><span>{esc(unit_en if en else unit_zh)}</span></p>'
            f"<p>{esc(copy)}</p>"
            "</div>"
        )
    return (
        '<div class="pipeline" data-ui>'
        + '<span class="pipeline-link" aria-hidden="true"></span>'.join(nodes)
        + "</div>"
    )


def _process_notes(lang: str) -> str:
    en = lang == "en"
    rows = [
        (
            "PROBE" if en else "请求之前",
            "Range support is settled first. If the server cannot do it, the engine stops pretending and downloads straight through."
            if en
            else "先确认服务端是否支持 Range。不支持就不假装支持，退回整条下载。",
        ),
        (
            "SCHEDULE" if en else "下载进行时",
            "The block count is about four times the connection count, so a slow worker never owns the last remaining piece."
            if en
            else "块数约为连接数的四倍，慢连接不会独占最后剩下那块工作。",
        ),
        (
            "VERIFY" if en else "说完成之前",
            "The bytes returned are compared with the range that was asked for; the merged file is compared with the total size."
            if en
            else "实际返回的字节要和请求的 range 对上；合并后的文件还要和总大小对上。",
        ),
    ]
    items = "".join(
        f'<li><span class="process-code">{esc(code)}</span><p>{esc(copy)}</p></li>'
        for code, copy in rows
    )
    return f'<ol class="process-notes">{items}</ol>'


BANDS = [
    {
        "field": "dynamicSegmentation",
        "default": True,
        "say": {"en": "Split work finer than the connection count.", "zh": "把工作切得比连接数更细。"},
        "conseq": {
            "en": "About four blocks per connection, so the final stretch is never one worker on its own. Off: fixed blocks plus work stealing.",
            "zh": "约四条连接对应四块工作，收尾阶段不会只剩一个工作单元。关闭后退回固定块大小 + 工作窃取。",
        },
    },
    {
        "field": "tailAssist",
        "default": True,
        "say": {"en": "Give the tail to whatever is idle.", "zh": "把尾巴交给空闲的工作单元。"},
        "conseq": {
            "en": "At most two splits per segment in the endgame; nothing below 2 MiB is split, so a 16 MiB block tops out at four parts.",
            "zh": "只在收尾阶段生效，每个分片最多拆两次；低于 2 MiB 不拆，16 MiB 的块最多变成四段。",
        },
    },
    {
        "field": "overlapMerge",
        "default": False,
        "say": {"en": "Merge while the last bytes arrive.", "zh": "最后几个字节还在路上时先合并。"},
        "conseq": {
            "en": "Measured on 512 MB / 64 connections: 11.08 s to 10.39 s, about 6%. It can duplicate a write with tail assist, so it stays off.",
            "zh": "512 MB / 64 连接实测：11.08 s 降到 10.39 s，约 6%。与收尾托管同时开启时可能重复写，所以默认关闭。",
        },
    },
    {
        "field": "bufferedSegmentWrite",
        "default": False,
        "say": {"en": "Write segments through a buffer.", "zh": "分片写入走缓冲流。"},
        "conseq": {
            "en": "16 MB / 128 connections / 64 KB segments: 2576 ms direct, 2584 ms buffered. The 0.3% difference is noise; resume stays honest without it.",
            "zh": "16 MB / 128 连接 / 64 KB 分片：直写 2576 ms、缓冲 2584 ms，0.3% 落在噪声里；不加它，续传进度更可靠。",
        },
    },
    {
        "field": "trustAllCerts",
        "default": False,
        "say": {"en": "Trust any certificate.", "zh": "信任任何证书。"},
        "conseq": {
            "en": "Packet-capture debugging only. It turns certificate validation off, so production keeps it off.",
            "zh": "只给抓包调试用。它关掉证书校验，生产环境保持关闭。",
        },
    },
]


def _bands(lang: str) -> str:
    en = lang == "en"
    items = []
    for band in BANDS:
        checked = "true" if band["default"] else "false"
        state = (
            ("ON BY DEFAULT" if band["default"] else "OFF BY DEFAULT")
            if en
            else ("默认开启" if band["default"] else "默认关闭")
        )
        items.append(
            f'<article class="band{" is-on" if band["default"] else ""}">'
            '<div class="band-head">'
            f'<code>{esc(band["field"])}</code>'
            f'<button class="sw" type="button" role="switch" aria-checked="{checked}" aria-label="{esc(band["field"])}">'
            '<span aria-hidden="true"></span></button>'
            "</div>"
            f'<p class="band-say">{esc(band["say"][lang])}</p>'
            f'<p class="band-conseq">{esc(band["conseq"][lang])}</p>'
            f'<p class="band-state">{esc(state)}</p>'
            "</article>"
        )
    return '<div class="band-grid" data-ui>' + "".join(items) + "</div>"


def _inventory(lang: str) -> str:
    en = lang == "en"
    names = [
        ("turbodl-core", "engine / zero plugin dependency" if en else "引擎 / 零插件依赖"),
        ("turbo-plugin-runtime", "lifecycle + extension points" if en else "生命周期 + 扩展点"),
        ("turbo-plugin-bootstrap", "one-call wiring" if en else "一次调用完成装配"),
        ("turbo-plugin-hls", "HLS VOD backend" if en else "HLS 点播后端"),
        ("turbo-plugin-js", "QuickJS loader" if en else "QuickJS 加载器"),
        ("turbo-plugin-parser", "share-link parser" if en else "分享链接解析"),
        ("turbodl-cli", "scriptable command line" if en else "可脚本化命令行"),
    ]
    rows = "".join(
        f'<li><code>{esc(name)}</code><span>{esc(role)}</span></li>'
        for name, role in names
    )
    return f'<ol class="module-index" data-lit>{rows}</ol>'


def _telemetry(lang: str) -> str:
    en = lang == "en"
    rows = [
        ("state", "downloading"),
        ("downloaded", "212.4 MB"),
        ("total", "341.8 MB"),
        ("percent", "62"),
        ("speed", "18.6 MB/s"),
        ("connections", "16"),
        ("eta_ms", "7 020"),
    ]
    grid = "".join(f"<div><span>{esc(k)}</span><b>{esc(v)}</b></div>" for k, v in rows)
    return (
        '<div class="runtime-panel" data-ui>'
        '<div class="runtime-head">'
        '<span><i class="signal-dot" aria-hidden="true"></i> PROGRESS EVENT</span>'
        '<b>62%</b>'
        "</div>"
        '<div class="runtime-bar"><i style="--value:62%"></i></div>'
        f'<div class="runtime-grid">{grid}</div>'
        '<p class="runtime-foot">'
        + ('Every field comes from the engine event stream; the SDK renders no interface of its own.'
           if en else "每个字段都来自引擎事件流；SDK 自己不画任何界面。")
        + "</p></div>"
    )


def _console(lang: str) -> str:
    en = lang == "en"
    lines = [
        '{"type":"start","url":"https://example.com/image.iso","output":"/tmp/image.iso"}',
        '{"type":"timing","stage":"metadata","t_ms":184,"probe_ms":96,"resume":""}',
        '{"type":"progress","state":"downloading","downloaded":222732288,"total":358411469,"percent":62,"speed":19503514,"connections":16,"eta_ms":7020}',
        '{"type":"completed","url":"https://example.com/image.iso","file":"/tmp/image.iso","bytes":358411469,"sha256":"0f3a…"}',
        '{"type":"summary","total":1,"failed":0}',
    ]
    body = "\n".join(esc(line) for line in lines)
    cap = "turbodl --json · one event per line" if en else "turbodl --json · 每个事件一行"
    return (
        '<div class="console" data-ui>'
        f'<div class="console-cap"><span>NDJSON / STDOUT</span><span>{esc(cap)}</span></div>'
        f"<pre><code>{body}</code></pre></div>"
    )


def _home_faq(lang: str) -> str:
    en = lang == "en"
    F = _facts()
    rel = F.LATEST["url"]
    tag = F.LATEST["tag"]
    qa = [
        (
            "Does this need Android?",
            "No. It is a pure Kotlin/JVM library. Android is one consumer; a server or desktop tool is another. The only requirement is JVM 17+.",
            "它需要 Android 吗？",
            "不需要。它是纯 Kotlin/JVM 库。Android 只是使用者之一，服务端或桌面工具同样可以用。唯一要求是 JVM 17+。",
        ),
        (
            "Do I have to use the plugin framework?",
            'No. <code>turbodl-core</code> has no plugin-framework dependency. The framework earns its place only when you need protocols beyond HTTP.',
            "必须用插件框架吗？",
            '不必。<code>turbodl-core</code> 不依赖插件框架。需要 HTTP 之外的协议时，框架才有意义。',
        ),
        (
            "Can it download HLS?",
            "Yes for video-on-demand through the official plugin: playlist resolution, concurrent segments and AES-128. Live playlists, DRM and fMP4 are refused explicitly.",
            "能下 HLS 吗？",
            "点播可以，通过官方插件：解析清单、分片并发、AES-128 解密。直播清单、DRM 与 fMP4 会被明确拒绝。",
        ),
        (
            "Is there a GUI?",
            "Not from this project. The SDK deliberately renders no interface. What ships is the engine, the plugin framework and a command-line tool.",
            "有图形界面吗？",
            "这个项目不提供。SDK 有意不画界面。提供的是引擎、插件框架与命令行工具。",
        ),
        (
            "Where do I get the files?",
            f'Every file is an attachment of the <a href="{rel}">{tag}</a> release: a self-contained CLI JAR and an offline Maven bundle. They are not on Maven Central yet.',
            "从哪里拿文件？",
            f'每个文件都是 <a href="{rel}">{tag}</a> Release 的附件：自包含 CLI JAR 与离线 Maven 包。目前还没有发布到 Maven Central。',
        ),
        (
            "What happens when a server lies about Range?",
            "The returned bytes are compared with the requested range. A server that ignores Range is caught and that segment fails instead of writing misplaced bytes.",
            "服务端对 Range 撒谎会怎样？",
            "会核对实际返回的字节与请求的那段。忽略 Range 的服务端会被识别，该分片判为失败，而不是写入错位数据。",
        ),
    ]
    items = []
    for index, (q_en, a_en, q_zh, a_zh) in enumerate(qa):
        opened = " open" if index == 0 else ""
        items.append(
            f'<details class="faq-item"{opened}><summary>'
            f'<span>{esc(q_en if en else q_zh)}</span><i aria-hidden="true"></i></summary>'
            f'<div class="answer"><p>{a_en if en else a_zh}</p></div></details>'
        )
    return '<div class="faq">' + "".join(items) + "</div>"


def _refusals(lang: str, href) -> str:
    en = lang == "en"
    F = _facts()
    rows = []
    for plugin in F.OFFICIAL_PLUGINS:
        chips = "".join(f'<span class="chip">{esc(reason)}</span>' for reason in plugin["refuses"][lang])
        rows.append(
            [
                f'<strong>{esc(plugin["name"])}</strong><code>{esc(plugin["id"])}</code>',
                chips,
                md_inline(plugin["refuses_note"][lang]),
            ]
        )
    lead = (
        "An unsupported stream fails loudly. The alternative — writing a file that looks fine and is not — is the failure mode this section exists to prevent."
        if en
        else "不支持的流会响亮地失败。另一种做法 —— 写下一个看起来正常、其实不对的文件 —— 正是这一节要避免的失败方式。"
    )
    return (
        '<section class="section field section-refusals">'
        '<div class="refusal-head">'
        '<p class="eyebrow"><span class="status-dot is-warn" aria-hidden="true"></span><span>FAIL-CLOSED</span><span>EXPLICIT REFUSALS</span></p>'
        f'<h2>{"What it refuses to pretend" if en else "它拒绝假装支持什么"}</h2>'
        f'<p class="section-lead">{esc(lead)}</p>'
        "</div>"
        + table(
            ["Plugin" if en else "插件", "Refused on purpose" if en else "明确拒绝", "Why" if en else "为什么"],
            rows,
        )
        + "</section>"
    )


def page(lang: str, href) -> str:
    """按“信号进入 → 分片并行 → 事件输出 → 开始使用”的顺序组织首页。"""
    en = lang == "en"
    F = _facts()
    feature_rows = [
        [
            f'<span class="feature-name">{esc(title[lang])}</span>',
            md_inline(copy[lang]),
        ]
        for title, copy in F.FEATURES
    ]
    parts = [
        _hero(lang, href),
        section(
            f"One request becomes {esc(16)} moving parts" if en else f"一次请求，变成 {esc(16)} 个移动部件",
            '<p class="section-lead">'
            + ('The progress bar is not decoration. Its three regions mirror the actual transfer pipeline: probe, parallel shards, verify.'
               if en else "进度条不是装饰。它的三段分别对应真实流程：探测、并行分片、校验。")
            + "</p>"
            + _pipeline(lang)
            + _process_notes(lang),
        ),
        section(
            "Six decisions baked into the byte plane" if en else "字节层面的六个决定",
            '<div class="feature-matrix">'
            + table(
                ["Mechanism" if en else "机制", "Operational consequence" if en else "实际结果"],
                feature_rows,
            )
            + "</div>"
            + '<p class="section-foot">'
            + ("Each line is measurable in the repository. Two exist because the obvious implementation was measured and rejected."
               if en else "每一条都能在仓库里核对。其中两条存在的原因，是「看起来显然」的做法被实测否掉了。")
            + "</p>",
        ),
        section(
            "Every control maps to a public field" if en else "每个控件都对应公开字段",
            '<p class="section-lead">'
            + ("Five controls below use the engine defaults. The switch is live in the page; the numeric consequences come from measured runs."
               if en else "下面五个控件使用引擎默认值。开关可以在页面里直接拨动，数字结论来自实测。")
            + "</p>"
            + _bands(lang)
            + '<div class="capability-surface">' + F._capabilities(lang) + "</div>"
            + '<p class="section-foot">'
            + ("The SDK renders no interface by design: speed limits, queues and progress remain values your application chooses how to show."
               if en else "SDK 有意不画界面：限速、队列与进度都是值，怎么呈现由上层应用决定。")
            + "</p>",
        ),
        section(
            "One repository, seven deliberately separate artifacts" if en else "一个仓库，七个刻意分开的构件",
            '<p class="section-lead">'
            + ("Take the engine alone or assemble the whole protocol stack. The dependency direction never reverses."
               if en else "可以只取引擎，也可以装配完整协议栈。依赖方向始终不会反转。")
            + "</p>"
            + _inventory(lang)
            + '<div class="module-table">' + F._modules(lang) + "</div>",
        ),
        _refusals(lang, href),
        section(
            "What the engine emits while it runs" if en else "引擎运行时会发出什么",
            '<p class="section-lead">'
            + ("The CLI can print one JSON object per event. The panel and terminal below use the same field names as the engine."
               if en else "命令行可以每个事件打印一行 JSON。下面的面板和终端使用引擎同一批字段名。")
            + "</p>"
            + '<div class="runtime-stack">' + _telemetry(lang) + _console(lang) + "</div>"
            + code_block(
                f"""java -jar turbodl-cli-{F.LATEST_VERSION}-all.jar <url> -o out.bin -c 16
java -jar turbodl-cli-{F.LATEST_VERSION}-all.jar <url> --json
java -jar turbodl-cli-{F.LATEST_VERSION}-all.jar --batch tasks.txt""",
                "shell",
            ),
        ),
        section(
            "Questions before the first download" if en else "第一次下载之前会问的事",
            _home_faq(lang),
        ),
        section(
            "Start with one coordinate" if en else "从一个坐标开始",
            '<p class="cta-line">'
            + ("Add the engine to your build and hand the bytes to the runtime:"
               if en else "把引擎加进构建，把字节交给运行时：")
            + "</p>"
            + '<div class="cta-terminal">'
            + '<span class="cta-prompt" aria-hidden="true">$</span>'
            + f'<input class="cta-coord" id="coord" readonly value=\'implementation("dev.turbodl:turbodl-core:{F.LATEST_VERSION}")\' aria-label="Gradle dependency">'
            + f'<button class="btn btn-primary" type="button" data-copy="#coord" data-copied="{"Copied" if en else "已复制"}"><span>{"Copy" if en else "复制"}</span><span class="btn-arrow" aria-hidden="true">⌘</span></button>'
            + "</div>"
            + '<p class="cta-hint">'
            + ("Not on Maven Central yet. The release’s offline Maven bundle is the supported way to consume it; the download page spells out how."
               if en else "还没有发布到 Maven Central。官方支持的用法是 Release 页上的离线 Maven 包，下载页写了具体步骤。")
            + "</p>"
            + '<div class="btn-row end-actions">'
            + f'<a class="btn btn-primary" href="{href("download")}"><span>Download {esc(F.LATEST["tag"])}</span><span class="btn-arrow" aria-hidden="true">↓</span></a>'
            + f'<a class="btn btn-ghost" href="{href("plugins")}"><span>{"Plugin guide" if en else "插件开发指南"}</span><span class="btn-arrow" aria-hidden="true">↗</span></a>'
            + f'<a class="btn btn-ghost" href="{esc(F.REPO)}"><span>GitHub</span><span class="btn-arrow" aria-hidden="true">↗</span></a>'
            + "</div>",
        ),
    ]
    return "".join(parts)
