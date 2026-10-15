# Shizuku network monitoring design change

Requested on 2026-10-15: add app upload/download totals and destination addresses,
using Shizuku and accepting ROM-dependent address availability. This explicitly
extends the frozen v1 exclusion of network monitoring. No VPN or root requirement
is introduced; the APK still has no INTERNET permission.

## Data contract

- User starts monitoring from the Network tab. Polling runs only while this tab
  is resumed. Stop, changing tabs, or backgrounding the app stops new reads.
  An already executing Binder/command read can finish after cancellation.
- A fixed `dumpsys netstats --uid` read supplies UID recorder **History
  since boot** counters. Sum untagged DEFAULT/FOREGROUND histories across network
  identities; ignore device totals, tagged histories, complete persisted history,
  and VPN debug sets to avoid duplicate accounting. These are Android-maintained
  cumulative counters, not a session total, billing statement, or real-time speed.
  Android can update them late; the displayed timestamp is the read time.
  `--full` is deliberately omitted: AOSP switches to complete persisted history
  with that option rather than outputting the since-boot recorder.
- `/proc/net/tcp`, `tcp6`, `udp`, `udp6` supply numeric local/remote addresses,
  ports, protocol, state, and UID. Unconnected UDP/listeners are excluded. Short
  connections between polls are not captured. A remote address can also be the
  peer of an accepted inbound connection. No domain/URL claims, reverse DNS,
  packet inspection, or per-destination byte counts are made.
- Resolve the union of traffic and socket UIDs through PackageManager, including
  apps with no running process. Shared UIDs retain all candidate packages, with
  one total; unknown/system/uninstalled UIDs remain visible by UID.
- Traffic and socket read statuses are independent. Permission denial, command
  timeout, unrecognized format, missing tables, malformed records, and output
  limits must not silently become complete zero-traffic results. Unknown values
  are displayed as an em dash. Empty supported tables can legitimately be empty.

## Resource/privacy boundaries

The UserService caches sockets for 4 seconds and traffic for 15 seconds. The UI
requests a snapshot every 5 seconds after each completed read. The fixed command
has a 5-second timeout and 4 MiB output cap; clipped output is discarded. Each
socket file has a 512 KiB cap. At most 512 traffic UIDs and 512 sockets cross
Binder as serialized typed JSON. The receiver validates sizes, counters, and
field bounds. AIDL and Shizuku service versions advance together to 5 so an old
resident service cannot serve the new client.

Snapshots stay in memory; they are not included in Room sessions, playback,
diagnostic ZIPs, or CSV exports. No network/storage permission is added. No
arbitrary command, path, PID, UID, or shell option is accepted through the new IPC.

## Verification

Unit fixtures cover UID aggregation, duplicate sections, tagged/debug sets,
unsupported/malformed/omitted histories, counter overflow, IPv4/IPv6 byte order,
TCP/UDP remote targets, listeners, missing headers, and entry limits. Desktop
tests/build/lint cannot establish vendor SELinux access or actual ROM dump format.
On a Shizuku-authorized Android 11+ device, compare a known app download/upload
against system counters, check all four tables, shared UID labeling, disconnect
and reconnect, tab/background cancellation, and delayed updates. No physical
device validation is claimed by a successful APK build.

References: [Android proc/net access restrictions](https://developer.android.com/about/versions/10/privacy/changes#proc-net-filesystem),
[AOSP network traffic accounting](https://source.android.com/docs/core/data/ebpf-traffic-monitor),
[AOSP NetworkStatsHistory dump format](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/core/java/android/net/NetworkStatsHistory.java).
The [AOSP recorder implementation](https://android.googlesource.com/platform/frameworks/base/+/f7599ba9eeb39f5d55ae5f57d1634df90a5e2d30/packages/ConnectivityT/service/src/com/android/server/net/NetworkStatsRecorder.java)
defines the distinction between since-boot and complete history.
