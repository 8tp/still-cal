# still-cal — spec

A local-only calendar that never asks you to sign in. Third app in the Still ecosystem, after `still-launcher` and `still-notes`. Same temperament, same fonts, same refusal to phone home.

This document is the source of truth for v0.1. The fresh Claude Code session that builds the app should treat it as a contract: every section under **must** is acceptance criteria, every section under **should** is preferred-but-revisable, and every "refuses to do" line is a hard floor.

---

## 1. Pitch

A monochrome, OLED-first, text-first Android calendar. Plain `.ics` files on disk, hand-rolled parser, no account, no sync, no CalDAV, no attendees, no color tags. Month view, week view, event detail, edit. Reminders fire locally via `AlarmManager`. Exports back out as `.ics` through SAF — a folder of events that any other calendar app can read.

It is the companion to `still-launcher` and `still-notes` and slots into the same daily flow on a GrapheneOS Pixel.

## 2. Pact compliance (`STILL.md`)

This app must satisfy every line of the still pact:

| Pact line | How still-cal honors it |
| --- | --- |
| No `INTERNET` permission, ever | Manifest declares zero networking permissions. No HTTP client in dependencies. CalDAV is off the table by design. |
| No analytics, Firebase, GMS, AI bolt-ons | Dependencies limited to AndroidX + Compose + DataStore. No Crashlytics. No remote config. No on-device ML. |
| Plaintext export, forever | Every event stored as a `.ics` file you can `cat`. SAF export of a single event or the whole notebook as one concatenated `.ics`. |
| No acquisitions | Repo is MIT under `8tp/still-cal`. README links to `STILL.md`. |
| MIT or bust | License file is MIT. No relicensing clauses anywhere in code or docs. |

Three permissions ARE declared because they are unavoidable for a calendar with reminders. Each gets a row in the README's "what it refuses to do (and what it asks for honestly)" table:

- `POST_NOTIFICATIONS` — Android 13+ runtime requirement to surface a notification. Asked the first time a reminder is enabled on an event.
- `SCHEDULE_EXACT_ALARM` — Android 12+ runtime requirement for `setExactAndAllowWhileIdle`. A reminder that fires fifteen minutes late is broken.
- `RECEIVE_BOOT_COMPLETED` — `AlarmManager` forgets every scheduled alarm across reboots; without this the reminder for tomorrow's 9am stops mattering after tonight's reboot.

None involve the network. None pull a third-party SDK.

## 3. What it does (must)

- A **month view** as the home screen: 7 columns × 6 rows, day numbers in serif, today emphasized, days with events show one or more hairline dots underneath the number.
- A **week view** toggle: a 7-day strip showing the current week with each day's events listed as text rows underneath.
- A **day list**: tap any day in the month grid to open a chronological list of that day's events.
- **Event create/edit**: title, all-day toggle, start date+time, end date+time, optional reminder offset, optional `notes` field. Save writes a `.ics` file to disk and updates the index.
- **Recurrence** limited to `daily / weekly / monthly with a fixed end date`. No exceptions, no by-day-of-month surgery, no "every other Tuesday." If you want it, you create more events.
- **Reminders**: per-event, optional, expressed as a single offset (`at start`, `5 min before`, `15 min before`, `1 hr before`, `1 day before`). Implemented via `AlarmManager.setExactAndAllowWhileIdle` to a `BroadcastReceiver` that posts a notification.
- **Settings**: font preset (System / Editorial / Terminal / Grotesk — same four as still-notes), default view (month or week), week start (system / Sunday / Monday), 12/24 hour, import `.ics`, export `.ics`, delete all events.
- **Import**: SAF `OpenMultipleDocuments` for `text/calendar` (and `text/plain` as a courtesy). Each `VEVENT` becomes one event on disk.
- **Export**: SAF `CreateDocument` to write either a single event or all events as one `.ics` file (a valid `VCALENDAR` envelope wrapping every `VEVENT`).
- **System share-in**: `ACTION_VIEW` for `text/calendar` lets another app hand a `.ics` to still-cal; the events inside are imported and the user lands on whichever month contains the first one.

## 4. What it refuses to do (hard no's)

