#!/usr/bin/env python3
"""Static site generator for TurboDL.

Content lives in ``content.py``; this file owns the shared shell, metadata,
navigation, footer, routes, and output manifest. The generated site is a light
packet-transit control board rather than a dark terminal-themed microsite.
"""

from __future__ import annotations

import json
import re
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

from sitekit import (
    asset,
    asset_fingerprint,
    breadcrumb,
    buttons,
    esc,
    icon,
    rel,
    set_asset_fingerprints,
    write,
)

MANIFEST = ".site-manifest.json"


def load_content(repo_root: Path) -> dict:
    web = str(repo_root / "website")
    if web not in sys.path:
        sys.path.insert(0, web)
    import content  # type: ignore

    return content.SITE


class Site:
    """Render one complete site build."""

    def __init__(self, cfg: dict, repo_root: Path):
        self.cfg = cfg
        self.root = repo_root
        self.out = repo_root / cfg.get("out_dir", "docs")
        self.pages: list[dict] = cfg["pages"]
        self.assets_dir: Path = repo_root / cfg["assets_dir"]
        for page in self.pages:
            page.setdefault("lang", cfg["default_lang"])
            page["dir"] = str(Path(page["path"]).parent).replace("\\", "/")
            if page["dir"] == ".":
                page["dir"] = ""
        self.index: dict[str, dict[str, str]] = {}
        for page in self.pages:
            self.index.setdefault(page["key"], {})[page["lang"]] = page["path"]

    def ui(self, lang: str, key: str) -> str:
        table = self.cfg.get("ui", {})
        return (
            table.get(lang, {}).get(key)
            or table.get(self.cfg["default_lang"], {}).get(key, key)
        )

    def label(self, entry: tuple, lang: str) -> str:
        key, labels = entry
        return labels.get(lang) or labels.get(self.cfg["default_lang"], key)

    def href(self, from_page: dict, key: str) -> str:
        lang = from_page.get("lang", self.cfg["default_lang"])
        targets = self.index.get(key)
        if not targets:
            raise KeyError(f"Unknown page key={key!r}")
        target = targets.get(lang) or next(iter(targets.values()))
        return rel(from_page["dir"], target)

    def _absolute(self, path: str) -> str:
        return f"{self.cfg['base_url'].rstrip('/')}/{path.lstrip('/')}"

    def _page_url(self, path: str) -> str:
        clean = path.replace("\\", "/").lstrip("/")
        if clean == "index.html":
            clean = ""
        elif clean.endswith("/index.html"):
            clean = clean[: -len("index.html")]
        return f"{self.cfg['base_url'].rstrip('/')}/{clean}"

    def head(self, page: dict) -> str:
        cfg = self.cfg
        lang = page["lang"]
        title = page["title"]
        full_title = title if page.get("title_is_full") else f"{title} · {cfg['name']}"
        desc = page.get("desc", cfg["tagline"])
        canonical = self._page_url(page["path"])
        og_image = self._absolute(cfg.get("og_image", "assets/og.png"))

        alternate_rows: list[str] = []
        locale_name = {"en": "en_US", "zh": "zh_CN"}
        hreflang_name = {"en": "en", "zh": "zh-CN"}
        for code, _labels in cfg.get("langs", []):
            target = self.index.get(page["key"], {}).get(code)
            if target:
                alternate_rows.append(
                    f'<link rel="alternate" hreflang="{hreflang_name.get(code, code)}" '
                    f'href="{esc(self._page_url(target))}">'
                )
        en_target = self.index.get(page["key"], {}).get("en")
        if en_target:
            alternate_rows.append(
                f'<link rel="alternate" hreflang="x-default" '
                f'href="{esc(self._page_url(en_target))}">'
            )
        other_locales = "".join(
            f'<meta property="og:locale:alternate" content="{locale_name[code]}">'
            for code, _labels in cfg.get("langs", [])
            if code != lang and code in locale_name
        )
        return f"""<!DOCTYPE html>
<html lang="{esc(lang)}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{esc(full_title)}</title>
<meta name="description" content="{esc(desc)}">
<meta name="color-scheme" content="light">
<link rel="canonical" href="{esc(canonical)}">
{''.join(alternate_rows)}
<meta property="og:type" content="website">
<meta property="og:site_name" content="{esc(cfg['name'])}">
<meta property="og:title" content="{esc(full_title)}">
<meta property="og:description" content="{esc(desc)}">
<meta property="og:url" content="{esc(canonical)}">
<meta property="og:image" content="{esc(og_image)}">
<meta property="og:image:width" content="1200">
<meta property="og:image:height" content="630">
<meta property="og:image:alt" content="{esc(cfg['tagline'])}">
<meta property="og:locale" content="{locale_name.get(lang, 'en_US')}">
{other_locales}
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="{esc(full_title)}">
<meta name="twitter:description" content="{esc(desc)}">
<meta name="twitter:image" content="{esc(og_image)}">
<meta name="theme-color" content="#EEF1EC">
<link rel="icon" href="{rel(page["dir"], "assets/favicon.svg")}" type="image/svg+xml">
<link rel="icon" href="{rel(page["dir"], "assets/favicon-32.png")}" sizes="32x32">
<link rel="apple-touch-icon" href="{rel(page["dir"], "assets/apple-touch-icon.png")}">
<link rel="stylesheet" href="{asset(page["dir"], "site.css")}">
</head>
<body class="{esc(cfg.get('body_class', ''))} page-{esc(page['key'])}" data-page="{esc(page['key'])}">
<a class="skip" href="#main">{esc(self.ui(lang, "skip"))}</a>
"""

    def _nav_items(self, page: dict, *, mobile: bool = False) -> str:
        items = []
        for key, labels in self.cfg["nav"]:
            active = page["key"] == key
            current = ' aria-current="page"' if active else ""
            label = esc(self.label((key, labels), page["lang"]))
            items.append(
                f'<a class="nav-stop{" is-current" if active else ""}" '
                f'href="{self.href(page, key)}"{current}>'
                '<span class="stop-lamp" aria-hidden="true"></span>'
                f'<span class="nav-stop-label">{label}</span>'
                f'{"" if mobile else icon("arrow", "nav-stop-icon")}'
                "</a>"
            )
        return "".join(items)

    def header(self, page: dict) -> str:
        cfg = self.cfg
        lang = page["lang"]
        en = lang == "en"
        home = rel(page["dir"], self.index["home"].get(lang) or self.index["home"][cfg["default_lang"]])
        brand_sub = "PACKET TRANSIT CONTROL" if en else "数据包运输控制"
        menu_label = "Open menu" if en else "打开菜单"
        close_label = "Close menu" if en else "关闭菜单"
        nav_label = "Primary" if en else "主导航"
        language_label = "Language" if en else "语言"
        repo_label = "Repository" if en else "仓库"
        core_tests = cfg.get("test_counts", {}).get("core", cfg.get("test_count", ""))
        cli_tests = cfg.get("test_counts", {}).get("cli", "")

        language_links = []
        for code, labels in cfg.get("langs", []):
            target = self.index.get(page["key"], {}).get(code) or self.index["home"].get(code)
            if not target:
                continue
            name = self.label((code, labels), lang)
            if code == lang:
                language_links.append(
                    f'<span class="lang-option is-current" aria-current="true">{esc(name)}</span>'
                )
            else:
                language_links.append(
                    f'<a class="lang-option" data-site-language="{code}" href="{rel(page["dir"], target)}">{esc(name)}</a>'
                )

        mobile_nav = self._nav_items(page, mobile=True)
        mobile_langs = "".join(language_links)
        other_lang = "zh" if en else "en"
        other_target = self.index.get(page["key"], {}).get(other_lang) or self.index["home"][other_lang]
        mobile_language = f'<a class="mobile-language" data-site-language="{other_lang}" href="{rel(page["dir"], other_target)}" lang="{other_lang}" aria-label="{("Switch to Chinese" if en else "Switch to English")}">{("中文" if en else "EN")}</a>'

        return f"""<header class="site-header">
  <div class="header-main wrap">
    <a class="brand" href="{home}" aria-label="{esc(cfg['name'])}">
      <span class="brand-mark">{cfg['mark_svg']}</span>
      <span class="brand-copy">
        <span class="brand-name">{esc(cfg['name'])}</span>
        <span class="brand-sub">{esc(brand_sub)}</span>
      </span>
    </a>
    <nav class="primary-nav" aria-label="{nav_label}">
      {self._nav_items(page)}
    </nav>
    <div class="header-tools">
      {mobile_language}
      <div class="langs" aria-label="{language_label}">{''.join(language_links)}</div>
      <a class="repo-link" href="{esc(cfg.get('repo', ''))}" aria-label="{repo_label}">
        {icon("external")}<span>{repo_label}</span>
      </a>
      <button class="menu-toggle" type="button" aria-label="{menu_label}"
        aria-expanded="false" aria-controls="mobile-menu" data-menu-open>
        {icon("menu")}<span>{"Menu" if en else "菜单"}</span>
      </button>
    </div>
  </div>
  <div class="system-strip">
    <div class="wrap system-strip-inner">
      <span><i class="status-lamp is-green" aria-hidden="true"></i>{"Release" if en else "版本"} {esc(cfg.get("release_tag", ""))}</span>
      <span>API {esc(cfg.get("api_version", ""))}</span>
      <span>{"Core tests" if en else "核心测试"} {esc(core_tests)}</span>
      <span>{"CLI tests" if en else "命令行测试"} {esc(cli_tests)}</span>
      <span class="system-strip-end">JVM 17+ · MIT</span>
    </div>
  </div>
</header>
<dialog class="sheet-dialog mobile-menu" id="mobile-menu" aria-labelledby="mobile-menu-title">
  <div class="sheet-head">
    <div>
      <span class="sheet-code">ROUTE MAP</span>
      <p class="sheet-title" id="mobile-menu-title">{"Navigate" if en else "站点导航"}</p>
    </div>
    <button class="icon-button" type="button" aria-label="{close_label}" data-menu-close>
      {icon("close")}
    </button>
  </div>
  <nav class="mobile-nav" aria-label="{nav_label}">{mobile_nav}</nav>
  <div class="sheet-section">
    <span class="sheet-code">{"LANGUAGE" if en else "语言"}</span>
    <div class="langs sheet-langs">{mobile_langs}</div>
  </div>
  <a class="sheet-repo" href="{esc(cfg.get("repo", ""))}">
    {icon("external")}<span>{esc(cfg.get("repo", "").split("//")[-1])}</span>
  </a>
</dialog>
"""

    def footer(self, page: dict) -> str:
        cfg = self.cfg
        lang = page["lang"]
        en = lang == "en"
        home = rel(page["dir"], self.index["home"].get(lang) or self.index["home"][cfg["default_lang"]])
        cols_cfg = cfg["footer"][lang] if lang in cfg["footer"] else cfg["footer"][cfg["default_lang"]]
        columns = []
        for title, links in cols_cfg["columns"]:
            items = "".join(
                f'<li><a href="{self.href(page, h[1:]) if h.startswith("@") else h}">{esc(t)}</a></li>'
                for t, h in links
            )
            columns.append(f'<section class="footer-column"><h2>{esc(title)}</h2><ul>{items}</ul></section>')
        paras = "".join(f"<p>{p}</p>" for p in cols_cfg["paragraphs"])
        repo_url = cfg.get("repo", "")
        tagline = cfg["tagline"] if en else "JVM 多线程下载引擎"
        return f"""</div>
</main>
<footer class="site-footer">
  <div class="footer-route" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i><i></i></div>
  <div class="wrap footer-main">
    <div class="footer-brand">
      <a class="brand" href="{home}">
        <span class="brand-mark">{cfg['mark_svg']}</span>
        <span class="brand-copy"><span class="brand-name">{esc(cfg['name'])}</span>
        <span class="brand-sub">{"PACKET TRANSIT CONTROL" if en else "数据包运输控制"}</span></span>
      </a>
      <p>{esc(tagline)}</p>
      <a class="footer-repo" href="{esc(repo_url)}">
        {icon("external")}<span>{esc(repo_url.split("//")[-1])}</span>
      </a>
    </div>
    <div class="footer-columns">{''.join(columns)}</div>
  </div>
  <div class="wrap footer-base">
    <div class="footer-notes">{paras}</div>
    <p class="footer-stamp">{"CONTROL BOARD" if en else "控制板"} / {esc(cfg.get("release_tag", ""))}</p>
  </div>
</footer>
<script src="{asset(page["dir"], "site.js")}" defer></script>
</body>
</html>
"""

    def render(self, page: dict) -> str:
        body = page["body"]
        if callable(body):
            body = body(page["lang"], lambda key: self.href(page, key))
        body = re.sub(
            r"\{LINK:([a-zA-Z0-9_-]+)\}",
            lambda m: self.href(page, m.group(1)),
            body,
        )
        crumbs = page.get("breadcrumb")
        crumb_html = (
            breadcrumb([(t, self.href(page, k) if k else "") for t, k in crumbs])
            if crumbs
            else ""
        )
        return (
            self.head(page)
            + self.header(page)
            + '<main id="main" class="page">'
            + '<div class="page-content wrap">'
            + crumb_html
            + body
            + self.footer(page)
        )

    def sitemap(self) -> str:
        urls = []
        for page in self.pages:
            if page.get("noindex"):
                continue
            urls.append(
                f"  <url><loc>{esc(self._page_url(page['path']))}</loc>"
                f"<priority>{page.get('priority', '0.6')}</priority></url>"
            )
        return (
            '<?xml version="1.0" encoding="UTF-8"?>\n'
            '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
            + "\n".join(urls)
            + "\n</urlset>\n"
        )

    def robots(self) -> str:
        return (
            "User-agent: *\nAllow: /\n\n"
            f"Sitemap: {self._absolute('sitemap.xml')}\n"
        )

    def not_found(self) -> str:
        lang = self.cfg["default_lang"]
        page = {
            "key": "404",
            "lang": lang,
            "dir": "",
            "path": "404.html",
            "title": self.ui(lang, "notfound_title"),
            "noindex": True,
        }
        links = [
            (self.label((key, labels), lang), self.href(page, key))
            for key, labels in self.cfg["nav"]
        ]
        body = (
            '<section class="not-found">'
            '<span class="error-code">ERR / 404</span>'
            f'<h1>{esc(self.ui(lang, "notfound_title"))}</h1>'
            f'<p class="lead">{esc(self.ui(lang, "notfound_body"))}</p>'
            + buttons(links)
            + "</section>"
        )
        page["body"] = body
        page["desc"] = self.ui(lang, "notfound_body")
        return (
            self.head(page)
            + self.header(page)
            + '<main id="main" class="page"><div class="page-content wrap">'
            + body
            + self.footer(page)
        )

    def build(self) -> int:
        manifest_path = self.out / MANIFEST
        previous: set[str] = set()
        if manifest_path.exists():
            try:
                previous = set(json.loads(manifest_path.read_text(encoding="utf-8")))
            except (ValueError, OSError):
                previous = set()
        written: set[str] = set()

        def emit(path: str, text: str) -> None:
            write(self.out / path, text)
            written.add(path)

        set_asset_fingerprints(self.assets_dir)
        self.out.mkdir(parents=True, exist_ok=True)
        emit(".nojekyll", "")
        for page in self.pages:
            emit(page["path"], self.render(page))
        emit("sitemap.xml", self.sitemap())
        emit("robots.txt", self.robots())
        emit("404.html", self.not_found())

        if self.assets_dir.exists():
            for src in sorted(self.assets_dir.rglob("*")):
                if not src.is_file() or src.name.startswith("."):
                    continue
                rel_path = "assets/" + str(src.relative_to(self.assets_dir)).replace("\\", "/")
                dst = self.out / rel_path
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, dst)
                written.add(rel_path)
                fingerprinted = asset_fingerprint(src.name)
                if fingerprinted and fingerprinted != src.name:
                    fingerprint_path = f"assets/{fingerprinted}"
                    shutil.copy2(src, self.out / fingerprint_path)
                    written.add(fingerprint_path)

        for stale in sorted(previous - written):
            target = self.out / stale
            if target.is_file():
                target.unlink()
        for directory in sorted(
            (path for path in self.out.rglob("*") if path.is_dir()), reverse=True
        ):
            if directory != self.out and not any(directory.iterdir()):
                directory.rmdir()

        write(
            manifest_path,
            json.dumps(sorted(written), ensure_ascii=False, indent=1) + "\n",
        )
        return len(self.pages)


def main() -> int:
    repo_root = Path(__file__).resolve().parent.parent
    cfg = load_content(repo_root)
    site = Site(cfg, repo_root)
    count = site.build()
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ")
    print(f"[build] {cfg['name']}: {count} pages -> {site.out} ({stamp})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
