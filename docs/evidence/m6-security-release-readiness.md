# M6 security and release readiness

Date: 2026-08-12

Status: debug, minified-release build, and target-device instrumentation gates
passed; release signing and the remaining device matrix remain open.

## Static manifest boundary

- Requested permissions are `QUERY_ALL_PACKAGES`, foreground service,
  special-use foreground service, notifications, and wake lock.
- Source manifest contains no `INTERNET`, broad storage, boot-completed, usage
  stats, accessibility, overlay, device-admin, install, or process-control
  permission.
- The launcher activity is exported for its launcher intent. `MonitorService`
  is non-exported. The exported Shizuku provider is the required upstream
  integration component and is protected by its platform permission.
- Backup is disabled and data-extraction rules exclude the database, files,
  root domain, and shared preferences from cloud backup and device transfer.
- Cleartext network traffic is disabled even though the application requests no
  network permission.
- The current Android foreground-service reference lists no runtime prerequisite
  for `specialUse`; ProcView supplies the required base/type permissions,
  manifest service type, subtype explanation property, and matching
  `startForeground` type constant. Android 16's forced edge-to-edge and
  predictive-back changes are handled with `enableEdgeToEdge`, insets-aware
  Compose scaffolding, the platform back callback enabled, and Compose
  `BackHandler` for in-app detail navigation.

References: [foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types),
[Android 16 target behavior](https://developer.android.com/about/versions/16/behavior-changes-16).

## Fixed privileged command boundary

There is one private `ProcessBuilder` call site. It can be reached only by four
internally constructed, read-only argument arrays:

1. `ps -A -o PID` for capability counting;
2. numeric, wide, fixed-column `ps` for the visibly labelled compatibility
   fallback;
3. `dumpsys meminfo --local --checkin <validated-current-pid>`;
4. bounded batch `dumpsys meminfo --local --checkin`, used by both the
   capability count and session PSS sampling.

No command text crosses AIDL. There is no shell interpreter, `su`, write
command, kill/freeze/force-stop path, or caller-provided option. Command time,
output, catalog size, string size, PSS target count, and request cadence are
bounded. Fallback parsing rejects malformed/overflowing time and identity data,
tracks source changes, splits identities after an observed PID gap, and
revalidates the current fallback identity before a PSS result is accepted.

## Local data and export boundary

- Monitoring data remains in the app-private Room database; SAF writes only to
  a document URI explicitly selected by the user.
- Anonymous exports use a fresh unexported 256-bit salt, pseudonymize package,
  application, process, command, and UID values, omit event payloads, and omit
  optional raw metadata by default.
- Both regular and anonymous previews explain sensitive fields. Session name,
  note, device details, and wall time are included in anonymous output only
  after the user explicitly enables their switches.
- Anonymous exports without absolute time use a timestamp-free suggested
  filename and fixed ZIP-entry timestamps.
- CSV text is quoted and protected against spreadsheet-formula execution; ZIP
  generation streams bounded cursor rows and deletes a partial target after
  failure or cancellation when the provider permits deletion. A final source
  session existence check runs only after the ZIP/output stream has fully closed
  and rejects cross-screen deletion during export. Output ownership begins
  before database/anonymizer setup so setup failures cannot leak a provider
  stream.
- A third-party runtime notice and the Apache-2.0 license text are bundled as
  APK assets. Signing material and release credentials remain ignored and
  outside the source tree.
- Navigation is implemented directly with saveable Compose state and the
  monitor is a platform `Service`; unused Navigation Compose and
  LifecycleService runtime dependencies were removed from the release graph.

## Current static evidence

- `git diff --check` passes.
- All 19 source XML files parse; the adaptive launcher icon has separate API 26
  and API 33 variants so Android 11 never receives the newer monochrome node.
- English and Simplified Chinese contain the same 326 string keys, and all
  source string references resolve.
- Current source contains 116 unit-test methods and eight Android instrumentation
  test methods.
- Apart from the specification-required `QUERY_ALL_PACKAGES`, text search finds
  no forbidden permission or process-control command. The one `ProcessBuilder`
  site is the fixed runner described above.
- KSP generation succeeds against the Room version-1 schema: nine tables, no
  views, identity hash `215709e2364e2b7561d857d17543e656`, schema SHA-256
  `A88121997F30F7F45076886EA477D9D7111E415FC33F92A436B9AEE5B5989669`.
- Suspend-aware UI error fallbacks rethrow `CancellationException`; broad
  exception handling is reserved for non-cancellation query/storage failures.

## Executed debug verification on 2026-08-12

- Command: `testDebugUnitTest lintDebug assembleDebug --no-daemon`, using the
  Android Studio JDK 21 configured in `local.properties`.
- Result: `BUILD SUCCESSFUL`; 25 suites, 116 tests, zero failures/errors/skips.
- Lint: zero errors, 23 reviewed warnings, and one hint. Warnings are limited to
  the frozen SDK/tool/dependency baseline, sideload locale/AAB guidance,
  plural suggestions, the intentional API 26/33 adaptive-icon split, and one
  KTX style suggestion.
- Debug APK: 67,500,290 bytes; SHA-256
  `816F7E2E0DEA8F86DE67F45234620F4A73ED8438D490743797A53B02C2F52F57`.
- The merged debug manifest has no `INTERNET` or storage permission. In addition
  to ProcView's five declared permissions, it contains AndroidX's scoped
  dynamic-receiver permission and Shizuku's protected API permission.

On Xiaomi `2509FPN0BC` / Android 16 / HyperOS OS3.0, separate installation of
the Debug main and instrumentation APKs succeeded and direct
AndroidJUnitRunner execution passed all eight tests (`OK (8 tests)`).

## Executed release build on 2026-08-12

- `assembleRelease assembleDebugAndroidTest --no-daemon`: `BUILD SUCCESSFUL`.
- R8/minification and resource shrinking completed with no `missing_rules.txt`
  or warnings file. Mapping, usage, seeds, resources, configuration, Compose
  mapping, and baseline-profile outputs were generated.
- Unsigned release APK: 3,462,072 bytes; SHA-256
  `585072810E00A0A45312D01213BE39D92020EEE8489C639EF21D8F69EDE335C9`.
  R8 mapping SHA-256:
  `59D8A5E5475CADE3BA07316E9EC29A812ACBA73D364699A7D4238A5D85D6E2A3`.
- APK inspection confirms the expected permission boundary and bundled
  third-party notice/Apache-2.0 assets. `apksigner` correctly reports the
  artifact as unsigned; no release key has been created or reused.
- The 2,377,792-byte instrumentation APK compiled successfully. Gradle UTP's
  combined install was rejected by HyperOS with `INSTALL_FAILED_USER_RESTRICTED`
  despite USB installation being enabled. Installing the two APKs separately
  worked; direct runner execution completed all eight tests with zero failures.
- After the post-build UI-state review fix, Release/R8 and the instrumentation
  APK were rebuilt successfully and all eight device tests passed again
  (`OK (8 tests)`, 0.146 seconds).
- The Debug package then completed a 1 minute 26 second foreground-monitoring
  smoke test, including pause, stable paused frame count, resume, stop, and
  persisted history. The checked log window contained no crash or ANR.

## Required release evidence

M6 remains open until a separately protected signing key and signed APK are
produced and verified. The wider Android 11/14/16 and restricted-ROM matrix,
reference CPU/RSS/PSS accuracy, two-hour locked-screen power test, and eight-hour
stability test also remain mandatory.

The exact artifact, signing, and handoff sequence is recorded in
`docs/release-checklist.md`.
