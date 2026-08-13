# M3 live UI readiness

Date: 2026-08-11

Status: source/build and target-phone smoke test passed; adaptive, font-scale,
and TalkBack exit gates remain open.

## Implemented source paths

- The three top-level destinations use a compact bottom bar on phones and a
  navigation rail from 600 dp. Live and history details become side-by-side at
  900 dp while retaining native Android back behavior.
- Destination saveable-state storage keeps search, filter, stable sort,
  expanded rows, selected detail, history cursor, and lazy-list position across
  top-level navigation and configuration recreation without coupling them to
  the foreground monitoring service.
- The Shizuku status surface presents one state-specific next action. Capability
  probing now has an explicit cancel action, discards a cancelled generation,
  reports the cancellation, retains a retry path, and displays the latest
  completed probe time and coverage details, including separate enumerated,
  CPU-readable, RSS-readable, and PSS-readable counts.
- Live monitoring shows the session controls, current cadence and wake-lock
  state, 60-second system CPU/memory trends, source label, environment context,
  process/application counts, stable search/filter/sort controls, package
  icons, application aggregates, child processes, and one-refresh exited rows.
- Detail views provide application and child-process trends, current CPU/RSS/PSS
  with PSS age, full-session peaks for current identities,
  PID/UID/PPID/state/start ticks, all package candidates, process/command text,
  source and last-sample time, plus app,
  process, shared-UID, and native-process pin semantics. No process-control
  action is exposed.
- Application aggregates visibly say when only part of their child-process
  metrics is available. Aggregate charts omit incomplete points instead of
  presenting a partial sum as exact, and expired process detail cannot fall
  back to an application-wide PSS value or application-wide pin action. An
  expired selection is labelled Exited, and detail retention remains scoped to
  that process key rather than widening to every current child of its app.
- Charts expose current/range/trend text and semantics in addition to color.
  Historical compatibility-fallback ranges are shaded and accompanied by a
  visible and TalkBack-readable explanation. Primary actions use minimum
  48-dp targets and fixed semantic metric colors remain independent of dynamic
  wallpaper color.
- Metric strips, history rows, chart headers, and label/value rows can wrap or
  stack at large font scales instead of forcing single-line truncation. The app
  draws edge-to-edge with theme-aware system bars, preserves inset padding, and
  uses resize behavior for the on-screen keyboard.
- The visual skin uses rounded, quiet, iOS-18-inspired hierarchy while keeping
  Material controls, ripple, system fonts, predictive back, Android navigation,
  and adaptive behavior. Its light/dark surface roles use explicit contrasting
  on-colors rather than the lower-contrast literal iOS accent values. No Apple
  font, symbol, or design asset is embedded.
- Live application rows use the whole card as the detail action and keep only a
  compact 21-dp pin glyph in a 40-dp layout slot. CPU/RSS share the first metric
  line and PSS uses the second, with the numeric value preceding optional age
  and partial-data text. Long supplementary text ellipsizes before the value;
  large font scales retain the accessible stacked fallback.
- The normal live path no longer renders the full capability report or Debug
  typed-sampling tool. A compact one-line backend status precedes a reduced
  session control card. The system section shows CPU, memory, battery, and
  temperature in one compact row; 60-second charts and detailed environment
  context are collapsed by default. Live labels, metrics, filters, and
  supplementary text use the smaller Material text roles while preserving
  scalable system typography and touch-target sizes.
- Settings is now a short five-entry index: sampling/storage,
  appearance/language, export/privacy, compatibility diagnostics, and About.
  Each opens a back-navigable secondary page. Full probe details, reprobe/share,
  diagnostic ZIP, and the Debug-only typed sampling check live only on the
  diagnostics page rather than in either top-level scrolling feed.

## Static evidence

- English and Simplified Chinese contain the same 340 keys; current source
  references no missing string key.
- All 16 files under `res` parse; the application manifest parses separately.
- Stable application and process keys are supplied to lazy lists. Numeric sorts
  use deterministic name/stable-ID tie breakers, and selected sort/expansion
  state uses saveable storage.
- Source inspection finds content descriptions on interactive icon buttons and
  explicit text summaries on every chart type.
- Session-start, export-preview, and edit-session dialog bodies are bounded and
  scrollable for the pending 200% font-scale device check.

On Xiaomi `2509FPN0BC` / Android 16 / HyperOS OS3.0, the Simplified Chinese
Debug UI completed capability probing, rendered the detailed report, ran the
typed-sampling check, controlled a foreground session through run/pause/resume/
stop, and displayed the resulting history row. This is a phone/default-theme
smoke test, not evidence for every adaptive or accessibility variant.

The compact layout was then verified in the real application on Xiaomi
`2509FPN0BC`: the accessibility tree and captured screen show only one trailing
pin action, CPU/RSS on the first metric row, and PSS on the second without
character-by-character wrapping. An isolated Compose test-host experiment was
discarded because both available rule schedulers stalled on this HyperOS build;
it is not retained as a test that could hang the executable device suite.

## Pending executable/device evidence

M3 remains open for a fresh approved build plus Compose/device verification on
phone, landscape, tablet/foldable widths, both languages, both themes, dynamic
and fixed palettes, 200% font scale, TalkBack, configuration changes, and a
one-second live list under process churn. The Xiaomi 17 Pro Max / Android 16
daily-use exit condition remains mandatory.
