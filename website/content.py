#!/usr/bin/env python3
"""TurboDL 官网的内容定义（英文在 `/`，中文在 `/zh/`）。

## 这份文件里只有"事实"

页面正文全部来自仓库里**可核对**的东西：README 的能力表、`build.gradle.kts` 的模块清单、
各模块的 `turbodl-plugin.json` 清单、以及 `website/data/releases.json`（由
`refresh_releases.py` 从 GitHub API 抓取的真实 Release 数据）。

写内容时的三条纪律（对应设计文档第 19 / 23 / 26 章）：

1. **下载页只列真实存在的产物**：资产表由 `releases.json` 渲染，不手写任何 URL；
   某个文件不存在就不显示，而不是给一个 404 的链接。
2. **版本号以"已发布的 Release"为准**：源码版本可能已经领先于发布（正在开发下一版），
   官网上写的必须是用户**现在真能下到**的那个版本。
3. **不写"已验证 / 绝对安全"**：不存在签名与验证流程，就不声称有；插件页如实说明来源。

## 与 `build.py` 的分工

本文件只产出 **page 定义**（key / 语言 / 路径 / 标题 / 正文构造函数）。
正文构造函数接收一个 `href(page_key) -> 相对链接` 解析器，**不在本文件里拼路径** ——
同一份内容要同时存在于 `/` 与 `/zh/download/` 两个深度下，手拼必然算错。
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import mdlite
from sitekit import buttons, cards, code_block, esc, note, section, table

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
DATA = json.loads((HERE / "data" / "releases.json").read_text(encoding="utf-8"))
LATEST = DATA["latest"]
RELEASES = DATA["releases"]
LATEST_VERSION = LATEST["tag"].lstrip("v")

REPO = "https://github.com/jiayuxuan123/TurboDL"
BLOB = f"{REPO}/blob/main"

#: 本仓库实测的测试数（`./gradlew test`，0 跳过 0 失败）—— 不是估计值。
TEST_COUNT = 258
#: 公开 API 版本，来自 turbodl-core 的 ApiVersion.CURRENT。
API_VERSION = "1.0.0"

# --------------------------------------------------------------------------- #
# 事实表（双语共用，渲染时按语言挑）
# --------------------------------------------------------------------------- #

TESTED = {"en": f"{TEST_COUNT} tests, 0 skipped, 0 failed", "zh": f"{TEST_COUNT} 个用例，0 跳过，0 失败"}

MODULES = [
    (
        "turbodl-core",
        {"en": "Download engine SDK", "zh": "下载引擎本体"},
        {
            "en": "Segmented parallel download, resume, retry, speed limit, DNS/proxy. Publishes fine on its own and has **no** plugin-framework dependency.",
            "zh": "分片并发下载、断点续传、重试、限速、DNS/代理。可单独使用，**不依赖**插件框架。",
        },
        True,
    ),
    (
        "turbo-plugin-runtime",
        {"en": "Plugin runtime kernel", "zh": "插件运行时内核"},
        {
            "en": "Lifecycle, cleanup (disposer), event bus, service registry, extension points, version handshake, diagnostics. Knows nothing about any specific protocol.",
            "zh": "生命周期、清理（disposer）、事件总线、服务注册表、扩展点、版本握手、诊断。对任何具体协议一无所知。",
        },
        False,
    ),
    (
        "turbo-plugin-bootstrap",
        {"en": "Bootstrap wiring", "zh": "基础装配"},
        {
            "en": "One-call wiring of the base plugins (Kotlin loader + HTTP backend) and the bridge from the engine's event stream.",
            "zh": "一次调用装配基础插件（Kotlin 加载器 + HTTP 后端），并桥接引擎的事件流。",
        },
        False,
    ),
    (
        "turbo-plugin-hls",
        {"en": "HLS VOD adapter", "zh": "HLS VOD 适配"},
        {
            "en": "Resolves M3U8 playlists, downloads segments concurrently (with per-segment retry), decrypts AES-128, honors EXT-X-BYTERANGE. Live/DRM/fMP4 playlists are refused explicitly instead of producing corrupt output.",
            "zh": "解析 M3U8 清单、分片并发下载（分片级重试）、AES-128 解密、支持 EXT-X-BYTERANGE。直播/DRM/fMP4 清单会被**明确拒绝**，而不是产出一个损坏的文件。",
        },
        False,
    ),
    (
        "turbo-plugin-js",
        {"en": "JavaScript plugin loader", "zh": "JavaScript 插件加载器"},
        {
            "en": "Loads TurboDL plugins written in JavaScript. Embeds QuickJS — one runtime and one context per script — and exposes a stable `host`/`plugin` ABI with coarse-grained capabilities.",
            "zh": "加载用 JavaScript 写的 TurboDL 插件。内嵌 QuickJS —— 每个脚本一个 runtime 与 context —— 并给脚本一套稳定的 `host`/`plugin` ABI。",
        },
        False,
    ),
    (
        "turbodl-cli",
        {"en": "Command-line tool", "zh": "命令行工具"},
        {
            "en": "A complete CLI built on the SDK: multi-threaded, resumable, batch mode, NDJSON output for scripts and agents. Shipped as a fat JAR.",
            "zh": "基于 SDK 的完整命令行工具：多线程、断点续传、批量、给脚本与 Agent 用的 NDJSON 输出。以 fat JAR 形式提供。",
        },
        False,
    ),
]

CAPABILITIES = [
    ("Global speed limit", "全局限速", "globalSpeedLimitBytesPerSec", "0 = unlimited", "0 = 不限速"),
    ("Thread count", "线程数", "maxConnectionsPerTask", "1..256", "1..256"),
    ("Concurrent tasks", "并发任务数", "maxConcurrentTasks", "1..64", "1..64"),
    ("Max retries", "最大重试", "maxRetries", "0..50", "0..50"),
    ("Dynamic segmentation", "动态分片", "dynamicSegmentation", "true / false", "true / false"),
    ("Proxy", "代理", "proxy", "Direct / System / Manual(HTTP,SOCKS,auth) / Pac(url)", "直连 / 系统 / 手动(HTTP,SOCKS,鉴权) / PAC"),
    ("DNS", "DNS", "dns", "System / StaticHosts / DoH(url)", "系统 / 静态 hosts / DoH(url)"),
    ("Ignore SSL", "忽略 SSL", "trustAllCerts", "packet-capture debugging", "抓包调试用"),
    ("Theming", "主题", "—", "upper-layer app's job; the SDK renders no UI", "由上层应用负责；SDK 不涉及界面"),
]

FEATURES = [
    (
        {"en": "Segmented parallel download", "zh": "分片并发下载"},
        {
            "en": "HTTP Range segments downloaded in parallel, with connection reuse (HTTP/2 multiplexing + keep-alive).",
            "zh": "按 HTTP Range 并行下载分片，并复用连接（HTTP/2 多路复用 + keep-alive）。",
        },
    ),
    (
        {"en": "Dynamic segmentation + work stealing", "zh": "动态分片 + 工作窃取"},
        {
            "en": "Fine-grained pre-splitting plus work stealing, so one slow connection cannot drag the whole transfer down — and the \"last segment on a single thread\" long tail disappears.",
            "zh": "细粒度预分片 + 工作窃取：一条慢连接拖不垮整个传输，也消除了「最后一个分片单线程收尾」的长尾。",
        },
    ),
    (
        {"en": "Restrained adaptivity", "zh": "克制的自适应"},
        {
            "en": "Concurrency drops **only** on 429/503 or after consecutive failures. Ordinary jitter never touches the thread count — no AIMD oscillation.",
            "zh": "**只在**遇到 429/503 或连续失败时才降低并发。普通抖动绝不改线程数 —— 不做 AIMD 来回震荡。",
        },
    ),
    (
        {"en": "Resume from real progress", "zh": "按真实进度续传"},
        {
            "en": "Segments are persisted on disk; pause/resume continues from what actually landed, not from a remembered number.",
            "zh": "分片状态落盘；暂停/继续按磁盘上真实写完的字节接着下，而不是按记忆中的数字。",
        },
    ),
    (
        {"en": "Range tampering rejected", "zh": "拒绝 Range 篡改"},
        {
            "en": "The bytes actually returned are checked against the Range that was requested — a server that ignores Range and sends the whole file is caught, not written as misplaced data.",
            "zh": "会核对服务端实际返回的字节是否就是请求的那段 Range —— 忽略 Range 直接回整文件的服务端会被识别出来，而不是把错位数据写进文件。",
        },
    ),
    (
        {"en": "Byte-level integrity check", "zh": "字节级完整性校验"},
        {
            "en": "Total size is verified after the merge step; a short or oversized result is rejected.",
            "zh": "合并完成后校验总大小；少了或多了都判为失败。",
        },
    ),
]

PLUGIN_CATEGORIES = [
    ("turbodl-backend", {"en": "A protocol backend (replaces or extends the built-in HTTP engine).", "zh": "协议后端（替换或扩展内置 HTTP 引擎）。"}),
    ("turbodl-protocol", {"en": "Declares the protocols/schemes it handles (the manifest `protocols` list).", "zh": "声明它处理哪些协议 / scheme（清单的 `protocols` 列表）。"}),
    ("turbodl-adapter", {"en": "Bridges an external downloader.", "zh": "对接外部下载器。"}),
    ("turbodl-parser", {"en": "Turns a share link / magnet / kouling into download requests.", "zh": "把分享链接 / 磁力 / 口令变成下载请求。"}),
    ("turbodl-hook", {"en": "Observes or rewrites requests and results.", "zh": "观察或改写请求与结果。"}),
    ("turbodl-loader", {"en": "Loads plugins written in another language — this is what the JS loader is.", "zh": "加载用别的语言写的插件 —— JS 加载器就属于这一类。"}),
]

#: 官方插件。字段来自各自模块的 `turbodl-plugin.json`（不手写、不改写语义）。
OFFICIAL_PLUGINS = [
    {
        "key": "plugin-hls",
        "id": "backend.hls",
        "name": "HLS VOD Backend",
        "category": "turbodl-protocol",
        "capabilities": ["dev.turbodl.cap.hls", "dev.turbodl.cap.m3u8"],
        "protocols": ["hls"],
        "artifact": "turbo-plugin-hls",
        "manifest": f"{BLOB}/turbo-plugin-hls/turbodl-plugin.json",
        "source": f"{REPO}/tree/main/turbo-plugin-hls",
        "summary": {
            "en": "Treats an `.m3u8` URL as a stream to assemble rather than a text file to save: playlist resolution, variant selection, concurrent segments with per-segment retry, AES-128 decryption, byte ranges.",
            "zh": "把 `.m3u8` 当作**要拼装的流**而不是要保存的文本文件：解析清单、选码率、分片并发（分片级重试）、AES-128 解密、字节范围。",
        },
        "refuses": {
            "en": ["Live playlists (no `EXT-X-ENDLIST`)", "DRM / `SAMPLE-AES`", "fMP4 `EXT-X-MAP`", "Discontinuities"],
            "zh": ["直播清单（没有 `EXT-X-ENDLIST`）", "DRM / `SAMPLE-AES`", "fMP4 `EXT-X-MAP`", "不连续标记"],
        },
        "refuses_note": {
            "en": "Refused explicitly: an unsupported stream fails loudly instead of producing a file that looks fine and is not.",
            "zh": "**明确拒绝**：不支持的流直接失败，而不是产出一个看起来正常、其实不对的文件。",
        },
    },
    {
        "key": "plugin-js",
        "id": "loader.js",
        "name": "JavaScript Plugin Loader",
        "category": "turbodl-loader",
        "capabilities": ["dev.turbodl.cap.js", "dev.turbodl.cap.quickjs"],
        "protocols": [],
        "artifact": "turbo-plugin-js",
        "manifest": f"{BLOB}/turbo-plugin-js/turbodl-plugin.json",
        "source": f"{REPO}/tree/main/turbo-plugin-js",
        "summary": {
            "en": "Loads TurboDL plugins written in JavaScript. One QuickJS runtime and context per script, a stable `host`/`plugin` ABI, and a load/unload sequence that never closes a runtime while its script is still executing.",
            "zh": "加载用 JavaScript 写的 TurboDL 插件。每个脚本一个 QuickJS runtime 与 context、一套稳定的 `host`/`plugin` ABI，以及一套「绝不在脚本还在执行时关掉 runtime」的加载/卸载流程。",
        },
        "refuses": {
            "en": [
                "Registering a `DownloadBackend` (the byte plane stays in Kotlin)",
                "Publishing a service (a service is a Kotlin object graph)",
                "`require` / npm / `fetch` — the host ABI is the entire surface",
                "`jar:` or remote script sources (local file / `inline:` / `data:` only, for now)",
            ],
            "zh": [
                "注册 `DownloadBackend`（字节面留在 Kotlin 一侧）",
                "发布服务（服务是 Kotlin 对象图）",
                "`require` / npm / `fetch` —— 宿主 ABI 就是全部可用面",
                "`jar:` 与远程脚本源（目前只支持本地文件 / `inline:` / `data:`）",
            ],
        },
        "refuses_note": {
            "en": "A script computes URLs, headers and signatures; it never streams download data. That boundary is what keeps a script from becoming a bandwidth bottleneck.",
            "zh": "脚本负责算 URL、请求头和签名，**从不**经手下载数据流。这条边界正是它不会变成带宽瓶颈的原因。",
        },
    },
]


# --------------------------------------------------------------------------- #
# 文档页：官网上的每一篇文档，源头都是仓库里的那个文件
# --------------------------------------------------------------------------- #

#: 官网文档页 = 仓库里的 Markdown 文件本身。
#: 「官网一份、GitHub 一份」如果各写一遍，迟早会各说各话；这里让两者同源 ——
#: 改仓库里的文件、重建站点，官网就跟着变。
#:
#: 字段含义：`src_en` / `src_zh` 是仓库内的相对路径（`None` 表示这一篇没有对应语言的版本，
#: 此时照原样显示另一种语言，并在页面上说明），`raw` 为真时按纯文本原样排版（LICENSE 这种
#: 不该被 Markdown 规则重排的文件）。
DOC_PAGES = [
    {
        "key": "doc-readme",
        "slug": "readme",
        "group": "docs",
        "title": {"en": "Project README", "zh": "项目说明"},
        "blurb": {
            "en": "What TurboDL is, what it does, how to add it to a project, and the API surface — the repository's own README.",
            "zh": "TurboDL 是什么、能做什么、怎么加进项目、API 有哪些 —— 就是仓库里的那篇 README。",
        },
        "src_en": "README.md",
        "src_zh": "docs/i18n/README_zh-CN.md",
    },
    {
        "key": "doc-plugins",
        "slug": "plugins",
        "group": "docs",
        "title": {"en": "Plugin authoring guide", "zh": "插件开发指南"},
        "blurb": {
            "en": "How to write a plugin and get it loaded: the interfaces, the categories, and the lifecycle you must respect.",
            "zh": "怎么写出一个插件、怎么被加载进来：接口、分类，以及你必须遵守的生命周期。",
        },
        "src_en": "docs/plugins/README.md",
        "src_zh": "docs/i18n/plugins/README_zh-CN.md",
    },
    {
        "key": "doc-convention",
        "slug": "convention",
        "group": "docs",
        "title": {"en": "Development convention", "zh": "开发规范"},
        "blurb": {
            "en": "The compatibility rulebook: what counts as a stable API, how versions are negotiated, naming, and the safety rules a plugin may not bend.",
            "zh": "兼容性规则：什么算稳定 API、版本怎么协商、命名怎么定，以及插件不能弯的安全底线。",
        },
        "src_en": "docs/plugins/CONVENTION.md",
        "src_zh": "docs/i18n/plugins/CONVENTION_zh-CN.md",
    },
    {
        "key": "doc-market",
        "slug": "publishing",
        "group": "docs",
        "title": {"en": "Publishing & discovery", "zh": "发布与发现"},
        "blurb": {
            "en": "There is no registry to sign up to. Plugins are discovered through a GitHub topic plus a manifest — this is the whole convention.",
            "zh": "没有需要注册的中心仓库。插件靠 GitHub topic 加一份清单被发现 —— 这套约定就是全部。",
        },
        "src_en": "docs/plugins/MARKET.md",
        "src_zh": "docs/i18n/plugins/MARKET_zh-CN.md",
    },
    {
        "key": "doc-js",
        "slug": "js-plugins",
        "group": "docs",
        "title": {"en": "Writing a JavaScript plugin", "zh": "用 JavaScript 写插件"},
        "blurb": {
            "en": "The `host`/`plugin` ABI, the three primitives everything is built on, and what a script is deliberately not allowed to do.",
            "zh": "`host`/`plugin` ABI、构成一切的那三个原语，以及脚本被有意禁止做的事情。",
        },
        "src_en": "turbo-plugin-js/README.md",
        "src_zh": None,
    },
    {
        "key": "doc-arch",
        "slug": "js-loader",
        "group": "docs",
        "title": {"en": "JS loader architecture", "zh": "JS 加载器架构"},
        "blurb": {
            "en": "Why the loader is shaped the way it is: runtime/context ownership, the unload sequence, and the host-side boundaries.",
            "zh": "加载器为什么长这样：runtime 与 context 的归属、卸载顺序，以及宿主一侧的边界。",
        },
        "src_en": "turbo-plugin-js/ARCHITECTURE.md",
        "src_zh": None,
    },
    {
        "key": "doc-manifest",
        "slug": "manifest",
        "group": "docs",
        "title": {"en": "Manifest reference", "zh": "清单字段参考"},
        "blurb": {
            "en": "Every field of turbodl-plugin.json, generated from the JSON Schema that validates it.",
            "zh": "turbodl-plugin.json 的每一个字段 —— 由校验它的那份 JSON Schema 生成。",
        },
        "src_en": None,
        "src_zh": None,
    },
    {
        "key": "legal-license",
        "slug": "license",
        "group": "legal",
        "title": {"en": "License (MIT)", "zh": "开源协议（MIT）"},
        "blurb": {
            "en": "MIT, plus four supplemental clauses that keep plugins independent works. Verbatim, not summarised.",
            "zh": "MIT 协议，附四条澄清插件是独立作品的追加条款。原文照录，不做概括。",
        },
        "src_en": "LICENSE",
        "src_zh": "LICENSE",
        "raw": True,
    },
    {
        "key": "legal-third-party",
        "slug": "third-party",
        "group": "legal",
        "title": {"en": "Third-party components", "zh": "第三方组件与致谢"},
        "blurb": {
            "en": "Which libraries ship with TurboDL, under what license, and which projects supplied ideas without supplying code.",
            "zh": "TurboDL 会连带引入哪些库、各自什么协议，以及哪些项目提供了思路（但没有提供代码）。",
        },
        "src_en": "docs/legal/THIRD-PARTY.md",
        "src_zh": "docs/i18n/legal/THIRD-PARTY_zh-CN.md",
    },
    {
        "key": "legal-privacy",
        "slug": "privacy",
        "group": "legal",
        "title": {"en": "Privacy", "zh": "隐私说明"},
        "blurb": {
            "en": "What leaves your machine when you use TurboDL — and the list of things that never do.",
            "zh": "用 TurboDL 的时候，哪些数据会离开你的机器；以及那些永远不会离开的东西。",
        },
        "src_en": "docs/legal/PRIVACY.md",
        "src_zh": "docs/i18n/legal/PRIVACY_zh-CN.md",
    },
    {
        "key": "legal-terms",
        "slug": "terms",
        "group": "legal",
        "title": {"en": "Terms of use", "zh": "使用条款"},
        "blurb": {
            "en": "Responsibility, warranty, third-party plugins, and the name — the parts a license does not spell out.",
            "zh": "责任、担保、第三方插件与名称使用 —— 协议本身不会展开讲的那部分。",
        },
        "src_en": "docs/legal/TERMS.md",
        "src_zh": "docs/i18n/legal/TERMS_zh-CN.md",
    },
]

#: 仓库路径 → 站点页面 key。文档正文里的相对链接靠它落到站内；
#: 落到站外的（例如各语言的 i18n 文件）退回 GitHub 的 blob 地址，绝不产生死链。
DOC_BY_SRC: dict[str, dict] = {}
for _d in DOC_PAGES:
    for _src in (_d["src_en"], _d["src_zh"]):
        if _src:
            DOC_BY_SRC.setdefault(_src, _d)

#: 本页显示的文档属于哪一组；用于文档中心的分类。
DOC_GROUPS = {
    "docs": {"en": "Documentation", "zh": "文档"},
    "legal": {"en": "Terms & policies", "zh": "协议与说明"},
}


def _doc_src(doc: dict, lang: str) -> tuple[str, bool]:
    """挑出该语言要渲染的仓库文件。返回 `(路径, 是否为另一种语言)`. """
    primary = doc.get("src_zh" if lang == "zh" else "src_en")
    if primary:
        return primary, False
    fallback = doc.get("src_en") or doc.get("src_zh")
    return fallback, True


def _resolver(src_path: str, page_href):
    """造一个把文档里的链接改写到站内（或 GitHub）的回调。

    文档里写的是 `CONVENTION.md`、`../README.md` 这类仓库内相对路径 —— 直接搬到官网上
    就是死链。这里按「相对当前文件解析 → 查站点页面表 → 查不到就指到 GitHub」处理，
    所以文档作者不需要为了网站改写法。
    """
    base_dir = str(Path(src_path).parent).replace("\\", "/")
    if base_dir == ".":
        base_dir = ""

    def link(url: str, kind: str = "link") -> str:
        if url.startswith(("http://", "https://", "mailto:", "#", "data:")):
            return url
        head, _, tail = url.partition("#")
        # 归一化 `a/../b` 形式的相对路径
        parts: list[str] = []
        for seg in (base_dir + "/" + head).split("/"):
            if seg in ("", "."):
                continue
            if seg == "..":
                if parts:
                    parts.pop()
                continue
            parts.append(seg)
        target = "/".join(parts)
        doc = DOC_BY_SRC.get(target)
        if doc:
            return page_href(doc["key"]) + (f"#{tail}" if tail else "")
        suffix = f"#{tail}" if tail else ""
        return f"{BLOB}/{target}{suffix}"

    return link


def _manifest_reference(lang: str) -> str:
    """由 JSON Schema 生成清单字段参考 —— 字段名、类型、是否必填、说明、枚举值。"""
    schema = json.loads((ROOT / "docs/plugins/turbodl-plugin.schema.json").read_text(encoding="utf-8"))
    required = set(schema.get("required", []))
    props = schema.get("properties", {})
    en = lang == "en"
    rows = []
    for name, spec in props.items():
        types = spec.get("type") or ("object" if "properties" in spec else "—")
        if isinstance(types, list):
            types = " / ".join(types)
        detail = esc(spec.get("description", "") or "")
        if "enum" in spec:
            detail += "<br>" + "".join(f'<span class="chip">{esc(v)}</span>' for v in spec["enum"])
        if spec.get("pattern"):
            detail += f'<br><span class="mono small">pattern: {esc(spec["pattern"])}</span>'
        rows.append(
            [
                f'<span class="mono">{esc(name)}</span>',
                f'<span class="mono small">{esc(types)}</span>',
                ('<span class="req">' + ("required" if en else "必填") + "</span>") if name in required else "",
                detail,
            ]
        )
    heads = ["Field", "Type", " ", "Notes"] if en else ["字段", "类型", " ", "说明"]
    groups = schema.get("$comment") or ""
    example = {
        "manifestVersion": "1.0",
        "id": "parser.example",
        "name": "Example parser",
        "description": "Turns example:// links into download requests.",
        "version": "0.1.0",
        "author": "you",
        "homepage": "https://github.com/you/turbodl-plugin-example",
        "license": "MIT",
        "category": "turbodl-parser",
        "capabilities": ["dev.turbodl.cap.parser"],
        "protocols": ["example"],
        "turbodl": {"apiMajor": 1, "requiredApiVersion": "1.0.0"},
        "entry": {"language": "kotlin", "pluginClass": "com.example.ExamplePlugin"},
        "extensionPoints": ["turbo.linkParser"],
    }
    return (
        (f'<p>{esc(schema.get("description", ""))}</p>' if schema.get("description") else "")
        + table(heads, rows)
        + (f'<p class="small">{esc(groups)}</p>' if groups else "")
        + f'<h2>{"Minimal manifest" if en else "一份最小清单"}</h2>'
        + code_block(json.dumps(example, indent=2, ensure_ascii=False), "turbodl-plugin.json")
        + f'<p class="small">{"Download the schema itself: " if en else "Schema 原文下载："}'
        + f'<a href="{esc(schema.get("$id", ""))}" class="mono">{esc(schema.get("$id", ""))}</a></p>'
    )


def _page_doc(doc: dict, lang: str, href) -> str:
    """渲染一篇镜像文档：标题区 + 目录侧栏 + 正文。"""
    en = lang == "en"
    src, fallback = _doc_src(doc, lang)
    if doc["key"] == "doc-manifest":
        body, toc = _manifest_reference(lang), ""
        src_label = "docs/plugins/turbodl-plugin.schema.json"
    else:
        text = (ROOT / src).read_text(encoding="utf-8")
        if doc.get("raw"):
            body = f'<pre class="doc-raw">{esc(text.strip())}</pre>'
            toc = ""
        else:
            # 文档的首行标题就是页面顶部的 <h1>，这里去掉，免得同一句话出现两次。
            # 去掉之后文档里的 `##` 就落到页面的 <h2>，标题层级不断档（所以 offset=0）；
            # 万一某篇文档没有首行标题，就退回 offset=1，保证页面里仍然只有一个 <h1>。
            text, dropped = re.subn(r"\A\s*#[^\n]*\n", "", text, count=1)
            body, toc = mdlite.render(
                text, _resolver(src, href), h_offset=0 if dropped else 1, toc_max=3
            )
        src_label = src

    group = DOC_GROUPS[doc["group"]][lang]
    src_url = f"{BLOB}/{src}"
    head = (
        f'<section class="doc-head"><h1>{esc(doc["title"][lang])}</h1>'
        f'<p class="lead">{esc(doc["blurb"][lang])}</p></section>'
    )
    if fallback and not doc.get("raw"):
        head += note(
            (
                f'This page has no {lang} version yet — the original text below is shown as it is in the '
                f'repository. <a href="{REPO}">Contributions</a> are welcome.'
                if en
                else "这一篇还没有中文版，下面是仓库里的原文，照原样显示。欢迎帮忙翻译。"
            ),
            "info",
        )
    meta = (
        '<div class="doc-meta">'
        f'<span class="mono small">{esc(src_label)}</span>'
        f'<a class="mono small" href="{esc(src_url)}">'
        + ("view on GitHub" if en else "在 GitHub 上查看")
        + "</a>"
        f'<a class="mono small" href="{esc(src_url.replace("/blob/", "/raw/"))}">'
        + ("raw file" if en else "原文下载")
        + "</a></div>"
    )
    aside = f'<aside class="doc-aside">{toc}' + _doc_nav(doc, lang, href) + "</aside>"
    return (
        head
        + meta
        + f'<div class="doc-grid">{aside}<article class="doc-body">{body}</article></div>'
        + f'<p class="small"><a href="{href("docs")}">'
        + ("← All documentation" if en else "← 全部文档")
        + "</a></p>"
    )


def _doc_nav(current: dict, lang: str, href) -> str:
    """侧栏里的其它文档。"""
    items = []
    for d in DOC_PAGES:
        if d["key"] == current["key"]:
            items.append(f'<li class="cur"><span>{esc(d["title"][lang])}</span></li>')
        else:
            items.append(f'<li><a href="{href(d["key"])}">{esc(d["title"][lang])}</a></li>')
    return (
        f'<nav class="toc doc-nav"><div class="toc-title">'
        + ("All pages" if lang == "en" else "全部页面")
        + "</div><ul>" + "".join(items) + "</ul></nav>"
    )


# --------------------------------------------------------------------------- #
# 页面正文
# --------------------------------------------------------------------------- #

def asset_rows(release: dict) -> list[list[str]]:
    """把 Release 的附件渲染成表格行。

    **直链来自 GitHub API**（`refresh_releases.py` 抓的），不手写 —— 手写的链接迟早会指向
    一个不存在的文件，而"下载按钮点了没反应"正是这个站点最不该有的东西。
    """
    from sitekit import fmt_bytes

    return [
        [
            f'<a href="{esc(a["url"])}" class="mono">{esc(a["name"])}</a>',
            f'<span class="num">{esc(fmt_bytes(a["size"]))}</span>',
        ]
        for a in release["assets"]
    ]


def _hero(lang: str, href) -> str:
    if lang == "en":
        h1 = "A multi-threaded download engine for the JVM"
        lead = (
            "TurboDL is a download core written from scratch in pure Kotlin: HTTP Range segments in "
            "parallel, resumable, with a plugin framework for everything that is not HTTP. "
            "No Android dependency — it runs in any JVM application, servers included."
        )
        pill = f"{LATEST['tag']} · MIT · JVM 17+"
    else:
        h1 = "给 JVM 的多线程下载引擎"
        lead = (
            "TurboDL 是一个用纯 Kotlin 从零写的下载内核：按 HTTP Range 分片并发、可断点续传，"
            "并把「不是 HTTP 的东西」做成插件框架。不依赖 Android，任何 JVM 应用都能直接用。"
        )
        pill = f"{LATEST['tag']} · MIT · JVM 17+"
    dl = {"en": "Download", "zh": "下载"}[lang]
    ds = {"en": "Read the docs", "zh": "看文档"}[lang]
    return (
        '<section class="hero"><div class="hero-grid"><div>'
        f"<h1>{h1}</h1><p class=\"lead\">{lead}</p>"
        + buttons([(f"{dl} {LATEST['tag']}", href("download")), (ds, href("docs"))])
        + '</div><div class="hero-side">'
        f'<div class="version-bar"><span class="tag">{pill}</span>'
        f'<span>{TESTED[lang]}</span></div>'
        "</div></div></section>"
    )


def _capabilities(lang: str) -> str:
    heads = ["Capability", "Config field", "Notes"] if lang == "en" else ["能力", "配置项", "说明"]
    rows = []
    for en, zh, field, note_en, note_zh in CAPABILITIES:
        rows.append([en if lang == "en" else zh, f'<span class="mono">{field}</span>', note_en if lang == "en" else note_zh])
    return table(heads, rows)


def _modules(lang: str) -> str:
    heads = ["Artifact", "Role", "Required?"] if lang == "en" else ["构件", "作用", "必需？"]
    yes, no = ("required", "optional") if lang == "en" else ("必需", "可选")
    rows = []
    for artifact, role, desc, required in MODULES:
        rows.append(
            [
                f'<span class="mono">{artifact}</span>',
                f"<strong>{role[lang]}</strong><br><span class=\"muted\">{desc[lang]}</span>",
                yes if required else no,
            ]
        )
    return table(heads, rows)


def _page_home(lang: str, href) -> str:
    en = lang == "en"
    feats = cards([(t[lang], f"<p>{b[lang]}</p>") for t, b in FEATURES], columns=3)
    parts = [
        _hero(lang, href),
        section(
            "What it does" if en else "它能做什么",
            f"<p class=\"lead\">{'Six things that matter for a download engine — the rest is in the README.' if en else '下载引擎真正要紧的六件事 —— 其余在 README 里。'}</p>" + feats,
        ),
        section(
            "Nine engine capabilities" if en else "九项引擎能力",
            f"<p class=\"lead\">{'Every knob is a public config field — no hidden magic.' if en else '每一项都是公开的配置字段 —— 没有藏着的手法。'}</p>" + _capabilities(lang),
        ),
        section(
            "Modules" if en else "模块构成",
            (
                "<p class=\"lead\">Only <span class=\"mono\">turbodl-core</span> is required. "
                "Everything else is a plugin you add on purpose.</p>"
                if en
                else "<p class=\"lead\">只有 <span class=\"mono\">turbodl-core</span> 是必需的。"
                "其余都是你主动加进来的插件。</p>"
            )
            + _modules(lang),
        ),
        section(
            "Quick start" if en else "快速开始",
            (
                "<p>Add one dependency, submit a request, collect events. "
                "The full API — pause / resume / cancel / batch / diagnostics — is in the docs.</p>"
                if en
                else "<p>加一个依赖、提交一个请求、订阅事件。完整的 API（暂停 / 继续 / 取消 / 批量 / 诊断）见文档。</p>"
            )
            + code_block(
                """val client = TurboClient(
    TurboConfig(
        maxConnectionsPerTask = 16,      // up to 256 / 最高 256
        globalSpeedLimitBytesPerSec = 0, // 0 = unlimited / 不限速
        dynamicSegmentation = true,
    )
)

