# Third-party notices

ProcView's dependency versions are defined in `gradle/libs.versions.toml`.
Release packaging includes a copy of this dependency notice at
`app/src/main/assets/THIRD_PARTY_NOTICES.md`; the release checklist verifies it
and `app/src/main/assets/licenses/Apache-2.0.txt` are present in the final APK.

The principal runtime dependencies are distributed under the Apache License 2.0:

- [AndroidX, Jetpack Compose, Room, DataStore, and Lifecycle](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt)
- [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt)
- [Kotlin serialization](https://github.com/Kotlin/kotlinx.serialization/blob/master/LICENSE.txt)
- [Shizuku API and provider](https://github.com/RikkaApps/Shizuku/blob/master/LICENSE)

This notice is informational and does not declare a license for ProcView's own
source code.
