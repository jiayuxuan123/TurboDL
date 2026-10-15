#!/usr/bin/env python3
"""TurboDL 首页正文（英文在 `/`，中文在 `/zh/`）。

为什么单独一个文件：首页按 taste-skill 的定向单重写后，是九节、双语的版式代码，
和 `content.py` 里那些"页面清单 + 事实表"不是同一种东西。放在一起会让两个文件
都难以阅读；拆开之后 `content.py` 仍然拥有**全部事实**，本文件只负责把它们
排成版式，并通过 [facts] 延迟读取。

延迟导入不是风格问题：`content.py` 在模块级 `import home`（由它的 `_page_home`
转调本文件），所以本文件**不能**在模块级 `import content` —— 那会循环导入。
`_pages()` 只是在列表里存下函数对象，真正调用发生在渲染阶段，那时两个模块都已就绪。

版式与事实的对应（每一节都能在仓库里核对）：
  1. 首屏两场        —— 定向单的 Headline across two fields
  2. 三段进度条      —— 1 次探测 / 16 个并发分片（TurboConfig 默认）/ 1 次合并校验
  3. 六件事实        —— README 的能力表
  4. 开关与字段表    —— TurboConfig.kt 的字段、默认值与实测注释（11.08→10.39s、2576/2584ms）
  5. 箱子里有什么    —— 各模块的 turbodl-plugin.json
  6. 它拒绝什么      —— 插件的 refuses 清单（原样，不改写语义）
  7. 报告什么        —— turbodl-cli 的 progress 事件字段与 NDJSON 行
  8. 提问            —— 六个真问题
  9. 怎么开始        —— 真实坐标（dev.turbodl:turbodl-core:<version>）
"""

from __future__ import annotations

from sitekit import code_block, esc, md_inline, section, seg_rule, table


def _facts():
    """延迟读取事实表（见模块头注释：不能在模块级 import，会循环）。"""
    import content

    return content


# --------------------------------------------------------------------------- #
# 界面碎片（[data-ui]）：整页的"图"由产品自己的控件担任，不用装饰性图片
# --------------------------------------------------------------------------- #


def _strip() -> str:
    """内联在句子里的分片带（16 根柱子；每根的推进由 site.js 用固定序列推）。

    它是句子里的「一块」而不是插图：定向单要求句子里的某个位置由产品自己的东西
    占着，读的人用眼睛把那句话补完。
    """
    return '<span class="strip" data-strip aria-hidden="true"></span>'


def _stages(lang: str) -> str:
    """一个转移的三段：放大到整幅宽度的进度条（[data-ui]）。

    段的宽度按真实的量分配：1 次探测请求 / 16 个并发分片（引擎默认
    `maxConnectionsPerTask = 16`）/ 1 次合并与校验。
    """
    en = lang == "en"
    seg = [
        ("1", "request", "次请求", "First, one request asks whether the server honours Range.", "先发一个请求，问服务端认不认 Range。"),
        (
            "16",
            "in flight",
            "个在飞",
            "Then sixteen segments are in flight; work stealing keeps one slow connection from holding up the rest.",
            "然后 16 个分片同时在飞；工作窃取让一条慢连接拖不垮其余的。",
        ),
        ("1", "pass", "次校验", "Finally the parts are merged and the byte count is verified.", "最后合并这些分片，并核对总字节数。"),
    ]
    cells = []
    for i, (count, unit_en, unit_zh, clue_en, clue_zh) in enumerate(seg, start=1):
        unit = unit_en if en else unit_zh
        cells.append(
            f'<div class="stage stage-{i}">'
            f'<div class="stage-count">{count} <span class="stage-unit">{esc(unit)}</span></div>'
            f'<p class="stage-clause">{esc(clue_en if en else clue_zh)}</p>'
            "</div>"
        )
    return '<div class="stages" data-ui>' + "".join(cells) + "</div>"


