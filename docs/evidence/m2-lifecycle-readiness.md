# M2 lifecycle readiness evidence

Date: 2026-08-11

Status: build/unit and short target-device lifecycle smoke test passed; extended
device exit gates remain open.

## Implemented

- Explicit session state machine covering not-ready, ready, starting, running,
  user/Shizuku/storage pause, completed, and interrupted states.
- User-initiated `specialUse` foreground service with immediate promotion,
  private lock-screen notification content, immutable explicit actions, and
  idempotent start/pause/resume/stop handling. A safe Starting notification is
  posted before Room, Shizuku, or environment initialization can consume the
  platform foreground-service deadline, then replaced with the Intent-specific
  session state. Durable session recovery keeps a truthful Starting notification
  visible, and a new start cannot interleave with an unfinished stop.
  Pause/resume taps received while a stop is awaiting its durable terminal
  acknowledgement are ignored instead of ending the service early.
- A stop received during startup actively cancels the preflight/start job,
  waits for its cancellation-safe durable handoff, then completes an already
  created session or exits without waiting for the capability-probe timeout.
  The cancellation cause distinguishes an explicit user stop from owner loss:
  an in-flight durable user stop is awaited as `COMPLETED`, while an unexpected
  service cancellation remains `INTERRUPTED`.
- Foreground/background/screen-off sampling cadence switching for the fine,
  balanced, and power-saver presets.
- Timed, non-reference-counted partial wake lock held only while a supported
  preset is actively sampling with the screen off.
- Shizuku disconnect handling, bounded exponential UserService rebind, same-boot
  recovery, and exactly one data-gap start/end pair per outage. Reconnection
  refreshes the catalog and continues a session-owned frame sequence even when
  the replacement UserService restarts its counters. A protocol-mismatched
  endpoint is destroyed immediately and remains an explicit error instead of
  being retained or entering an automatic bind loop.
- Stopping the foreground service terminates its non-daemon UserService binding.
  The last capability report remains visible while idle, but a new service
  verifies that the cached `AVAILABLE`/`PARTIAL` report also has a live Binder;
  otherwise it moves the controller to not-ready and performs a fresh
  bind/probe before persisting the next session. This prevents a second session
  from inheriting stale readiness while honoring the stop-time unbind contract.
  A later Shizuku manager Binder-ready callback also preserves this dormant
  state; only an explicit refresh or next-session preflight may bind again.
  An overlapping bind/probe cancellation restores an actionable idle,
  permission, or Shizuku-not-running state instead of leaving the UI stuck in
  `CONNECTING`/`PROBING`; protocol mismatch invalidates the cached report.
- Sampling stop/restart is serialized, so an interval change racing a Binder
  recovery cannot create two collectors and trip the backend's single-consumer
  guard. A fresh service also re-probes when an application-scoped `READY`
  snapshot has no controller-owned capability report.
- Backend changes arriving during the durable start transaction are serialized;
  a disconnect becomes an active Shizuku pause and a changed boot ID interrupts
  the session before sampling. The post-commit race check reads only the bounded
  boot ID; it does not repeat the full PSS capability probe for every session.
  If persisting that interrupted terminal transition fails, the service remains
  in a retryable storage pause; recovery retries `INTERRUPTED` without sampling
  the new boot or converting the session to user-completed. Service shutdown is
  requested only after the controller returns from the durable terminal
  acknowledgement, rather than racing it from a raw runtime-state observer.
- CPU/RSS sampling isolated from optional PSS work so a slow or failed PSS read
  cannot stop the primary frame stream.
- Release builds reject partial capability reports; debug builds label and may
  exercise the partial-coverage path for internal diagnosis.
- Runtime events and notification updates are bounded and contain no arbitrary
  shell entry point or network dependency. Process peaks are pruned with the
  bounded 60-second live window. Application peaks use an access-ordered,
  4096-entry cap that always protects currently visible application IDs, so a
  normal package stop/restart retains its full-session peak without allowing
  pathological churn to grow service memory without bound.
- An OEM failure while registering protected battery/screen broadcasts degrades
  to point-in-time `PowerManager` state instead of crashing an already promoted
  foreground service; teardown unregisters only a successfully registered
  receiver.
- Capability probing can be cancelled without allowing its stale Binder result
  to overwrite a later state.

## Local automated evidence

The last completed local run before the final defensive patches passed 53 unit
tests, `lintDebug` with 0 errors and 13 reviewed SDK/dependency-version warnings,
and `assembleDebug`. Its APK was 66,172,037 bytes with SHA-256
`5F13C540AE17ED41F15F2895133F994D452E4F9317716B156FF04D0592C9D590`.

The current source contains 116 unit-test methods and eight Android
instrumentation-test methods. New lifecycle coverage includes
partial-capability policy, storage-pause replay, stopping from a recovered
storage pause, retrying either pending terminal kind from storage recovery,
data-source transitions, startup disconnect/boot races, and same-session
sequence continuity. A failed `COMPLETED` write can no longer turn a storage
retry into resumed sampling. User and Shizuku recovery before the first
accepted frame now remain in `STARTING` rather than publishing a false running
state. A fresh Gradle run is pending because
execution approval was denied. The previous artifact and report are retained as
historical evidence and are not represented as validation of the newest source.

`git diff --check` passes for the current worktree.

## Static manifest/security audit

- `MonitorService` is non-exported and declares `specialUse` plus its subtype.
- Declared runtime capabilities are package visibility, foreground service,
  special-use foreground service, notification, wake lock, and Shizuku.
- `INTERNET` and broad storage permissions are absent.
- Notification action pending intents are explicit and immutable.

## Required device evidence

M2 is not accepted until all of the following are recorded:

1. API 36 emulator or AOSP device foreground-service smoke test.
2. Xiaomi 17 Pro Max / Android 16 two-hour locked-screen balanced session.
3. Sampling interval distribution, wake-lock release, and battery delta.
4. Shizuku stop/restart on the same boot with visible gap markers and recovery.
5. Authorization revocation and process-kill behavior.

On 2026-08-12, Xiaomi `2509FPN0BC` / Android 16 / HyperOS OS3.0 completed a
1 minute 26 second fine-preset Debug session. `dumpsys` confirmed a running
`specialUse` foreground service and a two-action ongoing notification. Sampling
ran at 1,000 ms; pausing held the frame count at 39 for five seconds, resuming
advanced it to 49, and stopping removed the service and persisted a Completed
history row. No ProcView crash or ANR appeared in the checked log window. This
is smoke evidence only and does not replace the five extended gates above.

A second balanced-preset session confirmed the screen-off transition on the
same device: the system entered Dozing, ProcView acquired its named partial wake
lock, emitted a row with a 5,000 ms target period, and released the lock after
wake. The row's collection duration was 232 ms. The session was intentionally
short and therefore does not satisfy the two-hour cadence or battery gate.