val id = client.submit(
    DownloadRequest(url = "https://example.com/big.zip", destination = File("big.zip"))
)

client.events.collect { event ->
    when (event) {
        is TurboEvent.Progress  -> println("${event.progress.percent}%")
        is TurboEvent.Completed -> println("done: ${event.file}")
        is TurboEvent.Failed    -> println("failed: ${event.reason}")
        else -> {}
    }
}

val result = client.await(id)   // Result<File>
client.shutdown()""",
                "Kotlin",
            )
            + buttons(
                [
                    ("Download " + LATEST["tag"], href("download")),
                    ("Plugin guide" if en else "插件开发", href("plugins")),
                    ("GitHub", f"{REPO}"),
                ]
            ),
        ),
        section(
            "Plugin framework" if en else "插件框架",
            (
                "<p class=\"lead\">TurboDL is a standalone engine <em>and</em> an optional plugin platform. "
                "The kernel knows no protocols: lifecycle, cleanup, event bus, service registry, "
                "extension points, a version handshake and diagnostics — that is the whole of it.</p>"
                if en
                else "<p class=\"lead\">TurboDL 既是独立的引擎，<em>也是</em>可选的插件平台。"
                "内核不认识任何协议：它只提供生命周期、清理、事件总线、服务注册表、扩展点、"
                "版本握手与诊断 —— 全部就这些。</p>"
            )
            + code_block(
                f"""val boot = TurboBootstrap.create(
    config = TurboConfig(maxConnectionsPerTask = 16),
    extraPlugins = listOf(HlsPlugin()),
)

