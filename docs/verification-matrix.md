# ProcView verification matrix

| Gate | Evidence | Status |
|---|---|---|
| P0 build, unit test, lint | [`p0-build-baseline.md`](evidence/p0-build-baseline.md) | Passed |
| M0 Xiaomi capability probe | [`m0-probe-readiness.md`](evidence/m0-probe-readiness.md) | In progress |
| M1 typed sampling core | [`m1-sampling-core.md`](evidence/m1-sampling-core.md) | Local checks passed; device accuracy pending |
| CPU/RSS/PSS correctness | Reference-window comparison report | Pending |
| Foreground 1 s timing | Sampling interval distribution | Pending |
| Locked 5 s timing | Sampling interval distribution | Pending |
| Two-hour locked-screen power | A/B battery report | Pending |
| Eight-hour stability | Session export, log and leak checks | Pending |
| Android 11 / 14 / 16 matrix | Per-device capability reports | Pending |
| Restricted OEM degradation | UI and timeline evidence | Pending |
| Anonymous export privacy | Automated sensitive-field scan | Pending |
| Release APK security | Manifest, command audit, signature and hash | Pending |
