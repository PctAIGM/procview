# ProcView privacy statement

ProcView is designed for local diagnostics. It does not request Android's
`INTERNET` permission and does not contain telemetry, advertising, accounts, or
an upload service.

## Data collected locally

Only after the user starts a monitoring session, ProcView may store:

- whole-device CPU and memory counters;
- battery level, charging state, battery temperature, thermal status, and screen
  state;
- visible process PID/UID, start ticks, package candidates, display/process
  names, command line, CPU, RSS, optional PSS, and Linux state;
- session name, note, sampling preset, app/device/ROM version strings, Shizuku
  backend mode, capability report, and lifecycle events.

Command lines can contain sensitive arguments. They remain local unless the user
explicitly includes that field in a regular export. ProcView does not collect a
device serial number, Android ID, account, phone number, contact, location,
media, or message content.

## Storage and deletion

Session data is stored in a private Room database. Completed sessions remain
until the user confirms irreversible deletion. ProcView never deletes old
sessions automatically. It warns at the configured ProcView-data threshold and
whenever device free space falls below 10%.

## User-created exports

Exports use Android's Storage Access Framework; ProcView receives only the
document URI selected by the user and requests no broad storage permission.

The separately triggered capability-report share action opens Android's share
sheet, and the diagnostic ZIP uses the document picker. These compatibility
artifacts are not anonymous session exports: they can include the device
manufacturer/model, Android and ROM display versions, a temporary per-boot ID,
Shizuku and service UIDs, SELinux context, thermal sensor names, capability
counts, timings, and error flags. They contain no monitoring-session samples.
Create or share them only with a recipient and destination you trust.

A regular export can contain local process metadata. An anonymous export uses a
fresh random secret salt for each ZIP, pseudonymizes packages, applications,
processes, commands, and UIDs, omits event payloads, and defaults to relative
time with no session name, note, device details, or timestamp in the suggested
filename. The preview allows the user
to choose the explicitly optional fields. Session name, note, device details,
and wall time are copied as-is when the user explicitly enables them; the
preview warns about this distinction. Device identifiers that ProcView never
collects cannot be added to an export.

`USER_NOTE` timeline events never export their payload. When notes are included,
only the session's current note is exported; previous note text is not retained
in new event records or disclosed from older records.

Deleting an exported document is the responsibility of the user and the chosen
document provider. Deleting a ProcView session does not delete copies previously
exported elsewhere.
