# Lagrange — Design Specification (v2)

> **App Name:** Lagrange  
> **Application ID:** `com.celestiallemon.lagrange`  
> **Status:** Draft v2 (incorporating Opus 5.5 & Sol 6.2 reviews + Android 17 updates)  
> **Target Device:** Google Pixel 7a running **Android 17 (API 37)**  
> **Architecture:** Native Android (`minSdk = 33`, `compileSdk = 37`, `targetSdk = 37`, Kotlin, Jetpack Compose, Material 3, Room/SQLite, WorkManager, Jetpack Glance)  
> **Distribution:** Personal sideloaded app (strictly local-only, no backend/cloud, no `INTERNET` permission)

---

## 1. Product Vision & Principles

Android's built-in Digital Wellbeing reduces phone behavior to flat daily totals over a 30-day rolling window with blunt app timers. This application replaces it with a **local-first behavioral analytics and gentle-friction system** built around five core principles:

1. **Raw Events First, Unlimited History:** Continuously harvest system events into a permanent, compact local SQLite ledger before Android purges them. Keep raw events immutable and separate from derived tables (`algorithm_version`) so sessions, rollups, and baselines can always be cleanly recomputed when categories, rules, or rollover hours change.
2. **Honest Measurement & Visible Coverage:** Never fake certainty. Track data-collection coverage (harvest gaps, service downtime, pre-install backfill days) explicitly so incomplete days are visually distinct from zero-use days and never corrupt baselines.
3. **Behavioral Explanation + Subjective Value:** Combine objective signals (triggers, app-hop chains, rabbit holes, usage spikes) with lightweight self-report (*intention* and occasional *"Was that worth it?"* ratings) so analytics separate **valued time** from **regretted time**.
4. **Awareness & Gentle Friction Over Hard Lockouts:** Break autopilot through escalating mindful pauses, optional healthy redirects, cross-app soft session timers, time-blindness nudges, a home-screen widget, and a safe temporary snooze—measuring real behavioral outcomes (immediate exits, quick reopens, substitution, habituation) rather than making unprovable claims.
5. **Strictly Local-Only & Safe Forever:** No `INTERNET` permission in the manifest, Android cloud auto-backup explicitly disabled, fixed signing keystore from install #1, and **automatic scheduled weekly backups** to a user-selected folder (e.g., local storage or Syncthing).

### Explicit Non-Goals (v1)
* No cloud sync, user accounts, or network telemetry.
* No in-app sub-surface inspection (e.g., YouTube Shorts vs. regular video, Instagram Reels, or browser URLs/domains). *(Note: browser usage can act as a workaround around app-level friction; this is an accepted trade-off for v1, tracked via substitution detection.)*
* No notification content/sender text storage (metadata only).
* No ADB/Shizuku-dependent features (such as automated grayscale toggling).
* No hard/unescapable app lockouts or device-admin enforcement.

---

## 2. Permissions & Android 17 System Model

Everything runs on standard Android permissions without root, ADB, or Shizuku. Notably, by drawing overlays via `AccessibilityService` (`TYPE_ACCESSIBILITY_OVERLAY`), we **eliminate the `SYSTEM_ALERT_WINDOW` permission entirely**.

| Permission / Service | Purpose | Degraded Behavior if Off / Blocked |
| :--- | :--- | :--- |
| **Usage Access** (`PACKAGE_USAGE_STATS`) | App foreground/background, screen on/off, unlock events (`UsageStatsManager.queryEvents`), plus Day-1 backfill of whatever recent days the OS still retains. | Core requirement; Permission Health guides setup. |
| **Notification Listener** (`NotificationListenerService`) | Captures notification arrival, updates, dismissal, and click metadata for pickup-trigger attribution and notification cost analysis. | Analytics still work; periods marked as missing notification coverage. |
| **Accessibility Service** (`TYPE_WINDOW_STATE_CHANGED` only, `canRetrieveWindowContent="false"`) | Instant foreground-app detection and service-owned `TYPE_ACCESSIBILITY_OVERLAY` friction cards + `GLOBAL_ACTION_HOME` exit. | Analytics unaffected; real-time friction paused and logged as friction coverage gap. |
| **Notifications** (`POST_NOTIFICATIONS`) | Daily/weekly reflection digests, coverage-gap alerts, and optional milestone notifications. | Notifications silenced. |
| **Query All Packages** (`QUERY_ALL_PACKAGES`) | Resolves installed app names, icons, and activity metadata for categorization and foreground filtering. | Limited app metadata. |
| **Battery Optimization Exemption** *(optional troubleshooting)* | Offered if missed harvests or Doze delays are observed under Android 16/17 JobScheduler quotas. | Periodic WorkManager jobs remain best-effort. |

