#!/usr/bin/env python3
"""生成站点视觉资产（纯本地，不联网）。

产出（写进 `website/assets/`，`build.py` 再拷进 `docs/assets/`）：

- `logo.svg` / `logo-mark.svg`：品牌标识，手写矢量，无外部依赖；
- `favicon.svg`：站点图标（矢量，现代浏览器直接用）；
- `apple-touch-icon.png`、`favicon-32.png`：栅格兜底（用 PIL 从同一几何画出来）；
- `og.png`：社交分享大图 1200×630（真实版本号 + 真实标语，不是装饰画）。

为什么用代码画而不是找图：TurboDL 仓库**零图片资产**，而设计文档要求"真实、清晰、
像一个正在使用的产品"——那就用能表达产品语义的几何图形（并发下载的多路箭头 /
并行分片），而不是塞一张通用科技感插画。

运行：`python website/make_assets.py [--site turbodl|yunget]`
"""

from __future__ import annotations

import argparse
import io
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
OUT = HERE / "assets"

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyhbd.ttc",
    r"C:\Windows\Fonts\msyh.ttc",
    "/System/Library/Fonts/PingFang.ttc",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]


def font(size: int):
    for path in FONT_CANDIDATES:
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    return ImageFont.load_default()


# --------------------------------------------------------------------------- #
# 品牌标识（几何，两站共用构图、换色）
# --------------------------------------------------------------------------- #

def mark_svg(color: str, size: int = 32) -> str:
    """多路并发下行的抽象：三条并行的流汇聚到一个向下的箭头。"""
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="{size}" height="{size}" role="img" aria-label="TurboDL">
  <g fill="none" stroke="{color}" stroke-width="2.3" stroke-linecap="round">
    <path d="M6 5v9.5M16 3.2v11.3M26 5v9.5"/>
  </g>
  <path d="M16 15.6l6.2 6.2H9.8z" fill="{color}"/>
  <path d="M16 21v7" stroke="{color}" stroke-width="2.3" stroke-linecap="round"/>
</svg>
'''


def turbodl_mark_svg(signal: str = "#c8ff4a", cyan: str = "#62e7e7", background: str | None = None) -> str:
    """TurboDL 专用标记：三块不等长分片汇入一条下行指令。"""
    bg = f'<rect width="40" height="40" fill="{background}"/>' if background else ""
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 40 40" width="40" height="40" role="img" aria-label="TurboDL">
  {bg}
  <path d="M7 8h7v9H7zM16 8h7v15h-7zM25 8h8v6h-8z" fill="{signal}"/>
  <path d="M7 25h18M20 18v17M14 29l6 6 6-6" fill="none" stroke="{cyan}" stroke-width="2"/>
  <path d="M7 20h8" stroke="{signal}" stroke-width="2"/>
</svg>
'''


def draw_turbodl_mark(d: ImageDraw.ImageDraw, ox: int, oy: int, s: float, signal: str, cyan: str) -> None:
    """PIL counterpart of `turbodl_mark_svg` for favicon fallbacks."""
    d.rectangle([ox + 7 * s, oy + 8 * s, ox + 14 * s, oy + 17 * s], fill=signal)
    d.rectangle([ox + 16 * s, oy + 8 * s, ox + 23 * s, oy + 23 * s], fill=signal)
    d.rectangle([ox + 25 * s, oy + 8 * s, ox + 33 * s, oy + 14 * s], fill=signal)
    d.line([(ox + 7 * s, oy + 20 * s), (ox + 15 * s, oy + 20 * s)], fill=signal, width=max(2, int(2 * s)))
    d.line([(ox + 7 * s, oy + 25 * s), (ox + 25 * s, oy + 25 * s)], fill=cyan, width=max(2, int(2 * s)))
    d.line([(ox + 20 * s, oy + 18 * s), (ox + 20 * s, oy + 35 * s)], fill=cyan, width=max(2, int(2 * s)))
    d.line([(ox + 14 * s, oy + 29 * s), (ox + 20 * s, oy + 35 * s)], fill=cyan, width=max(2, int(2 * s)))
    d.line([(ox + 26 * s, oy + 29 * s), (ox + 20 * s, oy + 35 * s)], fill=cyan, width=max(2, int(2 * s)))


def draw_mark(d: ImageDraw.ImageDraw, ox: int, oy: int, s: float, color: str) -> None:
    """把 [mark_svg] 的同一几何用 PIL 画出来（用于需要栅格的场合）。"""
    w = max(2, int(2.3 * s))
    for x, y0, y1 in ((6, 5, 14.5), (16, 3.2, 14.5), (26, 5, 14.5)):
        d.line([(ox + x * s, oy + y0 * s), (ox + x * s, oy + y1 * s)], fill=color, width=w)
    d.polygon(
        [
            (ox + 16 * s, oy + 15.6 * s),
            (ox + 22.2 * s, oy + 21.8 * s),
            (ox + 9.8 * s, oy + 21.8 * s),
        ],
        fill=color,
    )
    d.line([(ox + 16 * s, oy + 21 * s), (ox + 16 * s, oy + 28 * s)], fill=color, width=w)


# --------------------------------------------------------------------------- #
# 各站点的资产
# --------------------------------------------------------------------------- #