boot.host.loadSource(
    PluginSource(kind = "js", uri = "/opt/turbodl-plugins/acme-parser.js")
)""",
                "Kotlin · bootstrap",
            )
            + f'<div class="btn-row"><a class="btn" href="{href("plugins")}">'
            + ("See the official plugins" if en else "看官方插件")
            + "</a></div>",
        ),
        section(
            "Written down, in one place" if en else "写在文档里的东西",
            (
                "<p class=\"lead\">Everything a reader might want — how to write a plugin, what the "
                "manifest requires, which libraries ship along, what leaves your machine, and what the "
                "license actually says — is a page here <em>and</em> a file in the repository. "
                "Both are rendered from the same source, so they cannot drift apart.</p>"
                if en
                else "<p class=\"lead\">读者会想知道的每一件事 —— 插件怎么写、清单有哪些必填项、"
                "会连带引入哪些库、什么数据会离开你的机器、协议究竟写了什么 —— "
                "在这里是一页，在仓库里是一个文件。两处由同一份源渲染，不会各说各话。</p>"
            )
            + buttons(
                [
                    ("Plugin guide" if en else "插件开发指南", href("doc-plugins")),
                    ("Convention" if en else "开发规范", href("doc-convention")),
                    ("JS plugins" if en else "JS 插件", href("doc-js")),
                    ("Manifest" if en else "清单字段", href("doc-manifest")),
                    ("Privacy" if en else "隐私说明", href("legal-privacy")),
                    ("License" if en else "开源协议", href("legal-license")),
                ]
            ),
        ),
    ]
    return "".join(parts)


def _page_download(lang: str, href) -> str:
    en = lang == "en"
    ver = LATEST_VERSION
    tag = LATEST["tag"]
    rel_url = LATEST["url"]
    n_assets = len(LATEST["assets"])
    published = (LATEST["published"] or "")[:10]

    lead_en = (
        f'Every file below is an attachment of the <a href="{rel_url}">{tag}</a> release — '
        f"the links are read from the GitHub API, not typed by hand."
    )
    lead_zh = (
        f'下面每个文件都是 <a href="{rel_url}">{tag}</a> 这个 Release 的附件 —— '
        f"链接由 GitHub API 读取，不是手写的。"
    )
    bar = (
        f'<div class="version-bar"><span class="tag">{tag}</span>'
        f"<span>{published}</span>"
        f'<span>{n_assets} {"files" if en else "个文件"}</span></div>'
    )
    parts = [
        f'<section><h1>{"Download" if en else "下载"}</h1>'
        f'<p class="lead">{lead_en if en else lead_zh}</p>{bar}</section>',
        section(
            f"{tag} — all files" if en else f"{tag} —— 全部文件",
            table(["File", "Size"] if en else ["文件", "大小"], asset_rows(LATEST)),
        ),
        section(
            "Gradle" if en else "Gradle 依赖",
            (
                "<p>Publish the release's own POMs into your local Maven once, then depend on them by "
                "coordinate — the offline bundle below contains exactly those files:</p>"
                if en
                else "<p>把该 Release 自带的 POM 装进本地 Maven 一次，之后按坐标依赖即可 —— "
                "下面的离线包就是这些文件：</p>"
            )
            + code_block(
                f"""# 1) unpack the offline bundle into ~/.m2/repository
