const RELEASES = {
  yunget: "YunGet",
  turbodl: "TurboDL",
};

const OWNER = "jiayuxuan123";
const ASSET_CACHE_CONTROL = "public, max-age=300, s-maxage=86400, immutable";

function plain(status, message, extraHeaders = {}) {
  return new Response(message, {
    status,
    headers: {
      "Cache-Control": "no-store",
      "Content-Type": "text/plain; charset=utf-8",
      "X-Content-Type-Options": "nosniff",
      ...extraHeaders,
    },
  });
}

export default {
  async fetch(request) {
    if (request.method !== "GET" && request.method !== "HEAD") {
      return plain(405, "Method Not Allowed", { Allow: "GET, HEAD" });
    }

    const incoming = new URL(request.url);
    if (incoming.search || incoming.hash) {
      return plain(400, "Query strings are not supported");
    }

    const parts = incoming.pathname.split("/").filter(Boolean);
    if (
      parts.length !== 5 ||
      parts[1] !== "releases" ||
      parts[2] !== "download"
    ) {
      return plain(404, "Release asset not found");
    }

    const repository = RELEASES[parts[0]];
    if (!repository) return plain(404, "Release asset not found");

    let tag;
    let asset;
    try {
      tag = decodeURIComponent(parts[3]);
      asset = decodeURIComponent(parts[4]);
    } catch {
      return plain(400, "Invalid release asset path");
    }

    if (
      !/^v?[A-Za-z0-9][A-Za-z0-9._+-]{0,79}$/.test(tag) ||
      !/^[A-Za-z0-9][A-Za-z0-9._+-]{0,199}$/.test(asset)
    ) {
      return plain(400, "Invalid release asset path");
    }

    const upstreamUrl =
      "https://github.com/" + OWNER + "/" + repository + "/releases/download/" +
      encodeURIComponent(tag) + "/" + encodeURIComponent(asset);
    const method = request.method;

    let upstream;
    try {
      upstream = await fetch(upstreamUrl, {
        method,
        redirect: "follow",
        headers: { "Accept-Encoding": "identity" },
      });
    } catch {
      return plain(502, "Release source is temporarily unavailable");
    }

    if (upstream.status !== 200) {
      return plain(upstream.status, upstream.status === 404 ? "Release asset not found" : "Release source error");
    }

    const headers = new Headers(upstream.headers);
    headers.delete("Set-Cookie");
    headers.set("Cache-Control", method === "GET" ? ASSET_CACHE_CONTROL : "no-store");
    headers.set("X-Content-Type-Options", "nosniff");

    // The Worker cache handles Range requests by invoking this Worker without
    // Range, caching the complete 200 response, then slicing it at the edge.
    return new Response(method === "HEAD" ? null : upstream.body, {
      status: 200,
      headers,
    });
  },
};
