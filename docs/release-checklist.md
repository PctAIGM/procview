# ProcView side-load release checklist

This checklist is intentionally evidence-driven. Do not mark an item complete
from a source review alone, and do not reuse another application's signing key.

## 1. Clean, approved verification

- Confirm the toolchain remains Gradle 9.4.1, AGP 9.2.0, Kotlin 2.3.21, JDK 21,
  and Android SDK 36.
- Run the unit tests, Android lint, and the instrumentation suite from a
  clean checkout using the local wrapper documented in `README.md`.
- Generate and commit the Room version-1 schema under `app/schemas`; compare it
  with the entity and DAO contract before signing.
- Build both debug and minified release variants. Inspect R8 warnings and the
  generated mapping/usage/seeds reports instead of assuming the keep rules are
  sufficient.

## 2. Manifest and package audit

- Inspect the merged release manifest, not only the source manifest.
- Verify the final permission list contains no `INTERNET` or broad storage
  permission.
- Verify `MonitorService` is non-exported and the Shizuku provider retains only
  its required protected exposure.
- Confirm backup/data-transfer exclusion rules and the bundled
  `assets/THIRD_PARTY_NOTICES.md` plus `assets/licenses/Apache-2.0.txt` are
  present in the final APK.
- Search the decompiled release package for an arbitrary command entry point,
  process-control command, embedded credential, local path, or debug endpoint.

## 3. Signing outside the repository

- Create a dedicated ProcView signing key in a protected directory outside the
  repository, or supply an existing ProcView-only key. Never commit the key,
  passwords, or a generated credentials file.
- Align an unsigned release APK with the SDK's `zipalign`, then sign it with
  `apksigner`. Pass passwords through protected environment variables or an
  interactive prompt rather than command-line literals.
- Run `apksigner verify --verbose --print-certs` on the exact APK to distribute.
- Record the certificate SHA-256 digest, APK byte length, and APK SHA-256 hash in
  the M6 evidence document.

## 4. Device gates

- Complete every item in `docs/evidence/m0-probe-readiness.md` first on Xiaomi
  17 Pro Max / Android 16.
- Execute the Android 11, Android 14, AOSP/Pixel Android 16, and restricted-OEM
  matrix from `docs/verification-matrix.md`.
- Attach CPU/RSS/PSS accuracy results, foreground and locked-screen cadence
  distributions, the two-hour locked-screen A/B power report, and the eight-hour
  stability report.
- Exercise notification denial, Shizuku stop/restart and permission revocation,
  reboot recovery, storage exhaustion, process churn/PID reuse, 200% font scale,
  both languages, both themes, and anonymous-export sensitive-field scanning.

## 5. Handoff

- Install the signed APK as a fresh install and as an upgrade over the prior
  signed version.
- Re-run the final manifest, signature, and hash checks after any rebuild.
- Distribute the APK together with `docs/user-guide.md`, `docs/privacy.md`, and
  the compatibility report for the target device.
