# Fixed development signing verification — 2026-10-15

Configuration and upgrade contract:
[`../development-signing.md`](../development-signing.md).

## Identity preserved

The network-monitoring APK used certificate SHA-256
`c9a77535daec4c4f56ee07c64bd7580e256ff361b23d7eb5298d91e7778cc75f`.
The existing `.android-user-home/debug.keystore` was copied byte-for-byte to
`app/debug.keystore`; no key was generated or borrowed from aidrawer-app.
Both keystore copies have file SHA-256
`2bdddfa3e7277d9ddf0529485edba9a9f5347bcde9ea81d08bbd75b62bd1a405`.

The old instrumentation APK had certificate
`dc96ae5024b297dc74d1d4f5c7f93063295ba058155c63c4d2e495d934f1416d`.
The user explicitly selected preserving the current ProcView key for future
upgrades. Compatibility with that other historical certificate is not claimed.

## Executed checks

1. `verifyDebugSigningKey assembleDebug assembleDebugAndroidTest
   testDebugUnitTest lintDebug --no-daemon`: passed. The certificate guard ran
   before the debug build. All 127 unit tests passed (0 failures/errors); lint
   reported 0 errors, 36 warnings, 1 hint.
2. `apksigner verify --verbose --print-certs` verified the main APK's v2 signature.
   Main and rebuilt instrumentation APKs both have the preserved ProcView
   certificate above.
3. Simulated an unrelated build machine by creating a different default key
   under `build/signing-verification/other-machine-android-home` and pointing
   `ANDROID_USER_HOME` there. The unrelated certificate is
   `0f316b1543b46f7e76d7cbfdd805be4232efe370494c9eaa07f466c6e36d80cf`.
   Saved the first APK, removed only the generated main APK, and rebuilt using
   `gradlew.bat --no-daemon assembleDebug`. `packageDebug` executed; the output
   still carries the preserved ProcView certificate. The two APK file hashes
   are identical, demonstrating that this build ignored the unrelated default key.
4. A temporary, uncommitted Gradle init script overrode only the verification
   task's input with the unrelated key. The task failed with the expected
   `ProcView debug certificate changed` message. The canonical keystore and
   signing configuration were never replaced. The negative test used
   `--no-configuration-cache`; no incorrect task input was cached.
5. `git check-ignore app/debug.keystore` confirms the intentional development-key
   exception; other keystores and release signing material remain ignored.
   `git diff --check` passed.

The checks ran on one Windows machine with isolated Android user directories,
not on two physical build hosts. No Android device was attached, so actual
on-device overwrite installation was not executed.

## Delivered artifact

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- Application ID: `io.github.PctAIGM.procview.debug`
- Version: `1.0.0-debug`, code 1; min SDK 30, target SDK 36
- Size: 67,081,869 bytes
- APK SHA-256:
  `6f971d944ce1bb557cf18a58f30f5fbbc25d9f4589d1d641c8602495b3ab013e`

This artifact replaces the earlier output documented in
[`network-monitoring-readiness.md`](network-monitoring-readiness.md) while
preserving that APK's signing certificate. Network-monitoring code was unchanged.