- **No CalDAV.** No Google sync. No Exchange. No "tap to connect." No account flows of any kind.
- **No attendees, no invitees, no organizers, no RSVPs.** A still-cal event has a title, a time, and optional notes. That's it.
- **No attachments, no color tags, no categories.** The event row is text.
- **No recurrence-with-exceptions, no `EXDATE`, no `RRULE` beyond `FREQ=DAILY|WEEKLY|MONTHLY` + `UNTIL`/`COUNT`.** A one-week vacation in the middle of a recurring event is one delete + two new events, not an exception list.
- **No multi-day spanning blocks** in the month grid. A 3-day event is rendered as a dot on each of the three days, not a colored bar across them. (The day list shows the full range.)
- **No widgets, no quick-add tile, no notification listener, no accessibility service.**
- **No third-party iCalendar library.** The parser is hand-rolled in `dev.chuds.stillcal.ical`, same posture as still-notes' markdown parser.
- **No INTERNET, no QUERY_ALL_PACKAGES, no MANAGE_EXTERNAL_STORAGE.**
- **No `+` button.** New events are reached via a footer verb (`new`).

## 5. Data model

### 5.1 The `Event` data class

```kotlin
data class Event(
    val id: String,                  // UUID, doubles as VEVENT UID and the .ics filename
    val title: String,
    val notes: String,               // free-form, may be empty
    val startEpochMs: Long,          // UTC instant; render in device time zone
    val endEpochMs: Long,            // exclusive end, per .ics convention
    val allDay: Boolean,             // when true, start/end are interpreted as date-only at local 00:00
    val tzId: String,                // IANA zone id at the moment of authoring (e.g. "America/New_York")
    val rrule: Recurrence?,          // null = single occurrence
    val reminder: ReminderOffset?,   // null = no reminder
    val createdAt: Long,
    val updatedAt: Long,
)

sealed interface Recurrence {
    val until: LocalDate            // inclusive last possible occurrence date
    data class Daily(override val until: LocalDate) : Recurrence
    data class Weekly(override val until: LocalDate) : Recurrence
    data class Monthly(override val until: LocalDate) : Recurrence
}

enum class ReminderOffset(val minutesBefore: Int) {
    AtStart(0),
    FiveMin(5),
    FifteenMin(15),
    OneHour(60),
    OneDay(60 * 24),
}
```

### 5.2 Storage layout under `filesDir`

```text
events/<id>.ics      one VEVENT per file, wrapped in a VCALENDAR envelope
index.json           fast list metadata: id, title, startEpochMs, endEpochMs, allDay, tzId,
                     rrule (serialized), reminder (enum name), createdAt, updatedAt
```

### 5.3 The `.ics` subset on disk

Each per-event file is a complete, valid iCalendar document — not just a fragment — so `cat events/*.ics > backup.ics` is *almost* a valid backup (the bulk export concatenates them properly with a single envelope).

```text
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//8tp//still-cal//EN
BEGIN:VEVENT
UID:<uuid>
DTSTAMP:20260510T143000Z
DTSTART;TZID=America/New_York:20260512T090000
DTEND;TZID=America/New_York:20260512T100000
SUMMARY:Coffee with K
DESCRIPTION:bring the book
RRULE:FREQ=WEEKLY;UNTIL=20260801T235959Z
BEGIN:VALARM
ACTION:DISPLAY
TRIGGER:-PT15M
DESCRIPTION:reminder
END:VALARM
END:VEVENT
END:VCALENDAR
```

All-day events use `DTSTART;VALUE=DATE:20260512` and `DTEND;VALUE=DATE:20260513` (exclusive end). The parser must accept both `TZID=...` and floating-time `DTSTART:20260512T090000` (defaulting floating to device zone).

### 5.4 The hand-rolled `.ics` parser

Lives in `dev.chuds.stillcal.ical/`. Structure mirrors the markdown parser's split:

| File | Responsibility |
| --- | --- |
| `IcsLexer.kt` | Line unfolding (per RFC 5545: continuation lines start with whitespace). Handles `\r\n` and `\n`. |
| `IcsParser.kt` | Reads `BEGIN:VCALENDAR` / `BEGIN:VEVENT` blocks. Produces a list of `RawVEvent` — name, params, value triples. |
| `IcsTypes.kt` | Maps `RawVEvent` → `Event` (parses `DTSTART` / `DTEND` / `RRULE` / `VALARM`). Tolerates missing fields with sensible defaults. |
| `IcsWriter.kt` | The reverse direction. Folds long lines at 75 octets. Escapes `,`, `;`, `\n` inside `SUMMARY` / `DESCRIPTION`. |

The parser **must** round-trip its own output (write an event, parse it back, get the same `Event`). It **need not** parse every legal RFC 5545 file — it tolerates only the subset still-cal itself produces, plus the most common shapes Google Calendar / Fastmail / Fossify Calendar emit. Unknown properties are silently dropped.

## 6. Screens

Hand-rolled router in `StillCalApp.kt`, sealed `Route`. No NavCompose. Same pattern as `StillNotesApp.kt`.

### 6.1 `MonthScreen` (home)

- Top row: a mono kicker (`MONTH`), the year in serif, a small left/right pair (`prev`, `next`) in lowercase.
- Below that: month name in `Display` serif, large.
- A 7-column header row of weekday letters (`s m t w t f s` or `m t w t f s s` per setting), in mono `Caption`.
- A 7×6 grid of cells. Each cell:
  - Day number top-left in `DayNum` serif. Today gets `SoftWhite`; other-month days get `DimGray`; current-month non-today days get `MutedWhite`.
  - 0–3 hairline dots centered below the number, one per event-day (cap at 3, no overflow indicator).
  - Tap → `DayListScreen` for that date.
- Footer row pinned to bottom: `week`, `today`, `new`, `settings` — the four lowercase verbs, monospace small.

The grid math:
- The visible window starts on the configured week-start of the week containing the 1st of the displayed month.
- Always render exactly 6 rows (42 cells). Trailing days from the next month fill the tail.
- Use `java.time.YearMonth` and `LocalDate.with(TemporalAdjusters.previousOrSame(...))`. No Joda-Time.

Swipe horizontally to advance month (use `pointerInput` + `detectHorizontalDragGestures`). Threshold: 80dp.

### 6.2 `WeekScreen`

- Same kicker treatment (`WEEK`), the date range as `May 11–17` in serif.
- Seven rows (one per day). Each row:
  - Left rail: weekday letter and date number in serif.
  - Right body: events for that day stacked, each shown as `HH:mm  title` in `Title` style. Multi-day or all-day events render as `all day  title`.
- Tap a row → `DayListScreen`. Tap an event row → directly into `EventEditScreen` for that event/occurrence.
- Footer: `month`, `today`, `new`, `settings`.

### 6.3 `DayListScreen`

- Top: weekday + date in serif (`Wednesday, May 13`), mono caption underneath (`MAY 2026`).
- A reverse-chronological list isn't right here — events list **chronologically**, all-day first, then by start time.
- Each row: `HH:mm – HH:mm  title` (or `all day  title`). Tap to open `EventEditScreen`. Long-press for an action sheet (`edit`, `delete`).
- Empty state: a quiet line in mono `Caption` (`nothing scheduled`).
- Footer: `back`, `new`.

### 6.4 `EventEditScreen`

A single scrollable Compose column. No two-pane "view + edit" split — the same screen is the view; tap a field to edit.

Fields in order:
1. **Title** — `BasicTextField`, `Title` style, no decoration line. Placeholder: `untitled`.
2. **All-day** — a row with the label and a hairline-styled toggle (text-based, e.g. `[ on ]` / `[ off ]`).
3. **Start** — date and time pickers, but **typographic**, not the system spinners. Tap the date string → an inline numeric picker (year, month, day). Same for time. (See §6.6.)
4. **End** — same shape.
5. **Repeat** — `none / daily / weekly / monthly`. When non-none, an additional `until` row appears.
6. **Reminder** — `none / at start / 5 min before / 15 min / 1 hour / 1 day`.
7. **Notes** — multi-line `BasicTextField`, no border.
8. Footer: `save`, `delete` (only if editing an existing event), `cancel`.