#    (or: unzip TurboDL-{tag}-mavenLocal.zip -d "$HOME/.m2/repository")
#
# 2) depend on it by coordinate / 按坐标依赖:
#    implementation("dev.turbodl:turbodl-core:{ver}")""",
                "shell",
            )
            + code_block(
                f"""dependencies {{
    // required / 必需
    implementation("dev.turbodl:turbodl-core:{ver}")

    // optional plugins / 可选插件
    implementation("dev.turbodl:turbo-plugin-runtime:{ver}")
    implementation("dev.turbodl:turbo-plugin-bootstrap:{ver}")
    implementation("dev.turbodl:turbo-plugin-hls:{ver}")
    implementation("dev.turbodl:turbo-plugin-js:{ver}")
}}""",
                "build.gradle.kts",
            )
            + note(
                (
                    "These coordinates are <strong>not</strong> on Maven Central yet. "
                    "The offline bundle on the release page is the supported way to consume them — "
                    "it is exactly the set of files Gradle publishes into a local repository."
                    if en
                    else "这些坐标<strong>还没有</strong>发布到 Maven Central。"
                    "官方支持的用法是 Release 页上的离线包 —— 它就是 Gradle 发布到本地仓库的那一组文件。"
                )
            ),
        ),
        section(
            "Command line" if en else "命令行",
            (
                "<p>The release ships a self-contained CLI JAR. It needs only Java 17+ — "
                "no Gradle, no sources:</p>"
                if en
                else "<p>Release 里附带一个自包含的 CLI JAR，只要有 Java 17+ 就能跑 —— "
                "不需要 Gradle，也不需要源码：</p>"
            )
            + code_block(
                f"""java -jar turbodl-cli-{ver}-all.jar <url> -o out.bin -c 64
java -jar turbodl-cli-{ver}-all.jar <url> --json        # NDJSON, script/agent friendly
java -jar turbodl-cli-{ver}-all.jar --batch tasks.txt   # one URL per line / 每行一个 URL""",
                "shell",
            )
            + '<p class="small">'
            + ("Exit codes: 0 success · 1 download failed · 2 usage error." if en else "退出码：0 成功 · 1 下载失败 · 2 用法错误。")
            + "</p>",
        ),
        section(
            "Previous versions" if en else "历史版本",
            table(
                ["Version", "Published", "Assets", "Notes"] if en else ["版本", "发布时间", "附件", "说明"],
                [
                    [
                        f'<span class="mono">{r["tag"]}</span>'
                        + (' <span class="chip">pre</span>' if r["prerelease"] else ""),
                        (r["published"] or "")[:10],
                        f'<span class="num">{len(r["assets"])}</span>',
                        f'<a href="{r["url"]}">{"Release" if en else "发布页"}</a>',
                    ]
                    for r in RELEASES[:12]
                ],
            ),
        ),
    ]
    return "".join(parts)


