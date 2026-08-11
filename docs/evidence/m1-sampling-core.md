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
  and an age timestamp.
- CPU Top 20 and RSS Top 20 are retained as a union. Pin and detail reasons are
  additive bit flags.
- UID mapping preserves every package candidate, selects the longest exact
  Android process-name prefix when possible, and otherwise represents shared
  UID or native identities explicitly. Application aggregation tracks partial
  CPU/RSS/PSS values rather than presenting incomplete sums as complete.
- Fixed-target scheduling skips stale ticks without burst catch-up.
- `PrivilegedMonitorBackend` has both the Shizuku implementation and a fake
  implementation. The Live screen exposes a user-triggered two-frame typed
  sampling check for on-device validation.

## IPC and command boundary

- Protocol version 2 separates bounded numeric frames from chunked string
  catalogs. Catalog chunks contain at most 32 entries and the backend enforces a
  4,096-process ceiling, revision consistency, progress, and transfer bounds.
- PSS requests accept at most 128 typed `ProcessKey` values, only for the current
  catalog. Invalid targets do not consume the rate-limit window.
- AIDL accepts no command string. The only process-launch site uses fixed arrays
  for `ps` and the two `dumpsys meminfo` check-in variants; there is no shell,
  `su`, kill, force-stop, or network permission.

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
