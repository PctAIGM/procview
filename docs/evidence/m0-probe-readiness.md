# M0 capability-probe readiness

Status: target Xiaomi/Android 16 capability probe and short-session smoke test
passed; long-duration, failure-recovery, and power gates remain open.

## Implemented probe boundary

- Shizuku binder, API-version, permission, UserService connection, and death
  states are represented explicitly.
- The non-daemon UserService has a stable tag, process suffix, and protocol
  version. Its AIDL surface returns a structured parcelable and accepts no
  command text.
- Privileged commands are limited to fixed argument arrays for `ps -A -o PID`,
  a numeric/wide fixed-column `ps` compatibility snapshot, single-PID
  `dumpsys meminfo --local --checkin <known-pid>`, and bounded batch
  `dumpsys meminfo --local --checkin`.
- Command execution enforces positive catalogued PIDs, timeouts, bounded output,
  and no shell interpreter.
- The probe measures procfs enumeration/readability, PID 1, the actual runtime
  batch-PSS path, single-target and batch PSS cost, thermal zones, package
  mapping, boot ID, and scan/total duration. CPU/RSS/readability PID sets are
  intersected with the same `ps -A` reference set used by the displayed
  denominator, so process churn cannot produce a numerator above enumeration.
- The UI distinguishes unavailable, denied, incompatible, partial, and available
  states. Coverage is labelled only within enumerated processes and separately
  shows the enumerated, CPU-readable, RSS-readable, and PSS-readable counts. Its
  integer percentage is truncated rather than rounded, so a below-95% result is
  never displayed as meeting the 95% release threshold.
- A user-triggered share action produces a versioned JSON capability report.

## Historical local verification

- `testDebugUnitTest`: 11 tests, 0 failures.
- `lintDebug`: 0 errors; 13 reviewed toolchain/version notices.
- `assembleDebug`: passed.
- Debug APK size: 65,705,906 bytes.
- Debug APK SHA-256:
  `52C2F5CFFEA707D6E1DD5151511F6761B8BE550C73195D5D46B67BDC6B176790`.
- Security scan found one `ProcessBuilder` site containing only fixed read-only
  command definitions. These results predate the compatibility-snapshot parser.

Current source adds bounded fixed-column parsing, source-change flags, 16 KiB
page-size correction, degraded PID identity tracking, and a protocol-v4 batch
PSS readability count. The current host suite executes 116 tests with zero
failures, and the Debug/instrumentation APKs compile successfully.

## Executed target-device evidence

On 2026-08-12, ADB detected Xiaomi model `2509FPN0BC` (`popsicle`) running
Android 16 / API 36, build `BP2A.250605.031.A3`, HyperOS `OS3.0`. Shizuku
13.6.0.r1086 is installed. The user confirmed that HyperOS's USB-install option
was already enabled. Gradle UTP's combined APK installation was rejected with
`INSTALL_FAILED_USER_RESTRICTED`, but separately installing the Debug main APK
and instrumentation APK succeeded. Direct AndroidJUnitRunner execution passed
all eight tests (`OK (8 tests)`). The installed and tested application ID was
`io.github.PctAIGM.procview.debug`.

The in-app capability probe reported Shizuku ADB UID 2000, 1,080 enumerated
processes, 1,079 CPU/RSS-readable processes (99% coverage), and 151 PSS-readable
processes. Its catalog scan took 322 ms and the complete probe, including the
batch PSS path, took 5,872 ms; batch PSS took 5,010 ms. A subsequent typed
two-frame check measured a 994 ms interval, 1,053 CPU/RSS-readable processes,
24 PSS results, 763 directory/application entries, a 340 ms primary frame, and
zero source flags.

A 1 minute 26 second fine-preset session ran as a `specialUse` foreground
service. It sampled every 1,000 ms, exposed pause/stop notification actions,
held no screen-off wake lock while the screen was on, paused at frame 39,
remained at frame 39 for five seconds, resumed to frame 49, then stopped and
persisted a completed history row. `dumpsys` confirmed the foreground service;
the sampled log window contained no ProcView crash or ANR.

M0 remains open until the remaining specification checklist is captured,
including screen-off sampling with and without a wake lock, recovery after
stopping Shizuku, HyperOS background behavior, and the two-hour power
comparison. A diagnostic ZIP was saved through Android SAF and independently
reopened: it contains schema-v1 `capabilities.json` plus `README.txt`, both with
the fixed 1980 ZIP timestamp. Its SHA-256 is
`6F8FD07CF683AC18F0E3C73F7E11D7674CD111CDC1498888209C355F00D3C2AB`.

A second short balanced-preset session exercised the screen-off transition.
While `dumpsys power` reported Dozing, it showed the expected partial wake lock
`io.github.PctAIGM.procview:monitor`; the lock was released after wake. The
exported data contains a screen-off row with a 5,000 ms target and 232 ms
collection duration. Only one complete screen-off row was captured, so this is
transition smoke evidence rather than the required timing distribution or
power comparison.