def _stage_list(lang: str) -> str:
    """三段各自的判断依据（顺序词写在句子开头，不用 01/02/03 编号）。"""
    en = lang == "en"
    rows = [
        (
            "Ahead of the request" if en else "请求之前",
            "A Range probe settles whether the server can serve byte ranges at all. If it cannot, the engine stops pretending and downloads straight through."
            if en
            else "先用一个 Range 探测确认服务端到底能不能按字节范围给数据。不认就不假装认，直接整条下载。",
        ),
        (
            "While it runs" if en else "下载进行时",
            "Block count is about four times the connection count, so a slow connection never owns the last remaining piece of work."
            if en
            else "块数大约是连接数的四倍，所以某条慢连接不会独占最后剩下那点工作。",
        ),
        (
            "Before it says done" if en else "说完成之前",
            "The bytes actually returned are checked against the range that was asked for, and the merged file is checked against the total size."
            if en
            else "核对服务端实际返回的字节是不是请求的那段，合并后再核对总大小。",
        ),
    ]
    items = "".join(f"<li><b>{esc(k)}</b><span>{v}</span></li>" for k, v in rows)
    return f'<ul class="stage-list">{items}</ul>'


#: 五个真实的配置字段：字段名、默认值、开关打开后的真实结论。
#: 默认值就是代码里的默认值（`dynamicSegmentation`、`tailAssist` 默认 true，所以进页面
#: 就有两条横带是亮的）；结论里的数字全部来自 `TurboConfig.kt` 的实测注释。
BANDS = [
    {
        "field": "dynamicSegmentation",
        "default": True,
        "say": {"en": "Split the work finer than the connections.", "zh": "把工作切得比连接更细。"},
        "conseq": {
            "en": "Block count runs about four times the connection count, so the last stretch is never one connection on its own. Turn it off and you get fixed blocks with work stealing.",
            "zh": "块数约为连接数的四倍，收尾阶段不会只剩一条连接在干活。关掉它则退回固定块大小 + 工作窃取。",
        },
    },
    {
        "field": "tailAssist",
        "default": True,
        "say": {"en": "Hand the tail to whatever is idle.", "zh": "把尾巴交给闲着的连接。"},
        "conseq": {
            "en": "Only in the endgame: at most 2 splits per segment, nothing under 2 MiB is split, so a 16 MiB block becomes at most 4. Splitting earlier would only raise the request count.",
            "zh": "只在收尾阶段生效：每个分片最多拆 2 次，低于 2 MiB 不拆 —— 16 MiB 的块最多变成 4 段。更早拆分只会抬高请求数。",
        },
    },
    {
        "field": "overlapMerge",
        "default": False,
        "say": {"en": "Merge while the last bytes are still arriving.", "zh": "最后几个字节还在路上时先合并。"},
        "conseq": {
            "en": "Measured on 512 MB / 64 connections: 11.08 s down to 10.39 s, about 6%. With tail assist on it can write the same range twice, so it stays off by default.",
            "zh": "512 MB / 64 连接实测：11.08 s 降到 10.39 s，约 6%。但与收尾托管同时开启时可能重复写同一段，所以默认关闭。",
        },
    },
    {
        "field": "bufferedSegmentWrite",
        "default": False,
        "say": {"en": "Write segments through a buffer.", "zh": "分片写入走缓冲流。"},
        "conseq": {
            "en": "A/B on 16 MB / 128 connections / 64 KB segments: 2576 ms direct against 2584 ms buffered, 0.3% inside the noise. Resume reads the file length, so buffering would blur what landed on disk.",
            "zh": "16 MB / 128 连接 / 64 KB 分片实测：直写 2576 ms、缓冲 2584 ms，0.3% 落在噪声里。续传按文件长度判断进度，缓冲只会让「已落盘」变模糊。",
        },
    },
    {
        "field": "trustAllCerts",
        "default": False,
        "say": {"en": "Trust any certificate.", "zh": "信任任何证书。"},
        "conseq": {
            "en": "For packet-capture debugging only: it turns certificate validation off. Production keeps it off, and the default does.",
            "zh": "只给抓包调试用：它会关掉证书校验。生产环境保持关闭 —— 默认就是关的。",
        },
    },
]


def _bands(lang: str) -> str:
    """能力开关横带（[data-ui]）：每句句尾是产品真正的开关。

    开关状态与引擎默认值一致（`dynamicSegmentation` 与 `tailAssist` 默认 true，
    所以进页面就有两条亮着）；键盘 Space / Enter 与点击等效，语义在 site.js 里。
    """
    en = lang == "en"
    out = []
    for i, b in enumerate(BANDS):
        on = " on" if b["default"] else ""
        # 两条亮着的横带用两种色：一条纸场、一条红场（红只在这一处与按钮上出现）
        alt = " alt" if i == 1 else ""
        default_label = ("default on" if b["default"] else "default off") if en else ("默认开启" if b["default"] else "默认关闭")
        out.append(
            f'<div class="band{on}{alt}">'
            f'<p class="band-say">{esc(b["say"]["en" if en else "zh"])}</p>'
            f'<button class="sw" type="button" role="switch" aria-checked="{"true" if b["default"] else "false"}" '
            f'aria-label="{esc(b["field"])}"></button>'
            f'<p class="band-field"><span class="mono">{esc(b["field"])}</span> · {esc(default_label)}</p>'
            f'<p class="band-conseq">{b["conseq"]["en" if en else "zh"]}</p>'
            "</div>"
        )
    return '<div class="bands" data-ui>' + "".join(out) + "</div>"


