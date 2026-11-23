# Lagrange

A local-first behavioral analytics and gentle-friction app for Android, built to replace Digital Wellbeing on a personal
Pixel (Pixel 7a, Android 17).

Lagrange continuously harvests Android usage events and notification metadata into a permanent, compact on-device SQLite
ledger, then reconstructs honest behavioral episodes from it: pickups, glances, app-hop chains, rabbit holes and the
notifications that triggered them. Gentle, escalating pauses (instead of hard lockouts) help break autopilot, and every
measurement carries visible coverage so incomplete days never masquerade as quiet ones.

## Principles

- **Raw events first, unlimited history.** Immutable raw ledgers, derived tables that can always be recomputed.
- **Honest measurement.** Harvest gaps, listener downtime and backfilled days are tracked explicitly; a day with a gap is
  shown as a lower bound and never compared.
- **Behavior plus subjective value.** Separate valued time from regretted time.
- **Gentle friction over lockouts.** Mindful pauses, soft timers and nudges, measured by real outcomes.
- **Strictly local.** No `INTERNET`, no `ACCESS_NETWORK_STATE`, no `SYSTEM_ALERT_WINDOW`, no cloud backup.

## What's in v1

### The five tabs

| Tab | What it shows |
| --- | --- |
| **Today** | Distracting time against your daily budget and weekday/weekend baseline, current and longest phone-free stretch, a Spike Autopsy or Good Day card, a day note, the category bar, and the reconstructed session timeline (tap a session for its hop chain, trigger evidence and a *"Worth it?"* rating). |
| **Trends** | 7D to All-Time (or custom) charts with weekday/weekend splits, the budget line and coverage-gap shading, plus a searchable app list leading to the per-app deep dive. |
| **Lab** | Rabbit Hole Explorer, Spike History, Gateway Apps, Notification Cost (with one-tap *Mute channel*), the circadian heatmap and Honest Friction Effectiveness (sustained exits, substitution, habituation). |
| **Friction** | App categories and per-app friction, daily budget, escalation tiers and a healthy redirect, contextual schedules, 1-hour Snooze and Focus Boost, and Reflection Digest settings. |
| **System** | Permission Health (including Android 17 Advanced Protection and hibernation checks), collection stats, backups and exports, the logical-day rollover, and the Debug Timeline Inspector. |

### Gentle Friction

An `AccessibilityService` that listens only to `TYPE_WINDOW_STATE_CHANGED` (it never reads window content) drives a
foreground-app state machine. Opening a distracting app shows a calm `TYPE_ACCESSIBILITY_OVERLAY` launch pause that
escalates with repeated opens, offers intention chips, an optional redirect and a soft cross-app session timer, then
time-blindness nudges. Contextual schedules (e.g. *Work Mon–Fri 09:00–17:30*, *Wind-Down 22:30–04:00*) raise the tier
or tighten nudges. Snooze and Focus Boost last one hour on the monotonic clock, so changing the wall clock can't end
them early.

### Behavior Lab

Every Lab section reads the same derived tables as Today and Trends and shows how many fully covered days it rests on.
Gateway Apps use minimum-sample shrinkage; friction effectiveness counts an exit only when it was sustained.

### Home-screen widget and Quick Settings tile

- **Widget (Jetpack Glance, Material You colours):** today's distracting time against the budget with a progress bar,
  the phone-free stretch (*since 14:05*, or the gap before the current pickup), today's longest, and a friction badge
  (*Focus Boost*, *Snoozed*, or *set up*). Missing Usage Access shows *Grant access* and a collection gap shows
  *Coverage gap* with a `≥` lower bound, never a confident `0m`. Tap to open Today. It is redrawn by the app after
  rollups, budget changes, boost/snooze toggles, boot and data reset; it never polls.
- **Quick Settings tile "Focus Boost":** one tap starts a 1-hour Focus Boost (ending any snooze), another tap ends it.
  The subtitle reads `1h · 42m left`, `Snoozed`, `Off`, or `Set up friction` (then a tap opens the Friction tab).

