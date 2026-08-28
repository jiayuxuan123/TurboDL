#!/usr/bin/env node
/**
 * @turbodl/cli —— TurboDL 命令行下载器的 NPM 薄包装。
 *
 * 设计：NPM 包不重新实现任何下载逻辑，只负责「找到/获取 JAR → 启动 JVM → 原样透传参数与输出」。
 *
 * JAR 解析顺序：
 *  1. 环境变量 TURBODL_JAR 指定的 JAR 路径；
 *  2. 本地缓存 ~/.turbodl/turbodl-cli-<version>.jar（首次运行自动从 GitHub Release 下载）；
 *  3. 下载失败时给出手动下载指引。
 *
 * 所有命令行参数原样透传给 JVM CLI（包括 --json NDJSON 输出），退出码保持一致：
 *   0 成功 / 1 下载失败 / 2 用法错误。
 */
"use strict";

const { spawn, spawnSync } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");
const https = require("https");

const VERSION = "0.2.0-rc7";
const RELEASE = `v${VERSION}`;
const JAR_NAME = `turbodl-cli-${VERSION}-all.jar`;
const JAR_URL = `https://github.com/jiayuxuan123/TurboDL/releases/download/${RELEASE}/${JAR_NAME}`;
const CACHE_DIR = path.join(os.homedir(), ".turbodl");
const CACHE_JAR = path.join(CACHE_DIR, JAR_NAME);

function info(msg) {
  if (!process.argv.includes("--json") && !process.argv.includes("-q") && !process.argv.includes("--quiet")) {
    console.error(`[turbodl] ${msg}`);
  }
}

/** 检查 java 是否可用（JDK/JRE 17+）。 */
function findJava() {
  const exe = process.platform === "win32" ? "java.exe" : "java";
  const probe = spawnSync(exe, ["-version"], { encoding: "utf8" });
  if (probe.error || probe.status !== 0) {
    console.error(
      "[turbodl] 未找到 Java。TurboDL 以 JVM 运行，需要 Java 17+。\n" +
      "  安装指引：https://adoptium.net/  （或设置 JAVA_HOME 后重试）\n" +
      "  也可用环境变量 TURBODL_JAVA 指定 java 可执行文件路径。"
    );
    process.exit(2);
  }
  return process.env.TURBODL_JAVA || exe;
}

/** 跟随重定向下载文件（GitHub Release 会 302 到 objects.githubusercontent.com）。 */
function download(url, dest, redirects = 0) {
  return new Promise((resolve, reject) => {
    if (redirects > 5) return reject(new Error("重定向次数过多"));
    https
      .get(url, { headers: { "User-Agent": "turbodl-npm-wrapper" } }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume();
          return resolve(download(new URL(res.headers.location).toString(), dest, redirects + 1));
        }
        if (res.statusCode !== 200) {
          res.resume();
          return reject(new Error(`HTTP ${res.statusCode}`));
        }
        const total = Number(res.headers["content-length"] || 0);
        let done = 0;
        let lastPct = -1;
        const out = fs.createWriteStream(dest);
        res.on("data", (chunk) => {
          done += chunk.length;
          if (total > 0) {
            const pct = Math.floor((done / total) * 100);
            if (pct !== lastPct) {
              lastPct = pct;
              info(`下载 TurboDL 运行时… ${pct}%`);
            }
          }
        });
        res.pipe(out);
        out.on("finish", () => out.close(() => resolve(dest)));
        out.on("error", reject);
      })
      .on("error", reject);
  });
}

/** 下载 JAR（带 curl 兜底以支持系统代理环境变量）。 */
async function ensureJar() {
  if (fs.existsSync(CACHE_JAR) && fs.statSync(CACHE_JAR).size > 1024 * 1024) return CACHE_JAR;
  fs.mkdirSync(CACHE_DIR, { recursive: true });
  info(`首次运行：下载 TurboDL 运行时 ${RELEASE} → ${CACHE_JAR}`);
  const proxy = process.env.HTTPS_PROXY || process.env.https_proxy || process.env.HTTP_PROXY || process.env.http_proxy;
  try {
    await download(JAR_URL, CACHE_JAR);
  } catch (directErr) {
    info(`直连失败（${directErr.message}），改用 curl（自动识别系统代理）…`);
    const tmp = CACHE_JAR + ".tmp";
    const r = spawnSync("curl", ["-fSL", "--retry", "2", "-o", tmp, JAR_URL], { stdio: "inherit" });
    if (r.status !== 0 || !fs.existsSync(tmp) || fs.statSync(tmp).size < 1024 * 1024) {
      console.error(
        `[turbodl] 运行时下载失败。请手动下载后设置环境变量：\n` +
        `  ${JAR_URL}\n` +
        `  set TURBODL_JAR=<jar 路径>`
      );
      process.exit(1);
    }
    fs.renameSync(tmp, CACHE_JAR);
  }
  return CACHE_JAR;
}

async function main() {
  const args = process.argv.slice(2);
  if (args.includes("--npm-version")) {
    console.log(`@turbodl/cli ${VERSION}`);
    return;
  }

  const java = findJava();
  const jar = process.env.TURBODL_JAR || (await ensureJar());
  if (!fs.existsSync(jar)) {
    console.error(`[turbodl] JAR 不存在：${jar}`);
    process.exit(2);
  }

  // 参数与输出原样透传；退出码保持与 JVM CLI 一致。
  const child = spawn(java, ["-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar", jar, ...args], {
    stdio: "inherit",
  });
  child.on("exit", (code, signal) => {
    if (signal) process.kill(process.pid, signal);
    else process.exit(code ?? 1);
  });
  child.on("error", (e) => {
    console.error(`[turbodl] 启动失败：${e.message}`);
    process.exit(1);
  });
}

main().catch((e) => {
  console.error(`[turbodl] ${e.message}`);
  process.exit(1);
});
