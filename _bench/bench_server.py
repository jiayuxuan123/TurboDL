#!/usr/bin/env python3
"""基准用 HTTP 服务器：支持 Range，可按连接限速。

为什么需要它：回环不限速时两个引擎都受限于 CPU/内存，测不出调度差异。
真实 CDN 的常态是**每条连接各自限速**（例如每连接 200KB/s），
此时"谁能把 N 条连接都跑满"才是吞吐的全部来源 —— 这正是要比的东西。

用法：
    python bench_server.py --port 8793 --size 64 --rate 200
      --size 文件大小(MB)  --rate 每连接限速(KB/s，0=不限)
"""
import argparse
import threading
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PAYLOAD = b""
RATE_KB = 0


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):  # 静音
        pass

    def do_GET(self):
        size = len(PAYLOAD)
        rng = self.headers.get("Range")
        start, end = 0, size - 1
        if rng and rng.startswith("bytes="):
            spec = rng[6:].split(",")[0]
            a, _, b = spec.partition("-")
            try:
                if a:
                    start = int(a)
                if b:
                    end = int(b)
            except ValueError:
                pass
            if start >= size:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return

        length = end - start + 1
        is_partial = rng is not None
        self.send_response(206 if is_partial else 200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if is_partial:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()

        # 分块发送 + 每连接限速
        chunk = 64 * 1024
        pos = start
        budget_per_sec = RATE_KB * 1024
        t0 = time.monotonic()
        sent = 0
        try:
            while pos <= end:
                n = min(chunk, end - pos + 1)
                self.wfile.write(PAYLOAD[pos:pos + n])
                pos += n
                sent += n
                if budget_per_sec > 0:
                    # 按"应耗时间"对齐，避免累积漂移
                    expected = sent / budget_per_sec
                    actual = time.monotonic() - t0
                    if expected > actual:
                        time.sleep(expected - actual)
        except (BrokenPipeError, ConnectionResetError):
            pass


def main():
    global PAYLOAD, RATE_KB
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8793)
    ap.add_argument("--size", type=int, default=64, help="MB")
    ap.add_argument("--rate", type=int, default=0, help="每连接 KB/s，0=不限")
    a = ap.parse_args()

    total = a.size * 1024 * 1024
    # 可压缩但不可预测的填充（避免两边被 zlib 影响）
    block = bytes((i * 7 + 13) & 0xFF for i in range(65536))
    reps = total // len(block) + 1
    PAYLOAD = (block * reps)[:total]
    RATE_KB = a.rate

    srv = ThreadingHTTPServer(("127.0.0.1", a.port), Handler)
    srv.daemon_threads = True
    print(f"[bench] {a.size}MB @ 127.0.0.1:{a.port} 每连接限速={a.rate or '不限'}KB/s", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
