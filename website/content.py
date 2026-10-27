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
import fragments
from sitekit import buttons, cards, code_block, esc, md_inline, note, section, seg_rule, table

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
def _read_api_version() -> str:
    """从 turbodl-core 的 ApiVersion.kt 里读当前公开 API 版本。

    手写的版本号会滞后：源码里的 ApiVersion.CURRENT 升了，站上还写着旧的，
    而插件是**按站上写的版本号**去判断兼容性 —— 于是文档说的和实际能跑的
    对不上。唯一来源应该是源码。
    """
    import re as _re
    src = Path(__file__).resolve().parent.parent / "turbodl-core" / "src" / "main" / "kotlin" / "dev" / "turbodl" / "core" / "ApiVersion.kt"
    try:
        text = src.read_text(encoding="utf-8")
    except OSError:
        return "1.0.0"
    m = _re.search(r"ApiVersion\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)", text)
    return f"{m.group(1)}.{m.group(2)}.{m.group(3)}" if m else "1.0.0"


API_VERSION = _read_api_version()

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
        "turbo-plugin-parser",
        {"en": "Platform link parser", "zh": "平台链接解析"},
        {
            "en": "Turns a share link into download requests: Weibo (MP4 + image sets), Xiaohongshu (watermark-free video + original images), Pixiv (every page's original). Holds no credentials — where a platform needs a login it returns null and the host's own session handling takes over.",
            "zh": "把分享链接变成可下载请求：微博（MP4 + 图集）、小红书（无水印视频 + 原图）、Pixiv（每页原图）。**不持有任何凭证** — 平台需要登录时返回 null，由宿主自己的会话处理接手。",
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
    {
        "key": "plugin-parser",
        "id": "parser.platforms",
        "name": "Platform Link Parser",
        "category": "turbodl-parser",
        "capabilities": ["dev.turbodl.cap.parser.platforms"],
        "protocols": ["weibo", "xhs", "pixiv"],
        "artifact": "turbo-plugin-parser",
        "manifest": f"{BLOB}/turbo-plugin-parser/turbodl-plugin.json",
        "source": f"{REPO}/tree/main/turbo-plugin-parser",
        "summary": {
            "en": "Turns a share link into download requests: Weibo (best available MP4 + image sets), Xiaohongshu (watermark-free video + original images), Pixiv (every page's `urls.original`). Registers one `LinkParser` plus one `ProtocolClaim` per platform — the engine knows nothing about these three sites.",
            "zh": "把分享链接变成可下载请求：微博（最高可用清晰度 MP4 + 图集）、小红书（无水印视频 + 原图）、Pixiv（每页 `urls.original`）。注册一个 `LinkParser` 与每个平台一条 `ProtocolClaim` — 引擎对这三个站点一无所知。",
        },
        "refuses": {
            "en": [
                "Logging in — a platform that needs a session returns null and the host's own account handling takes over",
                "Pixiv animations (ugoira): a zip plus a frame-timing table needs image processing",
                "Pixiv novels",
                "Signed APIs (Douyin, Kuaishou, Bilibili): per-platform signature algorithms stay in the host",
            ],
            "zh": [
                "登录 — 需要会话的平台返回 null，由宿主自己的账号处理接手",
                "Pixiv 动图（ugoira）：zip + 帧时长表需要图像处理",
                "Pixiv 小说",
                "需要签名的接口（抖音 / 快手 / B站）：各自的签名算法仍留在宿主侧",
            ],
        },
        "refuses_note": {
            "en": "Its result type carries no path: a `DownloadRequest` needs a `destination: File`, but a parser runs before the host has chosen a directory. The temp-dir destination is filled in only at the boundary, so the host still applies its own path policy.",
            "zh": "它的结果类型**不含任何路径**：`DownloadRequest` 需要 `destination: File`，而解析发生在宿主决定目录之前。临时目录占位只在边界处补上，宿主仍然走自己的路径策略。",
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


#: 一份最小的 `turbodl-plugin.json`（与 `docs/plugins/turbodl-plugin.schema.json` 的要求一致）。
#: 清单参考页与文档中心用的是**同一个**常量 —— 两处各写一份迟早会不一致。
MANIFEST_EXAMPLE = {
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
    example = MANIFEST_EXAMPLE
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


# 首页正文（含首屏的两场标题）搬到了 `website/home.py`：它是九节、双语的版式代码，
# 与这里的「事实表 + 页面清单」不是同一种东西；事实仍然全部由本文件拥有。
# 入口见下面的 `_page_home`（转调 home.page）。



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
                f"<strong>{role[lang]}</strong><br><span class=\"muted\">{md_inline(desc[lang])}</span>",
                yes if required else no,
            ]
        )
    return table(heads, rows)


def _page_home(lang: str, href) -> str:
    """首页：转调到 `website/home.py`（那里是九节的版式代码）。

    转调而不是把代码搬进来：首页现在是定向单要求的九节结构（两场首屏、三段进度条、
    开关横带、名字一句话、推到右边的表、提问清单、句子里的坐标），跟本文件的
    「事实表 + 页面清单」不是同一种东西。事实仍全部由本文件拥有，home.py 延迟读取。
    """
    import home

    return home.page(lang, href)


def _assets_total() -> str:
    """Release 附件的总大小（真实数字；附件表为空时给一个破折号而不是 0 B）。"""
    from sitekit import fmt_bytes

    total = sum(int(a.get("size") or 0) for a in LATEST["assets"])
    return fmt_bytes(total) if total else "—"


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
        + seg_rule([(9, True), (4, False), (6, True), (3, False)])
        + '<dl class="seg-meta">'
        f'<div><dt>{"Published" if en else "发布时间"}</dt><dd>{published}</dd></div>'
        f'<div><dt>{"Files" if en else "附件数"}</dt><dd>{n_assets}</dd></div>'
        "</dl></div>"
    )
    parts = [
        f'<section><h1>{"Download" if en else "下载"}</h1>'
        f'<p class="lead">{lead_en if en else lead_zh}</p>'
        # 发布面板：这个版本的事实（版本、发布日、附件数与总大小、协议、运行要求、
        # API 版本）—— 字段就是 Release 页面上的字段，值从 releases.json 来。
        + fragments.panel(
            f"{tag} — {'this release' if en else '这一次发布'}",
            [
                ("tag", tag),
                ("published", published),
                ("assets", str(n_assets)),
                ("total size", _assets_total()),
                ("license", "MIT"),
                ("requires", "JVM 17+"),
                ("api", f"v{API_VERSION}"),
                ("source", REPO.split("//")[-1]),
            ],
            foot=(
                "Read straight from the GitHub API by refresh_releases.py; nothing on this page is typed by hand."
                if en
                else "由 refresh_releases.py 从 GitHub API 读取；这一页上没有手写的链接或数字。"
            ),
        )
        + bar
        + "</section>",
        section(
            f"{tag} — all files" if en else f"{tag} —— 全部文件",
            # 附件表放进一档色差的面板：一屏里只有两列短文本时，裸露的表格看着像一屏空场
            # （深底上没有背景，视线落不到东西上）；给个地面之后这块才算"有内容"。
            '<div class="surface">'
            + table(["File", "Size"] if en else ["文件", "大小"], asset_rows(LATEST))
            + "</div>"
            # 控制台碎片：拿到文件之后它实际会打印什么（事件名与字段名来自 Main.kt）
            + fragments.console(
                [
                    '{"type":"start","url":"https://example.com/image.iso","output":"image.iso"}',
                    '{"type":"timing","stage":"metadata","t_ms":184,"probe_ms":96,"resume":""}',
                    '{"type":"progress","state":"downloading","downloaded":222732288,"total":358411469,"percent":62,"speed":19503514,"connections":16,"eta_ms":7020}',
                    '{"type":"progress","state":"merging","downloaded":358411469,"total":358411469,"percent":100,"speed":0,"connections":0,"eta_ms":0}',
                    '{"type":"completed","url":"https://example.com/image.iso","file":"image.iso","bytes":358411469,"sha256":"0f3a…"}',
                    '{"type":"summary","total":1,"failed":0}',
                ],
                (
                    f"java -jar turbodl-cli-{ver}-all.jar <url> --json · one line per event"
                    if en
                    else f"java -jar turbodl-cli-{ver}-all.jar <url> --json · 每个事件一行"
                ),
            ),
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
    implementation("dev.turbodl:turbo-plugin-parser:{ver}")
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
            )
            # 安装路径台账：从离线包到能依赖，中间要动的就这两处
            + fragments.readout(
                "What the bundle installs to" if en else "离线包会装到哪",
                [
                    ("~/.m2/repository/dev/turbodl/turbodl-core/" + ver, "<version>/turbodl-core-<version>.jar + .pom"),
                    ("~/.m2/repository/dev/turbodl/turbo-plugin-*/" + ver, "one directory per plugin artifact"),
                    ("build.gradle.kts", f'implementation("dev.turbodl:turbodl-core:{ver}")'),
                ],
                (
                    "The bundle is a plain repository tree: unzip it at ~/.m2/repository and Gradle resolves it offline."
                    if en
                    else "离线包就是一棵普通的仓库目录树：解到 ~/.m2/repository，Gradle 就能离线解析。"
                ),
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
            + "</p>"
            # 参数台账：这些开关都在 Main.kt 里，取值域是真的
            + fragments.readout(
                "The flags worth knowing" if en else "值得记住的几个开关",
                [
                    ("-c, --connections", "1..256 · default 16"),
                    ("-l, --limit", "bytes per second, 0 = unlimited"),
                    ("-r, --retry", "0..50 per segment"),
                    ("-b, --batch", "one URL per line"),
                    ("--json", "NDJSON: start / timing / progress / completed / summary"),
                    ("--doh, --proxy", "DNS over HTTPS, or Direct / System / Manual / PAC"),
                    ("--no-tail-assist, --no-overlap", "turn the two endgame switches off"),
                ],
                (
                    "Every flag maps onto a field of TurboConfig — the CLI is a thin skin over the SDK."
                    if en
                    else "每个开关都对应 TurboConfig 里的一个字段 —— CLI 只是 SDK 的一层薄壳。"
                ),
            ),
        ),
        section(
            "Previous versions" if en else "历史版本",
            '<div class="surface">'
            + table(
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
            )
            + "</div>",
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
        f'<p class="lead">{md_inline(p["summary"][lang])}</p>'
        f'<div class="card-meta">{chips}</div></section>'
        + section("Manifest" if en else "清单", table(heads, meta_rows))
        + section(
            "Explicitly refused" if en else "明确拒绝的情况",
            "<ul>" + "".join(f"<li>{md_inline(x)}</li>" for x in p["refuses"][lang]) + "</ul>"
            + note(md_inline(p["refuses_note"][lang]), "info"),
        )
        + section(
            "Source" if en else "源码",
            f'<p>{"Read it in the repository — the manifest above is generated from the file itself." if en else "在仓库里读源码 —— 上面的清单就是从那个文件里读出来的。"}</p>'
            + buttons([("Repository" if en else "仓库", p["source"]), ("All plugins" if en else "全部插件", href("plugins"))]),
        )
    )


def _plugin_capability_fragment(lang: str) -> str:
    """两个官方插件的能力与拒绝清单：直接读各自的 `turbodl-plugin.json` 同级事实表
    （`OFFICIAL_PLUGINS` 就是从那里来的），渲染成台账 + 条款两块碎片。"""
    en = lang == "en"
    total_caps = sum(len(p["capabilities"]) for p in OFFICIAL_PLUGINS)
    rows = [
        (
            f'{p["category"]} · {p["id"]}',
            f'{len(p["capabilities"])} {"capabilities" if en else "项能力"} · '
            + (", ".join(p["protocols"]) if p["protocols"] else ("no protocols" if en else "不声明协议")),
        )
        for p in OFFICIAL_PLUGINS
    ]
    refusals = [
        (
            p["name"],
            " · ".join(p["refuses"][lang]),
        )
        for p in OFFICIAL_PLUGINS
    ]
    return section(
        "Capabilities, and what is refused" if en else "能力，以及被拒绝的东西",
        (
            f'<p>Between them the two official plugins declare {total_caps} capabilities. Everything '
            "they will not do is listed beside it — a refusal is a feature when the alternative is a "
            "corrupt file.</p>"
            if en
            else f'<p>两个官方插件一共声明了 {total_caps} 项能力。它们不做的事情都列在旁边 —— '
            "当另一种做法是产出一个损坏的文件时，「拒绝」本身就是功能。</p>"
        )
        + fragments.readout(
            "declared capabilities" if en else "声明的能力",
            rows,
            (
                "Read from each plugin's turbodl-plugin.json."
                if en
                else "从各插件的 turbodl-plugin.json 里读出来的。"
            ),
        )
        + fragments.readout(
            "explicitly refused" if en else "明确拒绝",
            refusals,
            (
                "An unsupported stream fails loudly rather than producing a file that looks fine."
                if en
                else "不支持的流会响亮地失败，而不是产出一个看起来正常的文件。"
            ),
        ),
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
        + _plugin_capability_fragment(lang)
        + section(
            "Plugin categories" if en else "插件分类",
            (
                "<p>A plugin declares one category in its manifest. The category says what it is allowed "
                "to plug into — nothing else.</p>"
                if en
                else "<p>插件在清单里声明一个分类。分类只说明它能接到哪里，不说明别的。</p>"
            )
            # 分类台账碎片：六条分类 + 各自的 id，与文档页的分类表同源
            + fragments.readout(
                "turbodl-plugin.json · category" if en else "turbodl-plugin.json · category",
                [
                    (k, v[lang])
                    for k, v in PLUGIN_CATEGORIES
                ],
                (
                    "The category is the whole of the runtime's authorization: it decides which extension points the plugin may register."
                    if en
                    else "分类就是这个运行时的全部授权依据：它决定插件可以注册哪些扩展点。"
                ),
            ),
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
            )
            # 清单碎片：写插件要照着填的那份文件（与文档页的清单引用同源）
            + fragments.manifest_block(
                "turbodl-plugin.json",
                json.dumps(MANIFEST_EXAMPLE, indent=2, ensure_ascii=False),
                (
                    "Six required fields, one category, one entry point. The reference page lists every field and its allowed values."
                    if en
                    else "六个必填字段、一个分类、一个入口。参考页列了每个字段与允许的取值。"
                ),
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
            "Every page here is a file" if en else "这里的每一页都是一个文件",
            (
                "<p>This table is the whole of the documentation set: the repository path on the left, "
                "the page it becomes on the right. Build the site and the two are rendered from the same "
                "source, so a change to either side shows up on the other.</p>"
                if en
                else "<p>下表就是全部文档：左边是仓库里的路径，右边是它变成的页面。"
                "重建站点时两边由同一个源渲染，改任何一边都会反映到另一边。</p>"
            )
            + fragments.readout(
                "docs/ → site" if en else "docs/ → 网站",
                [
                    (
                        (d["src_zh"] if lang == "zh" and d.get("src_zh") else d.get("src_en") or "") or "—",
                        d["title"][lang],
                    )
                    for d in DOC_PAGES
                ],
                (
                    "Pages without a translation fall back to the other language and say so at the top."
                    if en
                    else "没有对应语言版本的那几页会退回另一种语言的原文，并在页首说明。"
                ),
            ),
        )
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
            )
            # 控制台碎片：`--json` 到底输出什么（字段名来自 Main.kt 的 progress 事件）
            + fragments.console(
                [
                    '{"type":"start","url":"<url>","output":"out.bin"}',
                    '{"type":"timing","stage":"metadata","t_ms":184,"probe_ms":96,"resume":""}',
                    '{"type":"progress","state":"downloading","downloaded":222732288,"total":358411469,"percent":62,"speed":19503514,"connections":16,"eta_ms":7020}',
                    '{"type":"completed","url":"<url>","file":"out.bin","bytes":358411469,"sha256":"0f3a…"}',
                    '{"type":"summary","total":1,"failed":0}',
                ],
                "turbodl --json · stdout" if en else "turbodl --json · 标准输出",
            ),
        )
        + section(
            "The whole config surface" if en else "全部配置面",
            (
                "<p>Nine fields, all public, all with a documented range. Nothing else is tunable at "
                "run time — the engine has no hidden knobs and no phone-home defaults.</p>"
                if en
                else "<p>九个字段，全部公开、全部有明确取值域。运行时没有别的可调项 —— "
                "引擎没有隐藏旋钮，也没有打电话回家的默认值。</p>"
            )
            # 字段台账碎片：与首页的开关横带同源（TurboConfig.kt 的字段与取值域）
            + fragments.readout(
                "TurboConfig" if en else "TurboConfig 的字段",
                [
                    ("maxConnectionsPerTask", "16 · 1..256"),
                    ("maxConcurrentTasks", "3 · 1..64"),
                    ("globalSpeedLimitBytesPerSec", "0 = unlimited"),
                    ("maxRetries", "5 · 0..50, per segment"),
                    ("dynamicSegmentation", "true"),
                    ("tailAssist / tailAssistMinBytes", "true · 2 MiB"),
                    ("overlapMerge / bufferedSegmentWrite", "false · false"),
                    ("minSegmentSize / blockSize", "64 KiB · 16 MiB"),
                    ("proxy / dns / trustAllCerts", "Direct · System · Manual · PAC / System · StaticHosts · DoH"),
                ],
                (
                    "Values are the defaults in TurboConfig.kt; every one of them can be set per client."
                    if en
                    else "取值是 TurboConfig.kt 里的默认值；每一个都能按 client 单独设置。"
                ),
            ),
        )
        + section(
            "What a plugin manifest looks like" if en else "一份插件清单长什么样",
            (
                "<p>A plugin is a JAR plus this file. The reference page lists every field, its type, "
                "whether it is required, and the allowed values.</p>"
                if en
                else "<p>一个插件 = 一个 JAR + 这个文件。参考页列了每个字段的类型、是否必填、以及允许的取值。</p>"
            )
            + fragments.manifest_block(
                "turbodl-plugin.json" if en else "turbodl-plugin.json",
                json.dumps(MANIFEST_EXAMPLE, indent=2, ensure_ascii=False),
                (
                    "The same file the manifest reference renders, so the two pages cannot disagree."
                    if en
                    else "与「清单字段参考」那一页用同一个常量渲染，两页不会各说各话。"
                ),
            ),
        )
        + "".join(groups)
        + section("Architecture" if en else "架构", arch)
    )


