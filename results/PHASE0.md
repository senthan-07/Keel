# Phase 0 results

**Tested 2026-11-06 on one Samsung phone**, with YouTube 21.43.523.

**Verdict: the core ideas work on this phone, but Phase 0 has not fully passed.** The plan's exit test needs the overlay working on 3+ brands, and only one has been tested. Reels and backup to a cloud provider are also untested.

## Results

| Spike | Result | Evidence |
|---|---|---|
| Pause overlay, Accessibility | Shown in 40 of 40 timed trials | `overlay_trials_final.csv` |
| Pause overlay, fallback | Shown in 40 of 40 timed trials | `overlay_trials_final.csv` |
| YouTube Shorts-only blocking | Worked in 8 of 8 trials on the final build, plus 10 of 10 earlier | `raw/events_final.csv`, `surface_trials.csv` |
| Shorts false positives | None on the home tab with a Shorts shelf, after 8 scrolls, or on a normal video. The scrolling and normal-video checks ran on an earlier build | `raw/events_round1.csv`, `raw/events_final.csv` |
| Instagram Reels | **Not tested**: Instagram isn't installed | n/a |
| No `INTERNET` permission | Passes for this build, and the process isn't in the `inet` group. Billing and AI model import aren't included yet | `scripts/check_no_internet.sh` |
| Backup through the file picker | 100 MB written in about 3.6 s and restored in about 3.2 s with matching data, using the on-device Downloads provider | `raw/events_round2.csv` |
| Backup to Google Drive or another cloud | **Not tested**: I avoided your accounts | n/a |
| Tampered backups | Altered and truncated files were both rejected | `raw/events_round2.csv` |
| Database encryption | Wrong key rejected, file header hidden | `bench_samsung.csv` |
| 16 KB page compatibility | SQLCipher's native library passes `zipalign -P 16` | Build output |
| Play policy review | **No new work beyond the earlier research**, so policy risk is still open | `../PLAN.md` |

### Overlay timing (final build)

Medians in milliseconds, from just before the launch command to the overlay being drawn.

| Trigger | Start | Shown | Overlay | YouTube first frame | Overlay before first frame |
|---|---|---|---|---|---|
| Accessibility | Warm | 30/30 | 370 | 157 | 0 of 30 |
| Accessibility | Cold | 10/10 | 378 | 941 | 10 of 10 |
| Fallback | Warm | 30/30 | 368 | 149 | 0 of 30 |
| Fallback | Cold | 10/10 | 533 | 891 | 10 of 10 |

- **Warm opens show YouTube first.** The overlay comes roughly 200 ms after YouTube's reported first frame.
- **Treat these gaps as approximate.** The overlay time includes the launch command's own startup, and the first-frame figure is measured differently.

### Storage speed (medians of 3 runs)

| Work | Plain SQLite | SQLCipher, raw key | SQLCipher, passphrase |
|---|---|---|---|
| Reopen a database | Not comparable | **31 ms** | **723 ms** |
| 2,000 ID lookups | 177 ms | 218 ms | 245 ms |
| 20 account totals over 100,000 rows | 285 ms | 1,236 ms | 1,219 ms |
| Insert 100,000 rows | 3,742 ms | 967 ms | 1,258 ms |
| Insert 20,000 search notes | Not available | 1,014 ms | 1,125 ms |
| 200 full-text searches | Not available | 648 ms | 656 ms |

- **Totals are about 4x slower with encryption,** about 60 ms each. That's fine here, but cache totals rather than recompute them on every screen.
- **Ignore the insert comparison.** Plain SQLite was unexpectedly slower. It always ran first, and I didn't investigate why.
- **Size:** the 4-ABI APK is about 11 MB. SQLCipher accounts for about 2.1 MB in the APK for arm64.

## Problems found

### Fixed in the test app and retested

