# Phase 1 first-run test

**Tested 2026-11-06 on one Samsung phone**, with YouTube 21.43.523. Permissions were granted over `adb` and taps were simulated, with dummy data that was then wiped.

| Check | Result |
|---|---|
| Rule unit tests | 22 of 22 pass |
| No network permission | Passes; the process isn't in the `inet` group |
| Setup, then goal and rule saved | Works; data survives a reinstall |
| Shorts pause shows the real next step | Works, and YouTube's audio stopped |
| Don't open | Returns to YouTube's home tab |
| Do it now | 6 of 6 open Today with the step added and no duplicates |
| Open during the countdown | Ignored |
| Open with a reason | Plays, no re-pause when re-entering within 5 minutes |
| After 5 minutes in Shorts | Pause returned and audio stopped |
| Home while paused | 4 of 4 with no playback and no picture-in-picture |
| Whole-app pause | 10 of 10, stuck on the home screen 0 of 10 |
| Fallback without Accessibility | 6 of 6; stops when Accessibility returns |
| Accessibility off | Reported plainly on Your data |
| Commitment rules | Loosening deferred to Monday; tightening immediate and cancels the deferral |
| Today, Horizons, cooling-off, drop goal | Work |
| Export | Valid JSON; 37 pause events match the Review screen |
| Database at rest | No SQLite header; test goal text not readable in the file |
| Crashes | None recorded |

## Bugs found by this run and fixed

- With the keyboard open, lower fields and buttons sat hidden behind it.
- Only the small checkbox responded in the app picker, so a rule could silently fail to save.
- Do it now landed on an empty Today list, and could race with Back and close Keel.
- Pressing Home sent Shorts into picture-in-picture, where it kept playing past the pause.

## Known limits

- **Sending a media-pause command** when you leave a pause could also pause music from another app. Not tested.
- **After choosing Open,** a video can continue in picture-in-picture. This is by design during the 5 minutes, and whether the pause returns for a picture-in-picture window afterwards is untested.
- **Force-stop** turns Accessibility off, and Keel reports it only on next open.
- **Audio on the first Shorts open** escaped twice in Phase 0. It was silent in the runs I checked here, but the sample is small.
- **Exit-test numbers** can't come from this run: most pauses were triggered by the test script.

## Not tested

- The real permission screens (granted over `adb` here)
- Reboot, overnight use and battery drain
- Sunday review on an actual Sunday, and a deferred rule taking effect on Monday on the phone (covered by unit tests only)
- The health notification, which needs the 15-minute job to fire with a problem present

## Backup and restore test (2026-11-06)

Run on a separate "Keel check" app with dummy data, never on the real Keel, using the on-device Downloads folder.

| Check | Result |
|---|---|
| Encryption unit tests, including altered, reordered and truncated files | Pass (37 of 37 total) |
| Recovery phrase on screen | Screenshot blocked |
| Backup can't start with a wrong confirmation code | Pass |
| Backup is written, then read back and checked | Pass; no readable goal, task or phrase in the file |
| Uninstall, reinstall, restore from the first screen | Goal, task, rule and phrase all came back (run twice) |
| Phrase typed in lower case without dashes | Accepted, then stored in standard form |
| Wrong phrase, damaged file, non-backup, too-short phrase | Each refused with a clear message, no data changed |
| Restore over newer data | Warns; Cancel keeps data; Replace removes newer data |
| New backup after a restore | Saved and checked |
| Plain export | Contains data, never the phrase |

