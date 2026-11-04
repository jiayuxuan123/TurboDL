---
name: TurboDL Website
description: A packet transit control board for a Kotlin/JVM download engine.
colors:
  paper: "#eef1ec"
  paper-raised: "#f4f6f2"
  paper-cool: "#e5e9e4"
  warm-card: "#f7f2e6"
  ink: "#1d211e"
  ink-soft: "#3f4842"
  rail: "#2f6b5d"
  rail-dark: "#235044"
  signal: "#c75a36"
  signal-text: "#a94726"
  steel: "#cfd6d1"
  yellow: "#d7c751"
typography:
  display:
    fontFamily: "Sentient, Source Han Serif SC, Noto Serif CJK SC, Songti SC, serif"
    fontSize: "clamp(3.4rem, 7.4vw, 6.8rem)"
    fontWeight: 400
    lineHeight: 1.03
    letterSpacing: "-0.027em"
  body:
    fontFamily: "Aptos, Segoe UI Variable, PingFang SC, Microsoft YaHei UI, Noto Sans CJK SC, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: 1.62
  label:
    fontFamily: "Cascadia Mono, SFMono-Regular, Consolas, monospace"
    fontSize: "12px"
    fontWeight: 500
    lineHeight: 1.35
    letterSpacing: "0.02em"
rounded:
  sm: "4px"
  md: "7px"
  lg: "12px"
spacing:
  sm: "8px"
  md: "16px"
  lg: "32px"
  section: "clamp(64px, 8vw, 108px)"
components:
  button-primary:
    backgroundColor: "{colors.rail}"
    textColor: "{colors.paper-raised}"
    rounded: "{rounded.sm}"
    padding: "12px 18px"
    height: "44px"
  button-secondary:
    backgroundColor: "{colors.paper-raised}"
    textColor: "{colors.ink}"
    rounded: "{rounded.sm}"
    padding: "12px 18px"
    height: "44px"
---

# Design System: TurboDL Website

## Overview

**Creative North Star: "Packet Transit Control Board"**

TurboDL is presented as a light engineering console that moves one request through a controlled network route. Request, probe, partition, concurrency, and verification are stations with explicit state, source-derived values, and operational notes. The interface is information-dense but calm; lines, nodes, and timetable rows do the structural work instead of floating cards.

The old dark-neon hacker surface is an explicit anti-reference. The control-board metaphor must remain readable in daylight, survive long documentation pages, and degrade to a single clear route on mobile.

**Key Characteristics:**

- Continuous request-to-file route.
- Source-derived defaults and test counts.
- Flat mineral surfaces and engineering line-work.
- Code and data typography reserved for actual code and measurement.
- One authored packet motion, with content visible by default.

## Colors

The palette reads as mineral paper, dark ink, rail green, steel, and a restrained signal orange.

### Primary

- **Rail Green** (`#2f6b5d`): active route, primary action, configured state.
- **Rail Dark** (`#235044`): hover and emphasized links.

### Secondary

- **Signal Orange** (`#c75a36`): nodes, borders, and failure boundaries.
- **Signal Text** (`#a94726`): orange-family text that must clear 4.5:1.
- **Signal Yellow** (`#d7c751`): small release/test flags.

### Neutral

- **Mineral Paper** (`#eef1ec`): page ground.
- **Raised Mineral** (`#f4f6f2`): reading panels.
- **Control Ink** (`#1d211e`): body and headings.
- **Steel Rule** (`#cfd6d1`): structural rules and inactive tracks.

**The Daylight Rule.** The control board must remain legible without glow, blur, or a dark viewport.

## Typography

**Display Font:** Sentient with CJK serif fallback  
**Body Font:** Aptos / Segoe UI Variable with CJK sans fallback  
**Label / Mono Font:** Cascadia Mono / SFMono-Regular / Consolas

Sentient gives the engineering surface an editorial, human voice. Sans text carries operations. Monospace appears only for coordinates, configuration names, JSON, shell commands, and tabular identifiers.

### Hierarchy

- **Display** (400, `clamp(3.4rem, 7.4vw, 6.8rem)`, 1.03): landing-page thesis.
- **Headline** (400, `clamp(2.2rem, 5vw, 4.4rem)`, 1.02): route sections.
- **Title** (600, `1.1rem–1.6rem`, 1.25): station names and artifact groups.
- **Body** (400, `16px`, 1.62, max 72ch): explanation.
- **Label** (500, `12px`, 0.02em): status, coordinates, and table metadata.

## Layout

The content width is up to 1320px with fluid gutters. The homepage starts with the five-stage route rather than a metric hero. Defaults use a timetable table, controls use a ruled list, and component boundaries use full-width stops. Download pages are organized by task: CLI JAR, Maven/SDK, optional modules, then offline repository. Documentation uses a sticky, collapsible manual index and a readable article column. At 760px, tables become compact labelled rows and code scrolls inside its own frame.

## Elevation & Depth

The system is flat by default. Depth comes from mineral tone shifts, inset code surfaces, route markers, and structural lines. There are no decorative glass layers, colored halos, hard offset shadows, or full-page grid textures.

## Shapes

Containers use small 4–7px radii. Route stations and status lamps are circular. Long tracks, table rules, and code frames remain rectangular. Thick side-tab accents are prohibited.

## Components

### Buttons

- **Primary:** rail green with raised-mineral text, 44px minimum height.
- **Secondary:** raised mineral with steel border.
- **Focus:** 3px signal outline with visible offset.

### Route Board

Five unequal stages connected by a continuous rail. Each stage has a numbered node, state, short explanation, and source note.

### Config Row

Configuration name in mono, default value, allowed range, and operating effect. Demo toggles never mutate a live engine.

### Code Frame

Inset dark surface, copy control, horizontal scrolling, and file-name caption. Code remains readable in reduced motion.

### Documentation Navigation

Desktop uses a sticky directory; mobile uses a modal dialog with background inert, trapped focus, Escape close, and focus return.

## Do's and Don'ts

### Do

- **Do** label illustrative routes and sample output.
- **Do** derive defaults, test counts, and artifacts from source or Gradle XML.
- **Do** explain failure behavior with the same prominence as throughput.
- **Do** keep content visible before motion runs.

### Don't

- **Don't** present deprecated `overlapMerge` as a runtime control.
- **Don't** turn release attachments into an uncategorized card wall.
- **Don't** use dark neon, phosphor glow, or fake telemetry as the visual identity.
- **Don't** use decorative grid backgrounds or thick side stripes.
- **Don't** use functional text below 12px.
