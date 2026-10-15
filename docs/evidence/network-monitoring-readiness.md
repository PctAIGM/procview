# Shizuku network monitoring validation — 2026-10-15

Scope: [`../network-monitoring.md`](../network-monitoring.md).

The later fixed-signing build replaced the APK at the same output path while
preserving its certificate. Current artifact details and signing checks are in
[`development-signing-verification.md`](development-signing-verification.md).

## Desktop checks

- `testDebugUnitTest`: **127 tests, 0 failures, 0 errors**; the network suite
  contains 11 tests, covering parser/accounting boundaries, IPv4/IPv6, UDP,
  unsupported formats, partial reads, counter overflow, filtering, and JSON size.
- `assembleDebug`: passed.
- `lintDebug`: passed with no errors. No warnings reference the new network code.
- `git diff --check`: passed.
- `apksigner verify --verbose`: passed; APK Signature Scheme v2 verified.
- `aapt dump permissions`: no INTERNET or broad storage permission.
- Package: `io.github.PctAIGM.procview.debug`; version `1.0.0-debug` (code 1);
  min SDK 30, target SDK 36.

APK: `app/build/outputs/apk/debug/app-debug.apk`, 67,830,132 bytes.

SHA-256:

```text
ca48c625f18e7955b1fbedace234f13188bf835fc102e60aaeeb1d093319803b
```

Local build invocation (process environment only; no committed Gradle changes):

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=E:/github/procview/build/java-sockets'
.\gradlew-local.ps1 --no-daemon testDebugUnitTest assembleDebug lintDebug
```

The local socket directory avoids a reproduced JBR `UnixDomainSockets.connect0`
failure when Gradle initializes its local transport. The directory and all build
outputs are ignored. The regular sandbox also denied access to an existing
Gradle wrapper lock; validation ran with the approved project write scope.

## Device gates pending

`adb devices -l` returned no connected device. No APK installation, screenshot,
physical-device UI interaction, ROM netstats fixture, SELinux socket-access check,
live upload/download accuracy check, or lifecycle smoke test was performed.
The app exposes independent network read statuses; successful desktop checks do
not establish runtime availability on a particular phone.
