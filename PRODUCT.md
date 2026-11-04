# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

JVM, Kotlin, and Android developers who need a reusable HTTP download engine, plus CLI users who want concurrent resumable downloads without writing Kotlin. They evaluate the project through API clarity, release artifacts, failure semantics, observability, plugin boundaries, and reproducible evidence.

## Product Purpose

TurboDL provides a pure Kotlin/JVM download core with range-based concurrency, resumable state, host-controlled lifecycle policies, a CLI, and optional protocol/plugin modules. The website must help a developer decide whether to embed it, download the right artifact, understand the SDK and CLI entry points, and verify claims against source and tests.

## Positioning

The stable core stays small and generic: the host controls concurrency mode, slow start, start-up limits, recovery policy, and source refresh. Higher-level behavior such as HLS, JavaScript loading, and link parsing lives in separate modules. The project emphasizes correctness, lifecycle, and real network behavior rather than a single headline speed number.

## Operating Context

A consumer adds the core Maven coordinates to a JVM or Android project, configures a `TurboClient`, submits `DownloadRequest` objects, listens to progress/events, and controls pause/resume/cancel/shutdown. CLI users download a self-contained JAR. Plugin authors choose runtime/bootstrap and optional modules. Releases include module JAR/MODULE/POM files, CLI JAR, and an offline Maven bundle.

## Capabilities and Constraints

- Current release facts must come from `website/data/releases.json`; `v0.4.1.0` is the latest verified release in the repository data.
- Public Kotlin API version is `1.0.0`.
- Current modules are `turbodl-core`, runtime, bootstrap, HLS, JavaScript loader, parser, and CLI. Only the core is mandatory. The three official capability plugins are HLS, JavaScript loader, and platform parser; runtime and bootstrap are infrastructure modules.
- The current homepage claims and defaults must be checked against `TurboConfig.kt`; documentation that describes an obsolete nine-field surface is not authoritative.
- `TurboConfig.kt` currently exposes 44 public fields: 42 active fields and 2 deprecated compatibility fields. The website reads that surface at build time.
- `overlapMerge` is deprecated and must not be presented as a working runtime control.
- The website has English and Simplified Chinese routes. Other language links currently point to GitHub README translations and must not be presented as equivalent site locales.
- Website build is an existing Python standard-library static generator writing to `docs/`; release links and routes are generated from repository data.

## Brand Commitments

The project name is “TurboDL”. It is distributed under the MIT license with supplemental plugin-ecosystem terms. Public claims are evidence-led and engineering-specific. The visual identity may change, but the product should not be described as IDM, aria2, or another engine clone.

The committed web direction is “Packet Transit Control Board”: a light engineering console built from continuous routes, stations, status lamps, timetable rows, and fine technical lines. Display copy uses Sentient; body copy uses a clear CJK sans-serif; monospace is reserved for code, API names, and measured values. The previous dark neon/terminal aesthetic is superseded.

## Evidence on Hand

- Release metadata and assets: `website/data/releases.json`.
- Product defaults and API truth: `turbodl-core/src/main/kotlin/dev/turbodl/core/TurboConfig.kt`, `TurboClient.kt`, `ApiVersion.kt`.
- Current test evidence on 2026-11-04: Gradle XML reports contain core 254 and CLI 12 tests, all with 0 skipped, 0 failures, 0 errors. Counts are refreshed from the reports at build time rather than invented in marketing copy.
- Module and plugin truth: `website/content.py`, project README and module directories.
- Brand marks: `website/assets/logo-mark.svg`, `website/assets/favicon.svg`, `website/assets/og.png`.
- Download edge mapping and GitHub fallbacks: `website/content.py`.

No third-party customer testimonials, production SLA, benchmark victory, or account-scale numbers are approved evidence.

## Product Principles

1. Show a developer what to download and what to write next within one screen.
2. Separate verified SDK facts from illustrative runtime traces.
3. Explain correctness, lifecycle, and failure behavior alongside throughput.
4. Keep the core/plugin boundary explicit; never imply optional modules are required.
5. Treat release artifacts, package coordinates, and compatibility as first-class navigation.

## Accessibility & Inclusion

The website must meet WCAG AA for contrast, focus, semantics, reduced motion, and mobile table/card behavior. Code blocks need keyboard-visible copy affordances and horizontal scrolling without breaking page layout.