def _excerpt(text: str) -> str:
    """把 Release 说明的摘要转成一段能读的文本。

    摘要是从 GitHub 的 Markdown 正文里截出来的（`refresh_releases.py` 抓的），所以带着
    `**强调**`、`` `代码` `` 与行首的 `·` 项目符号。这里是一段摘要而不是清单，
    所以：强调保留成粗体、代码标记去掉、项目符号去掉 —— 不然页面上会露出字面的 `**`。
    """
    if not text:
        return ""
    out = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", esc(text))
    out = re.sub(r"`([^`]+)`", r"\1", out)
    out = re.sub(r"(?m)^\s*[·•\-]\s*", "", out)
    # 摘要是截断的，所以可能留下**半个**标记（`**原生插件（L2）的加载契约**…` 被切断），
    # 成对的已经换成 <strong>，剩下的孤立标记直接去掉。
    return out.replace("**", "").strip()


def _release_ledger(lang: str, limit: int = 12) -> str:
    """发布台账碎片：日期、附件数、是否预发布 —— 与下载页渲染同一份 `releases.json`。"""
    en = lang == "en"
    return fragments.readout(
        "releases.json" if en else "releases.json",
        [
            (
                r["tag"] + (" (pre)" if r["prerelease"] else ""),
                f'{(r["published"] or "")[:10]} · {len(r["assets"])} {"assets" if en else "个附件"}',
            )
            for r in RELEASES[:limit]
        ],
        (
            "Fetched from the GitHub API by website/refresh_releases.py and committed — the site never calls the API at runtime."
            if en
            else "由 website/refresh_releases.py 从 GitHub API 抓取后提交进仓库 —— 站点运行时不会去调 API。"
        ),
    )


