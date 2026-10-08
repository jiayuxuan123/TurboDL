/*
 * TurboDL JS Plugin ABI — runtime shim injected into every JS plugin context, before the plugin's
 * own script runs.
 *
 * This file IS the ABI. Plugins never see a Kotlin object: they see `plugin` (registration) and
 * `host` (capabilities), both of which are plain JS built on top of exactly three primitives that
 * the host binds:
 *
 *   __turbodlCall(path, payloadJson)      -> envelopeJson   // JS -> host capability, coarse-grained
 *   __turbodlRegister(kind, cbId, opts)   -> envelopeJson   // JS -> extension/service registration
 *   __turbodlLog(level, text)             -> void           // log fast path (no JSON round-trip)
 *
 * Everything crossing the boundary is JSON *text*, and callbacks travel as integer ids. That is
 * deliberate: QuickJS hands a JS function to Kotlin as an opaque handle that cannot be invoked from
 * Kotlin, so the only robust way to call back into JS is to keep the function on the JS side and
 * dispatch it by id here.
 *
 * Versioning: ABI_MAJOR is negotiated against the host's TurboDL ApiVersion by the loader, which
 * refuses to load a script whose declared `plugin.apiMajor` the host cannot satisfy.
 */
(function (global) {
  "use strict";

  var ABI_MAJOR = 1;
  var ABI_VERSION = "1.0.0";

  // ---------------------------------------------------------------- boundary primitives

  function nativeCall(path, payload) {
    var text;
    try {
      text = __turbodlCall(path, payload === undefined ? "null" : JSON.stringify(payload));
    } catch (e) {
      // A host-side throw arrives as a JS Error whose message is the host message (verified
      // behaviour of the QuickJS binding). Re-throw as a plain error so `catch` in plugin code
      // works exactly like it does for its own exceptions.
      throw new Error("host call '" + path + "' failed: " + (e && e.message ? e.message : String(e)));
    }
    var env;
    try {
      env = JSON.parse(text);
    } catch (e) {
      throw new Error("host call '" + path + "' returned a malformed envelope");
    }
    if (env && env.ok === true) return env.data;
    var err = (env && env.error) || {};
    var wrapped = new Error(err.message || ("host call '" + path + "' failed"));
    wrapped.code = err.code || "internal";
    wrapped.hostPath = path;
    throw wrapped;
  }

  function nativeRegister(kind, cbId, opts) {
    var env;
    try {
      env = JSON.parse(__turbodlRegister(kind, cbId, opts ? JSON.stringify(opts) : "null"));
    } catch (e) {
      throw new Error("registration for '" + kind + "' failed: " + (e && e.message ? e.message : String(e)));
    }
    if (env && env.ok === true) return env.data;
    var err = (env && env.error) || {};
    var wrapped = new Error(err.message || ("registration for '" + kind + "' failed"));
    wrapped.code = err.code || "internal";
    throw wrapped;
  }

  // ---------------------------------------------------------------- callback registry

  var callbacks = new Map();
  var nextCbId = 1;

  /**
   * Store a JS callable and return its integer handle. Kotlin invokes it through
   * __turboDispatchCallback(id, payloadJson) below — never by holding a JS function reference.
   */
  function bind(fn, label) {
    if (typeof fn !== "function") throw new Error("'" + label + "' must be a function");
    var id = nextCbId++;
    callbacks.set(id, { fn: fn, label: label });
    return id;
  }

  /**
   * Called BY THE HOST. Invokes the registered callback with a decoded payload and returns the
   * JSON-encoded result. Errors are converted to a returned envelope rather than thrown across the
   * boundary, so the host can report them without distinguishing plugin bugs from engine faults.
   */
  global.__turboDispatchCallback = function (id, payloadJson) {
    var entry = callbacks.get(id);
    if (!entry) return JSON.stringify({ ok: false, error: { code: "gone", message: "callback " + id + " is no longer registered" } });
    var payload;
    try {
      payload = payloadJson === null || payloadJson === undefined ? null : JSON.parse(payloadJson);
    } catch (e) {
      return JSON.stringify({ ok: false, error: { code: "validation", message: "payload is not valid JSON" } });
    }
    try {
      var out = entry.fn(payload);
      // A callback may return undefined (a void hook) — that is a successful no-op, not null data.
      return JSON.stringify({ ok: true, data: out === undefined ? null : out });
    } catch (e) {
      return JSON.stringify({
        ok: false,
        error: {
          code: e && e.code ? String(e.code) : "plugin",
          message: e && e.message ? String(e.message) : String(e),
        },
      });
    }
  };

  /** Called BY THE HOST: drop a callback (extension unregistration / unload). */
  global.__turboReleaseCallback = function (id) {
    callbacks.delete(id);
    return true;
  };

  /** Introspection helper: is a callback id still live? (used by diagnostics and by unload). */
  global.__turboHasCallback = function (id) {
    return callbacks.has(id);
  };

  // ---------------------------------------------------------------- host capabilities

  var host = Object.create(null);

  host.http = Object.create(null);

  /** POST-shaped request descriptor is normalized here so JS cannot smuggle extra fields inward. */
  host.http.request = function (options) {
    return nativeCall("http.request", normalizeHttpOptions(options, false));
  };

  /** Coarse-grained whole-file download: returns {file, bytes, status,...}. Never a byte callback. */
  host.http.downloadToFile = function (options) {
    return nativeCall("http.downloadToFile", normalizeHttpOptions(options, true));
  };

  function normalizeHttpOptions(options, forDownload) {
    if (!options || typeof options !== "object") throw new Error("host.http options must be an object");
    var out = { url: String(options.url) };
    if (options.method !== undefined) out.method = String(options.method);
    if (options.headers !== undefined) out.headers = options.headers;
    if (options.body !== undefined) out.body = options.body;
    if (options.bodyBase64 !== undefined) out.bodyBase64 = String(options.bodyBase64);
    if (options.timeoutMillis !== undefined) out.timeoutMillis = Number(options.timeoutMillis);
    // Narrowing only: `false` can disable redirects for this call, `true` can never enable them if
    // the loader disabled them — the ceiling is the loader's, not the script's.
    if (options.followRedirects !== undefined) out.followRedirects = Boolean(options.followRedirects);
    if (options.maxBytes !== undefined) out.maxBytes = Number(options.maxBytes);
    // Where this request's credentials legitimately live; the host strips Authorization/Cookie for
    // any hop outside it. A plugin that logs in on host A and fetches from CDN B declares A here.
    if (options.originUrl !== undefined) out.originUrl = String(options.originUrl);
    if (forDownload) {
      if (options.fileName !== undefined) out.fileName = String(options.fileName);
    }
    if (!forDownload && options.as === undefined) {
      out.as = "text"; // default: body as text; "base64" for binary, "none" to skip the body
    } else if (!forDownload) {
      out.as = String(options.as);
    }
    return out;
  }

  host.crypto = Object.create(null);
  host.crypto.digest = function (algorithm, data) {
    return nativeCall("crypto.digest", { algorithm: String(algorithm), data: encodeBytes(data, "crypto.digest data") });
  };
  host.crypto.hmac = function (algorithm, key, data) {
    return nativeCall("crypto.hmac", {
      algorithm: String(algorithm),
      key: encodeBytes(key, "crypto.hmac key"),
      data: encodeBytes(data, "crypto.hmac data"),
    });
  };
  host.crypto.randomBytes = function (length) {
    return nativeCall("crypto.randomBytes", { length: Number(length) });
  };
  host.crypto.base64Encode = function (data) {
    return nativeCall("crypto.base64", { mode: "encode", data: encodeBytes(data, "base64Encode data") });
  };
  host.crypto.base64Decode = function (text) {
    return nativeCall("crypto.base64", { mode: "decode", text: String(text) });
  };
  host.crypto.hexEncode = function (data) {
    return nativeCall("crypto.hex", { mode: "encode", data: encodeBytes(data, "hexEncode data") });
  };
  host.crypto.hexDecode = function (text) {
    return nativeCall("crypto.hex", { mode: "decode", text: String(text) });
  };

  function encodeBytes(data, label) {
    if (typeof data === "string") return { kind: "utf8", text: data };
    if (data && typeof data.byteLength === "number") {
      // ArrayBuffer / typed array: base64-encode on the JS side with a small loop (no Buffer).
      var view = data instanceof Uint8Array ? data : new Uint8Array(data.buffer || data);
      var CHUNK = 0x8000, parts = [], i;
      for (i = 0; i < view.length; i += CHUNK) parts.push(String.fromCharCode.apply(null, view.subarray(i, i + CHUNK)));
      return { kind: "base64", text: b64FromBinary(parts.join("")) };
    }
    throw new Error(label + " must be a string, ArrayBuffer or Uint8Array");
  }

  var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  function b64FromBinary(binary) {
    var out = "", i;
    for (i = 0; i < binary.length; i += 3) {
      var c0 = binary.charCodeAt(i) & 0xff;
      var c1 = i + 1 < binary.length ? binary.charCodeAt(i + 1) & 0xff : NaN;
      var c2 = i + 2 < binary.length ? binary.charCodeAt(i + 2) & 0xff : NaN;
      out += B64.charAt(c0 >> 2);
      out += B64.charAt(((c0 & 3) << 4) | (isNaN(c1) ? 0 : c1 >> 4));
      out += isNaN(c1) ? "=" : B64.charAt(((c1 & 15) << 2) | (isNaN(c2) ? 0 : c2 >> 6));
      out += isNaN(c2) ? "=" : B64.charAt(c2 & 63);
    }
    return out;
  }

  host.log = Object.create(null);
  function logAt(level, args) {
    var text = "";
    for (var i = 0; i < args.length; i++) {
      var a = args[i];
      if (i > 0) text += " ";
      if (typeof a === "string") text += a;
      else { try { text += JSON.stringify(a); } catch (e) { text += String(a); } }
    }
    // Direct fast path: the host already prefixes the plugin id and isolates its own failures.
    try { __turbodlLog(level, text); } catch (e) { /* logging must never break a plugin */ }
  }
  host.log.info = function () { logAt("info", arguments); };
  host.log.warn = function () { logAt("warn", arguments); };
  host.log.error = function () { logAt("error", arguments); };
  host.log.debug = function () { logAt("debug", arguments); };

  host.time = Object.create(null);
  host.time.now = function () { return nativeCall("time.now", null); };
  host.time.iso = function (millis) { return nativeCall("time.iso", millis === undefined ? null : Number(millis)); };
  host.time.sleep = function (millis) { return nativeCall("time.sleep", Number(millis)); };

  host.storage = Object.create(null);
  host.storage.get = function (key) { return nativeCall("storage.get", { key: String(key) }); };
  host.storage.set = function (key, value) { return nativeCall("storage.set", { key: String(key), value: value === undefined ? null : value }); };
  host.storage.remove = function (key) { return nativeCall("storage.remove", { key: String(key) }); };
  host.storage.keys = function () { return nativeCall("storage.keys", null); };

  host.env = Object.create(null);
  host.env.get = function (name) { return nativeCall("env.get", { name: String(name) }); };
  host.env.has = function (name) { return nativeCall("env.has", { name: String(name) }); };

  // Timers are host-owned so unload can cancel them. The handle a plugin gets back is the *number*
  // it passes to clear: returning the raw `{id}` envelope here would make the obvious
  // `host.clearTimeout(host.setTimeout(...))` silently target `NaN`.
  function timerId(data) {
    return (data && typeof data === "object" && "id" in data) ? data.id : data;
  }
  host.setTimeout = function (fn, millis) {
    if (typeof fn !== "function") throw new Error("setTimeout handler must be a function");
    return timerId(nativeCall("timer.setTimeout", { cb: bind(fn, "setTimeout"), delayMillis: Number(millis) || 0 }));
  };
  host.setInterval = function (fn, millis) {
    if (typeof fn !== "function") throw new Error("setInterval handler must be a function");
    return timerId(nativeCall("timer.setInterval", { cb: bind(fn, "setInterval"), delayMillis: Number(millis) || 0 }));
  };
  host.clearTimeout = function (handle) {
    return nativeCall("timer.clear", { id: Number(timerId(handle)) }).cleared === true;
  };
  host.clearInterval = host.clearTimeout;

  // ---------------------------------------------------------------- plugin registration API

  var registrations = [];
  var lifecycle = { init: null, destroy: null, event: null };
  var requirements = { abiMajor: ABI_MAJOR, permissions: [] };

  function register(kind, fn, opts) {
    var id = bind(fn, kind);
    var data = nativeRegister(kind, id, opts);
    registrations.push({ kind: kind, cb: id, handle: data && data.handle });
    return data;
  }

  /** `impl.priority` is honoured so one script can order its own registrations. */
  function optionsFor(impl, extra) {
    var opts = extra && typeof extra === "object" ? extra : null;
    if (impl && typeof impl.priority === "number") {
      opts = opts || {};
      opts.priority = impl.priority;
    }
    return opts;
  }

  var plugin = Object.create(null);

  plugin.id = null;          // filled in by the loader before the script runs
  plugin.manifest = null;    // likewise: the parsed turbodl-plugin.json, as plain data
  plugin.apiMajor = ABI_MAJOR;

  plugin.defineMeta = function (meta) {
    if (!meta || typeof meta !== "object") throw new Error("defineMeta expects an object");
    if (meta.name !== undefined) plugin.name = String(meta.name);
    if (meta.version !== undefined) plugin.version = String(meta.version);
    if (meta.apiMajor !== undefined) plugin.apiMajor = Number(meta.apiMajor);
    return plugin;
  };

  /**
   * Declare what this script needs, so the loader can refuse it with a useful message instead of
   * letting it fail mid-download on a denied capability.
   * `plugin.requires({ permissions: ["http", "storage"], abiMajor: 1 })`
   */
  plugin.requires = function (spec) {
    if (!spec || typeof spec !== "object") throw new Error("plugin.requires expects an object");
    if (spec.permissions !== undefined) {
      var list = spec.permissions;
      if (typeof list === "string") list = list.split(/[,\s]+/);
      if (Object.prototype.toString.call(list) !== "[object Array]") {
        throw new Error("plugin.requires permissions must be a string or array");
      }
      for (var i = 0; i < list.length; i++) requirements.permissions.push(String(list[i]).trim());
    }
    if (spec.abiMajor !== undefined) requirements.abiMajor = Number(spec.abiMajor);
    return requirements;
  };

  /** Link parser: (raw string) -> array of request descriptors | null. */
  plugin.registerParser = function (impl) {
    return register("parser", requireMethod(impl, "parse", "registerParser"), optionsFor(impl));
  };

  /** Task pre-hook: (request descriptor) -> request descriptor | same input. */
  plugin.registerTaskPreHook = function (impl) {
    return register("preHook", requireMethod(impl, "beforeSubmit", "registerTaskPreHook"), optionsFor(impl));
  };

  /** Task post-hook: ({request, success, detail}) -> void. */
  plugin.registerTaskPostHook = function (impl) {
    return register("postHook", requireMethod(impl, "afterFinish", "registerTaskPostHook"), optionsFor(impl));
  };

  /** Observe engine events: ({type, taskId, ...}) -> void. Read-only by construction. */
  plugin.onEvent = function (fn) {
    if (lifecycle.event) throw new Error("plugin.onEvent may only be called once");
    lifecycle.event = register("event", fn, null);
    return lifecycle.event;
  };

  plugin.onInit = function (fn) {
    if (lifecycle.init) throw new Error("plugin.onInit may only be called once");
    lifecycle.init = { fn: fn, cb: bind(fn, "onInit") };
  };

  plugin.onDestroy = function (fn) {
    if (lifecycle.destroy) throw new Error("plugin.onDestroy may only be called once");
    lifecycle.destroy = { fn: fn, cb: bind(fn, "onDestroy") };
  };

  function requireMethod(impl, method, label) {
    if (!impl || typeof impl[method] !== "function") {
      throw new Error(label + " expects an object with a " + method + "() method");
    }
    return function (payload) { return impl[method](payload); };
  }

  // Called BY THE HOST at the end of script evaluation: runs onInit, reports declared surface.
  global.__turboSeal = function () {
    var errors = [];
    if (lifecycle.init) {
      try {
        lifecycle.init.fn();
      } catch (e) {
        errors.push("onInit: " + (e && e.message ? e.message : String(e)));
      }
    }
    return JSON.stringify({
      abiMajor: ABI_MAJOR,
      abiVersion: ABI_VERSION,
      requires: { abiMajor: requirements.abiMajor, permissions: requirements.permissions.slice() },
      meta: { id: plugin.id, name: plugin.name || null, version: plugin.version || null },
      registrations: registrations.map(function (r) { return { kind: r.kind, handle: r.handle }; }),
      hasDestroy: !!lifecycle.destroy,
      destroyCb: lifecycle.destroy ? lifecycle.destroy.cb : null,
      errors: errors,
    });
  };

  global.__turboAbiVersion = function () { return ABI_VERSION; };

  global.host = host;
  global.plugin = plugin;
})(globalThis);
