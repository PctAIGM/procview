# M5 export, privacy, and settings readiness

## Implemented source paths

- Terminal sessions export through SAF to a streaming ZIP with the six specified
  entries. Room cursors stream rows directly; cancellation is checked every 512
  rows. Cancellation remains the primary failure even if closing the partial
  ZIP entry also fails; that close error is retained as suppressed evidence.
- CSV uses UTF-8, CRLF records, RFC 4180-compatible quoting, missing-value
  preservation, and spreadsheet-formula text protection, including formula
  markers after leading Unicode whitespace.
- Anonymous exports use a fresh, unexported 256-bit salt; package, application,
  process, command, and UID values are stable only inside that ZIP. Boot ID,
  default wall time, event payloads, and unselected metadata are removed.
- Thermal sensor names are treated as device details. Note-event payloads are
  removed even from regular exports so older note revisions cannot bypass the
  current-note preview switch.
- Anonymous exports that omit absolute time also omit the timestamp from the
  suggested ZIP filename; ZIP-entry timestamps are fixed.
- The preview controls session name, note, device details, wall time, and command
  field inclusion. File names also respect the session-name choice. Preview and
  pending SAF options survive Activity recreation, while an in-process export
  always clears its running state in a cancellation-safe `finally` block.
- Editing and deletion are disabled while the detail pane is exporting. The
  exporter also rechecks session existence after the ZIP and provider stream
  have fully closed, so deletion from another screen cannot be reported as a
  valid partial export. Provider-stream ownership begins before anonymizer or
  database setup, including setup-failure cleanup.
- DataStore-backed settings cover sampling preset, system/light/dark,
  fixed/dynamic palette, system/Chinese/English, storage threshold, and separate
  regular/anonymous export defaults.
- A separate SAF diagnostic ZIP contains the capability envelope and privacy
  explanation. The settings page also manages pins and displays privacy/version
  information.

## Added verification source

- Anonymous alias stability and salt separation.
- Capability-report field removal/remapping.
- CSV quotes, newlines, Unicode, formula injection, nulls, and 100,000-row
  streaming behavior.
- Required ZIP entry reparse contract and safe file-name generation.
- CSV formula neutralization covers visible operators after leading whitespace
  and raw Tab/CR/LF control prefixes before RFC 4180 escaping.
- Chart text now reports current/range/trend, fixed-height primary actions can
  grow at large font scales, and export previews distinguish pseudonymized
  fields from optional raw metadata.

## Executed and pending evidence

The current source passed 116 host unit tests, Debug lint/build, and the
minified Release build. On Xiaomi `2509FPN0BC` / Android 16, all eight Room
instrumentation tests passed and the settings/live/history surfaces completed a
Simplified Chinese smoke test. A diagnostic ZIP was created through Android's
Downloads SAF provider, pulled back, reopened, and parsed. It contains schema-v1
`capabilities.json` and the privacy `README.txt`, uses fixed entry timestamps,
identifies the Debug application/device/API correctly, and has SHA-256
`6F8FD07CF683AC18F0E3C73F7E11D7674CD111CDC1498888209C355F00D3C2AB`.

The completed short session was also exported anonymously through SAF. The
33,803-byte ZIP has SHA-256
`0A50D4B64CEFC9D8B877D026E36166D4056FAC8E5AE0534FFCDE40021BA0BCDE`
and exactly the six required entries with fixed timestamps. PowerShell's CSV
parser read 60 system rows, 1,996 retained-process rows, and five event rows;
no parsed field began with a spreadsheet formula marker. The manifest declares
anonymous mode, disabled raw metadata switches, UID remapping, and name
pseudonymization. A whole-ZIP scan found none of the known original session
name, representative process/package names, model, ROM build, or boot ID.
Regular full-session export, Excel-specific import, 200% font checks, and
TalkBack remain pending.
