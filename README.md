# Keel

A local-only phone app that pauses the apps you choose and points you to your next planned step.
It's a personal project for one Samsung phone. See `PLAN.md` and `results/PHASE0.md`.

## Install

```fish
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Install from the same computer each time: the app is signed with `~/.android/debug.keystore`, and a different key would force an uninstall, which deletes the database.

## Check

```fish
./gradlew :domain:test      # Rules: ranking, commitments, sessions, weekly stats
scripts/check_no_internet.sh  # Fails if the APK requests network access
```

## Layout

- `domain/`: plain Kotlin rules, tested without a phone
- `app/`: encrypted storage (`data/`), pause system (`pause/`), screens (`ui/`)
- `spike/`: throwaway Phase 0 test app

## Features

- **Rules:** pause, time limit, task-locked and Sprint, with commitment rules, for whole apps or YouTube Shorts
- **Plans:** Horizons, Today, imported Markdown plans, typed money goals
- **Review:** abandoned opens, weekly check-in, time budgets, a self-set time-to-money rule, and an on and off experiment
- **Your data:** live privacy check, backup, restore, export and delete

## Data

Everything is in a SQLCipher database. Its key is wrapped by the Android Keystore, so the database file can't be moved to another phone.

Under Your data, **Back up to this phone** writes an encrypted `.keelbak` file to `Download/Keel` with no storage permission, and reads it back to check it. **Choose another location** saves anywhere the file picker offers. Backups can be deleted individually, and **Delete all my Keel data** erases the database contents after a typed confirmation. Only your 25-character recovery phrase can open it, and it can't be recovered if lost. **Restore from a backup** replaces everything, and is also offered on Keel's first screen after a reinstall. The readable JSON export can't be restored.

## Plans

Keel is generic and ships with no plans. Under Horizons, **Plans and money** imports any Markdown file or pasted text, and every `[ ]` line becomes a step, including ones in table rows. Importing a changed file with the same name keeps your ticks. Plans start in a library, and switching one to Active feeds Horizons and the pause screen.

To share a plan, export it. The file is ordinary Markdown, with your ticks or a clean copy, and anyone can import it. **Don't add personal plans to this repo.**

## Testing

The `check` build is a separate "Keel check" app with its own data, so tests can't touch your real Keel:

```fish
./gradlew :app:assembleCheck
adb install -r app/build/outputs/apk/check/app-check.apk
```

Avoid `connectedAndroidTest` on the real phone: it uninstalls the app afterwards, which deletes the database.

Tables: `goal`, `goal_touch`, `plan_step`, `task`, `rule`, `commitment_change`, `intercept_event`, `usage_session`, `review`, `setting`.

Shorts detection lives in `app/src/main/assets/surface_rules.json`. If a YouTube update breaks it, Your data shows a warning when the version differs, and the view IDs can be updated there.
