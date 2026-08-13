# ProcView

ProcView is a local-first, read-only Android 11+ process monitor backed by
Shizuku. It provides live CPU/RSS/PSS views, named diagnostic sessions, Room
history and synchronized playback, stable application/process pins, and
user-created ZIP exports.

The authoritative scope is [`ProcView-产品与技术规格.md`](ProcView-%E4%BA%A7%E5%93%81%E4%B8%8E%E6%8A%80%E6%9C%AF%E8%A7%84%E6%A0%BC.md).
Accepted specification clarifications are recorded in
[`docs/implementation-decisions.md`](docs/implementation-decisions.md).

## Capabilities

- Typed Shizuku UserService with bounded, fixed read-only procfs/PSS probes and
  a labelled Toybox `ps` fallback for restricted ROMs.
- User-started `specialUse` foreground sessions with pause, resume, stop,
  Binder-death recovery, screen-aware cadence, and explicit timeline gaps.
- Adaptive Compose UI with live search/filter/sort, application and process
  details, 60-second charts, and iOS 18-inspired neutral styling.
- WAL-backed Room history retaining whole-device frames plus the union of CPU
  Top 20, RSS Top 20, pinned targets, and open details.
- Streaming SAF ZIP exports with UTF-8 CSV, previewed field selection, and
  per-export salted pseudonyms in anonymous mode.
- English and Simplified Chinese, system/light/dark modes, optional Android
  dynamic color, capacity warnings, and manual session cleanup.

## Local build

Requirements:

- Android SDK 36
- JDK 21
- Shizuku-compatible Android 11+ device for privileged integration tests

The local wrapper isolates Gradle and Android user caches inside ignored
workspace directories and reads the JBR from `local.properties`:

```powershell
.\gradlew-local.ps1 testDebugUnitTest
.\gradlew-local.ps1 connectedDebugAndroidTest
.\gradlew-local.ps1 lintDebug
.\gradlew-local.ps1 assembleDebug
```

`local.properties`, signing keys, and release credentials are intentionally not
version controlled. A successful desktop build is not a substitute for the
device gates in [`docs/verification-matrix.md`](docs/verification-matrix.md).
Unsigned-build inspection, external-key signing, signature verification, and
artifact hashing are defined in
[`docs/release-checklist.md`](docs/release-checklist.md).

## Use and privacy

Start Shizuku, grant ProcView read-only access, and run the capability probe
before creating a session. See [`docs/user-guide.md`](docs/user-guide.md) for the
workflow and failure states.

The application manifest does not request `INTERNET` or broad storage access.
ProcView contains no arbitrary shell terminal or process-control actions; its
privileged command set is fixed and read-only. See
[`docs/privacy.md`](docs/privacy.md) and
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
