# Cloudflare Release proxy

The Worker attached to `dl.yunl.us.ci` streams public GitHub Release assets from
the `YunGet` and `TurboDL` repositories and lets Workers Cache retain the full
response at the edge. The GitHub Release remains the source of truth.

URL format:

```text
https://dl.yunl.us.ci/yunget/releases/download/<tag>/<asset>
https://dl.yunl.us.ci/turbodl/releases/download/<tag>/<asset>
```

Only GET and HEAD requests for those two fixed repositories are accepted. The
Worker does not accept an upstream hostname from the request, does not forward
credentials, and does not accept query strings. Websites link to the cached
address first and keep each original GitHub asset link beside it as a fallback.

The Worker returns a complete `200` body. Workers Cache handles `Range` by
fetching/caching the full response once and slicing later requests at the edge;
do not implement range slicing in the Worker or return a `206` from it. New
releases use distinct immutable URLs, with a one day edge cache lifetime.

The Worker source is deployed as `github-release-edge` in Cloudflare account
`jiayuxuan123`. Its custom domain is managed as `dl.yunl.us.ci`. Redeploy this
file through the Cloudflare Worker module API after changing the source.
