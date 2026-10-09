#!/usr/bin/env node
/**
 * @turbodl/cli —— TurboDL 命令行下载器的 NPM 薄包装。
 *
 * 设计：NPM 包不重新实现任何下载逻辑，只负责「找到/获取 JAR → 启动 JVM → 原样透传参数与输出」。
 *
 * JAR 解析顺序：
 *  1. 环境变量 TURBODL_JAR 指定的 JAR 路径；
 *  2. 本地缓存 ~/.turbodl/（首次运行自动从 GitHub Release 下载）；
 *  3. 下载失败时给出手动下载指引。
 *
 * 【JAR 名字不能写死】Gradle 的 fatJar 任务把 archiveVersion 设为空，实际产物叫
 * `turbodl-cli-all.jar`；而历史上传到 Release 的文件名可能带版本号。写死任何一个名字都会
 * 在另一边 404，所以这里按「候选列表逐个试」的方式解析，并在全部候选失败后回退到
 * Release API（挑最新一个含 CLI JAR 的发布）。可用 TURBODL_JAR_URL 直接指定地址。
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

/** 本 NPM 包的版本（与仓库版本一致）；`--npm-version` 输出它。 */
const PKG_VERSION = "0.2.0.7";

/**
 * 要拉取的运行时版本。默认与本包同版本，可用 TURBODL_VERSION 覆盖
 * （例如想用某个 rc 版内核，而不必等 NPM 包跟着发）。
 */
const RUNTIME_VERSION = (process.env.TURBODL_VERSION || PKG_VERSION).replace(/^v/, "");

const REPO = "jiayuxuan123/TurboDL";
const RELEASE = `v${RUNTIME_VERSION}`;
const RELEASE_DOWNLOAD_BASE = `https://github.com/${REPO}/releases/download/${RELEASE}`;

/**
 * 候选 JAR 文件名，按可能性排序。
 *
 * 前两个覆盖「同一 Release 里两种可能的命名」；第三个是为历史遗留的 rc 版准备的
 * （那个时期的 npm 包按 `turbodl-cli-<version>-all.jar` 命名）。
 */
const JAR_NAME_CANDIDATES = [
  `turbodl-cli-${RUNTIME_VERSION}-all.jar`,
  "turbodl-cli-all.jar",
];

const CACHE_DIR = path.join(os.homedir(), ".turbodl");

/**
 * 校验地址只能是 http/https，并返回解析好的 URL。
 *
 * 这个包装器会把地址分别交给 `https.get` 和 `curl`，而地址有三个来源：GitHub API 的响应、
 * 环境变量 `TURBODL_JAR_URL`、以及上一级的重定向。三者都不该带出 `file:`、`ftp:` 这类协议 ——
 * 在交给下游工具之前挡掉，比让工具自己去解释更可控。
 */
function assertHttpUrl(raw, what) {
  let u;
  try {
    u = new URL(raw);
  } catch {
    throw new Error(`${what} 不是合法 URL：${String(raw).slice(0, 120)}`);
  }
  if (u.protocol !== "http:" && u.protocol !== "https:") {
    throw new Error(`${what} 只接受 http/https，收到：${u.protocol}`);
  }
  return u;
}

/**
 * 由地址派生缓存文件名。
 *
 * **只取最后一段，并且限定字符集**：Release 的附件名来自远端 JSON，`path.basename` 挡不住
 * 末尾是 `..` 的路径（`https://host/a/..` 的 basename 就是 `..`），直接拼进缓存目录会写到
 * `~/.turbodl` 的上级去。派生的名字必须是普通的 `.jar` 文件名，否则退回固定名。
 */
function cacheNameFor(url) {
  let base = "";
  try {
    base = decodeURIComponent(new URL(url).pathname.split("/").pop() || "");
  } catch {
    base = "";
  }
  return /^[A-Za-z0-9][A-Za-z0-9._-]*\.jar$/.test(base) ? base : "turbodl-cli-all.jar";
}

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

/** 发一个 GET，跟随重定向；返回响应流（调用方负责消费）。 */
function get(url, redirects = 0) {
  return new Promise((resolve, reject) => {
    if (redirects > 5) return reject(new Error("重定向次数过多"));
    https
      .get(
        url,
        { headers: { "User-Agent": "turbodl-npm-wrapper", Accept: "application/vnd.github+json" } },
        (res) => {
          if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
            res.resume();
            return resolve(get(new URL(res.headers.location, url).toString(), redirects + 1));
          }
          resolve(res);
        }
      )
      .on("error", reject);
  });
}

/** 读一个 URL 的全部响应体（带上限，避免异常响应把内存吃光）。 */
function getText(url, limitBytes = 1024 * 1024) {
  return get(url).then(
    (res) =>
      new Promise((resolve, reject) => {
        if (res.statusCode !== 200) {
          res.resume();
          return reject(new Error(`HTTP ${res.statusCode}`));
        }
        let buf = "";
        res.setEncoding("utf8");
        res.on("data", (c) => {
          buf += c;
          if (buf.length > limitBytes) {
            res.destroy();
            reject(new Error("响应过大"));
          }
        });
        res.on("end", () => resolve(buf));
        res.on("error", reject);
      })
  );
}

