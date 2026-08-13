# ProcView verification matrix

| Gate | Evidence | Status |
|---|---|---|
| P0 build, unit test, lint | [`p0-build-baseline.md`](evidence/p0-build-baseline.md) | Passed |
| M0 Xiaomi capability probe | [`m0-probe-readiness.md`](evidence/m0-probe-readiness.md) | Probe/typed sampling passed; long-run gates pending |
| M1 typed sampling core | [`m1-sampling-core.md`](evidence/m1-sampling-core.md) | Debug build/unit verified; device accuracy pending |
| M2 monitored-session lifecycle | [`m2-lifecycle-readiness.md`](evidence/m2-lifecycle-readiness.md) | Short device lifecycle passed; recovery/long-run pending |
| M3 live UI | [`m3-live-ui-readiness.md`](evidence/m3-live-ui-readiness.md) | Target-phone smoke passed; accessibility/adaptive matrix pending |
| M4 history and storage | [`m4-history-storage-readiness.md`](evidence/m4-history-storage-readiness.md) | KSP/unit and 8/8 device instrumentation passed; stability pending |
| M5 export, privacy and settings | [`m5-export-settings-readiness.md`](evidence/m5-export-settings-readiness.md) | Diagnostic/anonymous SAF exports passed; regular export pending |
| M6 security and release | [`m6-security-release-readiness.md`](evidence/m6-security-release-readiness.md) | Debug/R8 and device instrumentation passed; signing/matrix pending |
| CPU/RSS/PSS correctness | Reference-window comparison report | Pending |
| Foreground 1 s timing | Sampling interval distribution | Pending |
| Locked 5 s timing | Sampling interval distribution | Transition smoke passed; distribution pending |
| Two-hour locked-screen power | A/B battery report | Pending |
| Eight-hour stability | Session export, log and leak checks | Pending |
| Android 11 / 14 / 16 matrix | Per-device capability reports | Pending |
| Restricted OEM degradation | UI and timeline evidence | Pending |
| Anonymous export privacy | Automated sensitive-field scan | Passed on target-device short session |
| Release APK security | Manifest, command audit, signature and hash | Pending |