### Reflection digests

Optional local notifications on their own channels (`lagrange_daily_digest`, `lagrange_weekly_digest`): budget
performance against your weekday/weekend baseline, the biggest rabbit hole, the longest phone-free stretch and launch
pause exits. The weekly digest compares with the week before only when both weeks have at least three fully covered
days. Times and the weekday are configurable; each digest is posted at most once per logical day / ISO week, and the
schedule follows time-zone and clock changes. A third channel, `lagrange_coverage_alerts`, warns once when nothing has
been recorded for over 48 hours (Android keeps only about a week of usage events). *Send preview digest* shows what
tonight's digest would say.

### Backups and exports

All file access goes through the Storage Access Framework.

- **Automatic weekly backups** into a folder you pick once (local storage or Syncthing stays fully self-hosted; a cloud
  provider syncs the files under its own rules). The newest 8 are kept.
- **Backup bundle (`.zip`)**: an integrity-checked `VACUUM INTO` snapshot, `preferences.json`, and a manifest with
  SHA-256 checksums. Restores are validated in staging and swapped in on the next start, with a rollback copy.
- **Raw `.sqlite` export** for DuckDB, Python or DB Browser.
- **CSV bundle (`lagrange-csv-YYYYMMDD-HHmmss.zip`)**: six RFC 4180 files, `daily_summary.csv`,
  `daily_app_usage.csv`, `sessions.csv`, `session_segments.csv`, `notifications.csv` and `friction_events.csv`. Every
  instant appears both as ISO-8601 local time with its offset (resolved through `tz_history`, so travel shows the clock
  you lived) and as UTC epoch milliseconds; apps carry package, name and category. Notifications are metadata only:
  no title, text, sender or key hashes, and conversation channels are pseudonymous. Rows are streamed in pages from a
  snapshot, so large histories export in constant memory.
- **Delete / Reset data** wipes everything before the database reopens, cancels all jobs (digests included), withdraws
  posted digests, and makes the reset time a permanent import cutoff.

## Privacy

Nothing leaves the phone unless you save a file somewhere yourself. The merged release manifest requests no
`INTERNET`, `ACCESS_NETWORK_STATE` (WorkManager's is stripped) or `SYSTEM_ALERT_WINDOW` permission, which
`ManifestHardeningTest` enforces. OS cloud backup and device-to-device transfer are disabled. Notification titles,
text and senders are never stored; notification keys are hashed and conversation channel ids are pseudonymized with a
per-install key.

## Setup on Android 17 (Restricted Settings)

Sideloaded apps can't be granted Accessibility or Notification Access straight away on Android 13+:

1. Install the APK and open Lagrange; grant **Usage Access** from the onboarding screen.
2. Try to enable **Notification Access** and **Accessibility → Lagrange gentle friction**. Android blocks it the first
   time with a *Restricted setting* dialog.
3. Open **Settings → Apps → Lagrange → ⋮ → Allow restricted settings**, confirm with your PIN, then enable both again.
4. Allow notifications if you want digests, and exempt Lagrange from battery optimization (Permission Health links to
   each screen).
5. Long-press the home screen to add the **Lagrange today** widget, and edit the Quick Settings panel to add
   **Focus Boost**.

With Advanced Protection on, Android 17 may block third-party accessibility services entirely; Permission Health
reports it.

## Building and installing

Requires JDK 21 and the Android SDK with API 37.

```sh
./gradlew testDebugUnitTest assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Release builds are signed with a local keystore described by `~/.lagrange/keystore.properties`; keep that keystore,
since updates signed with another key require an uninstall (and lose history). When the file is absent (for example on
CI), the release build is produced unsigned. The Room schema lives in `app/schemas/` and every schema change must ship
with a tested migration: the database never falls back to destructive migration.

## Tech

Kotlin, Jetpack Compose with Material 3, Jetpack Glance, Room, WorkManager, DataStore. `minSdk 33`, `targetSdk 37`.
See [`docs/DESIGN_SPEC.md`](docs/DESIGN_SPEC.md) for the full design.