### Android 16 & 17 (Pixel 7a) Specific Requirements
* **Strict Local-Only Enforcement:** Omit `android.permission.INTERNET` completely. Set `android:allowBackup="false"` and configure `android:dataExtractionRules` + `android:fullBackupContent` to exclude the database and DataStore files from Google Drive cloud backup and device-to-device transfers.
* **Advanced Protection Mode (AAPM):** On Android 17, if device-level Advanced Protection is enabled, Android restricts `AccessibilityService` to verified accessibility tools and can block unknown-source installs. Permission Health will query `AdvancedProtectionManager` (`isAdvancedProtectionEnabled`) and clearly explain if friction is unavailable due to AAPM.
* **Restricted Settings (Android 16/17):** Sideloaded APKs require **App Info → ⋮ → Allow restricted settings** before granting Usage Access, Notification Access, and Accessibility. Document this explicitly in onboarding and verify on-device in Phase 0.
* **WorkManager Quotas & Android 17 Memory Limits:** Treat periodic WorkManager intervals as best-effort. Chunk/page harvest queries and rollup recomputations to stay well below Android 17's tighter background memory ceilings (`MemoryLimiter:AnonSwap`), and inspect `ApplicationExitInfo` during soak tests.
* **App Hibernation:** Check `getUnusedAppRestrictionsStatus` in Permission Health and offer one-tap exemption so Android doesn't revoke permissions if the user goes weeks without opening the main UI.
* **Target SDK 37 Readiness:** Support forced edge-to-edge UI and predictive back in Jetpack Compose; audit third-party dependencies for API 37 compatibility (e.g., private `MessageQueue` reflection, `IntentSender` opt-ins).
* **Known Platform Blind Spots:** Apps inside Android's Private Space or a separate Work Profile are invisible to the main user's `UsageStatsManager`; document this clearly in Data & System info.

---

## 3. Data Capture & Storage Architecture

