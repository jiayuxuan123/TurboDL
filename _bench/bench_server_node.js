#!/usr/bin/env node
"use strict";

const http = require("node:http");
const { performance } = require("node:perf_hooks");

const args = Object.fromEntries(
  process.argv.slice(2).map((arg) => {
    const [key, ...rest] = arg.replace(/^--/, "").split("=");
    return [key, rest.join("=")];
  }),
);

const port = Number(args.port || 8895);
const sizeMb = Number(args.size || 64);
const rateKb = Number(args.rate || 0);
const total = sizeMb * 1024 * 1024;
const rateBps = rateKb * 1024;

const block = Buffer.allocUnsafe(64 * 1024);
for (let i = 0; i < block.length; i += 1) block[i] = (i * 7 + 13) & 0xff;
const payload = Buffer.allocUnsafe(total);
for (let offset = 0; offset < total; offset += block.length) {
  block.copy(payload, offset, 0, Math.min(block.length, total - offset));
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

const server = http.createServer(async (req, res) => {
  if (req.method !== "GET") {
    res.writeHead(405).end();
    return;
  }

  let start = 0;
  let end = total - 1;
  const rawRange = req.headers.range;
  if (rawRange && rawRange.startsWith("bytes=")) {
    const [a, b] = rawRange.slice(6).split(",")[0].split("-");
    if (a) start = Number(a);
    if (b) end = Number(b);
    if (!Number.isFinite(start) || start < 0 || start >= total) {
      res.writeHead(416, { "Content-Range": `bytes */${total}` }).end();
      return;
    }
    end = Math.min(Number.isFinite(end) ? end : total - 1, total - 1);
  }

  const partial = Boolean(rawRange);
  const length = end - start + 1;
  const headers = {
    "Accept-Ranges": "bytes",
    "Content-Type": "application/octet-stream",
    "Content-Length": String(length),
    "Cache-Control": "no-store",
  };
  if (partial) headers["Content-Range"] = `bytes ${start}-${end}/${total}`;
  res.writeHead(partial ? 206 : 200, headers);

  const chunkSize = 64 * 1024;
  const startedAt = performance.now();
  let sent = 0;
  try {
    while (sent < length) {
      const n = Math.min(chunkSize, length - sent);
      const ok = res.write(payload.subarray(start + sent, start + sent + n));
      sent += n;
      if (!ok) {
        await new Promise((resolve) => res.once("drain", resolve));
      }
      if (rateBps > 0) {
        const expectedElapsed = (sent * 1000) / rateBps;
        const actualElapsed = performance.now() - startedAt;
        if (expectedElapsed > actualElapsed) {
          await sleep(expectedElapsed - actualElapsed);
        }
      }
      if (res.destroyed) break;
    }
    res.end();
  } catch {
    res.destroy();
  }
});

server.keepAliveTimeout = 60_000;
server.requestTimeout = 0;
server.maxConnections = 20_000;
server.listen(port, "127.0.0.1", () => {
  console.log(
    `[bench-node] ${sizeMb}MB @ 127.0.0.1:${port} per-connection=${rateKb || "unlimited"}KB/s`,
  );
});
