# ProcView implementation decisions

This document records implementation clarifications accepted after the frozen
product specification. The original specification remains unchanged.

## Product and verification

- Completion means the full Definition of Done, including the Xiaomi 17 Pro Max
  Android 16 gate and the wider device matrix.
- M0 device probing happens before the full UI freezes the backend contract.
- CPU Top 20 and RSS Top 20 are retained as a union. Pinned and actively viewed
  targets are retained in addition, with a reason bit set per sample.
- Release blockers include correctness, security, privacy, state integrity,
  feature completeness, and primary-device stability/performance/power targets.
- Coverage below 95% is permitted only for an explicitly labelled internal build
  with a root-cause report.

## Identity and persistence

- Process identity remains `(pid, startTimeTicks)` within a session.
- Persistent app pins use package name. Child-process pins use package name and
  process name. Native-process pins use a stable command-name/UID matcher.
- Room adds a pause reason, package-candidate relation, stable pin targets, and
  per-session process summaries to support the specified behavior.
- Session size is presented as an estimate because all sessions share one Room
  database and WAL.
- Export includes every retained process sample, including `DETAIL`, and exposes
  the retention reason.

## UI

- Visual language is inspired by actual iOS 18, while navigation, back handling,
  touch feedback, semantics, and accessibility remain native Android behavior.
- The fixed neutral monitoring theme is the default. Dynamic Material color is an
  optional setting; metric semantic colors never change with the wallpaper.
- No Apple fonts, SF Symbols, or Apple design assets are embedded.
- Real-time background blur is not a core visual dependency.

## Build and release

- The build baseline follows the local ZCamera reference: Gradle 9.4.1,
  Android Gradle Plugin 9.2.0, Kotlin 2.3.21, and JDK 21.
- ProcView keeps its required SDK range: min 30, compile 36, target 36.
- AndroidX Core 1.17 and Lifecycle 2.10 are intentionally pinned because their
  next stable releases require compile SDK 37. Dependency-update lint notices
  are reviewed but do not override the SDK 36 product contract.
- Version 1 is `1.0.0`. Development artifacts use debug signing; release signing
  is supplied or explicitly generated at M6 and is never committed.
