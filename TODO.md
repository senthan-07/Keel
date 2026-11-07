# Keel TODO

Status as of 2026-11-07. Personal plans are not kept in this repo.

## Done

- [x] Pause screen, Shorts-only blocking, and fallback without Accessibility
- [x] Time limit, Task-locked and Sprint modes, with commitment rules
- [x] Horizons, Today, imported Markdown plans, and typed money goals
- [x] Deadlines in Today, from dates written in plans
- [x] Weekly check-in, time budgets, time-to-money rule, and on and off experiment
- [x] Encrypted backup and restore, automatic weekly backup, delete, and live privacy check
- [x] Premium dark redesign of every screen, including the pause screen
- [x] Release build, with shrinking and no network permissions

## Needs a real phone over time

- [ ] Restart the phone, then check that pauses still appear
- [ ] Automatic backup firing on its own after a week (only a forced run was tested)
- [ ] Overnight use and battery drain
- [ ] 4-week trial: at least 20% of paused opens abandoned by week 4
- [ ] A full on and off cycle of the friction experiment
- [ ] Saving a backup to Google Drive or another cloud app
- [ ] Your real plans on the phone, for speed with hundreds of steps
- [ ] Monday rollovers: deferred rule changes and ending a Sprint early

## Known limits

- Shorts detection was tested on YouTube 21.43.523. Your data warns when your version differs.
- A force-stop turns Keel's Accessibility service off, and Keel can only report it on next open.
- After a reinstall, Keel can't list or delete older backups, though Restore can open them.
- Only tested on Samsung. Xiaomi's USB limits and Play Protect blocked testing on a Redmi.
- White-on-Cerulean button labels measure 4.39:1, just under the 4.5:1 standard, to keep the requested palette.

## Publishing, if that's ever wanted

- [ ] Create a dedicated release signing key
- [ ] Google Play review of the Accessibility use, plus the store listing and declarations
- [ ] Test on other brands and Android versions

## Decided against for now

- Reels-only pausing, bank import, budgets and payday prompts, Samsung Health, AI, voice, sync, and iPhone
