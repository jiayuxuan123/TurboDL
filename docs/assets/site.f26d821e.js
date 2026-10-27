/* ============================================================================
   TurboDL 官网脚本（纯原生，无依赖）

   只有五件事，每一件都对应页面上一处真实控件：
   1. 侧栏在窄屏的展开/收起 + 主题切换
   2. 入场动效：首屏两份标题的错位入场、章节里各件内容的浮现
   3. 三处界面碎片是「活的」：首屏那条分片带在走、能力开关能按、名字会一个个点亮
   4. 收尾处那行坐标能一键复制（复制不了就选中让人自己按 Ctrl+C）
   5. 代码块右上角的复制按钮（渐进增强，注入失败就当没这功能）

   纪律：动效只改 transform / opacity / colour；脚本挂掉时页面内容依然完整
   （见 build.py 的 .js 类与 2.5s 兜底），所以这里不做任何"没有脚本就没有内容"的事。
   分片带的推进量用固定序列而不是 Math.random()：同一页面每次刷新长得一样 ——
   这是记号，不是随机的装饰（与 sitekit 里 seg_rule 的取舍同一个理由）。
   ========================================================================= */

(function () {
  "use strict";

  /* ---------------------------------------------------------------- 侧栏 */
  function initChrome() {
    var toggle = document.querySelector(".nav-toggle");
    var nav = document.getElementById("side-nav");
    if (!toggle || !nav) return;
    toggle.addEventListener("click", function () {
      var open = nav.classList.toggle("open");
      toggle.setAttribute("aria-expanded", open ? "true" : "false");
    });
  }

  /* ---------------------------------------------------------------- 确定性抖动 */
  // 一条固定的伪随机序列（线性同余）。用途只有一个：让 16 个分片的进度与厚度不整齐。
  // 不用 Math.random()：一是每次刷新画面要一样（同一页面在不同构建下长得一样），
  // 二是这里根本不是随机数场景，用固定序列说清楚它是版式而不是噪声。
  function jitter(n) {
    var s = (n * 1103515245 + 12345) & 0x7fffffff;
    return ((s >>> 8) % 1000) / 1000;
  }

  /* ---------------------------------------------------------------- 入场动效 */
  function initMotion() {
    window.__motionReady = true;

    // 首屏两份标题（上半份 / 下半份）同时入场：这是签名动作，不做逐件错位。
    var layers = document.querySelectorAll(".hl-layer");
    for (var i = 0; i < layers.length; i++) layers[i].classList.add("in");

    // 章节里的各件内容逐个浮现（不是整块淡入）：章节标题、导语、段落、表格、
    // 代码块、界面碎片各算一件。这里是**通用规则**而不是首页专用的类名清单 ——
    // 下载页、文档页、插件页各自的结构不同，靠类名列举总会有页面漏掉，
    // 而"每个章节的每件内容都有自己的入场"才是这条动效的本意。
    var all = document.querySelectorAll(".canvas > section > *, .canvas > .doc-head > *");
    var targets = [];
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      // 页标题立刻在场（内页的 h1 就是首屏主宰；首页的两份标题由 .hl-layer 直接点亮）
      if (el.tagName === "H1") continue;
      targets.push(el);
    }
    // 分段线也要入场：它不是装饰，是"文件被切开"这件事的记号 ——
    // 让它的几段从左到右依次填上（每段一小段延迟），比整块淡出更贴题，
    // 也让内页有足够的动效元素（内容少的页面本来就没几件东西可动）。
    for (var s = 0; s < targets.length; s++) targets[s].classList.add("rise");

    if (!("IntersectionObserver" in window)) {
      for (var k = 0; k < targets.length; k++) targets[k].classList.add("in");
      return;
    }

    // 同一批（相邻的几件）按顺序错开 70ms，读起来像是被"写上去"的，而不是一起弹出来。
    var io = new IntersectionObserver(
      function (entries) {
        var shown = [];
        for (var i = 0; i < entries.length; i++) {
          if (!entries[i].isIntersecting) continue;
          shown.push(entries[i].target);
          io.unobserve(entries[i].target);
        }
        shown.sort(function (a, b) {
          return a.getBoundingClientRect().top - b.getBoundingClientRect().top;
        });
        shown.forEach(function (el, idx) {
          setTimeout(function () { el.classList.add("in"); }, idx * 70);
        });
      },
      { rootMargin: "0px 0px -8% 0px", threshold: 0.06 }
    );
    for (var m = 0; m < targets.length; m++) io.observe(targets[m]);
  }

  /* ---------------------------------------------------------------- 首屏那条分片带 */
  // 引擎正在做的事：16 个分片各自在推进，快的已经跑完、慢的还在写。
  // 画面是真的（同一个任务的不同分片本来就不同步），分片数就是引擎的默认值。
  function initStrip() {
    var strips = document.querySelectorAll("[data-strip]");
    for (var i = 0; i < strips.length; i++) buildStrip(strips[i]);
  }

  function buildStrip(host) {
    var LANES = 16;
    var bars = [];
    var at = [];
    for (var i = 0; i < LANES; i++) {
      var span = document.createElement("i");
      span.style.width = 100 / LANES + "%";
      span.style.transform = "scaleY(" + (0.34 + jitter(i + 1) * 0.66).toFixed(2) + ")";
      span.style.setProperty("--p", "0%");
      host.appendChild(span);
      bars.push(span);
      at.push(0.06 + jitter(i + 41) * 0.2);
    }

    host._tick = function () {
      var done = 0;
      for (var k = 0; k < LANES; k++) {
        // 已经跑完的分片停在 1；其余继续推进，速度略有差异（真实网络就是这样）
        if (at[k] < 1) at[k] = Math.min(1, at[k] + 0.05 + jitter(k + Math.round(at[k] * 97)) * 0.11);
        if (at[k] >= 1) done++;
        bars[k].style.setProperty("--p", (at[k] * 100).toFixed(1) + "%");
      }
      if (done === LANES) for (var r = 0; r < LANES; r++) at[r] = 0.05 + jitter(r + 13) * 0.12;
    };
    host._tick();
  }

  function startStripTimer() {
    var strips = document.querySelectorAll("[data-strip]");
    if (!strips.length) return;
    if (window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    setInterval(function () {
      if (document.hidden) return;
      for (var i = 0; i < strips.length; i++) if (strips[i]._tick) strips[i]._tick();
    }, 700);
  }

  /* ---------------------------------------------------------------- 能力开关 */
  // 每个开关对应引擎里一个真实的配置字段（字段名写在开关下面那行里），
  // 默认值就是代码里的默认值（true 的保持 on）。键盘 Space / Enter 与点击等效。
  function initBands() {
    var switches = document.querySelectorAll(".sw");
    for (var i = 0; i < switches.length; i++) {
      var sw = switches[i];
      sw.addEventListener("click", function () { flip(this); });
      sw.addEventListener("keydown", function (ev) {
        if (ev.key === " " || ev.key === "Spacebar" || ev.key === "Enter") {
          ev.preventDefault();
          flip(this);
        }
      });
    }
  }

  function flip(sw) {
    var on = sw.getAttribute("aria-checked") !== "true";
    sw.setAttribute("aria-checked", on ? "true" : "false");
    var band = sw.closest(".band");
    if (band) band.classList.toggle("on", on);
  }

  /* ---------------------------------------------------------------- 名字一个个点亮 */
  // 同一时刻只有一个名字是满墨，每两秒换一个。停下来的条件：减少动效偏好、或标签页不可见。
  function initLit() {
    var blocks = document.querySelectorAll("[data-lit]");
    if (!blocks.length) return;
    if (window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      for (var i = 0; i < blocks.length; i++) {
        var first = blocks[i].querySelector("b");
        if (first) first.classList.add("on");
      }
      return;
    }
    var idx = 0;
    var groups = [];
    for (var j = 0; j < blocks.length; j++) {
      var names = blocks[j].querySelectorAll("b");
      if (names.length) groups.push(names);
    }
    function step() {
      if (document.hidden) return;
      for (var g = 0; g < groups.length; g++) {
        var names = groups[g];
        for (var k = 0; k < names.length; k++) names[k].classList.toggle("on", k === idx % names.length);
      }
      idx++;
    }
    step();
    setInterval(step, 2000);
  }

  /* ---------------------------------------------------------------- 分段线的入场 */
  // 分段线是"文件被切开"这件事的记号，所以它的几段从**左到右**依次填上，
  // 像是下载在推进 —— 这段动效只改 opacity/transform，尊重减少动效偏好。
  function initSegRules() {
    var rules = document.querySelectorAll(".seg-rule");
    if (!rules.length) return;
    var reduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    for (var i = 0; i < rules.length; i++) {
      var segs = rules[i].querySelectorAll("i");
      for (var j = 0; j < segs.length; j++) {
        segs[j].style.transition = reduced ? "none" : "opacity 0.5s var(--ease), transform 0.5s var(--ease)";
        segs[j].style.transitionDelay = reduced ? "0s" : (j * 80) + "ms";
        if (!reduced) {
          segs[j].style.opacity = "0";
          segs[j].style.transform = "scaleX(0.2)";
          segs[j].style.transformOrigin = "left center";
        }
      }
    }
    if (reduced) return;
    if (!("IntersectionObserver" in window)) {
      for (var k = 0; k < rules.length; k++) revealSegs(rules[k]);
      return;
    }
    var io = new IntersectionObserver(function (entries) {
      for (var e = 0; e < entries.length; e++) {
        if (!entries[e].isIntersecting) continue;
        revealSegs(entries[e].target);
        io.unobserve(entries[e].target);
      }
    }, { rootMargin: "0px 0px -6% 0px" });
    for (var m = 0; m < rules.length; m++) io.observe(rules[m]);
  }

  function revealSegs(rule) {
    var segs = rule.querySelectorAll("i");
    for (var i = 0; i < segs.length; i++) {
      segs[i].style.opacity = "1";
      segs[i].style.transform = "none";
    }
  }

  /* ---------------------------------------------------------------- 收尾的坐标 */
  function initCopy() {
    var btn = document.querySelector("[data-copy]");
    if (!btn) return;
    var source = document.querySelector(btn.getAttribute("data-copy"));
    if (!source) return;
    var original = btn.textContent;
    btn.addEventListener("click", function () {
      var text = (source.value || source.textContent || "").trim();
      var done = function () {
        btn.textContent = btn.getAttribute("data-copied") || "Copied";
        setTimeout(function () { btn.textContent = original; }, 1600);
      };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(done, function () { select(source); });
      } else {
        select(source);
      }
    });
  }

  function select(el) {
    try {
      el.focus();
      el.select();
      if (document.execCommand) document.execCommand("copy");
    } catch (e) {}
  }

  /* ---------------------------------------------------------------- 启动 */
  function boot() {
    initChrome();
    initStrip();
    startStripTimer();
    initBands();
    initLit();
    initSegRules();
    initCopy();
    initMotion();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