### 3.1 Time Model & Logical Day Rollover
* **Configurable Logical Day Rollover Hour:** Defaults to **`04:00` local time**.
  * A **behavioral episode (session)** belongs to the logical day it **starts** in (so scrolling from `23:45` to `00:40` stays intact on that night's timeline).
  * **Daily totals and hourly buckets** split duration accurately across logical-day and hourly boundaries, explicitly handling 23-hour (spring-forward) and 25-hour (fall-back) DST days.
* **Timezone History (`tz_history`):** Never stamp `tz_offset_min` at harvest time (which corrupts events spanning DST or travel). Maintain a `tz_history(effective_from_utc_ms, zone_id, is_assumed)` table updated via an `ACTION_TIMEZONE_CHANGED` manifest receiver + app/service startup checks. Resolve every event's local time using `java.time.ZoneId` rules at the event's own UTC instant. Mark initial backfilled days' timezone as `is_assumed = true`.
* **Monotonic Live Clocks:** All real-time friction budgets, launch pause countdowns, switch-back grace windows, and 1-hour friction snoozes use `SystemClock.elapsedRealtime()` so wall-clock jumps or NTP adjustments never break active timers.

### 3.2 Hybrid Ingestion Pipeline

1. **Usage Events Harvester (`SCREEN_OFF` Trigger + `WorkManager` Fallback + App-Open Sync)**
   * Dynamically triggered on `ACTION_SCREEN_OFF` (while Accessibility or NotificationListener keeps the process warm), on app open, and via periodic `WorkManager` (best-effort every 1–2 hours) as a fallback.
   * Serialized behind a single ingestion mutex so worker and app-open syncs never race.
   * Queries `UsageStatsManager.queryEvents(cursorMs - 60_000, nowMs)` (or wider 24h rescan on `ACTION_TIME_CHANGED`), committing the new cursor (based on the latest ingested event timestamp, not wall-clock "now") in the same transaction as the raw batch.
   * **Graceful Direct Boot Handling:** `queryEvents()` may return `null` or empty before first unlock after reboot; handle safely without advancing the cursor.
   * **Initial Backfill:** On first Usage Access grant, attempt to query back 14 days (accepting however many days the Pixel OS actually retains—often 7–10 days) and mark those days `has_notification_coverage = false`.
   * **Gap Detection:** Track harvest timestamps and alert the user if the gap approaches OS retention limits (e.g., > 48h without a harvest after a force-stop).
   * **Phase 0 Verification:** Check on the Pixel 7a whether `UsageEvents.Event.NOTIFICATION_INTERRUPTION` (type 12) is returned to Usage Access callers.

2. **Notification Metadata Listener (`NotificationListenerService`)**
   * Records `onNotificationPosted` and `onNotificationRemoved(sbn, rankingMap, reason)` into `raw_notifications` (**metadata only**—never title, body, sender, or extras).
   * **Update Deduplication:** Messaging apps frequently re-post updates to the same `sbn.key`. Upsert/update the active notification lifecycle record and increment `update_count` rather than creating duplicate notifications.
   * **Disconnect Reconciliation:** Log listener connect/disconnect intervals in `service_coverage_intervals`. In `onListenerConnected()`, reconcile `getActiveNotifications()` against open DB records so removals missed during downtime are closed cleanly and marked uncertain.
   * **Android 16/17 Details:** Exclude `FLAG_ONGOING_EVENT`, `FLAG_FOREGROUND_SERVICE`, and `FLAG_GROUP_SUMMARY` from trigger counts. Preserve `REASON_BUNDLE_DISMISSED` (`24`, API 36.1+) as a distinct removal reason alongside `CLICK`, `CANCEL`, `CANCEL_ALL`, `APP_CANCEL`, `TIMEOUT`, and `OTHER`.
   * **Interruption Eligibility:** Store `importance`, `matched_interruption_filter`, `is_ambient`, `is_conversation`, `group_key`, `is_only_alert_once_update`, and device ringer/DND mode snapshot at post time to assess interruption likelihood accurately.

3. **Real-Time Foreground Monitor (`AccessibilityService`)**
   * Configured strictly for `typeWindowStateChanged` with `canRetrieveWindowContent="false"`.
   * Runs a dedicated **Foreground App State Machine** (Section 5.2) to drive real-time friction overlays without polling `UsageStatsManager`.
   * Logs service connect/disconnect intervals in `service_coverage_intervals`.

### 3.3 SQLite / Room Schema

Key design improvements over v1: integer interning (`pkg_id`, `cls_id`) to keep multi-year storage compact (~10× smaller than raw strings), non-null unique keys so SQLite deduplication actually works, real relational hourly tables instead of JSON blobs, explicit coverage/timezone/settings history tables, and strict Room migrations (never `fallbackToDestructiveMigration`).

```text
-- Interning lookup tables (compact storage for 1–2M raw events/year)
packages
├── id (PK, auto)
├── package_name (TEXT, UNIQUE)
├── display_name (TEXT)
├── category (TEXT: DISTRACTING | NEUTRAL | UTILITY | EXCLUDED | SELF_APP)
├── is_launcher_or_system_ui (BOOL)
├── is_installed (BOOL)
├── installed_at_ms (nullable)
├── uninstalled_at_ms (nullable)
├── daily_goal_ms (nullable)
├── friction_override_json (TEXT, nullable)
└── updated_at_ms

classes
├── id (PK, auto)
├── class_name (TEXT, UNIQUE)                   -- '' (empty string) for null class

-- Immutable raw ledgers
raw_usage_events
├── id (PK, auto)
├── timestamp_utc_ms (INDEX)
├── event_type (INT)
├── pkg_id (INT, FK -> packages.id)
├── cls_id (INT, FK -> classes.id)              -- never NULL ('' maps to a fixed row)
├── seq_in_ms (INT, default 0)                  -- preserves source order within same ms
└── UNIQUE(timestamp_utc_ms, event_type, pkg_id, cls_id, seq_in_ms)

raw_notifications
├── id (PK, auto)
├── sbn_key_hash (TEXT, INDEX)                  -- SHA-256 of sbn.key
├── pkg_id (INT, FK -> packages.id, INDEX)
├── channel_id (TEXT, nullable)
├── android_category (TEXT, nullable)
├── group_key_hash (TEXT, nullable)
├── posted_at_ms (INDEX)
├── last_updated_at_ms
├── update_count (INT)
├── removed_at_ms (nullable)
├── removal_reason (TEXT: CLICK | APP_CANCEL | CANCEL | CANCEL_ALL | BUNDLE_DISMISSED | TIMEOUT | DISCONNECT_RECONCILED | OTHER | ACTIVE)
├── importance (INT)
├── matched_interruption_filter (BOOL)
├── is_ongoing (BOOL)
├── is_group_summary (BOOL)
├── is_ambient (BOOL)
├── is_conversation (BOOL)
└── dnd_or_silent_at_post (BOOL)

tz_history
├── id (PK, auto)
├── effective_from_utc_ms (INDEX)
├── zone_id (TEXT, e.g. "Asia/Kolkata")
└── is_assumed (BOOL)

service_coverage_intervals
├── id (PK, auto)
├── service_kind (TEXT: USAGE_HARVEST | NOTIFICATION_LISTENER | ACCESSIBILITY)
├── start_ms (INDEX)
├── end_ms (nullable)                           -- NULL while currently active
└── status (TEXT: ACTIVE | GAP | BACKFILL)

-- User context, goals, schedules & settings history
settings_history
├── id (PK, auto)
├── timestamp_utc_ms (INDEX)
├── setting_key (TEXT)
└── value_json (TEXT)

daily_notes
├── logical_date (PK, YYYY-MM-DD)
├── note_text (TEXT)
└── updated_at_ms

friction_schedules
├── id (PK, auto)
├── name (TEXT)
├── enabled (BOOL)
├── days_of_week_mask (INT, bitmask Mon..Sun)
├── start_minute_of_day (INT, 0..1439)
├── end_minute_of_day (INT, 0..1439)            -- supports wrap past midnight
├── escalation_boost (INT, e.g. +1)
├── require_timer_prompt (BOOL)
└── nudge_interval_min (INT, nullable)

-- Friction logs (stable across session rebuilds via timestamp + pkg_id)
friction_events
├── id (PK, auto)
├── timestamp_utc_ms (INDEX)
├── logical_date (TEXT, INDEX)
├── pkg_id (INT, FK -> packages.id, INDEX)
├── event_kind (TEXT: LAUNCH_PAUSE | TIMER_EXPIRED | TIME_BLINDNESS_NUDGE | SNOOZE_1H)
├── schedule_id (nullable)
├── escalation_tier (INT)
├── pause_duration_sec (INT)
├── post_countdown_reaction_ms (INT, nullable)  -- measures habituation
├── intention_tag (TEXT, nullable: REPLY | LOOKUP | UNWIND | BORED | CUSTOM)
├── selected_timer_min (INT, nullable)
├── redirect_pkg_id (INT, nullable)             -- if user tapped healthy redirect
└── outcome (TEXT: CLOSED_GO_HOME | CLOSED_SYSTEM_NAV | REDIRECTED | CONTINUED | EXTENDED_5M | DISMISSED_SESSION | NUDGE_DISMISSED | NUDGE_IGNORED | SNOOZE_ACTIVATED)

-- Derived tables (tagged with algorithm_version, cleanly recomputable by dirty date range)
sessions
├── id (PK, auto)
├── episode_key (TEXT, UNIQUE)                  -- deterministic key from start_ms + entry_pkg_id
├── algorithm_version (INT)
├── logical_date (TEXT YYYY-MM-DD, INDEX)
├── start_ms (INDEX)
├── end_ms
├── active_duration_ms                          -- excludes merged screen-off gaps & blocked pause time
├── physical_pickup_count (INT)                 -- 1 + merged micro-locks
├── is_glance_only (BOOL)                       -- screen on without unlock
├── trigger_evidence (TEXT: OBSERVED_CLICK | LIKELY_APP_CANCEL_CLICK | RECENT_POSSIBLE_ALERT | PENDING_NOTIFICATION | NO_OBSERVED_TRIGGER | PARTIAL_COVERAGE | BACKFILL)
├── trigger_pkg_id (INT, nullable)
├── trigger_notification_id (FK -> raw_notifications.id, nullable)
├── entry_pkg_id (INT, nullable)
├── total_distracting_ms
├── total_neutral_ms
├── total_utility_ms
├── total_excluded_ms
├── blocked_attempt_count (INT)
├── distinct_app_count
├── distinct_distracting_count
├── archetype (TEXT: GLANCE | MICRO_CHECK | UTILITY | FOCUSED | RABBIT_HOLE | MIXED)
├── is_rabbit_hole (BOOL)
├── rabbit_hole_reasons (TEXT)
├── worth_it_rating (INT, nullable, 1..5)       -- user self-report (preserved across rebuilds via episode_key)
└── context_flags (TEXT, nullable)              -- charging / headphones / DND snapshot if available

session_segments
├── id (PK, auto)
├── session_id (FK -> sessions.id, INDEX)
├── seq_index (INT)
├── pkg_id (INT, FK -> packages.id, INDEX)
├── start_ms
├── end_ms
├── duration_ms
├── is_blocked_attempt (BOOL)                   -- true when closed at launch pause
├── segment_trigger_evidence (TEXT, nullable)   -- catches mid-session shade taps
└── segment_trigger_pkg_id (INT, nullable)

daily_rollups
├── logical_date (PK, YYYY-MM-DD)
├── algorithm_version (INT)
├── coverage_status (TEXT: COMPLETE | PARTIAL_HARVEST | NO_NOTIF_COVERAGE | BACKFILL)
├── notif_coverage_ratio (REAL, 0.0..1.0)
├── friction_coverage_ratio (REAL, 0.0..1.0)
├── total_screen_ms
├── active_usage_ms                             -- excludes EXCLUDED & SELF_APP
├── distracting_ms
├── neutral_ms
├── utility_ms
├── excluded_ms
├── self_app_ms
├── episode_count
├── physical_pickup_count
├── glance_count
├── micro_check_count
├── rabbit_hole_count
├── blocked_attempt_count
├── notification_count
├── longest_focus_gap_ms
├── overnight_gap_ms (nullable)                 -- sleep proxy
├── first_pickup_ms (nullable)
├── last_activity_ms (nullable)
├── budget_target_ms (nullable)
├── baseline_median_ms (nullable)               -- weekday/weekend matched
├── day_status (TEXT: NORMAL | SPIKE | GOOD_DAY | INSUFFICIENT_BASELINE | UNCERTAIN_COVERAGE)
└── spike_summary_json (TEXT, nullable)

daily_app_rollups
├── logical_date (PK part 1)
├── pkg_id (PK part 2, INDEX)
├── total_ms
├── open_count                                  -- real opens (excludes blocked pause attempts)
├── blocked_attempt_count
├── session_count
└── rabbit_hole_contribution_ms

hourly_app_usage
├── logical_date (PK part 1)
├── hour_slot (PK part 2, INT 0..24)            -- 0..23 normal, handles 25h DST fall-back cleanly
├── pkg_id (PK part 3, INDEX)
├── duration_ms
└── open_count
```

> **Note on Recompute & Persistence:** User-authored annotations (`worth_it_rating` keyed by `episode_key`, `daily_notes`, `friction_events`, `settings_history`) live outside ephemeral derived columns or are re-attached on rebuild so algorithm upgrades never lose user input. Recomputes operate on dirty logical-date ranges (+28 trailing days for rolling baselines) via a background worker with progress reporting.

---

## 4. Behavioral Analytics Engine

All reconstruction and analytics live in a pure-Kotlin domain layer tested against both synthetic edge cases and exported real Pixel 7a JSON fixtures.

### 4.1 Session Reconstruction Rules
1. **Interval Sanitization & Multi-Window Policy:**
   * Pair `ACTIVITY_RESUMED` with `ACTIVITY_PAUSED`/`ACTIVITY_STOPPED` by `(pkg_id, cls_id)`.
   * **Split-Screen / PiP:** Since Android supports multiple simultaneously resumed activities, credit primary foreground time to the most recently resumed non-PiP activity so summed foreground time never exceeds wall-clock duration.
   * **Missing Close Events:** Clamp dangling foreground intervals at `SCREEN_NON_INTERACTIVE`, `DEVICE_SHUTDOWN`, or `DEVICE_STARTUP` (for sudden battery death), and cap unclosed tails conservatively with an uncertain-tail flag.
2. **Episode Boundaries, Glances & Phone Calls:**
   * **Glance:** `SCREEN_INTERACTIVE` → `SCREEN_NON_INTERACTIVE` with no `KEYGUARD_HIDDEN` (lockscreen notification check, media control, secure camera). Tracked separately from unlocked episodes.
   * **Unlocked Episode:** Opens at `KEYGUARD_HIDDEN` (or `SCREEN_INTERACTIVE` when unlocked), closes at `SCREEN_NON_INTERACTIVE`.
   * **Micro-Lock Merge:** Screen-off gaps `< 15s` merge into one behavioral episode while **excluding the screen-off gap from active duration** and incrementing `physical_pickup_count`.
   * **Phone Call Merge:** Screen-off/on cycles caused by the proximity sensor while the dialer/in-call UI is foregrounded merge into one episode regardless of the 15s cap.
3. **App Chain Cleaning & Blocked-Attempt Scrubbing:**
   * Collapse transient Launcher / SystemUI / PermissionController hops (`< 10s`) between real apps.
   * **Chrome Custom Tabs:** Treat Custom Tab activities (`*CustomTabActivity`) launched immediately from a host app as a continuation of that host app rather than splitting `Reddit → Chrome → Reddit`.
   * **Friction Scrubbing:** Join `friction_events` during reconstruction. When an app open ended in `CLOSED_GO_HOME`, `CLOSED_SYSTEM_NAV`, or `REDIRECTED` during the launch pause, mark the segment `is_blocked_attempt = true` and subtract the pause countdown from real app usage so blocked attempts never inflate `open_count`, `MICRO_CHECK` counts, or distracting time.
4. **Honest Trigger Attribution (Session & Segment Level):**
   * Computed at **episode start** and also on **mid-session segment transitions** (catching notification-shade taps mid-session):
     * `BACKFILL` / `PARTIAL_COVERAGE`: Notification listener was not active during the window.
     * `OBSERVED_CLICK`: Non-ongoing notification removed with `REASON_CLICK` within `[-5s, +15s]` of segment/episode start.
     * `LIKELY_APP_CANCEL_CLICK`: Non-ongoing notification for the exact opened app removed with `APP_CANCEL` within `[0s, +10s]` of opening that app (catches messaging/social apps that self-dismiss without auto-cancel).
     * `RECENT_POSSIBLE_ALERT`: Alerting, interruption-eligible notification posted within `[-60s, 0s]` prior to unlock. *(Also reports **pickup lift** vs. random 60s baseline rate so frequent phone checks aren't falsely blamed on alerts.)*
     * `PENDING_NOTIFICATION`: Entry app had an active, unseen non-ongoing notification already sitting in the shade at unlock (> 60s old).
     * `NO_OBSERVED_TRIGGER`: None of the above.
5. **Archetype & Rabbit Hole Classification** *(good defaults; tuning tucked under Advanced Settings)*:
   * `GLANCE`: Lockscreen-only, never unlocked.
   * `MICRO_CHECK`: Unlocked active duration `< 30s` (excluding blocked attempts).
   * `UTILITY`: ≥ 90% in `UTILITY`/`EXCLUDED` and `< 60s` distracting.
   * `RABBIT_HOLE` (`is_rabbit_hole = true`) if any rule matches:
     * **Long Binge:** `total_distracting_ms >= 20m`.
     * **Multi-App Spiral:** `distinct_distracting_count >= 2` and `total_distracting_ms >= 10m`.
     * **Hijacked Pickup:** Entry app (or triggering notification) is `UTILITY`/`NEUTRAL`, yet `total_distracting_ms >= 10m` and distracting share `>= 60%`.
   * `FOCUSED`: Single non-rabbit-hole app ≥ 85% of active duration.
   * `MIXED`: Everything else.
   * *Note:* Categories apply retroactively from the current `apps` table when recomputing so recategorizing an app immediately fixes historical trends.

### 4.2 Analytics Capabilities

#### A. Multi-Horizon Trends & Personal Goals
* **Windows:** `7D`, `30D`, `90D`, `1Y`, `All-Time`, and `Custom`.
* **Series:** Distracting Time, Active Phone Time, Total Screen Time, Episodes vs. Physical Pickups, Glances, Rabbit Holes, and **Regretted vs. Valued Time** (from self-report ratings).
* **Personal Budgets & Goals:** Configurable **daily distracting-time budget** and optional **per-app daily goals**, rendered as clear reference lines on charts.
* **Trendlines & Fair Baselines:** 7-day and 28-day moving averages, **separate Weekday vs. Weekend baselines** (computed over eligible covered days; hidden until ≥ 7 covered days exist), and visible greying/hatching for partial-coverage days.
* **Good Days Highlighted:** Flag and celebrate unusually **good days** (under budget or significantly below baseline) alongside spikes.

#### B. Per-App Deep Dive (The "Reddit View")
* Usage time-series + rolling trendline + optional per-app goal line over any window.
* **24-Hour Time-of-Day Profile:** Average minutes by logical hour (direct SQL over `hourly_app_usage`).
* **Session Length Distribution:** Histogram (`<1m`, `1–5m`, `5–15m`, `15–30m`, `30m+`) + **re-open loops** (same app reopened within 60s).
* **Sequence Flows (Inflow & Outflow):** Top preceding apps / idle launches and top next apps (labeled honestly as observed sequences).
* **Trigger Evidence Breakdown:** Confidence-tiered split with sample size and covered period noted.
* **Friction & Value Stats:** Immediate-exit rate, quick-reopen rate (< 10m), timer extensions, and average *"Worth it?"* rating for this app.

#### C. Behavioral & Spike Diagnostics
* **Fair Spike Autopsy:**
  * Compares completed days against the rolling **28-day Weekday/Weekend median** of covered days (including confirmed zero-use days).
  * For **today (in progress)**, compares elapsed usage through the current logical hour against the **same time-of-day slice** of prior eligible Weekday/Weekend days.
  * Shows top contributing long sessions (clickable into full hop chains), top apps with largest positive delta vs. their own medians *(without falsely claiming per-app medians sum linearly to 100% of excess)*, time-of-day excess block, trigger summary, and any user **day note** (e.g., *"sick"*, *"travel"*).
* **Rabbit Hole Explorer:** Filterable timeline of Rabbit Hole episodes with trigger badge, hop chain, and optional *"Was that worth it?"* rating.
* **Gateway Apps Ranking:** Ranks apps by spiral association rate and average downstream distracting minutes—with minimum session threshold / Bayesian shrinkage so low-sample apps don't dominate the top.
* **Notification Cost Ranking:** Ranks apps/channels by volume, observed click rate, pickup lift, and downstream distracting minutes—with a **one-tap "Mute / Manage Channel" button** opening Android's `ACTION_CHANNEL_NOTIFICATION_SETTINGS` directly for that noisy notification channel.
* **Focus, Sleep Proxy & Circadian Heatmap:** Longest waking phone-free stretch, **overnight phone-free gap** (sleep proxy), inter-pickup gap distribution, Day-of-Week × Hour heatmap, and late-night usage trend.
* **Honest Friction Effectiveness:** Reports observable outcomes—immediate exits, exits sustained ≥ 10 minutes without reopening any distracting app, **substitution alerts** (e.g., Reddit time down while Chrome time rose), **habituation tracking** (post-countdown tap latency), and timer extension rate.

---

## 5. Gentle Friction & Awareness Engine

### 5.1 App Categorization (Required Quick Onboarding Step)
Apps belong to:
* `DISTRACTING` — friction-eligible by default, counted toward distracting budget.
* `NEUTRAL` — tracked normally (e.g., messaging, browser, podcasts); friction opt-in.
* `UTILITY` — maps, banking, 2FA, camera, notes, dialer; never friction-gated.
* `EXCLUDED` — excluded from Active Phone Time and baselines (still in raw logs).
* `SELF_APP` — this app itself, tracked separately so checking stats doesn't distort usage.

> **Onboarding Rule:** Do not blindly rely on `ApplicationInfo.category` (which puts WhatsApp/Signal in `SOCIAL`). Show a fast 30-second app picker on first setup so the user confirms their `DISTRACTING` list (and starts friction on just 1–2 chosen apps after an initial ~1–2 week baseline period).

### 5.2 Reliable Foreground App Detection State Machine
To prevent false pauses from `TYPE_WINDOW_STATE_CHANGED` noise:
1. Ignore own package, active IME/keyboard, SystemUI (shade, volume panel, lockscreen), toasts, and non-Activity panes by verifying `ComponentName(pkg, cls)` resolves to an Activity via `PackageManager.getActivityInfo` (cached in memory).
2. Fire transition logic **only** when the resolved "real foreground app" changes from a different real app (or after unlock into a distracting app).
3. Debounce screen rotation and brief notification-shade pull-downs.
4. Persist active timer, grace window, and snooze state using monotonic timestamps so an `AccessibilityService` restart never drops an active budget.
5. Clear any visible overlay immediately on screen lock, incoming call, app switch, or service disconnect.

### 5.3 Escalating Mindful Launch Pause (`TYPE_ACCESSIBILITY_OVERLAY`)
* **Overlay Delivery:** Drawn via `TYPE_ACCESSIBILITY_OVERLAY` (no `SYSTEM_ALERT_WINDOW` needed). While displayed, request `AUDIOFOCUS_GAIN_TRANSIENT` so YouTube/Reels/TikTok don't play audio behind the pause card, releasing audio focus immediately when dismissed.
* **Escalation Tiers** *(driven by today's **real opens** and distracting time—blocked attempts where you chose to close do **NOT** escalate you!)*:
  * **Tier 0** (Real opens 1–2 & `< 15m` distracting): `3s` pause
  * **Tier 1** (Real opens 3–5 or `15–45m`): `5s` pause + optional timer chips
  * **Tier 2** (Real opens 6–10 or `45–90m`): `8s` pause + timer chips + intention chip
  * **Tier 3** (Real opens 11+ or `90m+`): `12s` pause + budget comparison + timer & intention chips
  * *(Configurable in Advanced Settings, plus an optional daily prompt cap to prevent annoyance.)*
* **Pause Card Elements:**
  * Calm progress/breathing ring + live stats (*"Opening Reddit · 5th time today · 38m / 60m budget"*).
  * Optional 1-tap **Intention Chip** (*Reply · Look something up · Unwind · Bored*).
  * Primary button: **"Go Home"** (calls `performGlobalAction(GLOBAL_ACTION_HOME)`, verifies return value, logs outcome).
  * Optional **Healthy Redirect** button (user-configured replacement app, e.g., Kindle, Notes, Duolingo).
  * Secondary button: **"Continue"** (unlocks after countdown; tracks post-countdown reaction time).
  * Subtle escape hatch link: **"Pause friction for 1 hour"** (logs `SNOOZE_1H` so late-night frustration never pushes you to disable Accessibility entirely).

### 5.4 Cross-App Soft Session Timer & Anti-Fatigue Rules
* **Cross-App Distracting Budget:** Quick presets (`2m` · `5m` · `10m` · `15m` · `No timer`). Once active, counts down across **all foreground time in any `DISTRACTING` app** within the current session. Pauses on `UTILITY`/`NEUTRAL` apps; clears on screen lock past the 15s merge window.
* **Expiry Check-In:** Calm overlay with **"Done · Go Home"**, **"+5 min"**, or **"Continue without timer"**. If a timer expires simultaneously with a launch pause, the timer check-in takes priority.
* **Switch-Back Grace Window:** Passing a launch pause grants a **2-minute** (monotonic) switch-back grace window so quickly checking a link or OTP and returning doesn't re-trigger the pause.
* **In-Session Time-Blindness Nudges:** Tracks continuous cumulative distracting time across apps in the active session and shows a subtle non-blocking milestone banner (default every `15m`: `15m`, `30m`, `45m`, ...) using `TYPE_ACCESSIBILITY_OVERLAY`.
* **Occasional *"Was that worth it?"* Check-In:** Sampled sparingly (e.g., max 1–2×/day, via gentle notification or next app open) after a Rabbit Hole ends: 1–5 rating stored against `episode_key`.

### 5.5 Contextual Schedules, Widget & Quick Settings Tile
* **Contextual Schedules:** Time windows (e.g., *Work Mon–Fri 09:00–17:30*, *Wind-Down 22:30–04:00*) that boost escalation (`+1` tier), encourage timer selection, or tighten nudge intervals.
* **Home-Screen Widget (Jetpack Glance):** Shows today's distracting time vs. daily budget, current phone-free streak, and a quick status indicator—providing passive awareness without opening the app.
* **Quick Settings Tile ("Focus Boost 1h"):** One tap in Android's Quick Settings shade activates a 1-hour elevated-friction boost (or toggles 1-hour snooze if already active).
* **Reflection Digests:** Local daily/weekly summary notifications (budget performance, biggest rabbit hole, longest focus streak, immediate exits).

---

## 6. Conceptual Screen Map

1. **Today (Home):** Distracting time vs. daily budget & weekday/weekend baseline, current & longest phone-free focus streak, Spike Autopsy or Good Day highlight card, optional quick day-note chip, category bar, and today's reconstructed session timeline (tap any session to see full chain, trigger evidence, and *"Worth it?"* rating).
2. **Trends & Apps:** Multi-horizon charts (`7D` to `All-Time` / `Custom`), weekday vs. weekend splits, budget line, coverage-gap shading, and searchable app list → **Per-App Deep Dive ("Reddit View")**.
3. **Behavior Lab:** Rabbit Hole Explorer, Spike History, Gateway Apps, Notification Cost (with 1-tap **"Mute Channel"**), Circadian Heatmap, and Honest Friction Effectiveness (including substitution & habituation).
4. **Friction:** App Categorization Manager, Daily Budget & Per-App Goals, Escalation & Redirect setup, Contextual Schedules, Quick Snooze, and Digest settings.
5. **Data & System:** Permission Health Dashboard (including Android 17 Advanced Protection & App Hibernation checks), Automatic Weekly Backup folder setup + Manual Backup/Restore, Raw SQLite & CSV Export, Logical Day Rollover, Advanced Thresholds, and **Debug Timeline Inspector** (raw events vs. reconstructed sessions + 1-tap JSON fixture export).

---

## 7. Data Portability: Automatic Backups, Safe Restore & Exports

All file access uses Android's **Storage Access Framework (SAF)**. Note in UI that saving into a cloud-synced SAF provider (like Google Drive) syncs those exported files per that provider's rules, whereas local storage or Syncthing stays fully self-hosted.

1. **Automatic Weekly Backups (Persisted SAF Folder):**
   * User picks a backup folder once (`ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission`), e.g., a local folder or Syncthing directory.
   * A weekly `WorkManager` job generates a verified backup ZIP and prunes old snapshots per a retention policy (e.g., keep last 8 weekly backups).
2. **Backup Bundle Format (`.zip`):**
   * `wellbeing.sqlite` — consistent snapshot generated in app-private storage via `VACUUM INTO`, integrity-checked (`PRAGMA integrity_check`), then copied into the ZIP.
   * `preferences.json` — user preferences (single authoritative store for schedules in Room so Room + DataStore never diverge).
   * `manifest.json` — `schema_version`, `app_version`, `created_at_utc_ms`, SHA-256 checksums.
3. **Coordinated Safe Restore:**
   * Never overwrite an open Room DB in-place.
   * Validate ZIP manifest, schema compatibility, and SHA-256 checksums in a staging directory first.
   * Pause ingestion, keep the previous DB generation as a rollback backup, and swap staged files cleanly on next process restart (deleting old `-wal`/`-shm` sidecars) before Room initializes.
   * Also provide a clean local **Delete / Reset Data** option.
4. **External Analysis Export:**
   * Direct `.sqlite` copy (already inside backup ZIP, also exportable standalone for DuckDB / Python / DB Browser).
   * Tidy CSV ZIP (`daily_summary.csv`, `daily_app_usage.csv`, `sessions.csv`, `session_segments.csv`, `notifications.csv`, `friction_events.csv`) added in Phase 5.

---

## 8. Phased Execution Plan (Data-First & Risk-First)

Reordered based on review feedback so we validate Android 17 unknowns first, never lose un-backfillable notification data, and protect history with tested backups from Week 1:

* **Phase 0 — Pixel 7a (Android 17) Technical Spike (2–3 days):**
  * Verify Restricted Settings onboarding and `AdvancedProtectionManager` status on your actual Pixel 7a build.
  * Build a tiny disposable prototype testing `AccessibilityService` (`TYPE_WINDOW_STATE_CHANGED`), `TYPE_ACCESSIBILITY_OVERLAY`, `GLOBAL_ACTION_HOME`, `AUDIOFOCUS_GAIN_TRANSIENT` over YouTube/Reddit, gesture nav, Recents, IME, and incoming calls.
  * Measure actual OS `UsageEvents` retention depth and check whether `NOTIFICATION_INTERRUPTION` (type 12) is returned.
  * Create and lock in your **permanent local release signing keystore** so future updates never require uninstall/wipe.

* **Phase 1 — Collector-First Build & Backup Safety (Install Immediately for Baseline):**
  * Room DB with integer interning, `raw_usage_events`, `raw_notifications`, `tz_history`, `service_coverage_intervals`, and migration tests.
  * `UsageStatsManager` harvester (with initial backfill + `SCREEN_OFF` trigger + `WorkManager` fallback + chunked Android 17 memory-safe batching) **AND** `NotificationListenerService` metadata logger from Day 1.
  * Permission Health screen, quick app categorization onboarding, and **tested Manual + Automatic Weekly SAF Backup/Restore**.
  * *Milestone:* Install on your Pixel 7a and let it quietly collect ~1–2 weeks of clean baseline data (with zero friction yet) so you get a real before/after comparison!

* **Phase 2 — Truth Engine, Debug Inspector & Core Analytics:**
  * Pure-Kotlin Session Reconstructor + JVM unit test suite + **Debug Timeline Inspector** (raw events vs. reconstructed sessions + real-device JSON fixture export).
  * Materialized `daily_rollups`, `daily_app_rollups`, and `hourly_app_usage` with dirty-range incremental recompute.
  * **Today** screen (with budgets & day notes) + **Trends & Apps** + **Per-App Deep Dive ("Reddit View")**.

* **Phase 3 — Gentle Friction Loop (Iterative Rollout):**
  * Production Foreground App State Machine + `TYPE_ACCESSIBILITY_OVERLAY` launch pause with audio focus, intention chips, optional healthy redirect, and logged 1-hour snooze.
  * Blocked-attempt scrubbing in the reconstructor so friction never pollutes usage stats.
  * Start with 1–2 user-selected apps to verify feel, then enable escalation tiers, cross-app soft session timer, 2-minute switch-back grace window, time-blindness nudges, and contextual schedules.

* **Phase 4 — Behavior Lab & Self-Report:**
  * Fair **Spike Autopsy** (weekday/weekend & partial-day slice math) + Good Day callouts.
  * **Rabbit Hole Explorer** + occasional *"Was that worth it?"* ratings (valued vs. regretted time).
  * **Gateway Apps** (with minimum sample shrinkage), **Notification Cost** (with 1-tap **"Mute Channel"** shortcut), Circadian Heatmap, and Honest Friction Effectiveness (sustained exits, substitution, habituation).

* **Phase 5 — Passive Awareness, Exports & Polish:**
  * **Jetpack Glance Home-Screen Widget** (today's distracting time vs. budget) & **Quick Settings Tile** (*"Focus Boost 1h"*).
  * Daily/Weekly Reflection Digests, Tidy CSV export bundle, and Android 17 soak testing (`ApplicationExitInfo` memory check, overnight idle, reboot, DST/timezone changes).
