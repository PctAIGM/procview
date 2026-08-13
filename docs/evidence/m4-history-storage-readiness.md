# M4 history and storage readiness

## Implemented source paths

- Room version 1 schema covers capability reports, sessions, system samples,
  PID/start-tick identities, package candidates, retained process samples,
  events, stable pin targets, and derived summaries.
- Writes are serialized through a bounded channel, use WAL transactions, and
  flush at 32 frames or five seconds. An independent first-pending-frame timer
  enforces the time bound even when the sampling interval itself exceeds five
  seconds. Session start waits for the actual Room
  transaction before sampling is published. A rejected frame moves the session
  to the dedicated storage pause.
- Startup marks open sessions Interrupted, records restart evidence, and repairs
  missing derived summaries. The new-session recovery gate opens immediately
  after the terminal transaction commits; derived repair then continues
  independently, so a slow or failed summary cannot reopen an old session or
  fail the recovery gate for an otherwise valid new one. Recovery places the
  interruption after the latest system sample or event, so environment events
  recorded during a long sampling pause cannot appear after the terminal marker;
  the same monotonic offset also advances the session duration used by lists and
  exports.
- Storage recovery atomically replays the original storage-pause state/event
  before resuming, and first flushes any earlier batch accepted before a queue
  saturation signal so heartbeat time cannot move backwards. If a Room
  transaction rolls back, the accepted frame/event snapshot remains buffered
  for the recovery retry; later dependent commands are rejected until that
  barrier succeeds. Low-frequency events flush even when no future sample frame
  is guaranteed. A terminal event and its Completed/Interrupted state share one
  Room transaction, so a failed stop cannot leave an early or duplicate terminal
  marker when storage recovery resumes or retries the session. The controller
  reuses the same runtime terminal event across a retry. Any durable terminal
  state releases recorder ownership, while a delayed Finish for an older session
  cannot clear a newer active session. The atomic terminal command followed by
  Finish is idempotent even if the user deliberately deletes the now-terminal
  session between those commands; that recorder-owned acknowledgement does not
  depend on another database read.
- Storage failure while committing an interruption is represented as a
  retryable storage pause. Both Retry and Stop preserve the pending
  `INTERRUPTED` terminal kind, so a boot boundary can never resume sampling or
  be relabelled `COMPLETED` merely because its first terminal transaction failed.
- Start acknowledgement is cancellation-aware across the recorder's independent
  application-lifetime actor. If its Room transaction commits after the service
  caller disappears, the row is atomically closed rather than left `STARTING`.
  The handoff preserves intent: an explicit user stop closes it as `COMPLETED`,
  unexpected caller loss closes it as `INTERRUPTED`, and cancellation after
  acknowledgement is closed through the controller's terminal-event path.
- Terminal summary rebuilds run on a separate serialized worker, are deduplicated
  per completed session, and do not occupy the recorder command loop or delay
  terminal acknowledgement. Summary-backed history lists are observable flows,
  so a completed asynchronous repair updates the UI without reopening the screen.
  Terminal deletion is enforced in SQL, cascades session data, removes orphan
  capability reports, and reclaims pages only when no session is active.
- Identity refreshes preserve previously confirmed UID, primary package,
  application label, command line, and classification when a later procfs or
  PackageManager read is transiently empty. A non-empty authoritative candidate
  set can still replace or clear the old primary package when a UID becomes
  shared, and candidate primary flags are rewritten only when that mapping
  changes. Last-seen offsets are monotonic.
- History provides lists, synchronized cursor playback, explicit/inferred gaps,
  monotonic session duration, retained-process reasons and package candidates,
  context/events, editing, confirmation deletion, and up to three pinned CPU
  overlays, including shared-UID targets. Compatibility-fallback intervals are
  visibly shaded and described in chart semantics rather than relying on color.
  A historical target aggregate becomes unknown if any retained child metric is
  missing, rather than allowing SQLite `SUM` to hide a partial measurement.
  Overlay aggregation only uses rows originally retained for the `PINNED`
  reason, preventing a newly created pin from presenting an incidental Top-20
  subset as complete historical coverage. Missing `FIRST_FRAME` event evidence
  is tolerated once a real sample exists at or before the cursor.
- Long-session cursor lookup and inferred-gap bounds use binary search. Overlay
  database windows refresh in 30-frame buckets instead of on every incoming
  frame while the visible cursor continues to update locally. Multi-series
  values and accessibility summaries read the point selected by that cursor
  rather than always reporting the newest non-empty point.
- Capacity UI reports database/WAL usage, per-session estimates, a configurable
  default-500 MB threshold, and a non-disableable device-free-space 10% warning.
  History-size and storage-health producers preserve structured cancellation:
  leaving or replacing a Compose destination cancels the Room/storage work
  instead of converting cancellation into an empty-value refresh.

## Added verification source

- Instrumentation coverage for terminal cascade deletion, active-session delete
  rejection, summary repair, secondary package-candidate/UID matching, partial
  aggregate rejection, monotonic duration despite wall-clock changes, and a
  forced terminal-state failure that verifies the paired terminal event rolls
  back in the same Room transaction. A paused-timeline case verifies that the
  recovery cursor includes events newer than the last system sample.
- Controller coverage for recorder rejection, atomic storage-pause replay,
  durable-start rejection, terminal completion after storage-pause recovery,
  retryable terminal-write failure without duplicating the completion event,
  and storage recovery that preserves a pending `COMPLETED` terminal instead of
  resuming sampling. Startup backend races and session sequence continuity are
  also covered. Recorder recovery discards identity row IDs from rolled-back
  transactions.
- A generated Room version-1 schema JSON is present in the workspace and
  describes all nine tables. A clean
  generation/identity comparison remains part of the pending Gradle gate.

## Executed and pending evidence

The current source passed 116 host unit tests, Debug lint/build, Room KSP schema
generation, and the minified Release build. On Xiaomi `2509FPN0BC` / Android 16,
direct AndroidJUnitRunner execution passed all eight Room instrumentation tests.
A short device session persisted a Completed history row that was immediately
visible in the history UI. Eight-hour stability, process restart, real disk-full
behavior, and future-version database migration artifacts remain pending.