/** 跟随重定向下载文件。 */
function download(url, dest, redirects = 0) {
  return new Promise((resolve, reject) => {
    if (redirects > 5) return reject(new Error("重定向次数过多"));
    let target;
    try {
      target = assertHttpUrl(url, "下载地址").toString();
    } catch (e) {
      return reject(e);
    }
    https
      .get(target, { headers: { "User-Agent": "turbodl-npm-wrapper" } }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume();
          // 每一跳都重新校验协议，别让一次重定向把非 http(s) 的地址带进来
          let next;
          try {
            next = assertHttpUrl(new URL(res.headers.location, target).toString(), "重定向地址").toString();
          } catch (e) {
            return reject(e);
          }
          return resolve(download(next, dest, redirects + 1));
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

/** 一个 JAR 至少要这么大才可能是真的 fatJar（HTML 错误页/占位文件都不止于此地小）。 */
const MIN_JAR_BYTES = 1024 * 1024;

/** 请求 HEAD，判断某个 URL 是否是可下载的真 JAR。返回 true/false（网络异常视为 false）。 */
function jarUrlLooksValid(url) {
  return get(url)
    .then(
      (res) =>
        new Promise((resolve) => {
          const len = Number(res.headers["content-length"] || 0);
          // 已经跟完重定向；200 + 体积合理才算真。
          const ok = res.statusCode === 200 && (len === 0 || len >= MIN_JAR_BYTES);
          res.resume();
          resolve(ok);
        })
    )
    .catch(() => false);
}

/**
 * 从 Release API 里挑最新一个「带 CLI JAR 附件」的发布。
 *
 * 为什么需要它：`--npm-version` 与运行时版本解耦了。若某个版本的 Release 没上传 CLI JAR
 * （历史上它们就没上传过），与其直接 404，不如回退到最近一个真的有的版本并明确告知用户。
 */
async function findLatestReleaseWithCliJar() {
  const body = await getText(`https://api.github.com/repos/${REPO}/releases?per_page=30`);
  const releases = JSON.parse(body);
  for (const rel of releases) {
    if (rel.draft) continue;
    const asset = (rel.assets || []).find(
      // 附件名来自远端 JSON：字符集收紧到「普通文件名」，别让带分隔符或 `..` 的名字
      // 混进来变成缓存目录的路径穿越（`path.join` 遇到 `..` 是会真的往上走的）。
      (a) => /^turbodl-cli[A-Za-z0-9._-]*\.jar$/.test(a.name) && a.size >= MIN_JAR_BYTES
    );
    if (asset) return { tag: rel.tag_name, url: asset.browser_download_url, name: asset.name };
  }
  return null;
}

/** 用 curl 兜底下载（能自动识别系统代理环境变量）。 */
function downloadWithCurl(url, dest) {
  assertHttpUrl(url, "下载地址");
  const tmp = dest + ".tmp";
  // `--` 之后一律按位置参数处理：地址来自远端 JSON 或环境变量，万一它以 `-` 开头，
  // curl 会把它当成选项执行（`--config`、`-o` 都能改写行为）。
  const r = spawnSync("curl", ["-fSL", "--retry", "2", "-o", tmp, "--", url], { stdio: "inherit" });
  if (r.status !== 0 || !fs.existsSync(tmp) || fs.statSync(tmp).size < MIN_JAR_BYTES) {
    return null;
  }
  fs.renameSync(tmp, dest);
  return dest;
}

/** 解析出可用的 JAR 路径，必要时下载。 */
async function ensureJar() {
  fs.mkdirSync(CACHE_DIR, { recursive: true });

  // 显式指定的地址优先。
  const explicit = process.env.TURBODL_JAR_URL;
  if (explicit) {
    const u = assertHttpUrl(explicit, "TURBODL_JAR_URL");
    return await fetchToCache(u.toString(), path.join(CACHE_DIR, cacheNameFor(u.toString())));
  }

  // 候选名字逐个试（带缓存命中检查）。
  for (const name of JAR_NAME_CANDIDATES) {
    const dest = path.join(CACHE_DIR, name);
    if (fs.existsSync(dest) && fs.statSync(dest).size >= MIN_JAR_BYTES) return dest;
    const url = `${RELEASE_DOWNLOAD_BASE}/${name}`;
    if (await jarUrlLooksValid(url)) return await fetchToCache(url, dest);
  }

  // 回退：找最近一个真的带 CLI JAR 的 Release。
  let fallback = null;
  try {
    fallback = await findLatestReleaseWithCliJar();
  } catch (e) {
    info(`查询 Release 列表失败（${e.message}）`);
  }
  if (fallback) {
    info(`当前发布 ${RELEASE} 未附带 CLI JAR，回退到 ${fallback.tag}（${fallback.name}）`);
    // 附件名虽已按字符集筛过，这里仍然只取"派生出的安全名"，与显式地址走同一条规则。
    return await fetchToCache(fallback.url, path.join(CACHE_DIR, cacheNameFor(fallback.url)));
  }

  console.error(
    `[turbodl] 运行时下载失败：${RELEASE} 的 Release 里没有找到 CLI JAR。\n` +
    `  手动下载后设置环境变量指向它即可：\n` +
    `  发布页：https://github.com/${REPO}/releases\n` +
    `  set TURBODL_JAR=<jar 路径>`
  );
  process.exit(1);
}

/** 下载到缓存并返回路径（先直连，失败再走 curl）。 */
async function fetchToCache(url, dest) {
  if (fs.existsSync(dest) && fs.statSync(dest).size >= MIN_JAR_BYTES) return dest;
  info(`下载 TurboDL 运行时 → ${dest}`);
  try {
    return await download(url, dest);
  } catch (directErr) {
    info(`直连失败（${directErr.message}），改用 curl（自动识别系统代理）…`);
    const got = downloadWithCurl(url, dest);
    if (!got) {
      console.error(`[turbodl] 运行时下载失败。请手动下载后设置环境变量：\n  ${url}\n  set TURBODL_JAR=<jar 路径>`);
      process.exit(1);
    }
    return got;
  }
}

async function main() {
  const args = process.argv.slice(2);
  if (args.includes("--npm-version")) {
    console.log(`@turbodl/cli ${PKG_VERSION}`);
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
