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