Validation:
- Title may be blank — fall back to `untitled` on save.
- End must be ≥ start. If the user moves start past end, end snaps to start + 1h (or start + 1 day for all-day).
- `until` must be ≥ start date. If not, save is disabled and a small mono caption appears beneath the row.

### 6.5 `SettingsScreen`

A vertical stack of `StillMenuItem` rows:

- `font` — cycles through System / Editorial / Terminal / Grotesk. Subtitle shows current.
- `default view` — cycles month / week.
- `week starts on` — cycles system / Sunday / Monday.
- `time format` — cycles 12-hour / 24-hour / system.
- `import .ics` — fires SAF `OpenMultipleDocuments`.
- `export all` — fires SAF `CreateDocument` for `text/calendar`.
- `delete all events` — two-tap confirmation (the row's subtitle becomes `tap again to confirm` for 4 seconds).

Bottom: a `privacy posture, in code` block (mono caption listing the three relevant manifest files), then a `still ecosystem` link row (`launcher`, `notes`, both inert text — no app launching from here).

### 6.6 The numeric date/time picker

Build a custom one — the system `DatePickerDialog` and `TimePickerDialog` are colorful Material widgets that fight the aesthetic. Spec:

- A 3-column inline picker. Each column is a `LazyColumn` of numbers in mono.
- Two pickers stacked vertically: top = date (year, month, day), bottom = time (hour, minute, am/pm if 12h).
- Snap behavior via `LazyListState.scrollToItem` on settle. Selected row is centered, `SoftWhite`; siblings are `DimGray`.
- Day column re-clamps when month/year change (Feb 30 → Feb 28).

If this picker proves too much for v0.1, the fallback is two `BasicTextField`s constrained to digit input (`yyyy-mm-dd` and `HH:mm`). Document the fallback in the screen file's top comment.

## 7. Reminders

### 7.1 Scheduling

- When an event with a reminder is saved (created or updated), compute the next occurrence's reminder timestamp and call `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, triggerAtMs, pendingIntent)`.
- The `PendingIntent` targets `ReminderReceiver` with extras: event id, the occurrence's local date.
- Use the event id's hashcode (positive) as the request code so reschedules replace the prior alarm.

### 7.2 Recurrence handling

- For recurring events, only the **next** alarm is scheduled. When the receiver fires, after posting the notification, it asks `RemindersScheduler` to schedule the *following* occurrence.
- This avoids enumerating thousands of alarms for `FREQ=DAILY;UNTIL=...` years out.

### 7.3 Boot

- `BootReceiver` triggers on `BOOT_COMPLETED` and `LOCKED_BOOT_COMPLETED`. It iterates the index, recomputes the next reminder per event, reschedules.
- Deliberately keep the receiver fast — defer to `goAsync()` and a small coroutine scope.

### 7.4 Notification

- Single notification channel, importance `IMPORTANCE_DEFAULT` (no full-screen, no sound override).
- Title = event title. Body = the event time as `HH:mm` plus the reminder lead (`in 15 minutes`).
- Tap the notification → opens still-cal directly to the day list of that event's date. No deep link to the event editor in v0.1; the day list is one tap further.

### 7.5 Permission flow

- First time the user toggles a non-`none` reminder on any event, request `POST_NOTIFICATIONS`. If denied, save the event anyway, but show a one-line mono caption under the reminder row: `notifications disabled — reminder won't fire`.
- Check `AlarmManager.canScheduleExactAlarms()` before each scheduling call. If false, drop a `Toast`: `enable exact alarms in settings`. Don't open the settings screen automatically (too aggressive).

## 8. SAF I/O

Pattern is identical to still-notes' `IoActions.kt`. New file: `dev/chuds/stillcal/data/IoActions.kt`.

- **Import**: `ActivityResultContracts.OpenMultipleDocuments()` with mime types `["text/calendar", "text/plain", "application/octet-stream"]`. Read each URI, run through `IcsParser`, pass each `VEVENT` to `EventsRepository.importEvent`. Toast: `imported N events`.
- **Single export**: `ActivityResultContracts.CreateDocument("text/calendar")`. Default filename `<safe-title>.ics`. Body is the file at `events/<id>.ics`.
- **Bulk export**: `ActivityResultContracts.CreateDocument("text/calendar")`. Default filename `still-cal-<timestamp>.ics`. Body is a single `VCALENDAR` envelope containing every event's `VEVENT` inner block.
- **VIEW intent**: `MainActivity` intercepts `ACTION_VIEW` with `text/calendar`, reads the URI's bytes, runs through `IcsParser`, imports. After import, navigate to the month containing the first imported event.

## 9. Architecture

```text
MainActivity
└── StillCalApp                      single-Activity Compose shell, hand-rolled router
    ├── EventsRepository             .ics files on disk + JSON index, debounced save
    ├── PreferencesRepository        DataStore — font preset, default view, week start, time format
    ├── IoActions                    SAF read/write, share-intent, bulk .ics export
    ├── ical
    │   ├── IcsLexer                 line unfolding
    │   ├── IcsParser                BEGIN/END block recognition → RawVEvent
    │   ├── IcsTypes                 RawVEvent → Event (DTSTART/DTEND/RRULE/VALARM)
    │   └── IcsWriter                Event → .ics, line folding, escaping
    ├── reminders
    │   ├── RemindersScheduler       compute next occurrence, AlarmManager calls
    │   ├── ReminderReceiver         BroadcastReceiver → notification + reschedule next
    │   └── BootReceiver             BOOT_COMPLETED → reschedule everything
    └── Compose surfaces
        ├── ui/month/MonthScreen     7×6 grid, dot per event-day
        ├── ui/week/WeekScreen       7 rows, events stacked
        ├── ui/day/DayListScreen     chronological events for a date
        ├── ui/event/EventEditScreen single scrollable form
        ├── ui/settings/SettingsScreen  font preset, default view, week start, import/export
        ├── ui/components/StillDivider, StillMenuItem, StillToggle, StillNumberPicker
        └── ui/theme/StillTheme, StillColors, StillTypography, StillFontFamilies
```

## 10. Gestures

| Gesture | Effect |
| --- | --- |
| Tap a day cell (month) | Open day list for that date |
| Tap an event row (day list / week) | Open event editor for that occurrence |
| Long-press an event row | Action sheet — edit, delete |
| Swipe left/right (month) | Advance / retreat one month |
| Swipe left/right (week) | Advance / retreat one week |
| Tap `today` | Snap to the current date in whichever view |
| Tap `month` / `week` | Toggle views |
| Tap a date in the editor | Reveal inline numeric picker |
| System back | One step back along the route stack |

## 11. Design language

Identical to still-launcher and still-notes:

- OLED black background. Soft white primary text. Gray secondary. Hairline (`#232320`) dividers.
- Serif for titles, year header, day numbers, event titles in pickers. Sans-serif for body and menu items. Monospace for kickers, captions, the picker numerals.
- Lowercase for verbs (`new`, `today`, `save`, `delete`, `cancel`, `back`, `import`, `export`). Title case only when the user typed it themselves.
- No ripple. Fade-only transitions. No bouncy motion. No accent color.
- Today's day-cell number is `SoftWhite`, not a circle, not a colored chip — typographic emphasis only.
- Event-day dots are `MutedWhite`, 3dp diameter, 4dp gap, capped at three.
- All four font presets (System, Editorial, Terminal, Grotesk) shipped — same as still-notes.

## 12. Build requirements

- JDK 17, Android SDK with `platforms;android-36` and `build-tools;36.0.0`.
- AGP 9.2.1, Kotlin 2.3.21 (Compose plugin), Compose BOM `2026.05.00`.
- Gradle wrapper 9.4.1 — copy from `still-notes/gradle/` into `still-cal/gradle/`.

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 13. Acceptance criteria (definition of done for v0.1)

- [ ] App launches; lands on month view of the current month.
- [ ] Tapping any day opens the day list for that date.
- [ ] Creating an event writes an `events/<id>.ics` file that another calendar app accepts.
- [ ] Editing an event updates the file in place; UID does not change.
- [ ] Deleting an event removes both the file and the index entry.
- [ ] All four font presets apply across every screen.
- [ ] Default view setting is honored on cold start.
- [ ] Week-start setting visibly reorders the month grid header and the first column.
- [ ] Recurrence — daily, weekly, monthly with `until` — produces the right dot count in the month grid for at least three consecutive months past the start.
- [ ] Reminders fire within 60 seconds of the scheduled instant on a Pixel 8a Android 36 emulator with the screen off.
- [ ] After a reboot of the emulator, a reminder scheduled before reboot still fires.
- [ ] SAF import of a 50-event `.ics` exported from Google Calendar produces 50 visible events with correct titles, times, and recurrences (subject to the parser's documented subset).
- [ ] SAF bulk-export of a 50-event notebook produces a single `.ics` file that round-trips cleanly back into still-cal.
- [ ] `ACTION_VIEW` of a `.ics` file from another app imports the events and lands on the right month.
- [ ] No crashes when an event's `DTEND` is missing (treat as `DTSTART` + 1h).
- [ ] No crashes when an event's `TZID` references an unknown zone (fall back to device zone, log nothing).
- [ ] Manifest permissions are exactly: `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`. No more.
- [ ] `data_extraction_rules.xml` excludes everything from cloud backup and device transfer.
- [ ] No `INTERNET` permission. No `QUERY_ALL_PACKAGES`. No Firebase, GMS, or analytics dependency in `app/build.gradle.kts`.
- [ ] README mirrors the still-notes / still-launcher template (title block, what it does, what it refuses, privacy posture, architecture, gestures, design language, build, GrapheneOS notes, status, license).

## 14. Out of scope for v0.1 (explicit non-goals)

- CalDAV, ICS subscription URLs, any networked sync.
- Multi-day spanning bars in month view.
- Per-event color/category/calendar-of-origin.
- Recurrence exceptions (`EXDATE`, `RECURRENCE-ID`, `BYDAY` lists).
- Notification action buttons (snooze, mark-done).
- Time zone editing per event (events store the device zone at authoring time and never re-anchor).
- Widget, quick settings tile, app shortcuts.
- Localization beyond English (numeric layouts work everywhere; verb labels stay English in v0.1).
- Tablet two-pane layout. The MVP is phone-only.
- Search across events. (If the day list scales painfully, revisit. The plan keeps the surface small intentionally.)

## 15. Open questions to resolve during build

1. **Floating-time vs. zone-anchored on disk.** Spec says `TZID=` always. If the parser frequently sees floating-time files in the wild, consider also writing floating-time on import-then-export to preserve the original's intent. Decide after the first import test.
2. **Notification tap target.** Spec sends the user to the day list. If that feels indirect, route directly into `EventEditScreen` for the occurrence. Try the day-list version first.
3. **Number picker vs. text-field fallback.** If the custom picker eats more than 1.5 days of work, ship the `BasicTextField` fallback for v0.1 and mark the picker as a v0.2 task in `TODO.md`.

## 16. Sibling repos to read before writing code

- `../still-notes/app/src/main/java/dev/chuds/stillnotes/StillNotesApp.kt` — the hand-rolled router pattern this app must mirror.
- `../still-notes/app/src/main/java/dev/chuds/stillnotes/data/NotesRepository.kt` — the file-on-disk + JSON-index pattern `EventsRepository` should mirror.
- `../still-notes/app/src/main/java/dev/chuds/stillnotes/data/IoActions.kt` — the SAF helper shape.
- `../still-notes/app/src/main/java/dev/chuds/stillnotes/markdown/` — the hand-rolled parser shape the `ical/` package should mirror.
- `../still-notes/app/src/main/java/dev/chuds/stillnotes/ui/list/NotesListScreen.kt` and `ui/components/StillMenuItem.kt` — the visual primitives.
- `../still-launcher/README.md` and `../still-notes/README.md` — the README template.
- `../still-ecosystem-plan.md` §`still-cal` — the original framing.

The scaffold already in this directory provides the build files, the design tokens (`StillColors`, `StillTypography`, `StillFontFamilies`, `StillTheme`), the Android manifest with permissions disclosed, and the `StillDivider` primitive. Copy the `.ttf` files from `../still-notes/app/src/main/res/font/` into `app/src/main/res/font/` to get the Editorial / Terminal / Grotesk presets. Copy `../still-notes/gradle/` into `gradle/` to get the wrapper. Everything else is yours to write.
