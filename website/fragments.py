#!/usr/bin/env python3
"""页面上的「界面碎片」（`[data-ui]`）：把产品自己会打印/显示的东西原样搬上页面。

为什么要单独一个模块：首页、下载页、文档页都要用同一批碎片（控制台、转账面板、
字段台账、清单片段），而房屋规则里对 `[data-ui]` 有单独的口径 —— 这些块内部允许
保留产品自己的控件外观（进度条、字段分栏），但**不允许包标题、也不允许超过一屏半**。
集中在这里，这几条约束只在一处遵守。

事实来源（每个碎片的数字都能在仓库里核对）：
  · `turbodl-cli/src/main/kotlin/dev/turbodl/cli/Main.kt` —— NDJSON 事件与字段名
  · `turbodl-core/src/main/kotlin/dev/turbodl/core/TurboConfig.kt` —— 配置字段与默认值
  · `website/data/releases.json` —— 版本、附件名与大小（由 refresh_releases.py 抓取）

尺寸口径：一个碎片要算作页面上的"图"，它得 ≥300×200（探针只数这个尺寸以上的块），
所以这里的每个碎片都保证有足够的内容行数撑到 200px 高 —— 不是靠内边距注水，
而是真把该显示的字段显示完。
"""

from __future__ import annotations

from sitekit import code_block, esc, table


def console(lines: list[str], caption: str) -> str:
    """控制台碎片：一行一个事件（真代码块，等宽在这里是允许的）。"""
    body = "\n".join(esc(l) for l in lines)
    return (
        '<div class="console" data-ui>'
        f'<div class="console-cap">{esc(caption)}</div>'
        f"<pre><code>{body}</code></pre>"
        "</div>"
    )


def panel(lead: str, rows: list[tuple[str, str]], *, pct: str = "", bar: bool = False, foot: str = "") -> str:
    """转账面板碎片：字段就是 CLI `progress` 事件里的字段。

    `pct` 给了就在右上角显示百分比；`bar` 为真时画一条进度条（两者一起用）。
    """
    head = '<div class="panel-head">'
    head += f'<p class="panel-state">{esc(lead)}</p>'
    if pct:
        head += f'<p class="panel-pct">{esc(pct)}</p>'
    head += "</div>"
    grid = "".join(f"<div><span>{esc(k)}</span><b>{esc(v)}</b></div>" for k, v in rows)
    return (
        '<div class="panel" data-ui>'
        + head
        + ('<div class="panel-bar"><i></i></div>' if bar else "")
        + f'<div class="panel-grid">{grid}</div>'
        + (f'<p class="panel-foot">{esc(foot)}</p>' if foot else "")
        + "</div>"
    )


def readout(title: str, items: list[tuple[str, str]], foot: str = "") -> str:
    """字段台账碎片：左列是字段名、右列是值，用一档色差的面板分层。

    用在「这个引擎有哪九个公开字段」「这个版本装了哪几个路径」这类**清单型**内容上：
    比表格更适合窄屏（窄屏时每项自己成一行，不需要 data-label 那一套）。
    """
    rows = "".join(
        f'<div class="readout-row"><span class="readout-key">{esc(k)}</span>'
        f'<span class="readout-val">{esc(v)}</span></div>'
        for k, v in items
    )
    return (
        '<div class="readout" data-ui>'
        f'<p class="readout-title">{esc(title)}</p>'
        f'<div class="readout-body">{rows}</div>'
        + (f'<p class="readout-foot">{esc(foot)}</p>' if foot else "")
        + "</div>"
    )


def manifest_block(title: str, json_text: str, foot: str = "") -> str:
    """清单碎片：一份真实的 `turbodl-plugin.json`（插件作者要对着它写）。"""
    return (
        '<div class="manifest-frag" data-ui>'
        f'<p class="readout-title">{esc(title)}</p>'
        f"<pre><code>{esc(json_text.strip(chr(10)))}</code></pre>"
        + (f'<p class="readout-foot">{esc(foot)}</p>' if foot else "")
        + "</div>"
    )


def asset_rows_table(heads: list[str], rows: list[list[str]]) -> str:
    """真表格的薄封装（下载页的附件表用；保留在模块里是为了让"碎片"与"表格"的区别一目了然）。"""
    return table(heads, rows)


def launcher_hint(commands: list[tuple[str, str]]) -> str:
    """命令行片段：`(命令, 用途)` —— 只有命令是真的，注释才是可信的。"""
    text = "\n".join(f"{cmd}   # {why}" for cmd, why in commands)
    return code_block(text, "shell")