def _plugin_detail(p: dict, lang: str, href) -> str:
    en = lang == "en"
    chips = "".join(f'<span class="chip">{esc(c)}</span>' for c in [p["id"], p["category"]] + p["capabilities"])
    protocols = p.get("protocols", [])
    meta_rows = [
        ["Id", f'<span class="mono">{esc(p["id"])}</span>'],
        ["Category", f'<span class="mono">{esc(p["category"])}</span>'],
        ["Protocols", "".join(f'<span class="chip">{esc(x)}</span> ' for x in protocols) or "—"],
        ["Capabilities", "".join(f'<span class="chip">{esc(c)}</span> ' for c in p["capabilities"])],
        ["Artifact", f'<span class="mono">dev.turbodl:{esc(p["artifact"])}:{LATEST_VERSION}</span>'],
        ["API version", f'<span class="mono">{API_VERSION}</span>'],
        ["Source", f'<a href="{esc(p["source"])}">{esc(p["source"].split("/main/")[-1])}</a>'],
        ["Manifest", f'<a href="{esc(p["manifest"])}">turbodl-plugin.json</a>'],
    ]
    if en:
        heads = ["Field", "Value"]
    else:
        heads = ["字段", "值"]
    return (
        f'<section><h1>{esc(p["name"])}</h1>'
        f'<p class="lead">{p["summary"][lang]}</p>'
        f'<div class="card-meta">{chips}</div></section>'
        + section("Manifest" if en else "清单", table(heads, meta_rows))
        + section(
            "Explicitly refused" if en else "明确拒绝的情况",
            "<ul>" + "".join(f"<li>{x}</li>" for x in p["refuses"][lang]) + "</ul>"
            + note(p["refuses_note"][lang], "info"),
        )
        + section(
            "Source" if en else "源码",
            f'<p>{"Read it in the repository — the manifest above is generated from the file itself." if en else "在仓库里读源码 —— 上面的清单就是从那个文件里读出来的。"}</p>'
            + buttons([("Repository" if en else "仓库", p["source"]), ("All plugins" if en else "全部插件", href("plugins"))]),
        )
    )


