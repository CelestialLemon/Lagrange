# Lagrange

A local-first behavioral analytics and gentle-friction app for Android, built to replace Digital Wellbeing on a personal
Pixel.

Lagrange continuously harvests Android usage events and notification metadata into a permanent, compact on-device SQLite
ledger, then reconstructs honest behavioral episodes from it: pickups, glances, app-hop chains, rabbit holes and the
notifications that triggered them. Gentle, escalating pauses (instead of hard lockouts) help break autopilot, and every
measurement carries visible coverage so incomplete days never masquerade as quiet ones.

## Principles

- **Raw events first, unlimited history.** Immutable raw ledgers, derived tables that can always be recomputed.
- **Honest measurement.** Harvest gaps, listener downtime and backfilled days are tracked explicitly.
- **Behavior plus subjective value.** Separate valued time from regretted time.
- **Gentle friction over lockouts.** Mindful pauses, soft timers and nudges, measured by real outcomes.
- **Strictly local.** No `INTERNET` permission, no cloud backup, and automatic weekly backups to a folder you choose.

## Status

Early development. See [`docs/DESIGN_SPEC.md`](docs/DESIGN_SPEC.md) for the full design and phased plan.

## Building

Requires JDK 21 and the Android SDK with API 37.

```sh
./gradlew testDebugUnitTest assembleDebug
```

Release builds are signed with a local keystore described by `~/.lagrange/keystore.properties`. When that file is
absent (for example on CI), the release build is produced unsigned.

## Tech

Kotlin, Jetpack Compose with Material 3, Room, WorkManager, DataStore. `minSdk 33`, `targetSdk 37`.