**Not tested:** Google Drive or any cloud location, very large databases (the backup is built in memory, which is fine at today's size but a limit for years of usage history), and restoring on a different phone.

## Phone backups, delete and privacy panel (2026-11-07)

Run on the separate "Keel check" app with dummy data, never on the real Keel.

| Check | Result |
|---|---|
| App installed after setup | Appears in Add apps, and can be added as a rule |
| Privacy panel | Shows a live result: no internet permission requested, network socket refused |
| Manage on a permission | Warns first; Keep it on stays in Keel; Open Settings reached the overlay screen, and Back returns |
| Back up to this phone | Saved to `Download/Keel` with no storage permission, then read back and checked |
| Delete a backup | Warning shown, stronger on the last one; Keep keeps it; Delete removes it |
| Uninstall Keel | Backup file stays |
| Reinstall and restore from `Download/Keel` | Goal and rules came back |
| Delete all my Keel data | Needs DELETE typed exactly; Cancel keeps data; afterwards Keel returns to its first screen, even after a restart, and backup files remain |

**Not tested:** Manage on the other three permissions (usage access, Accessibility and notifications, which open different Settings screens), and whole-app or Reels pausing for Instagram, which isn't installed. Reels-only pausing has no rule yet. Android only lets Keel list and delete backups from its current install, so older ones need the Files app to delete, though Restore can still open them.

## Modes: time limit, task-locked and Sprint (2026-11-07)

Run on the separate "Keel check" app with dummy data, with Calculator and Clock as test targets.

| Check | Result |
|---|---|
| Rule unit tests | 55 of 55 pass |
| Upgrade from the previous database format | Goal, task and rules survive; rules stay plain pauses |
| Backup from the previous format | Restores into the new version |
| Task-locked with a task left | Paused, with the reason and a 60-second countdown |
| Task-locked with the list finished | Opens freely |
| Time limit of 5 minutes on Clock | Free at first, paused at about 5 minutes |
| New rule, first 10 minutes | Changes apply straight away |
| Older rule, loosening | Deferred to next Monday |
| Sprint | Pauses even with a finished list; ending early waits until Monday |
| Shorts-only blocking on the new code | 5 of 5, none on the home tab |

**Not tested:** the empty-list and usage-unavailable cases on a phone (unit tests only), and a limit crossing midnight. Time from a session that began before midnight isn't counted towards the new day. Time limits apply to whole apps only, because time per surface can't be measured.

## Plans and money goals (2026-11-07)

Run on the separate "Keel check" app with made-up plan files. The parser was also checked on real files on the computer, reporting counts only.

| Check | Result |
|---|---|
| Rule unit tests | 72 of 72 pass |
| Parser on 17 real Markdown files | 347 steps, no leftover Markdown, and export then re-read matched on every file |
| Upgrade from the previous database format, and its backup | Data kept; backup restores |
| Import two files at once | Both imported, one with no steps kept as a reading plan |
| Active switch | The plan's next step appears on the pause screen |
| Very long step | Capped at 4 lines, with buttons on screen |
| Re-import a changed file | 1 new, 1 removed, ticks kept, title updated |
| Export with ticks, and clean copy | Correct ticks; rest of the file unchanged |
| Export all to a folder | All plans written |
| Paste text | Saved as a plan |
| Delete a plan | Warning, then removed |
| Money goal | Local currency format; latest amount, percent and last update shown |
| Uninstall, reinstall, restore | Plans and money goals came back |

**Not tested:** a plan with hundreds of steps on the phone, a plan file imported by another person's phone, and the pause screen's speed with all 347 steps loaded. Simple budgets and payday prompts from the original plan aren't built. Android's file picker may not list folders copied over USB under its Downloads view, so a file may need to be found through the phone's internal storage.

## Today from plans, check-in, time budgets and experiment (2026-11-07)

Run on the separate "Keel check" app with made-up plans.

| Check | Result |
|---|---|
| Rule unit tests | 86 of 86 pass |
| Upgrade from the previous database format | Plans and goals kept |
| Today, From your plans | Next steps from Active plans; Add moves one to Today and out of the suggestions |
| Browse all plans | Library plans can be browsed, and a step added |
| Weekly check-in | Saves and averages |
| Time budget with an app | Measured from real usage: 5 h 28 min against 1 h |
| Time-to-money rule | Correct amount; can be added to Today once a week |
| Experiment | Starts next Monday, can be stopped; on and off weeks covered by unit tests only |
| Manage on usage access and notifications | Opens the right Settings screen; Accessibility wasn't tried |
| Backup and restore | Check-in, budget, tasks and plans came back |
| App info on the phone | "No data used" for mobile data |

**Not tested:** a full on and off week of the experiment, restarting the phone, overnight use, and battery drain, because the phone had only been off its charger for about a minute.

## Redesign, deadlines and automatic backup (2026-11-07)

Run on the optimised release build under the separate "Keel check" identity, with made-up data.

| Check | Result |
|---|---|
| Rule unit tests | 95 of 95 pass |
| Android lint | No errors. It found a real crash on Android 12 and below, now fixed |
| Release build | About 5 MB, no network permissions |
| Screens viewed on the phone | Setup, Horizons, Plans, plan page, Today, Rules, Review, Your data, delete dialog, pause screen |
| Deadlines in Today | Overdue, upcoming and soonest-first, without repeating under From your plans |
| Automatic backup, forced job run | One file written; a second run made no duplicate |
| Restore from an automatic backup | Data came back |
| Swapping debug for release | Data, key and phrase kept |
| Shorts on the release build | Paused in 6 of 6, and the overlay left after Back each time |
| Normal YouTube | No pause on the home tab or across 8 scrolls |
| Audio behind the pause | None in the 6 checks |
| Manage on Accessibility | Warning, then the right Settings screen |
| Do it now and Don't open | Work on the release build |
| Delete all | Returns to the first screen |

**Not tested:** Open with a reason, time limit, Task-locked and Sprint on the redesigned build (the logic is unchanged and unit tested, and they worked on the previous build); plan import and export on the release build's redesigned action rows beyond a single import; automatic backup waiting a real week; restart; battery.

Contrast, measured from the palette: text 14.6-18.2:1; secondary text 6.2-7.7:1; rose on page and surface 5.0-5.4:1; jet on rose fill 5.4:1; button label on Cerulean 4.39:1, just under 4.5:1.

Design skills used: `emil-design-eng` (Emil Kowalski), `better-ui`, `better-typography` and `better-colors` (Jakub Krehel), and `web-design-engineer` (Garden Skills).