def _lit(lang: str) -> str:
    """「名字写成一句话」：随包发布的那几件东西写成一句，轮流点亮其中一个。

    对引擎站点来说，被点亮的名字就是 `turbo-plugin-*` 与 CLI —— 这是真实清单，
    不是客户墙（下载引擎没有客户名单可写）。
    """
    en = lang == "en"
    head = "In the box already: " if en else "已经装在箱子里："
    names = [
        ("turbodl-core", "the engine" if en else "引擎本体"),
        ("turbo-plugin-runtime", "the plugin runtime" if en else "插件运行时"),
        ("turbo-plugin-bootstrap", "the bootstrap" if en else "基础装配"),
        ("turbo-plugin-hls", "the HLS backend" if en else "HLS 后端"),
        ("turbo-plugin-js", "the JavaScript loader" if en else "JavaScript 加载器"),
        ("turbodl-cli", "the CLI" if en else "命令行工具"),
    ]
    parts = []
    for i, (_artifact, label) in enumerate(names):
        tail = "." if i == len(names) - 1 else ("、 " if lang == "zh" else ", ")
        parts.append(f'<b class="mono">{esc(label)}</b>{tail}')
    return f'<p class="lit" data-lit>{esc(head)}' + "".join(parts) + "</p>"


def _panel(lang: str) -> str:
    """一块传输面板（[data-ui]）：字段就是 CLI `progress` 事件里的字段。

    数字是一次真实跑动的取值（212 MB 的镜像，16 连接）。面板的用途是让人一眼看到
    这个引擎会报告什么，而不是 P 一个"演示数据大屏"。
    """
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
    lead = "a transfer in progress · 16 connections" if en else "一次正在进行的传输 · 16 连接"
    foot = (
        "Every field here comes from the engine's progress event; the SDK renders no interface of its own."
        if en
        else "这里的每个字段都来自引擎的 progress 事件；SDK 自己不画任何界面。"
    )
    return (
        '<div class="panel" data-ui>'
        '<div class="panel-head">'
        f'<p class="panel-state">{esc(lead)}</p>'
        '<p class="panel-pct">62%</p>'
        "</div>"
        '<div class="panel-bar"><i></i></div>'
        f'<div class="panel-grid">{grid}</div>'
        f'<p class="panel-foot">{esc(foot)}</p>'
        "</div>"
    )


def _console(lang: str) -> str:
    """NDJSON 控制台（[data-ui]）：CLI 真实打印的几行（事件名与字段名来自 Main.kt）。"""
    en = lang == "en"
    lines = [
        '{"type":"start","url":"https://example.com/image.iso","output":"/tmp/image.iso"}',
        '{"type":"timing","stage":"metadata","t_ms":184,"probe_ms":96,"resume":""}',
        '{"type":"progress","state":"downloading","downloaded":222732288,"total":358411469,"percent":62,"speed":19503514,"connections":16,"eta_ms":7020}',
        '{"type":"completed","url":"https://example.com/image.iso","file":"/tmp/image.iso","bytes":358411469,"sha256":"0f3a…"}',
        '{"type":"summary","total":1,"failed":0}',
    ]
    body = "\n".join(esc(l) for l in lines)
    cap = "turbodl --json · one line per event" if en else "turbodl --json · 每个事件一行"
    return (
        '<div class="console" data-ui>'
        f'<div class="console-cap">{esc(cap)}</div>'
        f"<pre><code>{body}</code></pre>"
        "</div>"
    )


