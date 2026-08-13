# M1 sampling-core evidence

Status: locally code-ready; target-device correctness evidence pending.

## Implemented contracts

- Process identity is `(pid, startTimeTicks)`. The procfs parser splits after
  the final `)` and rejects negative counters, overflow, malformed fields, and
  a PID that does not match its proc directory.
- Each bounded snapshot reads aggregate CPU, `MemTotal`/`MemAvailable`, process
  CPU ticks, state, RSS, and a separate metadata catalog. A second stat read
  rejects process exit or PID reuse during collection.
- CPU uses process delta divided by aggregate machine delta and is normalized to
  0–100%. The first frame remains unknown; reset and negative deltas are flagged.
- RSS prefers `VmRSS` and falls back to resident pages times the runtime page
  size. PSS uses the machine-readable check-in format, a 15-second cadence
  primitive, bounded target selection, identity revalidation, timeout isolation,
  and an age timestamp. Each asynchronous PSS result carries its originating
  data source; a late result from an earlier procfs/fallback identity domain is
  discarded after a source transition.
- CPU Top 20 and RSS Top 20 are retained as a union. Pin and detail reasons are
  additive bit flags. Pin matching is pre-indexed by package, process name, and
  UID, and one result is shared by history retention and that frame's PSS plan.
- UID mapping preserves every package candidate, selects the longest exact
  Android process-name prefix when possible, and otherwise represents shared
  UID or native identities explicitly. Application aggregation tracks partial
  CPU/RSS/PSS values rather than presenting incomplete sums as complete.
  PackageManager lookups use thread-safe, 30-second TTL caches capped at 512
  UIDs and 1,024 package metadata rows, so a process-list revision does not
  re-query every unchanged UID while long sessions still refresh package state.
  Transient PackageManager/Binder failures are never inserted into those caches.
  Resolution runs immediately on a catalog revision and at a bounded 30-second
  cadence for an otherwise stable catalog, allowing package changes and a
  failed lookup to recover without waiting for process churn.
- Fixed-target scheduling skips stale ticks without burst catch-up.
- The capability probe now executes and parses the same fixed-column Toybox
  snapshot used by sessions. If that bounded snapshot has better coverage than
  direct per-process reads, it becomes the effective path for the 95% release
  gate instead of leaving fallback code unreachable in release builds. It
  supplies a visibly labelled degraded source, converts CPU counters to a common unit, corrects
  Toybox RSS for the runtime page size, tracks rounded fallback start times, and
  revalidates fallback identities before accepting PSS. A reused PID must also
  retain the full command line and a non-regressing cumulative CPU counter;
  changed arguments, a counter reset, or absence from one complete fallback
  snapshot produces a new fallback identity.
- `PrivilegedMonitorBackend` has both the Shizuku implementation and a fake
  implementation. The Live screen exposes a user-triggered two-frame typed
  sampling check for on-device validation.

## IPC and command boundary

- Protocol version 4 separates bounded numeric frames from chunked string
  catalogs. Catalog chunks contain at most 32 entries and the backend enforces a
  4,096-process ceiling, revision consistency, progress, transfer bounds, and
  unique process keys. PSS replies reject invalid, non-requested, negative, or
  duplicate identity values instead of silently collapsing them.
- The v4 capability parcel reports fixed-snapshot parsed/readable counts,
  duration, viability, and the selected procfs/fallback path. The UI exposes
  that decision instead of presenting an effective fallback count as procfs.
- The capability probe exercises the same bounded batch-PSS command used by
  sessions. All procfs CPU/RSS/readability numerators and parsed PSS values are
  intersected with the same current `ps -A` reference PID set before coverage is
  calculated, so process churn cannot produce a numerator above its displayed
  denominator. That independent `ps -A` reference is a release hard gate; a
  failed enumeration cannot fall back to `/proc` as its own denominator and
  claim a false 100% result. Binder counts and a selected fallback decision are
  range- and consistency-checked before a report can become `AVAILABLE`.
  Single-target and batch timing are recorded independently. The
  check-in parser accepts only the verified Android v3/v4 row layouts so an
  unknown future format cannot silently move the total-PSS field.
- PSS requests accept at most 128 typed `ProcessKey` values, only for the current
  catalog. Invalid targets do not consume the rate-limit window.
- AIDL accepts no command string. The only process-launch site uses fixed arrays
  for the probe/fallback `ps` forms and the two `dumpsys meminfo` check-in
  variants; there is no shell, `su`, kill, force-stop, or network permission.

## Local verification on 2026-08-11

- `testDebugUnitTest`: 10 suites, 36 tests, 0 failures, 0 errors, 0 skipped.
- `lintDebug`: 0 errors and no source warning introduced by M1; 13 reviewed
  SDK/toolchain/dependency version notices remain from the frozen SDK 36 build
  baseline.
- `assembleDebug`: passed.
- `git diff --check`: passed.
- Debug APK size: 65,713,745 bytes.
- Debug APK SHA-256:
  `C2B3C4DA468C0FC5C2209AA4D358A7C4C72A9EAE19F3EABAC8247308DDEDFAA7`.

## Device gate still required

`adb devices -l` returned no attached devices on 2026-08-11. The local checks
prove parser and orchestration behavior, but not ROM visibility or measurement
accuracy. M1 remains device-pending until the accepted Xiaomi 17 Pro Max /
Android 16 run captures two stable typed frames and the CPU, RSS, and PSS
reference-window comparisons. M0 also remains open for foreground-service,
screen-off, wake-lock, HyperOS, and power evidence.

The fallback additions and their new parser/source-transition tests were made
after the historical local run above and remain unexecuted in this environment.
The later PackageManager-cache TTL/LRU tests are also source-only inventory
until the approved verification run.