def build_turbodl() -> list[str]:
    made = []
    ink = "#eef3ed"
    muted = "#9ba9a1"
    signal = "#c8ff4a"
    cyan = "#62e7e7"
    bg = "#090c0b"

    (OUT / "logo-mark.svg").write_text(
        turbodl_mark_svg(signal, cyan).replace("  \n", "\n"), encoding="utf-8"
    )
    (OUT / "favicon.svg").write_text(
        turbodl_mark_svg(signal, cyan, bg).replace("  \n", "\n"), encoding="utf-8"
    )
    made += ["logo-mark.svg", "favicon.svg"]

    # 矢量首选的现代做法；再给两个栅格兜底（Safari / 旧 Android 浏览器）
    for size, name in ((180, "apple-touch-icon.png"), (32, "favicon-32.png")):
        img = Image.new("RGB", (size, size), bg)
        d = ImageDraw.Draw(img)
        s = size / 40.0
        draw_turbodl_mark(d, 0, 0, s, signal, cyan)
        img.save(OUT / name, "PNG", optimize=True)
        made.append(name)

    # OG 大图：同一套传输工程台视觉，版本号来自已提交的 Release 数据。
    import json

    release = json.loads((HERE / "data" / "releases.json").read_text(encoding="utf-8"))["latest"]["tag"]
    img = Image.new("RGB", (1200, 630), bg)
    d = ImageDraw.Draw(img)
    for x in range(0, 1201, 32):
        d.line([(x, 0), (x, 630)], fill="#111713", width=1)
    for y in range(0, 631, 32):
        d.line([(0, y), (1200, y)], fill="#111713", width=1)
    d.rectangle([0, 0, 1200, 8], fill=signal)
    draw_turbodl_mark(d, 92, 82, 2.9, signal, cyan)
    d.text((236, 96), "TurboDL", font=font(62), fill=ink)
    d.text((96, 226), "Many hands, one file.", font=font(62), fill=ink)
    d.text((96, 304), "Segmented HTTP download engine for the JVM", font=font(34), fill=muted)
    d.text((96, 374), "Range probe  /  16 shards  /  byte-level verification", font=font(27), fill=cyan)
    d.text((96, 462), f"{release}   ·   JVM 17+   ·   MIT", font=font(28), fill=signal)
    d.line([(96, 520), (1104, 520)], fill="#354139", width=1)
    d.text((96, 548), "github.com/jiayuxuan123/TurboDL", font=font(26), fill=muted)
    d.text(
        (830, 548),
        "TRANSMISSION BENCH",
        font=font(20),
        fill=signal,
    )
    img.save(OUT / "og.png", "PNG", optimize=True)
    made.append("og.png")
    return made


def build_yunget() -> list[str]:
    made = []
    accent = "#d2691e"
    ink = "#14181f"

    (OUT / "logo-mark.svg").write_text(mark_svg(accent), encoding="utf-8")
    (OUT / "favicon.svg").write_text(mark_svg(accent), encoding="utf-8")
    made += ["logo-mark.svg", "favicon.svg"]

    for size, name in ((180, "apple-touch-icon.png"), (32, "favicon-32.png")):
        img = Image.new("RGB", (size, size), "#ffffff")
        d = ImageDraw.Draw(img)
        s = size / 32.0
        draw_mark(d, 0, int(-1.5 * s), s * 1.02, accent)
        img.save(OUT / name, "PNG", optimize=True)
        made.append(name)

    img = Image.new("RGB", (1200, 630), "#ffffff")
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 1200, 8], fill=accent)
    d.text((94, 96), "云取 YunGet", font=font(64), fill=ink)
    d.text((94, 200), "网盘分享链接解析与高速下载", font=font(52), fill=ink)
    d.text((94, 290), "夸克 · UC · 迅雷 · 百度 · 123 · 139", font=font(34), fill="#5a6472")
    d.text((94, 352), "分片并发 · 断点续传 · HLS 下载", font=font(34), fill="#5a6472")
    d.text((94, 470), "Android 6.0+   ·   AGPL-3.0", font=font(28), fill="#8b95a5")
    d.line([(94, 528), (1106, 528)], fill="#e3e7ee", width=2)
    d.text((94, 552), "github.com/jiayuxuan123/YunGet", font=font(26), fill=accent)
    img.save(OUT / "og.png", "PNG", optimize=True)
    made.append("og.png")
    return made


def build_screenshots() -> list[str]:
    """把截图压到适合网页的尺寸。

    **只处理调用方明确允许的那两张**：源图里 Login.jpg 含真实账号昵称与容量、
    Link.jpg 含明文签名直链、about.jpg 是上游旧界面 —— 都不上站，因此也不处理。
    """
    allowed = ["Parsing.jpg", "Setting.jpg"]
    src_dir = HERE.parent / "images"
    made = []
    for name in allowed:
        src = src_dir / name
        if not src.exists():
            print(f"  [skip] 缺少源图 {src}")
            continue
        img = Image.open(src)
        # 目标宽度 720 → 展示宽约 340 CSS px，2x 屏也够；同时转成体积更小的 WEBP
        if img.width > 720:
            img = img.resize((720, round(img.height * 720 / img.width)), Image.LANCZOS)
        out = OUT / (Path(name).stem.lower() + ".webp")
        img.convert("RGB").save(out, "WEBP", quality=82, method=6)
        made.append(out.name)
        print(f"  {name} {img.width}x{img.height} → {out.name} {out.stat().st_size // 1024} KB")
    return made


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--site", choices=["turbodl", "yunget"], default="turbodl")
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)

    made = build_turbodl() if args.site == "turbodl" else build_yunget()
    if args.site == "yunget":
        made += build_screenshots()

    print(f"[assets] {args.site}: {len(made)} 个文件 → {OUT}")
    for name in made:
        p = OUT / name
        print(f"  {name}  {p.stat().st_size // 1024} KB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
