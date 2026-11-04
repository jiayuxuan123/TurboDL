/* TurboDL site behaviour: modal navigation, document contents, copy controls,
   and runtime switches. Content is visible without JavaScript. */

(function () {
  "use strict";

  var reducedMotion = window.matchMedia
    ? window.matchMedia("(prefers-reduced-motion: reduce)")
    : { matches: false };
  var inertStore = new Map();
  var lastTrigger = null;

  function all(root, selector) {
    return Array.prototype.slice.call((root || document).querySelectorAll(selector));
  }

  function focusable(dialog) {
    return all(
      dialog,
      'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), ' +
        'textarea:not([disabled]), summary, [tabindex]:not([tabindex="-1"])'
    ).filter(function (element) {
      return !element.hidden && element.getClientRects().length > 0;
    });
  }

  function setBackgroundInert(dialog, inert) {
    var node = dialog;
    while (node && node !== document.body) {
      var parent = node.parentElement;
      if (!parent) break;
      Array.prototype.forEach.call(parent.children, function (sibling) {
        if (sibling === node || sibling.tagName === "SCRIPT") return;
        if (inert) {
          if (!inertStore.has(sibling)) {
            inertStore.set(sibling, sibling.inert);
          }
          sibling.inert = true;
        } else if (inertStore.has(sibling)) {
          sibling.inert = inertStore.get(sibling);
          inertStore.delete(sibling);
        }
      });
      node = parent;
    }
  }

  function openDialog(dialog, trigger) {
    if (!dialog || dialog.open) return;
    lastTrigger = trigger || document.activeElement;
    if (typeof dialog.showModal === "function") {
      dialog.showModal();
    } else {
      dialog.setAttribute("open", "");
    }
    document.body.classList.add("dialog-open");
    setBackgroundInert(dialog, true);
    var first = focusable(dialog)[0];
    if (first) first.focus({ preventScroll: true });
  }

  function closeDialog(dialog) {
    if (!dialog || !dialog.open) return;
    if (typeof dialog.close === "function") dialog.close();
    else dialog.removeAttribute("open");
    setBackgroundInert(dialog, false);
    inertStore.clear();
    document.body.classList.remove("dialog-open");
    if (lastTrigger && lastTrigger.focus) {
      lastTrigger.focus({ preventScroll: true });
    }
  }

  function trapFocus(event) {
    if (event.key !== "Tab") return;
    var dialog = event.currentTarget;
    var items = focusable(dialog);
    if (!items.length) {
      event.preventDefault();
      return;
    }
    var first = items[0];
    var last = items[items.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  function initDialog(dialog, openSelector, closeSelector) {
    if (!dialog) return;
    all(document, openSelector).forEach(function (button) {
      button.addEventListener("click", function () {
        openDialog(dialog, button);
      });
    });
    all(dialog, closeSelector).forEach(function (button) {
      button.addEventListener("click", function () {
        closeDialog(dialog);
      });
    });
    dialog.addEventListener("keydown", trapFocus);
    dialog.addEventListener("cancel", function (event) {
      event.preventDefault();
      closeDialog(dialog);
    });
    dialog.addEventListener("close", function () {
      setBackgroundInert(dialog, false);
      inertStore.clear();
      document.body.classList.remove("dialog-open");
    });
    dialog.addEventListener("click", function (event) {
      if (event.target !== dialog) return;
      var rect = dialog.getBoundingClientRect();
      if (
        event.clientX < rect.left ||
        event.clientX > rect.right ||
        event.clientY < rect.top ||
        event.clientY > rect.bottom
      ) {
        closeDialog(dialog);
      }
    });
  }

  function initMenus() {
    var menu = document.getElementById("mobile-menu");
    var menuButton = document.querySelector("[data-menu-open]");
    initDialog(menu, "[data-menu-open]", "[data-menu-close]");
    if (menuButton) {
      menu.addEventListener("close", function () {
        menuButton.setAttribute("aria-expanded", "false");
      });
      menuButton.addEventListener("click", function () {
        menuButton.setAttribute("aria-expanded", "true");
      });
    }

    initDialog(
      document.getElementById("doc-toc-dialog"),
      "[data-doc-toc-open]",
      "[data-doc-toc-close]"
    );
  }

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

  function copyLabel(button) {
    return button.querySelector(".copy-label") || button.querySelector("span") || button;
  }

  function flashCopied(button) {
    var label = copyLabel(button);
    var original = label.textContent;
    label.textContent = document.documentElement.lang === "zh" ? "已复制" : "Copied";
    button.classList.add("is-copied");
    window.setTimeout(function () {
      label.textContent = original;
      button.classList.remove("is-copied");
    }, 1500);
  }

  function initCopyControls() {
    all(document, "[data-copy-code]").forEach(function (button) {
      var block = button.closest("[data-code-block]");
      var code = block && block.querySelector("code");
      if (!code) return;
      labelForLanguage(button);
      button.addEventListener("click", function () {
        writeClipboard(code.textContent).then(function () {
          flashCopied(button);
        });
      });
    });

    all(document, "[data-copy]").forEach(function (button) {
      labelForLanguage(button);
      button.addEventListener("click", function () {
        var source = document.querySelector(button.getAttribute("data-copy"));
        if (!source) return;
        var text = (source.value || source.textContent || "").trim();
        writeClipboard(text).then(function () {
          flashCopied(button);
        });
      });
    });
  }

  function labelForLanguage(button) {
    var zh = document.documentElement.lang === "zh";
    var label = copyLabel(button);
    if (label && label.classList.contains("copy-label")) {
      label.textContent = zh ? "复制" : "Copy";
    } else if (button.hasAttribute("data-copy")) {
      label.textContent = zh ? "复制" : "Copy";
      button.setAttribute("aria-label", zh ? "复制" : "Copy");
    } else if (label && label.tagName !== "SPAN") {
      button.setAttribute("aria-label", zh ? "复制" : "Copy");
    }
  }

  function initSwitches() {
    all(document, '[role="switch"]').forEach(function (control) {
      function render() {
        var on = control.getAttribute("aria-checked") === "true";
        var label = control.querySelector(".switch-label");
        if (label) {
          label.textContent =
            control.getAttribute(on ? "data-on-label" : "data-off-label") || "";
        }
      }
      render();
      control.addEventListener("click", function () {
        control.setAttribute(
          "aria-checked",
          control.getAttribute("aria-checked") === "true" ? "false" : "true"
        );
        render();
      });
    });
  }

  function initDocSpy() {
    var body = document.querySelector(".doc-body");
    if (!body || !("IntersectionObserver" in window)) return;
    var links = all(document, '.toc a[href^="#"]');
    if (!links.length) return;
    var byId = {};
    links.forEach(function (link) {
      var id = link.getAttribute("href").slice(1);
      if (!byId[id]) byId[id] = [];
      byId[id].push(link);
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
        (byId[active.id] || []).forEach(function (link) {
          link.classList.add("active");
          link.setAttribute("aria-current", "location");
        });
      },
      { rootMargin: "-18% 0px -68% 0px", threshold: 0.01 }
    );

    all(body, "h2[id], h3[id]").forEach(function (heading) {
      if (byId[heading.id]) observer.observe(heading);
    });
  }

  function initManualNav() {
    var nav = document.querySelector(".manual-nav");
    if (!nav || !window.matchMedia) return;
    var mobile = window.matchMedia("(max-width: 980px)");

    function sync() {
      if (mobile.matches) nav.removeAttribute("open");
      else nav.setAttribute("open", "");
    }

    sync();
    if (mobile.addEventListener) mobile.addEventListener("change", sync);
  }

  function init() {
    document.documentElement.classList.add("js");
    initMenus();
    initCopyControls();
    initSwitches();
    initDocSpy();
    initManualNav();
    if (reducedMotion.matches) {
      document.documentElement.classList.add("reduce-motion");
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