def _home_faq(lang: str) -> str:
    """首页的提问（六个，都是真问题；答案都能在仓库里核对）。"""
    en = lang == "en"
    F = _facts()
    rel = F.LATEST["url"]
    tag = F.LATEST["tag"]
    qa = [
        (
            "Does this need Android?",
            "No. It is a pure Kotlin/JVM library — the Android app is one consumer, a server or a desktop tool is another. The only requirement is JVM 17+.",
            "它需要 Android 吗？",
            "不需要。它是纯 Kotlin/JVM 库 —— Android 应用只是使用者之一，服务端或桌面工具同样可以用。唯一的运行要求是 JVM 17+。",
        ),
        (
            "Do I have to use the plugin framework?",
            'No. <span class="mono">turbodl-core</span> does not depend on it: embed the engine and stop there. The framework earns its place only when you need protocols beyond HTTP.',
            "必须用插件框架吗？",
            '不必。<span class="mono">turbodl-core</span> 不依赖它：只用引擎就停下也可以。需要 HTTP 之外的协议时，这个框架才有意义。',
        ),
        (
            "Can it download HLS?",
            "Yes for video-on-demand, through an official plugin: playlists are resolved, segments are fetched concurrently and AES-128 is decrypted. Live playlists, DRM and fMP4 are refused explicitly instead of producing a file that looks fine and is not.",
            "能下 HLS 吗？",
            "点播可以 —— 通过官方插件：解析清单、分片并发、AES-128 解密。直播清单、DRM 与 fMP4 会被<strong>明确拒绝</strong>，而不是产出一个看起来正常、其实不对的文件。",
        ),
        (
            "Is there a GUI?",
            "Not from this project. The SDK renders no interface of its own — that is deliberate. What ships is the engine, the plugin framework and a command-line tool.",
            "有图形界面吗？",
            "这个项目不提供。SDK 自己不画任何界面 —— 这是有意的。提供的是引擎、插件框架与一个命令行工具。",
        ),
        (
            "Where do I get the files?",
            f'Every file is an attachment of the <a href="{rel}">{tag}</a> release: a self-contained CLI JAR and an offline Maven bundle. They are not on Maven Central yet, and the download page says so rather than pretending otherwise.',
            "从哪里拿文件？",
            f'每个文件都是 <a href="{rel}">{tag}</a> 这个 Release 的附件：一个自包含的 CLI JAR 和一个离线 Maven 包。它们还没有发布到 Maven Central —— 下载页如实写明，不假装有。',
        ),
        (
            "What happens when a server lies about Range?",
            "The bytes actually returned are compared with the range that was requested. A server that ignores Range and answers with the whole file is caught, and that segment fails instead of writing misplaced bytes into the output.",
            "服务端对 Range 撒谎会怎样？",
            "会核对实际返回的字节与请求的那段是否一致。忽略 Range、直接回整文件的服务端会被识别出来，该分片判为失败，而不是把错位的数据写进文件。",
        ),
    ]
    out = []
    for i, (q_en, a_en, q_zh, a_zh) in enumerate(qa):
        open_attr = " open" if i == 0 else ""
        out.append(
            f"<details{open_attr}><summary>{esc(q_en if en else q_zh)}</summary>"
            f'<div class="answer"><p>{a_en if en else a_zh}</p></div></details>'
        )
    return '<div class="faq">' + "".join(out) + "</div>"


# --------------------------------------------------------------------------- #
# 首屏与整页
# --------------------------------------------------------------------------- #


def _hero(lang: str, href) -> str:
    """首屏：两块平坦的场在正中相接，同一句标题被接缝切开、墨色翻面。

    结构（对应 site.css 的 .hl-*）：两块整幅的层，各自带自己的底色（纸场在上、
    黑场在下），各裁一半显示同一句话 —— 所以「接缝」既是两块场的硬边，也是墨色
    翻面的位置。两层同属一个 <h1>：探针把「同一个标题里互相压着的行」和
    「被别的东西压住的标题」分开判断，两句必须同属一个标题才不会被读成遮挡。
    """
    en = lang == "en"
    F = _facts()
    l1, l2 = ("Many hands,", "one file.") if en else ("各下一段，", "合成一份。")
    note = (
        "Sixteen connections work the same file at once, and a pause resumes from the bytes that actually landed on disk."
        if en
        else "16 条连接同时处理同一个文件；暂停后从磁盘上真实写完的字节接着下。"
    )
    dl = "Download" if en else "下载"
    ds = "Read the docs" if en else "看文档"
    meta = f"{F.LATEST['tag']} · MIT · JVM 17+ · {F.TESTED[lang]}"
    return (
        '<section class="two-field">'
        '<h1 class="hl">'
        f'<span class="hl-layer hl-top"><span class="hl-copy"><span>{esc(l1)}</span><span>{esc(l2)}</span></span></span>'
        f'<span class="hl-layer hl-bot"><span class="hl-copy"><span>{esc(l1)}</span><span>{esc(l2)}</span></span></span>'
        "</h1>"
        '<div class="hero-in">'
        f'<p class="hero-note">{esc(note)}</p>'
        '<div class="hero-act">'
        f'<a class="btn" href="{href("download")}">{esc(dl)} {esc(F.LATEST["tag"])}</a>'
        f'<a class="btn btn-ghost" href="{href("docs")}">{esc(ds)}</a>'
        "</div>"
        f'<p class="hero-meta">{esc(meta)}</p>'
        "</div>"
        "</section>"
    )


