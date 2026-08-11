# ProcView

ProcView is a read-only Android 11+ process monitor backed by Shizuku. It shows
real-time CPU and memory usage, records user-initiated diagnostic sessions, and
reports the actual visibility available on each ROM without claiming access to
hidden processes.

The authoritative product scope is defined in
[`ProcView-产品与技术规格.md`](ProcView-%E4%BA%A7%E5%93%81%E4%B8%8E%E6%8A%80%E6%9C%AF%E8%A7%84%E6%A0%BC.md).

## Local build

Requirements:

- Android SDK 36
- JDK 21
- Shizuku-compatible Android 11+ device for privileged integration tests

On this workstation, the local wrapper isolates Gradle and Android user caches
inside ignored workspace directories and reads the JBR from `local.properties`:

```powershell
.\gradlew-local.ps1 testDebugUnitTest
.\gradlew-local.ps1 lintDebug
.\gradlew-local.ps1 assembleDebug
```

`local.properties`, signing keys, and release credentials are intentionally not
version controlled.

## Privacy boundary

The application manifest does not request `INTERNET`. ProcView contains no
arbitrary shell terminal and exposes only fixed, read-only privileged probes.
