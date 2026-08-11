# P0 build baseline evidence

- Date: 2026-08-11 (Asia/Shanghai)
- Command: `./gradlew-local.ps1 testDebugUnitTest lintDebug assembleDebug`
- Toolchain: JDK 21, Gradle 9.4.1, Android Gradle Plugin 9.2.0,
  Kotlin 2.3.21, compile/target SDK 36
- Result: `BUILD SUCCESSFUL`
- Unit tests: 1 executed, 0 failures, 0 errors
- Android Lint: 0 errors; 13 reviewed version-update notices remain because
  the project intentionally follows the accepted ZCamera and SDK 36 baseline
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- APK size: 65,682,150 bytes
- SHA-256: `DA4A8FC082A8B0983EB64178F263A616F896DF22FDEC7B61E7A9D6E4FA4DBFFC`

`aapt2 dump permissions` confirmed the expected package-monitoring,
foreground-service, notification, wake-lock, and Shizuku permissions. The APK
does not request `android.permission.INTERNET`.

This evidence covers the reproducible engineering baseline only. Device and
product acceptance gates remain tracked separately in the verification matrix.
