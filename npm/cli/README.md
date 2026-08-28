# @jiaayuxuan/turbodl

TurboDL 命令行下载器的 NPM 入口（薄包装）。高性能多线程 HTTP(S) 下载，支持断点续传、批量、代理/DoH、NDJSON 输出。

> NPM 包不包含下载逻辑：它负责自动获取并缓存 TurboDL JVM 运行时（JAR），然后原样透传参数。
> 需要 **Java 17+**（[Adoptium](https://adoptium.net/) 一键安装）。

## 安装 / 使用

```bash
# 方式一：全局安装
npm install -g @jiaayuxuan/turbodl
turbodl "https://example.com/file.zip" -o file.zip -c 64

# 方式二：免安装直接跑（首次自动下载运行时到 ~/.turbodl/）
npx @jiaayuxuan/turbodl "https://example.com/file.zip" -o file.zip -c 64

# Agent / 脚本：NDJSON 事件流逐行输出
npx @jiaayuxuan/turbodl "https://example.com/file.zip" -o file.zip -c 256 --json
```

## 常用参数（与 JVM CLI 完全一致）

| 参数 | 说明 |
| --- | --- |
| `-o, --output <路径>` | 输出文件 |
| `-c, --connections <N>` | 每任务连接数（1..256，默认 16） |
| `-l, --limit <大小>` | 限速，如 `10MB` / `500KB` |
| `-H, --header "K: V"` | 附加请求头（可重复） |
| `-p, --proxy <URL>` | 代理，如 `http://127.0.0.1:7890` |
| `--doh <URL>` | DNS over HTTPS |
| `--http-policy auto\|http1\|http2` | HTTP 版本策略（默认 auto） |
| `--json` | NDJSON 事件流（start/progress/completed/failed） |
| `-b, --batch <文件>` | 批量下载（每行 `URL [输出]`） |
| `--insecure` | 忽略 SSL 证书校验 |

完整参数：`npx @jiaayuxuan/turbodl --help`

## 断点续传

中断后重跑同一命令自动从断点继续（分片缓存在输出旁 `.turbodl-parts/`，完成后自动清理）。

## 运行时管理

- 首次运行自动从 GitHub Release 下载 JAR 到 `~/.turbodl/`（走系统代理环境变量 `HTTPS_PROXY`，直连失败自动切 curl）。
- 自带 JAR：设置环境变量 `TURBODL_JAR=/path/to/turbodl-cli-all.jar` 跳过下载。
- 指定 java：设置环境变量 `TURBODL_JAVA=/path/to/java`。

## 退出码

`0` 成功 ｜ `1` 下载失败 ｜ `2` 用法错误/环境缺失

## 引擎

核心为 Kotlin/JVM 多线程下载引擎 [TurboDL](https://github.com/jiayuxuan123/TurboDL)（Range 分片满并发、
HTTP 版本自适应、连接预热、慢启动、分片重试、限速、断点续传、HLS 插件路由）。本包仅是它的 NPM 壳。