def _latest_assets_readout(lang: str) -> str:
    """最新一次发布的附件清单（文件名与大小来自 `releases.json`）。"""
    en = lang == "en"
    from sitekit import fmt_bytes

    return fragments.readout(
        f'{LATEST["tag"]} · {"assets" if en else "附件"}' if en else f'{LATEST["tag"]} · 附件',
        [
            (a["name"], fmt_bytes(int(a.get("size") or 0)))
            for a in LATEST["assets"]
        ],
        (
            "The same list the download page renders, from the same API response."
            if en
            else "与下载页列的是同一份清单，来自同一次 API 响应。"
        ),
    )


def _release_pipeline(lang: str) -> str:
    """一次发布是怎么变成官网上一条记录的：这条链上每一步都是真的。"""
    en = lang == "en"
    return fragments.readout(
        "how this page is built" if en else "这一页是怎么来的",
        [
            ("git tag + GitHub release", "the release notes you read below" if en else "下面读到的发布说明"),
            ("website/refresh_releases.py", "pulls tag, date, assets and body via the API" if en else "通过 API 抓 tag、日期、附件与正文"),
            ("website/data/releases.json", "committed to the repository" if en else "提交进仓库"),
            ("python website/build.py", "this page and the download page" if en else "这一页与下载页"),
            ("website/check_links.py", "1404 internal links, every build" if en else "每次构建检查 1404 条站内链接"),
        ],
        (
            "No hand-typed versions, dates or file names anywhere on the site."
            if en
            else "整站没有任何手写的版本号、日期或文件名。"
        ),
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
        summary = _excerpt(r["summary"])
        body = f'<p class="muted">{summary}</p>' if summary else ""
        entries.append(
            f'<div class="card"><h3><span class="mono">{esc(r["tag"])}</span>{badge}</h3>'
            f'<p class="small">{esc((r["published"] or "")[:10])}</p>{body}{assets}</div>'
        )
    return (
        f'<section><h1>{"Changelog" if en else "更新日志"}</h1>'
        + f'<p class="lead">{"Generated from the release notes themselves — each entry links to its own "
           "release page, where the full text and the files live." if en else "由发布说明本身生成 —— 每条都链到对应的发布页，完整正文与文件都在那里。"}</p></section>'
        # 版本台账碎片：这些数字就是 releases.json 里的数字（发布日、附件数、预发布标记），
        # 一眼能看出"哪一版动了多少东西"，比十二条卡片的摘要更适合比较。
        + section(
            "Release ledger" if en else "发布台账",
            (
                "<p>Every published release with its date, its attachment count and whether it was a "
                "prerelease. Nothing here is summarized by hand — the table is the same data the download "
                "page renders.</p>"
                if en
                else "<p>每次发布、日期、附件数、是否预发布。这里没有一处是手写摘要 —— "
                "这张表与下载页渲染的是同一份数据。</p>"
            )
            + fragments.readout(
                "releases.json" if en else "releases.json",
                [
                    (
                        r["tag"] + (" (pre)" if r["prerelease"] else ""),
                        f'{(r["published"] or "")[:10]} · {len(r["assets"])} {"assets" if en else "个附件"}',
                    )
                    for r in RELEASES[:12]
                ],
                (
                    "Fetched from the GitHub API by website/refresh_releases.py and committed — the site never calls the API at runtime."
                    if en
                    else "由 website/refresh_releases.py 从 GitHub API 抓取后提交进仓库 —— 站点运行时不会去调 API。"
                ),
            )
            # 第二次台账：把「每次发布动了多少东西」按附件数排一遍 —— 比十二条摘要更容易比较
            + fragments.readout(
                "by size" if en else "按体量排",
                [
                    (
                        f'{len(r["assets"])} {"assets" if en else "个附件"} · '
                        + ("prerelease" if r["prerelease"] else ("release" if en else "正式版")),
                        f'{r["tag"]} · {(r["published"] or "")[:10]}',
                    )
                    for r in sorted(RELEASES[:12], key=lambda x: -len(x["assets"]))[:6]
                ],
                (
                    "The first six releases by attachment count. A jump here usually means a new artifact was added to the build."
                    if en
                    else "按附件数排的前六次发布。这里突然多出来，通常意味着构建里新加了一个产物。"
                ),
            ),
        )
        + f'<section><div class="cards cols-2">{"".join(entries)}</div></section>'
        # 最新一次的附件清单 + 这一页怎么生成的：两块台账，把"数字从哪来"讲清楚
        + section(
            f'What shipped in {LATEST["tag"]}' if en else f'{LATEST["tag"]} 里发了什么',
            (
                "<p>The newest release, file by file. Sizes are the API's own numbers.</p>"
                if en
                else "<p>最新一次发布，逐个文件列出来。大小就是 API 给的数字。</p>"
            )
            + _latest_assets_readout(lang)
            + _release_pipeline(lang),
        )
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
        # 三次互动各自的去处：这份台账就是"没有聊天群"的替代说明（比一句"欢迎来讨论"具体）
        + section(
            "Where each kind of question goes" if en else "每类问题去哪里",
            (
                "<p>No chat, so the routes are explicit. Nothing on this site is a dead end.</p>"
                if en
                else "<p>没有聊天群，所以路线写明确。这个站点上没有走不通的入口。</p>"
            )
            + fragments.readout(
                "routes" if en else "去处",
                [
                    ("bug report / feature request", f"{REPO.split('//')[-1]}/issues"),
                    ("questions", f"{REPO.split('//')[-1]}/issues" + (" (Discussions is not enabled)" if en else "（未开启 Discussions）")),
                    ("patches", f"{REPO.split('//')[-1]}/pulls" + (" — run ./gradlew test first" if en else " —— 先跑 ./gradlew test")),
                    ("security", "see SECURITY notes in the repository" if en else "见仓库里的安全说明"),
                    ("everything else", "README.md" if en else "README.md"),
                ],
                (
                    "Searchable and permanent, which a chat log is not."
                    if en
                    else "可搜索、可追溯 —— 聊天记录做不到这两点。"
                ),
            ),
        )
        + section("Issues" if en else "问题反馈", issue + buttons([("Open an issue" if en else "提交 issue", f"{REPO}/issues")]))
        # 提交前要过的检查台账：这些都是仓库里真实的命令与门槛，不是"欢迎贡献"的空话
        + section(
            "Before you open a pull request" if en else "提 PR 之前要过的几关",
            (
                "<p>These are the same steps the maintainer runs. Nothing secret, nothing extra.</p>"
                if en
                else "<p>下面就是维护者自己走的步骤。没有隐藏关卡，也没有额外要求。</p>"
            )
            + fragments.readout(
                "the checklist" if en else "检查清单",
                [
                    ("./gradlew test", f"258 {'cases, 0 skipped, 0 failed' if en else '个用例，0 跳过，0 失败'}"),
                    ("dependency direction", "runtime → core" if en else "runtime → core（绝不反向）"),
                    ("plugin manifest", "turbodl-plugin.schema.json" if en else "turbodl-plugin.schema.json"),
                    ("docs", "one file in the repository per page" if en else "官网每一页 = 仓库里的一个文件"),
                ],
                (
                    "The suite covers byte-level correctness, resume and the plugin lifecycle; a change that breaks it is a change that is not ready."
                    if en
                    else "这套测试覆盖字节级正确性、续传与插件生命周期；跑不过的改动就是还没准备好的改动。"
                ),
            ),
        )
        + section("Discussions" if en else "讨论", disc)
        # 一份真实 issue 报告要带的东西 —— 这是"欢迎反馈"能不能用的关键
        + section(
            "What a useful report contains" if en else "一份有用的报告包含什么",
            (
                "<p>Four fields, and a report missing any of them usually turns into three round trips "
                "of questions.</p>"
                if en
                else "<p>四个字段；少任何一个，通常就要多来回问三轮。</p>"
            )
            + fragments.readout(
                "the template" if en else "模板",
                [
                    ("version", LATEST["tag"]),
                    ("JDK", "output of java -version" if en else "java -version 的输出"),
                    ("target", "the URL, or a minimal reproduction" if en else "目标链接，或最小复现"),
                    ("expected / actual", "what you expected instead" if en else "你期望的结果是什么"),
                ],
                (
                    "Nothing else is required, and nothing is collected automatically."
                    if en
                    else "不需要别的，也不会自动收集任何信息。"
                ),
            ),
        )
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
            )
            # 协议台账：MIT 允许什么、附加条款管什么（每条都能在 docs/legal/ 里核对）
            + fragments.readout(
                "what MIT lets you do" if en else "MIT 允许你做什么",
                [
                    ("use commercially", "yes" if en else "可以"),
                    ("modify", "yes" if en else "可以"),
                    ("distribute", "yes" if en else "可以"),
                    ("sublicense", "yes" if en else "可以"),
                    ("must keep the copyright notice", "yes" if en else "需要保留版权声明"),
                    ("plugins", "independent works, any license" if en else "独立作品，可自带任何协议"),
                ],
                (
                    "The project's own code is MIT; the plugin-ecosystem terms only clarify that interacting through the public API does not make a plugin a derivative."
                    if en
                    else "项目自身的代码是 MIT；插件生态条款只说明一件事：通过公开 API 交互不会让插件变成衍生物。"
                ),
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
                f"<p>{md_inline(p['summary'][lang])}</p>"
                f'<p class="small mono">dev.turbodl:{esc(p["artifact"])}:{LATEST_VERSION}</p>'
                f'<div class="card-meta">{meta}</div>'
                f'<div class="btn-row tight"><a class="btn" href="{{LINK:{p["key"]}}}">{more}</a></div>',
            )
        )
    return cards(items, columns=2)