def _page_plugins(lang: str, href) -> str:
    en = lang == "en"
    cat_rows = [[f'<span class="mono">{k}</span>', v[lang]] for k, v in PLUGIN_CATEGORIES]
    return (
        f'<section><h1>{"Plugins" if en else "插件"}</h1>'
        + f'<p class="lead">{"Two plugins ship with the project, and everything else is a plugin too — "
           "the Kotlin loader, the HTTP backend, the HLS adapter. The kernel itself knows no protocol."
           if en else "项目自带两个插件；事实上其它东西也都是插件 —— Kotlin 加载器、HTTP 后端、HLS 适配器。"
           "内核本身不认识任何协议。"}</p></section>'
        + section("Official plugins" if en else "官方插件", plugin_cards(lang))
        + section(
            "Plugin categories" if en else "插件分类",
            (
                "<p>A plugin declares one category in its manifest. The category says what it is allowed "
                "to plug into — nothing else.</p>"
                if en
                else "<p>插件在清单里声明一个分类。分类只说明它能接到哪里，不说明别的。</p>"
            )
            + table(["Category", "What it plugs in"] if en else ["分类", "接到哪里"], cat_rows),
        )
        + section(
            "Writing your own" if en else "写一个自己的插件",
            (
                "<p>A plugin is an ordinary class implementing one interface. Third-party plugins are "
                "discovered through a GitHub topic plus a manifest — there is no registry to sign up to.</p>"
                if en
                else "<p>插件就是一个实现了某个接口的普通类。第三方插件通过 GitHub topic + 清单被发现 —— 没有需要注册的中心仓库。</p>"
            )
            + code_block(
                f"""class MyPlugin : Plugin {{
    override val id = "parser.example"

    override fun onLoad(context: PluginContext) {{
        context.registerExtension(ExtensionPoints.LINK_PARSER, LinkParser {{ input ->
            input.takeIf {{ it.startsWith("example://") }}
                ?.let {{ listOf(DownloadRequest(url = it, destination = File("out.bin"))) }}
        }})
    }}
}}""",
                "Kotlin",
            )
            + buttons(
                [
                    ("Plugin authoring guide" if en else "插件开发指南", f"{BLOB}/docs/plugins/README.md"),
                    ("Development convention" if en else "开发规范", f"{BLOB}/docs/plugins/CONVENTION.md"),
                    ("Marketplace" if en else "插件市场", f"{BLOB}/docs/plugins/MARKET.md"),
                ]
            ),
        )
        + section(
            "Safety, stated plainly" if en else "关于安全，说清楚",
            note(
                (
                    "There is <strong>no signing or verification pipeline</strong> in this project, so the "
                    "site claims none. A plugin from this repository comes from this repository; a plugin "
                    "from anywhere else is someone else's code, and the category list above is the whole of "
                    "what the runtime enforces."
                    if en
                    else "本项目<strong>没有签名与验证流程</strong>，所以本站不声称有。"
                    "来自本仓库的插件就是来自本仓库；来自别处的代码就是别人的代码 —— "
                    "运行时能强制的东西，就是上面那张分类表。"
                ),
                "warn",
            ),
        )
    )


def _page_docs(lang: str, href) -> str:
    """文档中心：先给最短的上手路径，再把全部文档列出来。"""
    en = lang == "en"
    groups = []
    for group, label in DOC_GROUPS.items():
        items = []
        for d in DOC_PAGES:
            if d["group"] != group:
                continue
            items.append(
                (
                    d["title"][lang],
                    f'<p>{esc(d["blurb"][lang])}</p>'
                    f'<div class="btn-row tight"><a class="btn" href="{href(d["key"])}">'
                    + ("Read" if en else "阅读")
                    + "</a></div>",
                )
            )
        groups.append(section(label[lang], cards(items, columns=2)))

    quick = (
        "<p>The whole thing is one dependency, one config object, one submit call. "
        "Pause, resume, cancel, batch and diagnostics are on the same client.</p>"
        if en
        else "<p>全部通过三样东西完成：一个依赖、一个配置对象、一次提交。"
        "暂停、继续、取消、批量与诊断都在同一个 client 上。</p>"
    )
    cli = (
        "<p>The CLI is a plain JAR — Java 17+, nothing else. <span class=\"mono\">--json</span> "
        "prints NDJSON, which is what a script or an agent wants to read.</p>"
        if en
        else "<p>命令行就是一个普通 JAR —— 只要有 Java 17+。"
        "<span class=\"mono\">--json</span> 输出 NDJSON，脚本与 Agent 读这个最省事。</p>"
    )
    arch = (
        "<p>Three rules decide every design question in this project:</p>"
        "<ol>"
        "<li><strong>core works alone.</strong> <span class=\"mono\">turbodl-core</span> is a complete "
        "engine with zero plugin dependency; plugins are strictly additive.</li>"
        "<li><strong>the kernel is only mechanism.</strong> It knows no protocol — lifecycle, "
        "cleanup, event bus, service registry, extension points, version handshake, diagnostics.</li>"
        "<li><strong>dependency direction is strict.</strong> <span class=\"mono\">runtime → core</span>, "
        "never the reverse.</li>"
        "</ol>"
        if en
        else "<p>这个项目的每个设计问题都由三条规则决定：</p>"
        "<ol>"
        "<li><strong>core 能独立工作。</strong><span class=\"mono\">turbodl-core</span> 是完整引擎、"
        "零插件依赖；插件是纯粹的增量。</li>"
        "<li><strong>内核只是机制。</strong>它不认识任何协议 —— 只有生命周期、清理、事件总线、"
        "服务注册表、扩展点、版本握手、诊断。</li>"
        "<li><strong>依赖方向是硬的。</strong><span class=\"mono\">runtime → core</span>，绝不反向。</li>"
        "</ol>"
    )
    return (
        f'<section><h1>{"Documentation" if en else "文档"}</h1>'
        + (
            '<p class="lead">Every page below is a file in the repository, rendered here from the same '
            "source — so what you read here and what you find on GitHub cannot drift apart. "
            "Each page also links to its original, in case you want the raw text.</p>"
            if en
            else '<p class="lead">下面每一页都是仓库里的一个文件，官网与 GitHub 读的是同一份源 —— '
            "所以两边不会各说各话。每页都留了原文入口，想拿原始文件随时可以拿。</p>"
        )
        + "</section>"
        + section(
            "Start here" if en else "从这三页开始",
            quick
            + code_block(
                """// build.gradle.kts
dependencies {
    implementation("dev.turbodl:turbodl-core:VERSION")
}

// Kotlin
val client = TurboClient(TurboConfig(maxConnectionsPerTask = 16))
val id = client.submit(DownloadRequest(url, File("out.bin")))
val result = client.await(id)""".replace("VERSION", LATEST_VERSION),
                "Kotlin",
            )
            + code_block(
                """java -jar turbodl-cli-{v}-all.jar <url> -o out.bin -c 64
java -jar turbodl-cli-{v}-all.jar <url> --json       # NDJSON
java -jar turbodl-cli-{v}-all.jar --batch tasks.txt  # one URL per line""".replace("{v}", LATEST_VERSION),
                "shell",
            ),
        )
        + "".join(groups)
        + section("Architecture" if en else "架构", arch)
    )


def _page_changelog(lang: str, href) -> str:
    en = lang == "en"
    entries = []
    for r in RELEASES[:12]:
        badge = ' <span class="chip">prerelease</span>' if r["prerelease"] else ""
        assets = (
            f'<p class="small">{len(r["assets"])} {"files" if en else "个文件"} · '
            f'<a href="{esc(r["url"])}">{"release page" if en else "发布页"}</a></p>'
        )
        summary = r["summary"].strip()
        body = f'<p class="muted">{esc(summary)}</p>' if summary else ""
        entries.append(
            f'<div class="card"><h3><span class="mono">{esc(r["tag"])}</span>{badge}</h3>'
            f'<p class="small">{esc((r["published"] or "")[:10])}</p>{body}{assets}</div>'
        )
    return (
        f'<section><h1>{"Changelog" if en else "更新日志"}</h1>'
        + f'<p class="lead">{"Generated from the release notes themselves — each entry links to its own "
           "release page, where the full text and the files live." if en else "由发布说明本身生成 —— 每条都链到对应的发布页，完整正文与文件都在那里。"}</p></section>'
        + f'<section><div class="cards cols-2">{"".join(entries)}</div></section>'
        + section(
            "Full history" if en else "完整历史",
            buttons([("All releases" if en else "全部发布", f"{REPO}/releases"), ("Tags" if en else "标签", f"{REPO}/tags")]),
        )
    )


