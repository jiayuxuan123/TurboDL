# Privacy

This document answers one question: **when you use TurboDL, what leaves your machine?**

It exists for a practical reason. A download tool reaches the network on your behalf by design,
so "where exactly does it send things" is worth stating plainly instead of leaving you to read
the source to find out.

## TurboDL is a library, not a service

Everything below follows from that. There is no server of ours, so there is nothing for a server
of ours to collect. You run it on your own machine; it makes network requests on your behalf.
That is the whole relationship.

## When it touches the network

Only at these moments, and all of them start with something you asked for:

| Moment | Where the request goes |
|---|---|
| You submit a download | The URL you gave it. TurboDL contacts that address and nothing else |
| Segmented download | The same URL, with HTTP Range requests for different byte ranges |
| HLS download | The address of the M3U8 playlist, plus the segment addresses written inside that playlist |
| DNS resolution | Your system resolver; or, if you set `TurboConfig.dns`, the static hosts or DoH endpoint you chose |
| Proxy | Your proxy server, if you configured one |

There are no other outbound connections. No heartbeat, no telemetry ping, no request fired off in
the background.

## What does not happen

- **No usage statistics.** No device info, install counts, or feature-call tracking.
- **No crash reporting.** Exceptions surface where you call it, and your program decides what to do.
- **No update check.** The library never asks whether a newer version exists, and the CLI does not
  phone home at runtime either.
- **No reading of files that are none of its business.** It opens the destination you named and its
  own resume-state file.
- **No writing outside the paths you gave it** — unless your code hands it one, as a JS plugin does
  when the host explicitly enables an external destination.

## What lands on disk

| File | Notes |
|---|---|
| The downloaded file | Wherever you asked it to go |
| Resume state | Per-segment progress, next to the destination file. Cleaned up when the task finishes or is removed |
| JS plugin sandbox | Created only when the JS loader is used: `turbodl-js/<plugin-directory>/` under the working directory, containing `storage/` (the plugin's own persistent data) and `downloads/` (scratch downloads) |

A plugin's directory name is its id plus a short digest, so two different ids never share one and
unloading cleans up exactly one plugin's files.

## About JavaScript plugins

JS plugins run inside an embedded QuickJS engine — one runtime and one context per script.
What a script can do is bounded by the **host ABI**: build URLs, set headers, compute signatures,
read and write its own sandbox directory, write log lines.

It does not touch the download data stream, and it cannot read files outside its sandbox —
unless the host that loads it turns on `allowExternalDestination`, which deliberately permits
writing to an external path and deliberately takes on the consequences.

Plugin log lines go to the host's logging system (`host.log`). Where those end up is your
program's decision; TurboDL writes no log files of its own.

## This website

This site is static pages: no analytics script, no cookies, no fonts or icons pulled from a
third-party CDN — styles, icons and scripts ship with the page. Opening it is just reading files,
and it produces no visit statistics anywhere.

(The changelog page's version data is fetched from GitHub's public API *locally* by whoever updates
the repository, then committed. Visiting the page makes no request to GitHub.)

## If you find a problem

A statement here that the code does not honour is a bug — please tell us in
[Issues](https://github.com/jiayuxuan123/TurboDL/issues). Anything the code does not do should not
be written here as if it does.