def _refusals(lang: str, href) -> str:
    """「它拒绝什么」：整节翻成纸场（调色板允许中后段翻面），内容全部来自插件清单。

    为什么值得单独一节：下载工具最容易犯的错是「假装支持」—— 打开一个直播清单，
    存下一个永远播不了的文件。这里把拒绝清单原样摊开。
    """
    en = lang == "en"
    F = _facts()
    rows = []
    for p in F.OFFICIAL_PLUGINS:
        chips = "".join(f'<span class="chip">{esc(r)}</span>' for r in p["refuses"][lang])
        rows.append(
            [
                f'<strong>{esc(p["name"])}</strong><br><span class="mono small">{esc(p["id"])}</span>',
                chips,
                md_inline(p["refuses_note"][lang]),
            ]
        )
    lead = (
        "An unsupported stream fails loudly. The alternative — writing a file that looks fine and is not — is the failure mode this section exists to avoid."
        if en
        else "不支持的流会响亮地失败。另一种做法 —— 写下一个看起来正常、其实不对的文件 —— 正是这一节要避免的失败方式。"
    )
    foot = (
        "The plugin framework keeps the same discipline on its Kotlin side: a script may compute URLs, headers and signatures, but it never touches the download stream."
        if en
        else "插件框架在 Kotlin 一侧同样克制：脚本可以算 URL、请求头与签名，但从不经手下载数据流。"
    )
    return (
        '<section class="field">'
        + seg_rule([(4, True), (2, True), (7, False), (3, True), (5, False)])
        + f'<h2>{"What it refuses to do" if en else "它拒绝做什么"}</h2>'
        + f'<p class="section-lead">{esc(lead)}</p>'
        + '<div class="push-right">'
        + table(["Plugin" if en else "插件", "Refused on purpose" if en else "明确拒绝", "Why" if en else "为什么"], rows)
        + "</div>"
        + f'<p class="section-foot">{esc(foot)}</p>'
        + "</section>"
    )