def _page_community(lang: str, href) -> str:
    en = lang == "en"
    if en:
        issue = (
            "<p>A bug report is worth ten opinions. Useful reports include: the TurboDL version "
            "(<span class=\"mono\">" + LATEST["tag"] + "</span>), the JDK, the target URL or a minimal "
            "reproduction, and what you expected instead.</p>"
        )
        disc = (
            "<p>GitHub Discussions is not enabled on this repository, so this site does not pretend "
            "otherwise — questions go to Issues too.</p>"
        )
        lic = (
            "<p>MIT License, with supplemental terms clarifying that plugins are independent works: "
            "they may carry any license, and interacting through the public API does not make them "
            "derivatives of TurboDL.</p>"
        )
    else:
        issue = (
            "<p>一份能复现的报告胜过十条意见。有用的报告会写清：TurboDL 版本"
            "（<span class=\"mono\">" + LATEST["tag"] + "</span>）、JDK 版本、目标链接或最小复现，"
            "以及你期望的结果。</p>"
        )
        disc = "<p>本仓库没有开启 GitHub Discussions，所以本站也不假装有 —— 提问同样走 Issues。</p>"
        lic = (
            "<p>MIT 协议，并附「插件生态」附加条款：插件是独立作品，可以自带任何协议；"
            "通过公开 API 交互不会让它变成 TurboDL 的衍生物。</p>"
        )
    return (
        f'<section><h1>{esc("Community" if en else "社区")}</h1>'
        + f'<p class="lead">{esc("There is no chat server and no QQ group. Everything happens in the "
           "repository, where it is searchable and permanent." if en else "没有聊天服务器，也没有 QQ 群。所有事情都在仓库里发生，可搜索、可追溯。")}</p></section>'
        + section("Issues" if en else "问题反馈", issue + buttons([("Open an issue" if en else "提交 issue", f"{REPO}/issues")]))
        + section("Discussions" if en else "讨论", disc)
        + section(
            "Contributing" if en else "参与贡献",
            (
                "<p>Bug fixes, new plugin backends, and documentation corrections are all welcome. "
                "Please keep the dependency direction (<span class=\"mono\">runtime → core</span>) and "
                "run <span class=\"mono\">./gradlew test</span> before opening a pull request — the suite "
                "covers byte-level correctness, resume, and the plugin lifecycle.</p>"
                if en
                else "<p>修 bug、写新的插件后端、纠正文档都欢迎。请守住依赖方向"
                "（<span class=\"mono\">runtime → core</span>），并在提 PR 前跑一遍 "
                "<span class=\"mono\">./gradlew test</span> —— 这套测试覆盖字节级正确性、续传与插件生命周期。</p>"
            ),
        )
        + section(
            "License" if en else "开源协议",
            lic
            + buttons(
                [
                    ("License (MIT)" if en else "开源协议（MIT）", href("legal-license")),
                    ("Third-party components" if en else "第三方组件", href("legal-third-party")),
                    ("Terms of use" if en else "使用条款", href("legal-terms")),
                ]
            ),
        )
        + section(
            "Acknowledgements" if en else "致谢",
            (
                "<p>TurboDL borrows architectural and algorithmic ideas — <strong>no source code</strong> — "
                "from these projects, with thanks: "
                + ", ".join(
                    f'<a href="{u}">{n}</a>'
                    for n, u in (
                        ("aria2", "https://github.com/aria2/aria2"),
                        ("Xtreme Download Manager", "https://github.com/subhra74/xdm"),
                        ("axel", "https://github.com/axel-download-accelerator/axel"),
                        ("Persepolis", "https://github.com/persepolisdm/persepolis"),
                        ("Motrix", "https://github.com/agalwood/Motrix"),
                        ("ab-download-manager", "https://github.com/amir1376/ab-download-manager"),
                    )
                )
                + ".</p>"
                if en
                else "<p>TurboDL 只借鉴了这些项目的架构与算法思路（<strong>没有复制任何源码</strong>），在此致谢："
                + "、".join(
                    f'<a href="{u}">{n}</a>'
                    for n, u in (
                        ("aria2", "https://github.com/aria2/aria2"),
                        ("Xtreme Download Manager", "https://github.com/subhra74/xdm"),
                        ("axel", "https://github.com/axel-download-accelerator/axel"),
                        ("Persepolis", "https://github.com/persepolisdm/persepolis"),
                        ("Motrix", "https://github.com/agalwood/Motrix"),
                        ("ab-download-manager", "https://github.com/amir1376/ab-download-manager"),
                    )
                )
                + "。</p>"
            ),
        )
    )


# --------------------------------------------------------------------------- #
# 站点定义
# --------------------------------------------------------------------------- #

def plugin_cards(lang: str) -> str:
    items = []
    for p in OFFICIAL_PLUGINS:
        meta = "".join(f'<span class="chip">{esc(c)}</span>' for c in [p["id"], p["category"]] + p["capabilities"])
        more = "Details" if lang == "en" else "查看详情"
        items.append(
            (
                p["name"],
                f"<p>{p['summary'][lang]}</p>"
                f'<p class="small mono">dev.turbodl:{esc(p["artifact"])}:{LATEST_VERSION}</p>'
                f'<div class="card-meta">{meta}</div>'
                f'<div class="btn-row tight"><a class="btn" href="{{LINK:{p["key"]}}}">{more}</a></div>',
            )
        )
    return cards(items, columns=2)


def _nav() -> list:
    return [
        ("home", {"en": "Overview", "zh": "概览"}),
        ("download", {"en": "Download", "zh": "下载"}),
        ("plugins", {"en": "Plugins", "zh": "插件"}),
        ("docs", {"en": "Docs", "zh": "文档"}),
        ("changelog", {"en": "Changelog", "zh": "更新日志"}),
        ("community", {"en": "Community", "zh": "社区"}),
        ("legal", {"en": "Terms", "zh": "协议"}),
    ]


def _footer(lang: str) -> dict:
    en = lang == "en"
    if en:
        return {
            "columns": [
                (
                    "Project",
                    [
                        ("GitHub", REPO),
                        ("Download", "@download"),
                        ("Plugins", "@plugins"),
                        ("Changelog", "@changelog"),
                        ("Issues", f"{REPO}/issues"),
                    ],
                ),
                (
                    "Documentation",
                    [
                        ("README", "@doc-readme"),
                        ("Plugin guide", "@doc-plugins"),
                        ("Convention", "@doc-convention"),
                        ("JavaScript plugins", "@doc-js"),
                        ("JS loader architecture", "@doc-arch"),
                        ("Manifest reference", "@doc-manifest"),
                    ],
                ),
                (
                    "Terms & policies",
                    [
                        ("License (MIT)", "@legal-license"),
                        ("Third-party components", "@legal-third-party"),
                        ("Privacy", "@legal-privacy"),
                        ("Terms of use", "@legal-terms"),
                        ("Community", "@community"),
                    ],
                ),
                (
                    "Docs in other languages",
                    [
                        ("简体中文", f"{BLOB}/docs/i18n/README_zh-CN.md"),
                        ("繁體中文", f"{BLOB}/docs/i18n/README_zh-TW.md"),
                        ("日本語", f"{BLOB}/docs/i18n/README_ja.md"),
                        ("한국어", f"{BLOB}/docs/i18n/README_ko.md"),
                        ("Deutsch", f"{BLOB}/docs/i18n/README_de.md"),
                    ],
                ),
            ],
            "paragraphs": [
                f"TurboDL {LATEST['tag']} · MIT License (with plugin-ecosystem supplemental terms) · requires JVM 17+",
                "A pure Kotlin/JVM download engine. Idea-level acknowledgements: aria2, XDM, axel, Persepolis, Motrix, ab-download-manager — no source code copied.",
            ],
        }
    return {
        "columns": [
            (
                "项目",
                [
                    ("GitHub", REPO),
                    ("下载", "@download"),
                    ("插件", "@plugins"),
                    ("更新日志", "@changelog"),
                    ("问题反馈", f"{REPO}/issues"),
                ],
            ),
            (
                "文档",
                [
                    ("项目说明", "@doc-readme"),
                    ("插件开发指南", "@doc-plugins"),
                    ("开发规范", "@doc-convention"),
                    ("用 JavaScript 写插件", "@doc-js"),
                    ("JS 加载器架构", "@doc-arch"),
                    ("清单字段参考", "@doc-manifest"),
                ],
            ),
            (
                "协议与说明",
                [
                    ("开源协议（MIT）", "@legal-license"),
                    ("第三方组件", "@legal-third-party"),
                    ("隐私说明", "@legal-privacy"),
                    ("使用条款", "@legal-terms"),
                    ("社区", "@community"),
                ],
            ),
            (
                "其它语言的文档",
                [
                    ("English", f"{BLOB}/README.md"),
                    ("繁體中文", f"{BLOB}/docs/i18n/README_zh-TW.md"),
                    ("日本語", f"{BLOB}/docs/i18n/README_ja.md"),
                    ("한국어", f"{BLOB}/docs/i18n/README_ko.md"),
                    ("Deutsch", f"{BLOB}/docs/i18n/README_de.md"),
                ],
            ),
        ],
        "paragraphs": [
            f"TurboDL {LATEST['tag']} · MIT 协议（附插件生态附加条款）· 需要 JVM 17+",
            "纯 Kotlin/JVM 下载引擎。思路层面的致谢：aria2、XDM、axel、Persepolis、Motrix、ab-download-manager —— 未复制任何源码。",
        ],
    }


