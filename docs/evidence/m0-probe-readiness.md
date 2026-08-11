# M0 capability-probe readiness

Status: code-ready; target-device evidence pending.

## Implemented probe boundary

- Shizuku binder, API-version, permission, UserService connection, and death
  states are represented explicitly.
- The non-daemon UserService has a stable tag, process suffix, and protocol
  version. Its AIDL surface returns a structured parcelable and accepts no
  command text.
- Privileged commands are limited to fixed argument arrays for `ps -A -o PID`
  and `dumpsys meminfo --local --checkin <known-pid>`.
- Command execution enforces positive catalogued PIDs, timeouts, bounded output,
  and no shell interpreter.
- The probe measures procfs enumeration/readability, PID 1, PSS, thermal zones,
  package mapping, boot ID, and scan/total duration.
- The UI distinguishes unavailable, denied, incompatible, partial, and available
  states. Coverage is labelled only within enumerated processes.
- A user-triggered share action produces a versioned JSON capability report.

## Local verification

- `testDebugUnitTest`: 11 tests, 0 failures.
- `lintDebug`: 0 errors; 13 reviewed toolchain/version notices.
- `assembleDebug`: passed.
- Debug APK size: 65,705,906 bytes.
- Debug APK SHA-256:
  `52C2F5CFFEA707D6E1DD5151511F6761B8BE550C73195D5D46B67BDC6B176790`.
- Security scan found one `ProcessBuilder` site containing only the two fixed
  read-only command definitions above.

## Device gate still required

`adb devices -l` returned no attached devices on 2026-08-11. M0 remains open
until the accepted Xiaomi 17 Pro Max / Android 16 device is connected and the
full specification checklist is captured, including the exported JSON report,
1-second scan cost, PSS batch cost, special-use foreground service behavior,
screen-off sampling with and without a wake lock, recovery after stopping
Shizuku, HyperOS background behavior, and the two-hour power comparison.
