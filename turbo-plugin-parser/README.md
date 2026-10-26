# turbo-plugin-parser

Optional **link-parser** plugin for TurboDL: turns a share link from a video/social platform
into concrete `DownloadRequest`s. Same shape as `turbo-plugin-hls` — an ordinary `Plugin` that
registers itself at `LINK_PARSER`, with no change to `turbodl-core` or `turbo-plugin-runtime`.

```kotlin
val bootstrap = TurboBootstrap.create(
    config = TurboConfig(),
    extraPlugins = listOf(HlsPlugin(), PlatformParserPlugin()),
)
```

That is the whole integration. The host then queries the parser like any other:

```kotlin
val parsers = bootstrap.host.extensions.registrations(ExtensionPoints.LINK_PARSER)
for (reg in parsers) {
    val requests = reg.instance.parse(rawLink) ?: continue   // null = "not mine"
    // enqueue requests…
    break
}
```

---

## Platforms

| `platform` | Site | What it yields | Anonymous? |
|---|---|---|---|
| `weibo` | Weibo | MP4 (best available quality) + image sets | yes |
| `xhs` | Xiaohongshu | MP4 (watermark-free) + original images | mostly **no** — the note page redirects anonymous visitors to a login wall |
| `pixiv` | Pixiv | every page's `urls.original` | partly — public works yes, R18/restricted need login |

This plugin holds **no credentials**. Where a platform requires a login, the plugin returns
`null`; a host that has a session can implement its own parser and register it at a higher
priority (the router tries parsers highest-priority-first).

---

## The design problem worth knowing about

`LinkParser.parse` must return `DownloadRequest`, and `DownloadRequest` requires a
`destination: File`. But a parser runs **before the host has chosen a directory** — path policy,
name de-duplication and task grouping all belong to the host.

So this module splits the two concerns:

- `PlatformParser.parse` returns `ParsedPost` — media URLs, suggested file names, headers, and
  descriptive tags. **No paths anywhere.**
- `ParsedMedia.toDownloadRequest(workDir)` is the only place a `File` appears, and it points into
  a temp dir. It is a *legal placeholder*, not a decision; the host still applies its own path
  policy afterwards.

The alternative — having the plugin invent a real download path — would mean every plugin
reimplementing (and inevitably disagreeing with) the host's path rules.

---

## Writing another platform

Implement `PlatformParser` and add it to the list in `PlatformParserPlugin.onLoad`:

```kotlin
object ExampleParser : PlatformParser {
    override val platform = "example"
    override val displayName = "Example"

    // MUST be a pure string check — the router calls this on every input, so a network call
    // here would fire on links that have nothing to do with you.
    override fun matches(raw: String) =
        raw.contains("example.com", ignoreCase = true) && ID.containsMatchIn(raw)

    // Return null for "not mine / gone / needs login". Do not throw: one broken platform
    // must not take down the whole parse path.
    override fun parse(raw: String): ParsedPost? {
        val id = ID.find(raw)?.groupValues?.get(1) ?: return null
        val json = ParserHttp.getText("https://api.example.com/v/$id") ?: return null
        if (!json.ok) return null
        // … map the response into ParsedMedia(url=…, fileName=…, headers=…, tags=…)
    }
}
```

Three rules the tests enforce (see `PlatformParserTest` / `PlatformLinkTest`):

1. **Domain before path.** Matching only on a URL path shape (`/explore/<24 hex>`) claims
   *every* site with that shape, and the user then sees "parse failed" for what was really
   "wrong parser". Every `matches` checks the host first.
2. **`matches == false` means `parse` is never called.** Otherwise unrelated links each trigger
   a round of network requests.
3. **File names are sanitized at the boundary.** Platform titles contain `/`, `:`, `?`, and `..`;
   `sanitizeFileName` keeps only the last path segment, replaces the illegal characters, strips
   leading dots, and caps the length.

---

## Tags

The first media's `tags` carries the post-level metadata; the host reads it as "this is what the
whole batch is about".

| tag | meaning |
|---|---|
| `platform` | platform id (`weibo` / `xhs` / `pixiv`) |
| `title`, `author`, `cover`, `text`, `pageUrl` | post metadata, on the **first** media only |
| `quality` | display label (`720P`, `原图`, `P1/4`) |
| `kind` | `video` / `image` / `audio` — omit to let the host infer from the file extension |

---

## What this module deliberately does not do

- **ugoira (Pixiv animations)** — a zip plus a frame-timing table; producing a playable file
  needs image processing. Returns `null` rather than an unopenable file.
- **Pixiv novels** — out of scope for a link parser.
- **Signed platform APIs** (Douyin, Kuaishou, Bilibili) — those need per-platform signature
  algorithms or a WebView handshake and stay in the host for now.
- **Any content filtering** — visibility of age-restricted works is decided by the account's own
  settings on the platform side, which this plugin cannot see.

---

## Tests

```bash
./gradlew :turbo-plugin-parser:test
```

All offline: link recognition, HTML/JSON extraction, filename sanitizing, and the parser
dispatch rules (including that a throwing parser is isolated and that an unclaimed parser is
never invoked).
