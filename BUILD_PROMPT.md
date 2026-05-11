# Build prompt — paste into a fresh Claude Code session

Open Claude Code in `/Users/huntermeherin/Personal/android/still-cal/` and paste the block below. It is self-contained: no prior turns assumed, no shared memory.

---

You're building **still-cal**, the third app in the Still ecosystem. The other two — `still-launcher` and `still-notes` — are sibling directories one level up. They are your reference implementations for everything from gradle configuration to the hand-rolled router pattern to the look of a list row.

## Read these in order before writing any code

1. `spec.md` in this directory. It is the contract. Every checkbox in §13 must hold for v0.1 to ship.
2. `../still-ecosystem-plan.md` §`still-cal` and the `STILL.md` pact section. The pact is non-negotiable.
3. `../still-notes/README.md` — the README shape your README must mirror.
4. `../still-notes/app/src/main/java/dev/chuds/stillnotes/StillNotesApp.kt` — the hand-rolled router this app must mirror.
5. `../still-notes/app/src/main/java/dev/chuds/stillnotes/data/NotesRepository.kt` — the on-disk file + JSON index pattern `EventsRepository` must mirror.
6. `../still-notes/app/src/main/java/dev/chuds/stillnotes/data/IoActions.kt` — the SAF helper shape.
7. `../still-notes/app/src/main/java/dev/chuds/stillnotes/markdown/` — the hand-rolled parser shape your `ical/` package must mirror.
8. `../still-notes/app/src/main/java/dev/chuds/stillnotes/ui/list/NotesListScreen.kt` and `ui/components/StillMenuItem.kt` — the visual primitives, the lowercase-verb footer pattern, the no-ripple `combinedClickable` shape.

## What the scaffold already gave you

- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `app/build.gradle.kts`, `.gitignore`.
- `app/src/main/AndroidManifest.xml` with the three reminders-related permissions disclosed and two `BroadcastReceiver` declarations.
- `app/src/main/res/values/themes.xml`, `strings.xml`, `xml/data_extraction_rules.xml`.
- `app/src/main/java/dev/chuds/stillcal/ui/theme/` — `StillColors`, `StillTypography`, `StillFontFamilies`, `StillTheme`.
- `app/src/main/java/dev/chuds/stillcal/ui/components/StillDivider.kt`.
- Empty package directories ready for `data/`, `ical/`, `reminders/`, `ui/month/`, `ui/week/`, `ui/day/`, `ui/event/`, `ui/settings/`, `ui/components/`.

## What you must do

1. **Copy the gradle wrapper** from `../still-notes/gradle/` into `./gradle/` and copy `../still-notes/gradlew` and `../still-notes/gradlew.bat` to this directory's root. Make `gradlew` executable.
2. **Copy the font files** from `../still-notes/app/src/main/res/font/` into `app/src/main/res/font/`. (`StillFontFamilies` already references them by R id.)
3. **Create the launcher icon** at `app/src/main/res/drawable/ic_still_cal_launcher.xml` — copy `../still-notes/app/src/main/res/drawable/ic_still_notes_launcher.xml` and edit the glyph to a serif lowercase `c`. Same monochrome palette.
4. **Build the data layer** — `data/Event.kt`, `data/PreferencesRepository.kt` (mirroring still-notes' file), `data/EventsRepository.kt` (mirroring `NotesRepository`'s file-on-disk + JSON-index shape), `data/IoActions.kt`.
5. **Build the `.ics` parser** — `ical/IcsLexer.kt`, `ical/IcsParser.kt`, `ical/IcsTypes.kt`, `ical/IcsWriter.kt`. Hand-rolled. No third-party iCalendar library. The parser must round-trip its own output exactly.
6. **Build the reminders pipeline** — `reminders/RemindersScheduler.kt`, `reminders/ReminderReceiver.kt`, `reminders/BootReceiver.kt`. Use `setExactAndAllowWhileIdle`. Schedule one alarm per event (the next occurrence); reschedule the following one when the receiver fires.
7. **Build the screens** — `MonthScreen`, `WeekScreen`, `DayListScreen`, `EventEditScreen`, `SettingsScreen`. Hand-rolled router in `StillCalApp.kt` mirroring `StillNotesApp.kt`. `MainActivity.kt` mirroring still-notes', plus `ACTION_VIEW` handling for `text/calendar`.
8. **Write the README** at `./README.md`. Follow the still-notes template exactly: centered title block, screenshot strip placeholders, "what it does", "what it refuses to do (and what it asks for honestly)" — disclose the three permissions in that table — "privacy posture, in code", "architecture", "gestures", "design language", "build and install", "GrapheneOS notes", "status", "license".
9. **Confirm the build** — `./gradlew assembleDebug` must succeed. Then run it on the Pixel 8a Android 36 AOSP emulator and walk through every checkbox in `spec.md` §13. Report which ones pass and which don't.

## Posture (this is the part that matters)

- The pact in `STILL.md` is the brand. Every line of code defends it. Refuse with prejudice anything that pulls in a network dependency, an analytics SDK, or a third-party iCalendar library — the whole point is that the parser is small enough for one person to maintain.
- Lowercase verbs everywhere a verb appears (`new`, `today`, `save`, `delete`, `cancel`, `back`, `import`, `export`). Title case only for things the user typed.
- No ripple. Use `interactionSource = remember { MutableInteractionSource() }` + `indication = null` exactly like `StillMenuItem`.
- No accent color. No bouncy motion. Fade-only transitions if you add any.
- Comments: only when the *why* is non-obvious. Don't narrate code that names itself.
- No `+` button. New events are reached via a footer verb.
- Keep the surface small. If a feature isn't in spec.md, it isn't in v0.1. The two open questions in §15 of spec.md are yours to resolve as you go — write the decision into the source file's top comment.

## Two checkpoints I want you to pause for

- **After the data layer + parser are written** but before any UI: write a tiny in-process round-trip test (a `main()` in a debug-only file is fine, or a unit test if you wire one up). Generate three synthetic events, write each to disk, parse each back, assert equality of every `Event` field. Show me the diff if any field doesn't survive.
- **After the month grid renders** but before reminders or SAF: send a screenshot of an emulator showing the current month with three test events scattered across it, plus today emphasized. I want to see the typography decisions on a real screen before you go further.

Outside those two checkpoints, work autonomously. Use `TaskCreate` to track the 9 deliverables above and update them as you finish each one.

When the build is green and the acceptance checklist is run, your final message should be a punch list — every checkbox from §13 marked done or annotated with what's blocking. Under 200 words.
