# Stable development APK signing

ProcView debug builds use the repository's `app/debug.keystore` explicitly.
Android Studio, `gradlew`, `gradlew-local.ps1`, and CI all use this file instead
of the machine-specific Android user directory. Include this file when copying
or committing the source; `.gitignore` deliberately allows this one keystore.

This follows aidrawer-app's fixed development-keystore approach while retaining
ProcView's own current certificate. The aidrawer-app key was not reused.

## Fixed identity

- Debug application ID: `io.github.PctAIGM.procview.debug`.
- Key alias: `androiddebugkey`; development store/key passwords: `android`.
- Certificate SHA-256:
  `c9a77535daec4c4f56ee07c64bd7580e256ff361b23d7eb5298d91e7778cc75f`.
- Keystore file SHA-256:
  `2bdddfa3e7277d9ddf0529485edba9a9f5347bcde9ea81d08bbd75b62bd1a405`.

`verifyDebugSigningKey` validates the pinned certificate and private-key entry
before debug app and debug instrumentation builds. Missing or replaced keys fail
the build; never generate a replacement to work around the failure. The known
development password and key are intentionally shared, so this is not a private
production signing identity. Release signing remains separately protected.

## Build and verify

On any configured JDK 21 / Android SDK 36 machine:

```text
gradlew verifyDebugSigningKey assembleDebug assembleDebugAndroidTest
```

Use `./gradlew` on Linux/macOS, `gradlew.bat` on Windows, or the documented local
PowerShell wrapper. The certificate check runs automatically with normal debug
builds as well. Verify the exact output using the SDK's:

```text
apksigner verify --verbose --print-certs app/build/outputs/apk/debug/app-debug.apk
```

The debug app and its instrumentation APK must carry the same certificate.
Changing `ANDROID_USER_HOME`, Windows user, checkout location, or CI host must
not change the output certificate. Source updates must include the fixed key and
Gradle configuration together; do not distribute only the configuration change.

## Upgrade compatibility

The fixed certificate is the one used for the network-monitoring APK delivered
on 2026-10-15. Future debug APKs built from this configuration can update that
installation when their application ID is the same and their version code is
not lower. Keep version codes increasing for future releases.

The older locally retained instrumentation APK has certificate SHA-256
`dc96ae5024b297dc74d1d4f5c7f93063295ba058155c63c4d2e495d934f1416d`.
An installed app using that certificate (or any other certificate) cannot be
updated with this key merely by changing Gradle configuration. Retaining such
an installation requires its original signing key. Otherwise a one-time data
migration/reinstallation is necessary; do not uninstall automatically, since
uninstalling deletes the app's local sessions. The user explicitly chose to fix
the current ProcView key rather than switching to aidrawer-app's certificate.

See [Android app-signing documentation](https://developer.android.com/studio/publish/app-signing)
for the signing identity and update requirements.

Executed two-environment build and wrong-key rejection checks are recorded in
[`evidence/development-signing-verification.md`](evidence/development-signing-verification.md).
