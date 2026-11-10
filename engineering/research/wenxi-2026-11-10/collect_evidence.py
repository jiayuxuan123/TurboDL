"""Index the pinned public reference and collect the local review evidence."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import re

REF = "28e7b4576f5c3a600a44240967c03e21ef579a1f"
BASE = f"https://github.com/z7786/wenxi/blob/{REF}/"
HERE = Path(__file__).resolve().parent

PROVIDERS = [
    ("pan115", "115", 16, True, "share/app downurl; personal pickcode", "download"),
    ("baidu", "百度", 8, True, "public parser / video preview / account relay fallback", "download"),
    ("quark", "夸克", 512, True, "optional guest/share route; authenticated temporary transfer", "resolveFile"),
    ("uc", "UC", 512, True, "guest / web / authorized-original TV route", "download"),
    ("xunlei", "迅雷", 64, True, "personal/share file route; host overload policy", "download"),
    ("pan123", "123", 64, True, "share transfer; personal download_info", "download"),
    ("guangya", "光鸭", 64, True, "share/personal userres download_url", "download"),
    ("aliyun", "阿里", 8, True, "original-file download; separate OpenList OAuth playback", "download"),
    ("c139", "移动", 64, True, "share transfer; personal/family download route", "download"),
    ("tianyi", "天翼", 64, True, "personal/family/share file download", "download"),
    ("ilanzou", "蓝奏优享", 64, False, "personal authenticated redirect", "download"),
    ("weiyun", "微云", 64, False, "personal file download", "download"),
    ("wopan", "联通", 64, True, "personal/family/share handling; alternate download_url", "download"),
    ("lanzou", "蓝奏", "global:64", True, "HTML/AJAX share; personal session", "download"),
    ("ctfile", "城通", 8, True, "share info + personal/web route", "download"),
    ("feijipan", "小飞机", 8, True, "share route; encrypted personal redirect", "download"),
    ("pikpak", "PikPak", 8, True, "authenticated personal; share temporary transfer", "download"),
    ("github", "GitHub", 8, True, "repository file / source archive / release asset", "download"),
]

MODULES = [
    ("M01", "应用组合和启动", "lib/app_services.dart", "AppServices", "平台桥接、网盘、下载、播放、资源与更新的组合根", "YunGet 应用层", "入口追踪"),
    ("M02", "导航与布局", "lib/main.dart", "LayoutBuilder", "820dp 导航切换、页面保活与平台外壳", "仅借鉴交互原则", "关键段阅读"),
    ("M03", "18 类网盘与能力", "lib/data/cloud_repository.dart", "class CloudRepository", "浏览/取链/刷新/上传/转存统一契约与账号绑定", "云取网盘仓库", "关键链路阅读"),
    ("M04", "分享与原文件路线", "lib/data/providers/cookie_cloud.dart", "class CookieCloudConnector", "游客、账号和回退链；UC 原文件尺寸验证", "云取来源策略", "关键链路阅读"),
    ("M05", "下载任务总控", "lib/download/download_manager.dart", "class DownloadManager", "任务/写入者生命周期、暂停删除、校验保存分队列", "应用层调度", "关键链路阅读"),
    ("M06", "原生通信", "lib/platform/native_engine.dart", "abstract class NativeTransport", "Android JNI 与桌面 stdio；序号、超时、重启", "可选后端 SPI", "关键段和运行验证"),
    ("M07", "HTTP 数据面", "native/gopeed/internal/protocol/http/fetcher.go", "func (f *Fetcher) fetch", "按偏移写盘、重试、重定向缓存与传输计数", "TurboDL 内部实现", "关键段和运行验证"),
    ("M08", "连接/来源预算", "native/gopeed/internal/protocol/http/asterlink_policy.go", "func effectiveConnections", "64/256KiB 分片预算、每主机512/进程768、来源上限", "通用预算+宿主配置", "关键段和运行验证"),
    ("M09", "慢尾段", "native/gopeed/internal/protocol/http/asterlink_tail.go", "tailWarmup", "750ms 观察、3s ETA 与可取消空闲 helper", "TurboDL 尾段成本模型", "完整文件和运行验证"),
    ("M10", "主机背压", "native/gopeed/internal/protocol/http/asterlink_host_limit.go", "type adaptiveHostLimiter", "共享过载窗口，已有活动连接计入预算", "跨任务主机预算", "完整文件和运行验证"),
    ("M11", "持久化/任务代次", "native/gopeed/bind/asterlink/main.go", "func Begin", "稳定任务标识、过期事件隔离与暂停后替换请求", "SDK 与 App 生命周期", "关键段和运行验证"),
    ("M12", "HLS 下载", "lib/download/hls.dart", "class HlsPlaylist", "VOD/单 init map；拒绝加密、ByteRange 与直播", "保留现有 TurboDL 能力", "完整解析器与调用链阅读"),
    ("M13", "BT", "native/gopeed/bind/asterlink/torrent.go", "func", "元数据边界、单 BT 队列、共享下载和播放会话", "独立插件候选", "入口/策略追踪，未做 BT 负载测试"),
    ("M14", "ED2K", "native/gopeed/internal/ed2kcore/ASTERLINK_PATCHES.md", "# Embedded", "Kelpie 适配、MD4/AICH、原生保存队列与懒启动网络", "独立插件候选", "补丁说明和适配边界阅读，未联网验收"),
    ("M15", "Android 文件目标", "android/app/src/main/kotlin/com/asterlink/app/download/DownloadTargetStore.kt", "class DownloadTargetStore", "FD lease、按偏移写、原生摘要、fsync 和发布日志", "云取存储出口", "关键实现阅读"),
    ("M16", "空间预算", "lib/download/space_budget.dart", "class SpaceBudget", "按卷预留、复制成本与运行期扣减", "云取存储预算", "完整文件阅读"),
    ("M17", "状态与凭据", "lib/data/state_store.dart", "class StateStore", "原子快照/备份恢复；当前 v2 为校验和明文 JSON", "保留云取现有凭据加密", "关键实现阅读"),
    ("M18", "签名动态来源", "lib/data/sources/catalog.dart", "class SourceCatalog", "RSA-SHA256、有效期、修订和包摘要", "插件分发差分审计", "关键实现阅读"),
    ("M19", "JS 运行限制", "lib/data/sources/runtime.dart", "class SourceRuntime", "QuickJS+Isolate、取消、消息和时间/内存上限", "SDK JS 宿主差分审计", "关键实现阅读"),
    ("M20", "网络权限", "lib/domain/download_network_policy.dart", "class DownloadNetworkPolicy", "域名/端口/协议、DNS 公网地址和逐跳凭据策略", "SDK 插件网络权限", "关键实现阅读"),
    ("M21", "播放缓存", "lib/playback/stream_proxy.dart", "class PlaybackStreamProxy", "本地 Range 代理、seek 取消、窗口淘汰与来源刷新", "应用播放层", "关键实现阅读"),
    ("M22", "播放资源预算", "lib/playback/playback_buffer_policy.dart", "class PlaybackBufferPolicy", "手机96MiB/桌面320MiB，按时长和码率控制前读", "应用播放层，必须本机重测", "关键实现阅读"),
    ("M23", "定时停止", "lib/playback/playback_sleep_timer.dart", "class PlaybackSleepTimer", "到时/本集结束、退出和重新播放语义", "可选产品功能", "完整文件阅读"),
    ("M24", "上传和目录", "lib/upload/upload_manager.dart", "class UploadManager", "单独上传状态/队列、目录递归、取消与重试", "应用层可选功能", "入口与关键段阅读"),
    ("M25", "设备互联", "lib/lan/lan_server.dart", "Future<void> _handle", "局域网、配对令牌、Range 文件服务和反向链路", "后续独立功能", "入口与关键段阅读"),
    ("M26", "资源搜索", "lib/data/client_search_engine.dart", "class ClientSearchEngine", "签名规则、设备搜索、公开频道和后台补搜", "后续独立功能", "入口与关键段阅读"),
    ("M27", "在线公告/更新", "lib/data/remote_control_service.dart", "class RemoteControlService", "分节缓存/过期、能力开关与更新回退", "现有社区/更新差分检查", "关键实现阅读"),
    ("M28", "视觉与低功耗", "lib/ui/liquid_glass.dart", "class LiquidGlassSurface", "高对比/低功耗关闭 shader、缓存与降级", "学习降级方式，保持独立视觉", "关键实现阅读"),
    ("M29", "诊断和遥测", "lib/diagnostics/diagnostic_bundle.dart", "class DiagnosticBundle", "诊断包、脱敏、分阶段指标；供应商遥测单独处理", "完善现有日志", "入口与预算追踪"),
    ("M30", "后台服务", "android/app/src/main/kotlin/com/asterlink/app/FlutterDownloadService.kt", "class FlutterDownloadService", "前台服务、隐私通知、用户暂停和资源保护", "云取后台服务差分检查", "关键实现阅读，未真机验证"),
]

ADOPTION = [
    ("A01", "P0", "来源策略快照", "YunGet", "app/src/main/kotlin/com/yunget/app/data/download/TurboDownloadManager.kt", "M03 M04 M08", "账号/文件/来源版本绑定；宿主选并发、刷新及请求窗口，SDK 不硬编码网盘名", "同文件/账号/链路，取链时间与实际连接分开记录；旧任务保留快照", "有界改动，先对照现有实现"),
    ("A02", "P0", "跨任务主机预算", "TurboDL", "turbodl-core/src/main/kotlin/dev/turbodl/core/ConnectionBudget.kt", "M08 M10", "可选共享资源域；probe/媒体/key 与多个 client 的租约同域，取消不泄漏名额", "两个任务/两个client/插件混合，来源独立限速与429/503；SHA正确且可取消", "兼容方案设计，未实施"),
    ("A03", "P1", "尾段请求成本", "TurboDL", "turbodl-core/src/main/kotlin/dev/turbodl/core/SegmentScheduler.kt", "M09", "ETA 与观测窗口决定是否值得拆，避免极速尾段增加大量 Range", "均速/慢连接/高RTT/聚合限速 A/B；同时验请求数与耗时，回退不超过5%", "优先实验；本批已修小尾段粒度"),
    ("A04", "P1", "请求窗口与断点网格解耦", "TurboDL+YunGet", "turbodl-core/src/main/kotlin/dev/turbodl/core/SegmentDownloader.kt", "M04 M08", "由宿主声明来源可接受的请求窗口，断点区间仍保持稳定", "短读/Range忽略/206错位/重启；同文件完整SHA；服务端QPS与吞吐共同验收", "方案，不直接复制来源特例"),
    ("A05", "P1", "原生文件目标可选适配", "YunGet", "app/src/main/kotlin/com/yunget/app/data/download/TurboDownloadManager.kt", "M15 M16", "可选择直接写已授权目标，避免缓存到公共目录的完整二次拷贝；旧File路径保留", "MediaStore/SAF/不可seek/撤权/满盘/强杀；发布日志先后与资源关闭；A/B写盘成本", "收益原型后才接生产"),
    ("A06", "P1", "校验保存独立调度", "YunGet", "app/src/main/kotlin/com/yunget/app/data/download/DownloadPostProcessor.kt", "M05 M15", "网络完成、校验排队、保存、清理分阶段；避免慢导出堵住后续下载", "并发小/大文件；下载结束到文件可见时延、CPU和内存；失败不伪报完成", "先补分阶段诊断"),
    ("A07", "P1", "完整连接和流量统计", "YunGet", "app/src/main/kotlin/com/yunget/app/data/download/Aria2Readout.kt", "M05 M07", "请求上限/在飞/等待/传输字节/恢复字节分开；不使用稀疏文件逻辑长度测速", "真实服务端计数、短任务和续传；UI值追到执行参数", "aria2桥接已修，继续覆盖其他后端"),
    ("A08", "P1", "任务代次与账号所有权", "YunGet", "app/src/main/kotlin/com/yunget/app/data/download/TurboDownloadManager.kt", "M03 M11", "账号切换、旧回调、重复提交和地址替换使用一致的owner/generation规则", "暂停/删除/账号切换时故意延迟旧结果；不能污染新任务或覆盖凭据", "差分审计已有稳定键和事件映射"),
    ("A09", "P1", "插件权限贯穿数据连接", "TurboDL", "turbo-plugin-js/src/main/kotlin", "M18 M19 M20", "签名目录、降级保护、JS预算和下载网络权限一起核对，不只保护解析HTTP", "DNS变化、跳转来回、伪造头、脚本超时、旧版能力缺失；全部拒绝路径确定", "差分审计既有机制"),
    ("A10", "P2", "边下边播和缓存复用", "YunGet", "app/src/main/kotlin/com/yunget/app", "M21 M22 M23", "播放器读已确认区间；seek可取消；续播/定时停止/字幕和投屏分别可选", "同缓存复用字节量、seek首帧、内存峰值、退出泄漏；真机另验", "先确认现有播放器能力，独立外观"),
    ("A11", "P2", "协议插件补齐", "TurboDL", "turbo-plugin-runtime/src/main/kotlin", "M12 M13 M14", "BT/ED2K等走可选插件与资源域，不让HTTP用户承担所有常驻资源", "自有种子/本地peer/坏块/暂停/停止/退出；逐平台构建和资源证明", "分批实施，不改变本批范围"),
    ("A12", "P2", "文件管理与扩展功能", "YunGet", "app/src/main/kotlin/com/yunget/app", "M24 M25 M26 M27", "自然排序/分页/上传/家庭云/局域网/资源搜索逐项定义；公告继续接自己的后台", "能力表驱动入口；无能力不显示死按钮；各任务独立验收", "按实际需求选，不一次照搬"),
]


def write_csv(name, columns, rows):
    with (HERE / name).open("w", newline="", encoding="utf-8-sig") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns)
        writer.writeheader()
        writer.writerows(rows)


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def line(path, needle):
    return next((i + 1 for i, value in enumerate(path.read_text(encoding="utf-8").splitlines()) if needle in value), 1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--bench", type=Path, required=True)
    args = parser.parse_args()
    source = args.source.resolve()
    rows = json.loads((args.evidence / "source-inventory.json").read_text(encoding="utf-8"))
    deep_files = {entry[2] for entry in MODULES}
    source_rows = []
    for entry in rows:
        path = source / entry["path"]
        source_rows.append({**entry, "sha256": sha(path), "review_level": "关键实现/入口追踪" if entry["path"] in deep_files else "结构索引/按模块归属", "source_url": BASE + entry["path"]})
    write_csv("SOURCES.csv", ["path", "lines", "bytes", "sha256", "review_level", "source_url"], source_rows)
    module_rows = []
    for key, title, path, needle, lesson, target, coverage in MODULES:
        if not (source / path).exists():
            raise FileNotFoundError(path)
        first = line(source / path, needle)
        module_rows.append(dict(id=key, module=title, source=path, line=first, source_url=BASE + path + f"#L{first}", lesson=lesson, target=target, coverage=coverage))
    write_csv("MODULES.csv", list(module_rows[0]), module_rows)
    provider_rows = []
    for key, title, connections, share, route, method in PROVIDERS:
        path = f"lib/data/providers/{key}.dart"
        text = (source / path).read_text(encoding="utf-8")
        first = line(source / path, method + "(")
        provider_rows.append(dict(id=key, provider=title, connections_default=connections, share_parsing=share,
                                  route=route, source=path, line=first, source_url=BASE + path + f"#L{first}",
                                  own_download_entry=bool(re.search(r"Future<DownloadSpec>\s+download\(", text)),
                                  account_runtime="未使用真实账号验收", notes="默认值仍受来源/文件大小/任务快照/原生上限约束"))
    write_csv("PROVIDERS.csv", list(provider_rows[0]), provider_rows)
    adoption_rows = [dict(zip(["id", "priority", "item", "owner", "entry", "reference_modules", "implementation", "acceptance", "status"], x)) for x in ADOPTION]
    write_csv("ADOPTION.csv", list(adoption_rows[0]), adoption_rows)
    tests = {}
    for name in ["http-tests.jsonl", "recovery-tests.jsonl"]:
        events = []
        for value in (args.evidence / name).read_text(encoding="utf-8-sig").splitlines():
            try:
                events.append(json.loads(value))
            except json.JSONDecodeError:
                pass
        tests[name] = {"top_level_pass": sum(x["Action"] == "pass" and bool(x.get("Test")) and "/" not in x["Test"] for x in events),
                       "subtest_pass": sum(x["Action"] == "pass" and "/" in x.get("Test", "") for x in events),
                       "failed_events": sum(x["Action"] == "fail" for x in events),
                       "skipped_events": sum(x["Action"] == "skip" for x in events), "sha256": sha(args.evidence / name)}
    evidence = {"reference_commit": REF, "version_check_url": "https://api.github.com/repos/z7786/wenxi/branches/main",
        "review_date": "2026-11-10", "indexed_files": len(source_rows), "indexed_lines": sum(x["lines"] for x in source_rows),
        "dart_application_files": sum(x["path"].startswith("lib/") for x in source_rows),
        "coverage_note": "模块全景与关键调用链审查；SOURCES.csv 的结构索引项不等于逐行/运行验收。第三方 vendor 不逐行审查。",
        "runtime_platform": "Windows x64", "go_version": "1.25.7", "java_version": "21.0.12",
        "upstream_tests": tests,
        "queue_check": json.loads((args.evidence / "queue-result.json").read_text()),
        "header_check": json.loads((args.evidence / "header-result.json").read_text()),
        "native_binary_sha256": sha(args.evidence.parent / "wenxi-helper.exe"),
        "flutter_test_status": "BLOCKED: offline resolver missing pinyin; no new framework/dependencies downloaded",
        "flutter_resolution_log_sha256": sha(args.evidence / "flutter-offline-resolve.log"),
        "ab_final": json.loads((args.bench / "range-ab-final/summary.json").read_text()),
        "reference_comparison": json.loads((args.bench / "wenxi-compare-final/summary.json").read_text()),
        "raw_reference_evidence": str(args.evidence.resolve()), "raw_benchmark_evidence": str(args.bench.resolve()),
        "network": "local fixtures only; GitHub metadata for source revision; no account/private URL requests",
    }
    artifact = args.bench / "candidate-artifact.json"
    if artifact.exists():
        evidence["local_candidate"] = json.loads(artifact.read_text(encoding="utf-8"))
    (HERE / "EVIDENCE.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"indexed={len(source_rows)} modules={len(module_rows)} providers={len(provider_rows)} adoption_cards={len(adoption_rows)}")


if __name__ == "__main__":
    main()
