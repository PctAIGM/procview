# ProcView user guide

## 1. Prepare Shizuku

1. Install Shizuku from an official channel.
2. On Android 11 or newer, start Shizuku using wireless debugging (or an
   explicitly trusted root setup).
3. Open ProcView and grant its read-only Shizuku request.
4. Run the capability probe. A release-eligible path requires at least 95%
   CPU/RSS coverage against the `ps -A` reference enumeration (falling back to
   the procfs count only when that command is unavailable).

The probing screen shows its last completed time, the enumerated/CPU/RSS/PSS
readable counts, and both single-target and batch PSS cost. You can cancel an
in-progress check; ProcView discards that generation and offers a clean retry.

Shizuku normally needs to be started again after a phone reboot. ProcView keeps
existing history browsable when Shizuku is unavailable.

## 2. Monitor

From **Live**, start a named session and choose Fine, Balanced, or Power saver.
Fine and Balanced hold a partial wake lock while the screen is off; the start
dialog explains the battery trade-off. Android displays a foreground-service
notification with pause/resume and stop actions.

Search by application, package, process, or PID. Filter user/system/pinned
targets, choose CPU/RSS/PSS/name sorting, expand applications, and open a process
for identity and trend details. ProcView deliberately provides no kill, force
stop, freeze, or optimization action.

Detail charts show the latest 60 seconds. Their peak card is independent of that
chart window and keeps the highest CPU/RSS/PSS value observed for the current
identity across the active session. Exited process identities remain available
for the bounded live-history tick; application peaks survive normal package
stop/restart cycles in a bounded session cache.

An application normally pins by package. If Android reports several package
candidates for one shared UID and no single primary package can be selected,
ProcView labels it **Shared UID** and pins every process belonging to that UID.
Pin an individual process when a narrower target is required.

The Live and history views always label the active data source. `ps fallback`
means the ROM restricted the preferred per-process procfs path; values and PID
identity behavior on that device must be interpreted as degraded compatibility
data rather than a silent substitute for a successful primary probe.

## 3. Review history

**Sessions** shows terminal and interrupted sessions, estimated size, peaks, and
top-process summaries. Open a session and move the synchronized timeline cursor.
Recorded Shizuku gaps and inferred sleep gaps remain empty rather than borrowing
a nearby sample. Shaded CPU/memory regions are explicitly labelled as the `ps`
compatibility fallback. Up to three currently pinned targets can be overlaid on
the CPU timeline. Shared-UID targets include all matching UID samples and
historical process rows retain every package candidate reported at collection
time.

Names and notes can be edited after a session ends. Deletion requires a second
confirmation and cannot be undone.

## 4. Export

Choose **Export ZIP** or **Anonymous ZIP** in a terminal session. Review every
optional field, continue to Android's document picker, and select a destination.
The ZIP contains:

- `manifest.json`
- `system.csv`
- `processes.csv`
- `events.csv`
- `capabilities.json`
- `README.txt`

CSV files use UTF-8 and RFC 4180-compatible quoting. Text that could be
interpreted as a spreadsheet formula, including after leading whitespace, is
prefixed with an apostrophe. Anonymous aliases are stable only inside that one
export. If session name, note, device details, or absolute wall time are
explicitly enabled for an anonymous export, those selected fields are copied
as-is; the preview calls this out before Android's document picker opens.

## 5. Settings and troubleshooting

Settings controls the next session's default preset, theme, palette, language,
pins, capacity threshold, export defaults, diagnostics, and version information.
Changing theme or language recreates only the activity; an active foreground
monitoring service continues.

**Share capability report** opens Android's share sheet, while **Export
diagnostic ZIP** opens the document picker. Both are explicit compatibility
actions and may disclose device/ROM details, the current boot ID, service UIDs,
SELinux context, thermal sensor names, and probe results. They do not include
monitoring-session samples and are not anonymous exports.

- **Shizuku stopped or permission revoked:** the session pauses and opens a data
  gap. Restart/re-authorize Shizuku to resume on the same boot.
- **Phone reboot:** an open session becomes Interrupted; start a new session.
- **Storage write failure:** persistence and sampling pause. Export/delete data,
  then use Retry storage.
- **Earlier session cannot be recovered at startup:** ProcView blocks a new
  session instead of creating two open histories. Check free storage, then
  restart ProcView.
- **Notification permission denied:** Android can still show the foreground
  service under Active apps, but drawer controls may be hidden.
- **PSS unavailable or slow:** CPU/RSS continue; the last PSS is shown with age.
