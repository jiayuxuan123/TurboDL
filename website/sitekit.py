#!/usr/bin/env python3
"""Shared HTML helpers for the TurboDL site.

The visual system is a packet transit control board: explicit routes, stations,
status lamps, timetable rows, and reproducible engineering values. These helpers
emit the primitives once so every page speaks the same visual language.
"""

from __future__ import annotations

import html
import re
from pathlib import Path


def esc(text: object) -> str:
    """Escape text for HTML content or attributes."""
    return html.escape(str(text), quote=True)


def rel(from_dir: str, to_path: str) -> str:
    """Express a site path relative to a page directory."""
    from_parts = [p for p in from_dir.split("/") if p]
    to_parts = [p for p in to_path.split("/") if p]
    while from_parts and to_parts and from_parts[0] == to_parts[0]:
        from_parts.pop(0)
        to_parts.pop(0)
    return "/".join([".."] * len(from_parts) + to_parts) or "."


_ASSET_FINGERPRINTS: dict[str, str] = {}


def set_asset_fingerprints(assets_dir: Path) -> None:
    """Hash CSS and JavaScript names once at build start."""
    import hashlib

    _ASSET_FINGERPRINTS.clear()
    if not assets_dir.is_dir():
        return
    for f in sorted(assets_dir.iterdir()):
        if f.suffix not in (".css", ".js"):
            continue
        digest = hashlib.sha256(f.read_bytes()).hexdigest()[:8]
        _ASSET_FINGERPRINTS[f.name] = f"{f.stem}.{digest}{f.suffix}"


def asset_fingerprint(name: str) -> str:
    return _ASSET_FINGERPRINTS.get(name, name)


def asset(from_dir: str, name: str) -> str:
    base = _ASSET_FINGERPRINTS.get(name, name)
    return rel(from_dir, f"assets/{base}")


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


def fmt_bytes(n: int) -> str:
    if n >= 1024 * 1024:
        return f"{n / 1048576:.1f} MB"
    if n >= 1024:
        return f"{n / 1024:.0f} KB"
    return f"{n} B"


_ICONS = {
    "arrow": '<path d="M5 12h14"/><path d="m14 7 5 5-5 5"/>',
    "book": '<path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H11v16H6.5A2.5 2.5 0 0 0 4 21.5z"/><path d="M20 5.5A2.5 2.5 0 0 0 17.5 3H13v16h4.5a2.5 2.5 0 0 1 2.5 2.5z"/>',
    "check": '<path d="m5 12 4 4L19 6"/>',
    "chevron": '<path d="m7 9 5 5 5-5"/>',
    "close": '<path d="m6 6 12 12M18 6 6 18"/>',
    "copy": '<rect x="8" y="8" width="11" height="11" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/>',
    "download": '<path d="M12 3v12"/><path d="m7 10 5 5 5-5"/><path d="M5 20h14"/>',
    "external": '<path d="M14 5h5v5"/><path d="m10 14 9-9"/><path d="M19 14v4a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h4"/>',
    "file": '<path d="M7 3h7l4 4v14H7z"/><path d="M14 3v5h5"/>',
    "menu": '<path d="M4 7h16M4 12h16M4 17h16"/>',
    "package": '<path d="m12 3 8 4.5v9L12 21l-8-4.5v-9z"/><path d="m4 7.5 8 4.5 8-4.5M12 12v9"/>',
    "plug": '<path d="M8 7V3M16 7V3"/><path d="M6 7h12v4a6 6 0 0 1-12 0z"/><path d="M12 17v4"/>',
    "terminal": '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="m7 9 3 3-3 3M13 15h4"/>',
}


def icon(name: str, class_name: str = "") -> str:
    """Return one stroke-consistent icon from the site's authored icon set."""
    body = _ICONS.get(name)
    if not body:
        return ""
    cls = f"icon {class_name}".strip()
    return (
        f'<svg class="{cls}" viewBox="0 0 24 24" fill="none" '
        'stroke="currentColor" stroke-width="1.75" stroke-linecap="round" '
        f'stroke-linejoin="round" aria-hidden="true">{body}</svg>'
    )


