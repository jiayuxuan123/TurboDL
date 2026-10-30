/* TurboDL site behaviour: navigation, scroll reveal, shard telemetry,
   copy controls and documentation scroll-spy. No external dependencies. */

(function () {
  "use strict";

  var reduceMotion = window.matchMedia
    ? window.matchMedia("(prefers-reduced-motion: reduce)")
    : { matches: false };

  function qsa(root, selector) {
    return Array.prototype.slice.call((root || document).querySelectorAll(selector));
  }

  /* ---------------------------------------------------------------------- */
  /* Engineering rail / mobile drawer                                       */
  /* ---------------------------------------------------------------------- */

  function initNav() {
    var toggle = document.querySelector(".nav-toggle");
    var nav = document.getElementById("side-nav");
    var scrim = document.querySelector(".side-scrim");
    if (!toggle || !nav) return;

    var lastFocused = null;

    function setOpen(open) {
      nav.classList.toggle("open", open);
      document.body.classList.toggle("nav-open", open);
      toggle.setAttribute("aria-expanded", open ? "true" : "false");
      if (open) {
        lastFocused = document.activeElement;
        var first = nav.querySelector("a");
        if (first) first.focus({ preventScroll: true });
      } else if (lastFocused && lastFocused.focus) {
        lastFocused.focus({ preventScroll: true });
      }
    }

    toggle.addEventListener("click", function () {
      setOpen(toggle.getAttribute("aria-expanded") !== "true");
    });

    if (scrim) {
      scrim.addEventListener("click", function () {
        setOpen(false);
      });
    }

    nav.addEventListener("click", function (event) {
      if (event.target.closest("a") && window.innerWidth <= 960) setOpen(false);
    });

    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape" && toggle.getAttribute("aria-expanded") === "true") {
        setOpen(false);
      }
    });

    window.addEventListener("resize", function () {
      if (window.innerWidth > 960) setOpen(false);
    });
  }

  /* ---------------------------------------------------------------------- */
  /* Scroll progress                                                        */
  /* ---------------------------------------------------------------------- */

  function initScrollProgress() {
    var bar = document.getElementById("scroll-progress");
    if (!bar) return;
    var ticking = false;

    function update() {
      var max = document.documentElement.scrollHeight - window.innerHeight;
      var progress = max > 0 ? Math.min(1, Math.max(0, window.scrollY / max)) : 0;
      bar.style.transform = "scaleX(" + progress.toFixed(4) + ")";
      ticking = false;
    }

    function requestUpdate() {
      if (ticking) return;
      ticking = true;
      window.requestAnimationFrame(update);
    }

    window.addEventListener("scroll", requestUpdate, { passive: true });
    window.addEventListener("resize", requestUpdate);
    update();
  }

  /* ---------------------------------------------------------------------- */
  /* Deterministic shard plots                                              */
  /* ---------------------------------------------------------------------- */

  function seed(index) {
    var value = (index * 1664525 + 1013904223) >>> 0;
    return (value % 1000) / 1000;
  }

  function buildShardPlot(host, compact) {
    var lanes = 16;
    var levels = [];
    for (var i = 0; i < lanes; i++) {
      var bar = document.createElement("i");
      bar.style.setProperty("--p", (18 + seed(i + 7) * 38).toFixed(1) + "%");
      host.appendChild(bar);
      levels.push(0.16 + seed(i + 41) * 0.22);
    }

    host.__tick = function () {
      var bars = host.querySelectorAll("i");
      for (var lane = 0; lane < bars.length; lane++) {
        if (levels[lane] < 1) {
          levels[lane] = Math.min(
            1,
            levels[lane] + 0.035 + seed(lane + Math.round(levels[lane] * 113)) * 0.08
          );
        }
        bars[lane].style.setProperty("--p", (levels[lane] * 100).toFixed(1) + "%");
        if (levels[lane] >= 1) {
          levels[lane] = compact ? 1 : 0.1 + seed(lane + 19) * 0.13;
        }
      }
    };
  }

  function initShardPlots() {
    var plots = qsa(document, "[data-strip]");
    if (!plots.length) return;
    plots.forEach(function (plot) {
      buildShardPlot(plot, plot.classList.contains("shard-inline"));
    });
    if (reduceMotion.matches) return;

    window.setInterval(function () {
      if (document.hidden) return;
      plots.forEach(function (plot) {
        if (plot.__tick) plot.__tick();
      });
    }, 680);
  }

  /* ---------------------------------------------------------------------- */
  /* Reveal on scroll                                                       */
  /* ---------------------------------------------------------------------- */

  function initMotion() {
    window.__motionReady = true;
    document.documentElement.classList.add("motion-ready");
    if (reduceMotion.matches) return;

    var targets = qsa(
      document,
      ".canvas > section > *:not(h1):not(.seg-rule), .doc-head > *, .doc-aside"
    );
    if (!targets.length) return;

    targets.forEach(function (element) {
      if (element.classList.contains("hero-bench")) return;
      element.classList.add("rise");
    });

    if (!("IntersectionObserver" in window)) {
      targets.forEach(function (element) {
        element.classList.add("in");
      });
      return;
    }

    var observer = new IntersectionObserver(
      function (entries) {
        var visible = entries
          .filter(function (entry) {
            return entry.isIntersecting;
          })
          .sort(function (a, b) {
            return a.target.getBoundingClientRect().top - b.target.getBoundingClientRect().top;
          });

        visible.forEach(function (entry, index) {
          window.setTimeout(function () {
            entry.target.classList.add("in");
          }, index * 42);
          observer.unobserve(entry.target);
        });
      },
      { rootMargin: "0px 0px -7% 0px", threshold: 0.06 }
    );

    targets.forEach(function (element) {
      observer.observe(element);
    });

    // The first screen must never stay invisible if an observer misses a
    // large or partially clipped target.
    window.setTimeout(function () {
      targets.forEach(function (element) {
        if (element.getBoundingClientRect().top < window.innerHeight * 1.15) {
          element.classList.add("in");
        }
      });
    }, 900);
  }

  /* ---------------------------------------------------------------------- */
  /* Live config switches                                                   */
  /* ---------------------------------------------------------------------- */

  function initSwitches() {
    qsa(document, ".sw").forEach(function (control) {
      control.addEventListener("click", function () {
        var next = control.getAttribute("aria-checked") !== "true";
        control.setAttribute("aria-checked", next ? "true" : "false");
        var band = control.closest(".band");
        if (band) band.classList.toggle("is-on", next);
      });
    });
  }

  /* ---------------------------------------------------------------------- */
  /* Clipboard controls                                                     */
  /* ---------------------------------------------------------------------- */

  function writeClipboard(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      return navigator.clipboard.writeText(text);
    }
    return new Promise(function (resolve, reject) {
      var area = document.createElement("textarea");
      area.value = text;
      area.setAttribute("readonly", "");
      area.style.position = "fixed";
      area.style.opacity = "0";
      document.body.appendChild(area);
      area.select();
      try {
        document.execCommand("copy");
        resolve();
      } catch (error) {
        reject(error);
      } finally {
        area.remove();
      }
    });
  }

  function flashLabel(element, label, original) {
    element.textContent = label;
    window.setTimeout(function () {
      element.textContent = original;
    }, 1600);
  }

  function initCopyButtons() {
    qsa(document, "[data-copy]").forEach(function (button) {
      button.addEventListener("click", function () {
        var source = document.querySelector(button.getAttribute("data-copy"));
        if (!source) return;
        var text = (source.value || source.textContent || "").trim();
        var label = button.querySelector("span:first-child") || button;
        var original = label.textContent;
        writeClipboard(text)
          .then(function () {
            flashLabel(label, button.getAttribute("data-copied") || "Copied", original);
          })
          .catch(function () {
            if (source.select) source.select();
          });
      });
    });

    qsa(document, "[data-code-block]").forEach(function (block) {
      var code = block.querySelector("code");
      if (!code) return;
      var button = document.createElement("button");
      button.type = "button";
      button.className = "code-copy";
      button.textContent = "COPY";
      button.setAttribute("aria-label", "Copy code");
      button.addEventListener("click", function () {
        writeClipboard(code.textContent).then(function () {
          button.classList.add("copied");
          button.textContent = "COPIED";
          window.setTimeout(function () {
            button.classList.remove("copied");
            button.textContent = "COPY";
          }, 1500);
        });
      });
      block.appendChild(button);
    });
  }

  /* ---------------------------------------------------------------------- */
  /* Documentation table of contents                                        */
  /* ---------------------------------------------------------------------- */

  function initDocSpy() {
    var body = document.querySelector(".doc-body");
    var toc = document.querySelector(".doc-aside .toc:not(.doc-nav)");
    if (!body || !toc || !("IntersectionObserver" in window)) return;

    var links = qsa(toc, 'a[href^="#"]');
    if (!links.length) return;
    var byId = {};
    links.forEach(function (link) {
      byId[link.getAttribute("href").slice(1)] = link;
    });

    var observer = new IntersectionObserver(
      function (entries) {
        var active = null;
        entries.forEach(function (entry) {
          if (entry.isIntersecting) active = entry.target;
        });
        if (!active) return;
        links.forEach(function (link) {
          link.classList.remove("active");
          link.removeAttribute("aria-current");
        });
        var link = byId[active.id];
        if (link) {
          link.classList.add("active");
          link.setAttribute("aria-current", "location");
        }
      },
      { rootMargin: "-18% 0px -68% 0px", threshold: 0.01 }
    );

    qsa(body, "h2[id], h3[id]").forEach(function (heading) {
      if (byId[heading.id]) observer.observe(heading);
    });
  }

  function initDocToc() {
    var toc = document.querySelector(".doc-aside details.toc");
    if (!toc || !window.matchMedia) return;
    var mobile = window.matchMedia("(max-width: 760px)");

    function sync() {
      if (mobile.matches) toc.removeAttribute("open");
      else toc.setAttribute("open", "");
    }

    sync();
    if (mobile.addEventListener) mobile.addEventListener("change", sync);
  }

  function boot() {
    initNav();
    initScrollProgress();
    initShardPlots();
    initSwitches();
    initCopyButtons();
    initDocSpy();
    initDocToc();
    initMotion();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