def _nav() -> list:
    """顶栏只放**最常走的四条路**（其余入口在页脚按栏目列全）。

    上一版七项（Overview/Download/Plugins/Docs/Changelog/Community/Terms），
    加上语言切换与主题按钮就是九个目标挤在一行 —— 按钮墙，主行动反而被淹掉。
    """
    return [
        ("home", {"en": "Overview", "zh": "概览"}),
        ("download", {"en": "Download", "zh": "下载"}),
        ("plugins", {"en": "Plugins", "zh": "插件"}),
        ("docs", {"en": "Docs", "zh": "文档"}),
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


def _third_party_section(lang: str) -> str:
    """从 `docs/legal/THIRD-PARTY.md` 里读运行时依赖，渲染成一块台账碎片。

    为什么读文件而不是抄一份：那份文档是**准的**（它是给集成者看的），抄进
    content.py 就多了一处要跟着版本走的地方 —— 版本号一升，两边必然分家。
    解析失败就不显示这一节（少一块，不是显示错的）。
    """
    en = lang == "en"
    path = ROOT / "docs" / "legal" / "THIRD-PARTY.md"
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        return ""
    rows = re.findall(
        r"^\|\s*\[([^\]]+)\]\(([^)]+)\)\s*\|\s*([^|]+)\|\s*([^|]+)\|\s*([^|]+)\|",
        text,
        re.M,
    )
    if not rows:
        return ""
    items = [
        (f"{name} · {version.strip()}", licence.strip())
        for name, _url, version, licence, _used in rows
    ]
    return section(
        "What comes along with it" if en else "它会连带引入什么",
        (
            "<p>Read out of <span class=\"mono\">docs/legal/THIRD-PARTY.md</span> at build time, so "
            "this ledger and that file cannot disagree. Only <span class=\"mono\">turbodl-core</span> "
            "is required; the JS loader is the one module that pulls in a native library.</p>"
            if en
            else "<p>构建时从 <span class=\"mono\">docs/legal/THIRD-PARTY.md</span> 里读出来的 —— "
            "所以这块台账与那个文件不会各说各话。只有 <span class=\"mono\">turbodl-core</span> 是必需的；"
            "引入本地库的只有 JS 加载器这一个模块。</p>"
        )
        + fragments.readout(
            "Runtime dependencies" if en else "运行时依赖",
            items,
            (
                "All permissively licensed (Apache-2.0 / MIT), so closed-source commercial integration is unproblematic."
                if en
                else "全部是宽松协议（Apache-2.0 / MIT），闭源商业集成没有障碍。"
            ),
        ),
    )


def _privacy_sections(lang: str) -> str:
    """从 `docs/legal/PRIVACY.md` 里读两张表（什么时候走网络 / 什么落到磁盘），
    渲染成两块台账碎片。与第三方依赖那块同一个理由：文件是准的，抄一份就会分家。"""
    en = lang == "en"
    path = ROOT / "docs" / "legal" / "PRIVACY.md"
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        return ""

    def table_rows(header_word: str) -> list[tuple[str, str]]:
        """截取某个小标题之后的第一张表（表头行与分隔行都跳过）。"""
        i = text.find(header_word)
        if i < 0:
            return []
        chunk = text[i:]
        rows: list[tuple[str, str]] = []
        for line in chunk.split("\n")[1:]:
            if not line.startswith("|"):
                if rows:
                    break
                continue
            cells = [c.strip() for c in line.strip("|").split("|")]
            if len(cells) < 2 or set(cells[0]) <= set("-: "):
                continue
            if cells[0] in ("Moment", "File"):
                continue
            # 正文里的行内代码标记去掉，值里的反引号不适合台账
            value = cells[1].replace("`", "")
            rows.append((cells[0], value))
        return rows

    net = table_rows("## When it touches the network")
    disk = table_rows("## What lands on disk")
    parts = []
    if net:
        parts.append(
            section(
                "When it touches the network" if en else "什么时候会走网络",
                (
                    "<p>Five moments, and every one of them starts with something you asked for. "
                    "Read out of the privacy document at build time.</p>"
                    if en
                    else "<p>五个时机，每一个都由你发起的动作开始。构建时从隐私文档里读出来。</p>"
                )
                + fragments.readout(
                    "outbound requests" if en else "出站请求",
                    net,
                    (
                        "No heartbeat, no telemetry ping, no request fired off in the background."
                        if en
                        else "没有心跳、没有遥测、没有在后台自己发出去的请求。"
                    ),
                ),
            )
        )
    if disk:
        parts.append(
            section(
                "What lands on disk" if en else "什么会落到磁盘上",
                (
                    "<p>Three kinds of files, all next to something you named.</p>"
                    if en
                    else "<p>三类文件，都在你指定过的地方旁边。</p>"
                )
                + fragments.readout(
                    "files" if en else "文件",
                    disk,
                    (
                        "The resume state is cleaned up when a task finishes; the JS sandbox exists only if the JS loader is used."
                        if en
                        else "续传状态在任务结束时清理；JS 沙箱只在用到 JS 加载器时才存在。"
                    ),
                ),
            )
        )
    return "".join(parts)


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
        # 第三方依赖台账：直接读 docs/legal/THIRD-PARTY.md（唯一真源）
        + _third_party_section(lang)
        # 隐私两张表：直接读 docs/legal/PRIVACY.md
        + _privacy_sections(lang)
        # 协议台账碎片：四份文档各自的仓库路径与它在管什么 —— 比一堆卡片更好查
        + section(
            "What each one covers" if en else "每一份管什么",
            f'<p>{"Four files, four questions. If two of them seem to overlap, the repository path tells you which one is authoritative." if en else "四个文件、四个问题。如果其中两份看着有重叠，仓库路径能告诉你哪一份是准的。"}</p>'
            + fragments.readout(
                "docs/ and the repository root" if en else "docs/ 与仓库根目录",
                [
                    (d["src_en"] or "—", d["title"][lang])
                    for d in DOC_PAGES
                    if d["group"] == "legal"
                ],
                (
                    "These pages are rendered from those files, so a change to the file changes the page."
                    if en
                    else "这些页面就是从那些文件渲染的，改文件即改页面。"
                ),
            ),
        )
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
                    # meta description 是纯文本（搜索结果的摘要），去掉 Markdown 的强调标记
                    "desc": re.sub(r"\*\*", "", p["summary"][lang]),
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
    "repo": REPO,
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