def code_block(code: str, caption: str = "") -> str:
    """Code block with native horizontal scrolling and a keyboard copy control."""
    cap = (
        f'<figcaption class="code-cap"><span>{esc(caption)}</span>'
        '<button class="code-copy" type="button" data-copy-code '
        'aria-label="Copy code">'
        f'{icon("copy")}<span class="copy-label">Copy</span></button></figcaption>'
        if caption
        else
        '<figcaption class="code-cap code-cap-bare">'
        '<button class="code-copy" type="button" data-copy-code '
        'aria-label="Copy code">'
        f'{icon("copy")}<span class="copy-label">Copy</span></button>'
        "</figcaption>"
    )
    return (
        f'<figure class="code-block" data-code-block>{cap}'
        f'<div class="code-scroll"><pre><code>{esc(code.strip(chr(10)))}</code></pre></div>'
        "</figure>"
    )


def table(headers: list[str], rows: list[list[str]], *, compact: bool = False) -> str:
    """Semantic data table; mobile CSS turns each row into a compact ledger."""
    head = "".join(f'<th scope="col">{h}</th>' for h in headers)
    body = "".join(
        "<tr>"
        + "".join(
            f'<td data-label="{esc(_plain(h))}">{c}</td>'
            for h, c in zip(headers, r)
        )
        + "</tr>"
        for r in rows
    )
    cls = "table-wrap compact" if compact else "table-wrap"
    return (
        f'<div class="{cls}" data-table tabindex="0" role="region">'
        f'<table><thead><tr>{head}</tr></thead><tbody>{body}</tbody></table></div>'
    )


def _plain(fragment: str) -> str:
    text = re.sub(r"<[^>]+>", "", str(fragment))
    return html.unescape(text).strip()


def md_inline(text: object) -> str:
    return re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", esc(text))


def cards(items: list[tuple[str, str]], columns: int = 3) -> str:
    """Compatibility helper rendered as an editorial ledger, never a card grid."""
    inner = "".join(
        f'<article class="ledger-item"><h3>{t}</h3>'
        f'<div class="ledger-copy">{b}</div></article>'
        for t, b in items
    )
    return f'<div class="ledger cols-{columns}" data-ledger>{inner}</div>'


def buttons(items: list[tuple[str, str]], kind: str = "row") -> str:
    """Button row using the same arrow/external icon vocabulary throughout."""
    cls = f"btn-row {kind}".strip()
    inner = ""
    for label, href in items:
        ext = href.startswith(("http://", "https://"))
        glyph = icon("external" if ext else "arrow", "btn-icon")
        inner += (
            f'<a class="btn" href="{esc(href)}"><span>{label}</span>'
            f"{glyph}</a>"
        )
    return f'<div class="{cls}">{inner}</div>'


def section(title: str, body: str, anchor: str = "") -> str:
    aid = f' id="{anchor}"' if anchor else ""
    return f'<section class="section"{aid}>{seg_rule()}<h2>{title}</h2>{body}</section>'


def seg_rule(segments: list[tuple[int, bool]] | None = None) -> str:
    """Deterministic route divider derived from unequal transfer segments."""
    if segments is None:
        segments = [(7, True), (3, False), (5, True), (2, False), (9, False), (4, True)]
    total = sum(w for w, _ in segments) or 1
    parts = []
    for width, done in segments:
        pct = width / total * 100
        cls = "is-complete" if done else ""
        parts.append(f'<i class="{cls}" style="width:{pct:.3f}%"></i>')
    return '<div class="route-divider" aria-hidden="true">' + "".join(parts) + "</div>"


def note(text: str, tone: str = "") -> str:
    cls = f"note {tone}".strip()
    return f'<aside class="{cls}">{text}</aside>'


def breadcrumb(items: list[tuple[str, str]]) -> str:
    parts = []
    for i, (text, href) in enumerate(items):
        if i == len(items) - 1 or not href:
            parts.append(f'<span aria-current="page">{esc(text)}</span>')
        else:
            parts.append(f'<a href="{href}">{esc(text)}</a>')
    return (
        '<nav class="crumbs" aria-label="Breadcrumb">'
        + '<span class="sep" aria-hidden="true">/</span>'.join(parts)
        + "</nav>"
    )