def _page_legal(lang: str, href) -> str:
    """协议与说明的入口页。"""
    en = lang == "en"
    items = []
    for d in DOC_PAGES:
        if d["group"] != "legal":
            continue
        src = d["src_en"] or ""
        items.append(
            (
                d["title"][lang],
                f'<p>{esc(d["blurb"][lang])}</p>'
                f'<p class="mono small">{esc(src)}</p>'
                f'<div class="btn-row tight"><a class="btn" href="{href(d["key"])}">'
                + ("Read" if en else "阅读")
                + "</a></div>",
            )
        )
    return (
        f'<section><h1>{"Terms & policies" if en else "协议与说明"}</h1>'
        + (
            '<p class="lead">Four short documents that answer the questions a license does not: what '
            "leaves your machine, what ships alongside TurboDL, what you are responsible for, and what "
            "the MIT terms actually say. Each one is a file in the repository as well as a page here.</p>"
            if en
            else '<p class="lead">四份不长的文档，回答协议本身不会回答的问题：什么会离开你的机器、'
            "TurboDL 会连带引入什么、你要为什么负责、以及 MIT 条款到底写了什么。"
            "每一份既是仓库里的文件，也是这里的一页。</p>"
        )
        + "</section>"
        + section("The documents" if en else "这四份", cards(items, columns=2))
        + section(
            "Why these are written plainly" if en else "为什么写得这么直白",
            (
                "<p>Because the alternative is a document nobody reads. These pages say what the code "
                "does, including the parts that are inconvenient: no warranty, no signing pipeline, no "
                "isolation promises beyond what the category list enforces. If something here stops "
                "matching the code, that is a bug worth reporting — not a nuance to live with.</p>"
                if en
                else "<p>因为不这样写，就没有人会读。这几页说的都是代码真实在做的事，"
                "包括那些不方便承认的部分：没有担保、没有签名流程、沙箱隔离的承诺只到分类表为止。"
                "如果有哪一句和代码对不上了，那是值得报的 bug，不是可以凑合的细节。</p>"
            ),
        )
    )


def _pages() -> list:
    """页面清单。`lang` 决定输出目录：英文在根，中文在 `/zh/`。"""
    out = []
    en_pages = [
        ("home", "index.html", "TurboDL", "Multi-threaded download engine for the JVM — segmented parallel HTTP, resumable, plugin framework.", _page_home),
        ("download", "download/index.html", "Download", f"Download TurboDL {LATEST['tag']} — every file of the release, with Gradle and CLI instructions.", _page_download),
        ("plugins", "plugins/index.html", "Plugins", "Official TurboDL plugins (HLS backend, JavaScript loader) and how to write your own.", _page_plugins),
        ("docs", "docs/index.html", "Documentation", "Quick start, CLI, plugin authoring guide and the development convention.", _page_docs),
        ("legal", "legal/index.html", "Terms & policies", "License, third-party components, privacy and terms of use.", _page_legal),
        ("changelog", "changelog/index.html", "Changelog", "TurboDL release history, generated from the release notes.", _page_changelog),
        ("community", "community/index.html", "Community", "Issues, contributing, license and acknowledgements.", _page_community),
    ]
    zh_pages = [
        ("home", "zh/index.html", "TurboDL —— 给 JVM 的多线程下载引擎", "纯 Kotlin/JVM 下载引擎：HTTP Range 分片并发、断点续传、插件框架。", _page_home),
        ("download", "zh/download/index.html", "下载", f"下载 TurboDL {LATEST['tag']} —— 该版本的全部文件，含 Gradle 与命令行用法。", _page_download),
        ("plugins", "zh/plugins/index.html", "插件", "TurboDL 官方插件（HLS 后端、JavaScript 加载器），以及怎么写一个自己的插件。", _page_plugins),
        ("docs", "zh/docs/index.html", "文档", "快速开始、命令行、插件开发指南与开发规范。", _page_docs),
        ("legal", "zh/legal/index.html", "协议与说明", "开源协议、第三方组件、隐私说明与使用条款。", _page_legal),
        ("changelog", "zh/changelog/index.html", "更新日志", "TurboDL 版本历史，由发布说明生成。", _page_changelog),
        ("community", "zh/community/index.html", "社区", "问题反馈、参与贡献、开源协议与致谢。", _page_community),
    ]
    for lang, group in (("en", en_pages), ("zh", zh_pages)):
        for key, path, title, desc, builder in group:
            out.append(
                {
                    "key": key,
                    "lang": lang,
                    "path": path,
                    "title": title,
                    "desc": desc,
                    "body": builder,
                    # 首页标题里已经带了站名，模板不要再拼一次（否则是「TurboDL · TurboDL」）
                    "title_is_full": key == "home",
                    "priority": "1.0" if key == "home" else "0.7",
                }
            )
    # 插件详情页（两种语言各一份）
    for lang, prefix in (("en", "plugins"), ("zh", "zh/plugins")):
        for p in OFFICIAL_PLUGINS:
            out.append(
                {
                    "key": p["key"],
                    "lang": lang,
                    "path": f"{prefix}/{p['artifact']}/index.html",
                    "title": p["name"],
                    "desc": p["summary"][lang],
                    "body": (lambda pp: (lambda l, h: _plugin_detail(pp, l, h)))(p),
                    "breadcrumb": [
                        ({"en": "Plugins", "zh": "插件"}[lang], "plugins"),
                        (p["name"], None),
                    ],
                    "priority": "0.5",
                }
            )
    # 文档镜像页：每一篇都是仓库里的一个文件
    for lang, prefix in (("en", ""), ("zh", "zh/")):
        for d in DOC_PAGES:
            sub = "docs" if d["group"] == "docs" else "legal"
            out.append(
                {
                    "key": d["key"],
                    "lang": lang,
                    "path": f"{prefix}{sub}/{d['slug']}/index.html",
                    "title": d["title"][lang],
                    "desc": d["blurb"][lang],
                    "body": (lambda dd: (lambda l, h: _page_doc(dd, l, h)))(d),
                    "breadcrumb": [
                        (DOC_GROUPS[d["group"]][lang], d["group"]),
                        (d["title"][lang], None),
                    ],
                    "priority": "0.6",
                }
            )
    return out


SITE = {
    "name": "TurboDL",
    "tagline": "Multi-threaded download engine for the JVM",
    "base_url": "https://jiayuxuan123.github.io/TurboDL",
    "out_dir": "docs",
    "assets_dir": "website/assets",
    "default_lang": "en",
    "langs": [("en", {"en": "English", "zh": "English"}), ("zh", {"en": "中文", "zh": "中文"})],
    "body_class": "brand-turbodl",
    "mark_svg": (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="24" height="24" aria-hidden="true">'
        '<g fill="none" stroke="currentColor" stroke-width="2.3" stroke-linecap="round">'
        '<path d="M6 5v9.5M16 3.2v11.3M26 5v9.5"/></g>'
        '<path d="M16 15.6l6.2 6.2H9.8z" fill="currentColor"/>'
        '<path d="M16 21v7" stroke="currentColor" stroke-width="2.3" stroke-linecap="round"/></svg>'
    ),
    "nav": _nav(),
    "ui": {
        "en": {"skip": "Skip to content", "menu": "Menu", "notfound_title": "Page not found", "notfound_body": "That address does not exist on this site. Everything is one of these:"},
        "zh": {"skip": "跳到正文", "menu": "菜单", "notfound_title": "页面不存在", "notfound_body": "这个地址在本站不存在。全部内容就在下面这几处："},
    },
    "footer": {"en": _footer("en"), "zh": _footer("zh")},
    "pages": _pages(),
}