| Problem | What happened | Fix |
|---|---|---|
| Slow restore | Decrypting one 20 MB GCM stream took 42 s with constant blocking GC | Encrypt in 1 MB chunks. 100 MB now takes about 3 s |
| Thumbnails visible through the pause screen | The 95% opaque background still showed clickbait | Fully opaque background |
| Crash when overlay permission was revoked | Fallback crashed with `BadTokenException` | Catch it and log a health event |
| Stray events triggered the overlay | YouTube fired a window event about 1 s after Home, with the launcher focused, so the pause screen appeared on the home screen. Seen with audio focus on and off. **Cause unknown**, and round 1 didn't show it | Confirm with the active window or the last resumed app from UsageStats |
| Guard delayed cold starts | Checking only the active window pushed the overlay to about 1,125 ms, after YouTube's first frame | The UsageStats check brought it back to 378 ms |
| Audio kept playing behind the overlay | YouTube's audio player stayed active behind the pause screen | Request audio focus. See the remaining gap below |
| Fallback could miss a launch | 1 of 30 warm trials in round 1 produced no request. I suspected late-recorded events but didn't prove it | Overlapping poll window. Then 80 of 80 across two later runs |

### Still open, must be handled in Phase 1

1. **One force-stop silently turns blocking off.** It removed the app from Android's enabled Accessibility list, and reopening the app didn't restore it. Android also stops a force-stopped app's own background work, so Keel can only report this the next time it is opened. The plan's health alarm can't promise an instant warning.
2. **The overlay can get stuck on the home screen.** One Home press out of about 50 in one sequence produced no launcher event, so the overlay stayed up for about 3.7 s. Fix: recheck the foreground app about once a second while the overlay is showing.
3. **Audio can still escape on the first Shorts open.** YouTube had an active player on trial 1 of both retests (2 of 13 total), probably because it starts after focus is requested, though that's unproven. Fix: recheck and request focus again shortly after showing.
4. **Warm opens show the app for a moment first.** About 200 ms, and I haven't shown this can be removed.
5. **Plain background threads freeze when the phone locks.** Android froze the test app mid-transaction. Tracking must use a proper service or scheduled job.
6. **Tree scans use real time.** Median 11 ms, 95th percentile 24 ms, maximum 271 ms, up to 4 a second while YouTube's content changes. Battery use wasn't measured.

## Not tested

- Other brands and other Android versions. The plan calls for Pixel, Samsung, Xiaomi and a budget phone on Android 14–17.
- Reboot behaviour and battery drain.
- **The real permission path.** I granted permissions over `adb`, which skips the screens and restricted-settings checks a real user meets. Onboarding needs its own test.
- Long-running reliability. Every trial ran over a short period with the phone awake and on USB.
- Rule durability across YouTube updates. The Shorts IDs are confirmed only on version 21.43.523.

## Reproducing

```fish
./gradlew :spike:assembleDebug
scripts/check_no_internet.sh
adb install -r spike/build/outputs/apk/debug/spike-debug.apk
```

Then enable the service and permissions, and run `scripts/overlay_trials.sh`. `spike/src/main/java/dev/keel/spike/MainActivity.kt` lists the test settings.

`spike/` is throwaway test code. Phase 1 should rewrite it rather than build on it.

The logs in `raw/` contain timings, package names and view IDs, with no on-screen text. `raw/` is listed in `.gitignore`.

## Redmi: not tested, blocked twice

Connected on 2026-11-06. No tests ran, and the phone's settings are back as they were.

1. **USB route:** The phone blocked USB installs, simulated input and changing secure settings. The options that allow them need a Mi account, which the owner chose not to create.
2. **Manual install from the Files app:** Google Play Protect rejected the APK. The log shows `response=11`, `result=REJECT`, and `INSTALL_FAILED_VERIFICATION_FAILURE`. The phone showed "App not installed". The developer-verification check before it returned `VERIFICATION_ALLOW`.

**Likely cause, not confirmed:** Play Protect's enhanced fraud protection, which Google piloted in India from October 2024. It targets apps installed from sources such as file managers that request sensitive permissions, reportedly including Accessibility. The test app is debug-signed and requests Accessibility. I found no documentation of `response=11`, and didn't test whether a build without Accessibility or a properly signed build would pass.

**Why it matters:** the plan's fallback of a registered direct APK may not reach users where this protection applies, and this is a blocker app's core permission.

**Options to test this phone later:** a Google Play internal test track ($25 developer account; whether it avoids this block is untested), a Mi account, or the owner temporarily turning off Play Protect, which lowers the phone's protection and isn't recommended.