def page(lang: str, href) -> str:
    """首页正文：九节，每一节都是一句话配上它的证据。

    节的顺序按读者的疑问走：它是什么（首屏）→ 它怎么动（三段）→ 它做了什么
    （六件事实）→ 你能拨什么（开关 + 字段表）→ 箱子里有什么（名字 + 构件）→
    它拒绝什么（纸场）→ 你怎么看它干活（面板 + NDJSON）→ 还有疑问 → 怎么开始（坐标）。
    """
    en = lang == "en"
    F = _facts()
    parts = [
        _hero(lang, href),
        # 2. 三段（被放大的进度条）：句子里的空位由产品自己的分片带占着
        section(
            f"One file, {_strip()} three stages" if en else f"一个文件，{_strip()} 三段",
            _stages(lang)
            + f'<p class="stage-note">{"The bar is the engine\'s own progress bar, enlarged until it spans the page. Its widths follow the real amount of work in each stage, not a decorative ratio." if en else "这条就是引擎自己的进度条，被放大到整幅宽度。各段宽度按每段真实的量分配，不是装饰性的比例。"}</p>'
            + _stage_list(lang),
        ),
        # 3. 六件事实（表推到右侧；留白本身就是版式）
        section(
            "Six things it does to the bytes" if en else "它对字节做的六件事",
            '<div class="push-right">'
            + table(
                ["What" if en else "做什么", "How" if en else "怎么做"],
                [[f"<strong>{esc(t[lang])}</strong>", md_inline(b[lang])] for t, b in F.FEATURES],
            )
            + "</div>"
            + f'<p class="section-foot">{"Each line can be checked against the repository; they are not feature names. Two of them exist because the obvious version was measured and turned out wrong." if en else "每一条都能在仓库里核对，不是功能名词。其中两条存在的原因，是「看起来显然」的做法被实测否掉了。"}</p>',
        ),
        # 4. 开关（真开关 + 九项字段表）
        section(
            "Every knob is a public config field" if en else "每个旋钮都是公开的配置字段",
            f'<p class="section-lead">{"Five of them are switches you can flip right here; the state matches the engine\'s defaults, and the numbers in the answers are measured, not estimated." if en else "其中五个是你可以直接按的开关；开关状态与引擎默认值一致，答案里的数字是实测的，不是估计的。"}</p>'
            + _bands(lang)
            + F._capabilities(lang)
            + f'<p class="section-foot">{"The SDK deliberately renders no interface: speed limits, queues and progress are values your application decides how to show." if en else "SDK 有意不画界面：限速、队列与进度都是值，怎么呈现由你的应用决定。"}</p>',
        ),
        # 5. 箱子里有什么（名字一句话 + 构件表）
        section(
            "What ships in the box" if en else "箱子里到底有什么",
            _lit(lang)
            + '<div class="push-right">'
            + F._modules(lang)
            + "</div>"
            + f'<p class="section-foot">{"Six artifacts in one repository: the engine can be taken alone, the rest is added on purpose. Idea-level acknowledgements: aria2, XDM, axel, Persepolis, Motrix, ab-download-manager — no source code copied." if en else "一个仓库里六个构件：引擎可以单独取走，其余是主动加进来的。思路层面的致谢：aria2、XDM、axel、Persepolis、Motrix、ab-download-manager —— 未复制任何源码。"}</p>',
        ),
        # 6. 它拒绝什么（纸场：整节翻面）
        _refusals(lang, href),
        # 7. 你怎么看它干活（面板 + NDJSON，两块界面碎片）
        section(
            "What it reports while it runs" if en else "它跑起来会报告什么",
            f'<p class="section-lead">{"The command line can print one JSON object per event, which is what a script or an agent reads. The panel below uses the same field names." if en else "命令行可以每个事件打印一行 JSON —— 脚本与 Agent 读的就是这个。下面那块面板用的是同一批字段名。"}</p>'
            + _panel(lang)
            + _console(lang)
            + code_block(
                f"""java -jar turbodl-cli-{F.LATEST_VERSION}-all.jar <url> -o out.bin -c 16
java -jar turbodl-cli-{F.LATEST_VERSION}-all.jar <url> --json          # one event per line
java -jar turbodl-cli-{F.LATEST_VERSION}-all.jar --batch tasks.txt     # one URL per line""",
                "shell",
            ),
        ),
        # 8. 还有疑问
        section("Questions that come up first" if en else "最先被问到的几件事", _home_faq(lang)),
        # 9. 怎么开始（句子中间空出来的那块就是坐标）
        section(
            "Start with one line" if en else "从一行依赖开始",
            f'<p class="cta-line">{"Add the engine to your build, and the bytes are someone else\'s problem:" if en else "把引擎加进你的构建，字节的事就交出去了："}</p>'
            + '<div class="cta-row">'
            + f"<input class=\"cta-coord\" id=\"coord\" readonly value='implementation(\"dev.turbodl:turbodl-core:{F.LATEST_VERSION}\")'>"
            + f'<button class="btn" type="button" data-copy="#coord" data-copied="{"Copied" if en else "已复制"}">{"Copy" if en else "复制"}</button>'
            + "</div>"
            + f'<p class="cta-hint">{"Not on Maven Central yet: the release\'s offline Maven bundle is the supported way to consume it, and the download page spells out how." if en else "还没有发布到 Maven Central：官方支持的用法是 Release 页上的离线 Maven 包，下载页写了具体怎么做。"}</p>'
            # 收尾的动作行：红色只留给唯一的主行动（下载），另两个走一档色差。
            + '<div class="btn-row">'
            + f'<a class="btn" href="{href("download")}">Download {esc(F.LATEST["tag"])}</a>'
            + f'<a class="btn btn-ghost" href="{href("plugins")}">{"Plugin guide" if en else "插件开发指南"}</a>'
            + f'<a class="btn btn-ghost" href="{esc(F.REPO)}">GitHub</a>'
            + "</div>",
        ),
    ]
    return "".join(parts)
