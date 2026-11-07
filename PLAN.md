# Keel: full app plan

"Keel" is a working name. I haven't checked it for trademarks.

## Context

The goal is a phone app that runs locally and does two jobs:

1. **Controller:** controls screen time and app use.
2. **Life planner:** holds todos, short-term and long-term plans, and finance and health plans in one place.

The inspiration is HackerTube, which replaces YouTube's engagement-driven feed with a slower, human signal.

**Recommendation: build one app, with the controller as the way in.** Separately, these are two crowded markets. Together they make a blocker that knows what you are trying to do with your life.

**What the research corrected**
- Karpathy did build HackerTube, but it is at **hackertube.org**. I couldn't load hackertube.com, so I can't say what it is.
- I found no evidence that "deslopifying life" is Karpathy's phrase, so don't attribute it to him in marketing.
- "Unlock apps after finishing a task" is **already taken** by TaskLock, To-do Block, Habit Doom and Habits First.
- "Local and free" isn't enough to stand out either, because ScreenZen is already free with no account.

**The gap to target:** I found no product that links a phone lock to multi-year plans, money and health in one local database. The search was limited, so this doesn't prove none exists, and nobody has yet shown they would pay for it.

## Product principles, adapted from HackerTube

| HackerTube | Rule for Keel |
|---|---|
| Replaces the algorithm with a human signal | Rank goals by what you keep coming back to, not by urgency badges |
| Reportedly scores videos by how many months people recommended them | A goal rises with the number of separate months you returned to it |
| Finite list of 6,454 videos | Days and lists actually end, with no "suggested next" |
| Replaces clickbait thumbnails with a mid-video frame | Plans show their real state: "₹12,400 of ₹50,000, stalled 3 weeks" |
| No account, state kept locally | No account and no server |
| Dataset reportedly exported | Your whole life is one documented file you can export |
| Sort order can be changed | Ranking is visible and changeable |
| Serves the outlier ("we are a rounding error") | No leaderboards, cohorts or "users like you" |

HackerTube filters content, but it doesn't stop anyone going back to YouTube. The blocking design below therefore comes from behavioural research.

## The core loop

```
Open Instagram
  → Pause screen (about 10 seconds, skippable into a choice)
      "Next step: Compare 2 term plans (15 min)"
      "Serves: Family safety net, 2030"
      [Do it now]   [Don't open]   [Open for 5 min: why?]
  → The choice is logged
  → Sunday review: abandoned opens, steps done, one rule to change
```

**Headline number:** opens you changed your mind about, not total screen time.

## What the evidence says to build

Grades are my own judgement. Effects are mostly small and fade, so the app must not promise wellbeing gains.

| Build | Evidence | How Keel uses it |
|---|---|---|
| Short delay with an easy exit | one sec: 36% of attempts dismissed, but no field control group and only 280 of 719 users analysed. A separate experiment suggests the exit option matters more than the wait. Grade B- | Default intervention, and there is always a way out |
| Progress monitoring | Harkin 2016: 138 studies, d+=0.40. Grade A- | Weekly review using logged data, not self-estimates |
| If-then plans | d about 0.27–0.66, possibly much lower after bias correction. Grade A-/B | Every plan step can have "When X, I will Y" |
| WOOP | g about 0.28–0.34. Grade B | Goal setup: Wish, Outcome, Obstacle, Plan |
| Pre-commitment | Saving defaults; contracts gave +3 pp at 6 months but only about 11% took them up. Grade B/B+ | Opt-in rules that get harder to loosen |
| Notification batching | One RCT, N=237, about 3 batches a day. Grade B- | Digest option, but not total silence |
| Specific savings reminders | +16% when specific, none when generic (working-paper figures) | Name the goal in the prompt |
| Graded step targets | Large benefit by 5–8k steps; observational. Grade A- | Build up from your baseline, with no 10,000 target |

**Offer only as an opt-in:** total blocking sprints of 2–3 weeks. Only 25.5% met the compliance criterion in the best-known trial.

**Avoid**
- Streak counters that reset. Use "days done in the last 30", plus a positive return moment after a miss.
- Relying on self-set time limits alone. Evidence is weak, and some suggests they backfire.
- Promises of a "21-day" or "66-day" habit. Real timelines range from days to nearly a year.
- Time blocking as a core method. I found no direct trial.
- Finance education as a core feature. The meta-analyses conflict.
- Manual entry wherever import or sensing will do. It lowers engagement, and a review of health apps found a median of 70% stop within 100 days.

## Features

### Controller
- **Tracking:** copy Android usage events into Keel's own database often, because the system keeps them only a few days.
- **Pause screen:** per-app delay, with the next plan step shown.
- **Surface blocking:** block Shorts or Reels but keep the rest of the app, using Accessibility.
- **Modes:** Pause, Limit, Task-gated and Sprint.
- **Commitment rules:** tightening is immediate, while loosening takes effect next week.
- **Blocker health alarm:** detects a disabled service or broken rule and tells you. Silent failure is a major complaint about existing blockers.
- **Personal experiment:** alternate friction on and off by week, then report honestly, including "this did nothing for you".

### Planner
- **Horizons:** Life, Year, Quarter, Week and Today. The app opens on the long view.
- **Days that end:** after the last item, the screen says you're done.
- **Provenance:** every task links back to the note or moment that created it.
- **Cooling-off lane:** a new ambition waits 30 days before it enters the long-term plan.
- **One-tap goal kill:** dropping a goal is easy, and the reason is recorded.
- **Personal planning-fallacy check:** shows predicted vs actual time by task type. This extends project-level research and is untested for individuals.
- **Capture:** text first, voice later.

### Finance
- **Import:** bank CSV first, then OFX and QIF.
- **Ledger:** append-only events, with balances calculated from them. This stops a sync merge silently producing a wrong balance.
- **Plans:** envelope budgets, savings goals and payday prompts that name the goal.
- **Budget-review unlock (opt-in):** apps stay locked on the 1st until you reconcile the month.
- **Boundaries:** Keel never holds or moves money, and gives no investment advice.

### Health
- **Read-only import** from Health Connect: steps, sleep and workouts.
- **Sleep:** show timing regularity without implying it will extend your life.
- **Food and exercise:** offline logging and an exercise library, added after the first release.
- **Boundaries:** not a medical device and no diagnosis.

### Cross-domain views (the real differentiator)
- **Zero-based hours:** waking hours go into envelopes like a budget, so going over takes time from something named.
- **Self-set exchange rate:** "30 minutes over means I move ₹100 to savings", shown as a to-do you carry out. Keel must not claim screen time caused a financial result.
- **Life file:** one documented SQLite file with a public schema.

## Technical design

| Layer | Choice | Why or what to watch |
|---|---|---|
| First platform | **Android only** | Android can read usage, block individual surfaces and overlay custom UI. iOS can't do these |
| Language | Kotlin and Compose, with domain logic kept in a shared module | Can become Kotlin Multiplatform later |
| Database | SQLite, SQLCipher and FTS5 | Benchmark encrypted write and search speeds |
| Keys | Random key wrapped by Android Keystore, plus a recovery phrase | Losing the phrase and the device means losing the data, so the app must say so plainly |
| Backup | Encrypted file written through the Android file picker | The user's Drive or file app handles the upload. Needs a spike |
| Sync (later) | Encrypted change log with per-field last-writer-wins, and Loro for rich text only | PowerSync and Electric don't fit, because a server would see the data |
| Blocking | UsageStats, Accessibility rules and overlay | Fallback mode with no Accessibility: UsageStats and overlay only |
| Rules | Surface-detection rules stored as data | Target apps' updates will break them, so they need frequent updates |

**Trust claim to test: no `INTERNET` permission in the core app.** Only a few blockers can say this. If the spike holds up:
- Backups go through the file picker.
- Rule packs and AI models are imported as signed files.
- Local VPN domain blocking is left out of the first release.

Paid distribution or billing mustn't add network access, so check that too.

### Main data tables
- **Planning:** `goal`, `plan_step`, `task`, `if_then_plan`, `review`, `note`, `provenance_link`
- **Controller:** `app_rule`, `surface_rule`, `usage_session`, `intercept_event` (choice, reason, linked step), `commitment_change`, `experiment_week`
- **Finance:** `account`, `transaction_event`, `envelope`, `savings_goal`, `import_batch`
- **Health:** `health_sample`, `food_log`, `workout_log`
- **Cross-domain:** `time_envelope`, `exchange_rule`

### Main screens
1. **Onboarding:** explain each permission, pick 3 problem apps, set one goal using WOOP.
2. **Today:** a finite list, ending with a done state.
3. **Horizons:** the long-term plan view, and the home screen.
4. **Pause overlay.**
5. **Rules:** apps, surfaces and commitment settings.
6. **Money:** envelopes, goals and import.
7. **Body:** steps, sleep and workouts.
8. **Sunday review.**
9. **Your data:** export, backup, schema, and every permission with its reason.

### Android and Play limits to design around
- **Accessibility needs a Play declaration,** in-app disclosure and a video. A blocker can't claim to be an accessibility tool.
- **A self-control app on Play can't block disabling or uninstalling itself.** "Unkillable" isn't achievable, so Keel detects and reports it instead.
- **AI must never act through Accessibility on its own.** Rules stay fixed and human-approved.
- **Android 17 Advanced Protection** reportedly revokes Accessibility for non-tool apps, which is why the fallback mode exists.
- **SMS reading** is a discretionary Play exception, so leave it out of the Play build. Notification parsing comes first, with OTP redaction to test.
- **Developer verification** goes global in 2027, so register early and protect the signing key.
- **Health Connect** on Play is limited to approved use cases, and history beyond 30 days needs its own permission.

### iOS later, as a reduced companion
- **Can do:** system shields, schedules and a Shortcuts-based pause, plus the planner, finance and health modules.
- **Can't do:** read usage into your own database, block Reels or Shorts separately, or show a custom overlay.
- **Known problems:** the monitor extension has a 6 MB limit, and developers report missed callbacks.
- **Rule for iOS:** never make a commitment depend on exact threshold timing.

### On-device AI: optional, never required
The app must work fully without AI. Rules handle parsing first.

| Use | Model route |
|---|---|
| Transaction categorisation | Rules and MCC codes first, then a small model |
| Breaking a goal into steps | User-triggered, with results you approve |
| Personal distillation: what you keep returning to across old notes | Downloaded 1–3 GB Gemma-class model via LiteRT-LM or llama.cpp |
| Voice capture | whisper.cpp base (142 MiB on disk, about 388 MB memory) |

- **Gemini Nano** works only in the foreground and on limited devices, so it can't do overnight work.
- **Speed is unmeasured.** I found no benchmarks for mid-range phones, so measure before promising anything.

## Datasets

| Status | Dataset | Used for |
|---|---|---|
| Bundle | USDA FoodData Central (CC0), Foundation and SR Legacy subsets | Offline nutrients |
| Bundle | free-exercise-db (Unlicense; check image origins) | Exercise library |
| Bundle | greggles/mcc-codes (Unlicense) | Merchant categories |
| Bundle | Behaviour Change Technique Ontology (CC BY 4.0) | Tagging each feature to a technique |
| Bundle | `holidays` (MIT) | Planning around holidays |
| Bundle with attribution | O\*NET subset (CC BY 4.0) | Career goals into skills and steps |
| Train only | finnai-slm-data (Apache-2.0, synthetic Indian SMS), us-bank-transaction-categories (MIT, synthetic) | Parser and classifier. Quality unverified |
| Separate optional pack | Open Food Facts (ODbL, share-alike) | Barcode lookup |
| Don't bundle yet | IFCT 2017 | Indian foods. Needs permission from NIN |
| Don't use commercially | wikiHow goal-step (NC), Tsinghua App Usage (research only), LSApp (no licence) | Benchmarking at most |

No phone-usage dataset is clearly cleared for commercial use, so Keel learns only from the user's own logs. These licence readings aren't legal advice, so check each file before shipping.

**User imports:** bank statements, Apple Health XML, Garmin exports and Google Timeline exports. Start logging on first run, since Android keeps so little history.

## Competition

| Group | Examples | Keel's angle |
|---|---|---|
| Friction and blocking | one sec, Opal, ScreenZen, Freedom | Knows your plans, and reports its own failures |
| Task-gated | TaskLock, To-do Block, Habit Doom, Habits First | Long-term plans, money and health, not just a daily checklist |
| Open-source Android | Mindful, Nudge, TimeLimit, Atharok Screen Time | A real planner, with similar privacy |
| Planners | Sunsama, Motion, Todoist, TickTick, Amazing Marvin | Local, and enforced by the phone |
| Local specialists | Actual Budget, Loop, Anytype | Import from and export to them rather than beat them |

**Biggest product risk: all-in-one sprawl.** Evidence on why these apps fail is mostly opinion, but setup burden and shallow features come up repeatedly. So Keel ships opinionated defaults, needs no setup system, and adds domains only after the core loop works.

## Roadmap

Durations are rough estimates for one full-time developer, and I don't have data behind them.

| Phase | Time | Build | Exit test |
|---|---|---|---|
| **0. Spikes** | 2–3 weeks | Pause overlay across OEMs, Reels and Shorts detection, no-`INTERNET` backup, SQLCipher speed, Play policy review | Overlay appears reliably on 3+ brands; any failed spike changes this plan |
| **1. Core loop** | 8–10 weeks | Tracking, Pause, goals, steps, Today, Horizons, Sunday review, export, health alarm | 20 pilot users for 4 weeks |
| **2. Money** | 6–8 weeks | CSV import, ledger, envelopes, budget-review unlock, encrypted backup | Pilot users reconcile real statements correctly |
| **3. Body and cross-domain** | 6–8 weeks | Health Connect, exchange rule, zero-based hours, personal experiment | Users keep at least one cross-domain rule on |
| **4. AI** | 4–6 weeks | Optional model import, categorisation, goal breakdown, voice | Measured speed is acceptable on a mid-range phone |
| **5. Sync and iOS** | 12+ weeks | Encrypted sync, iOS companion, entitlement requests | External security review passed |

**Keep out of the first release:** iOS, sync, AI, SMS reading, Account Aggregator, social features, wearables, NFC tags and photo proof.

**Pilot targets (my proposals, to adjust after baseline data):**
- At least 50% still active at day 30.
- At least 20% of intercepted opens abandoned by week 4. one sec's 32–34% came from self-selected users, so treat that as a reference only.
- At least 2 of 4 Sunday reviews completed.

Since Keel has no telemetry, pilot users share a summary file they can read first.

## Business model

My recommendation, not something the research settles: open-source the core so the privacy claim can be checked, and sell a one-time paid build or supporter tier. There is no server cost to justify a subscription. Opal charges a reported $99.99 a year, while ScreenZen is free, so price alone won't decide this.

## Main risks

| Risk | Response |
|---|---|
| Play rejects the Accessibility use | Fallback mode, a registered direct APK, and policy review in Phase 0 |
| Reels and Shorts detection keeps breaking | Rules stored as data, health alarm, frequent updates |
| Android restrictions tighten | Keep Pause working without Accessibility |
| Users game task gating | Always offer an exit, log it without shaming, and judge success by the user's own results |
| Lost keys or corrupted finance data | Recovery phrase, append-only ledger, restore tests, security review |
| Scope overload | Each phase must pass its exit test before the next starts |
| Demand is unproven | Phase 1 pilot before building finance |

## Verification

- **Automated:** unit tests for ledger maths, rule evaluation and importers; a backup-and-restore round trip; database migration tests.
- **Real devices:** a test matrix of at least Pixel, Samsung, Xiaomi and one budget phone, on Android 14–17, including reboot, force-stop and permission revocation.
- **Blocking:** a scripted check of each surface rule against current Instagram and YouTube versions, repeated with each update.
- **Privacy:** confirm the merged manifest has no `INTERNET` permission, and capture network traffic to show nothing is sent.
- **Security:** confirm the database can't be read without the key, and get an external review before sync.
- **Behaviour:** the Phase 1 pilot against a 1-week baseline, plus the alternating-week experiment.

## Research limits

- Firecrawl credits ran out during the research, and some pages were blocked.
- Many figures come from abstracts or tool summaries and weren't checked by hand against the papers. Recheck any you plan to publish.
- I reached no Reddit threads or raw app store reviews, and most competitor prices come from competitor or affiliate blogs.
- I found no budgeting-app RCT, no long-term randomised friction trial, and no credible retention data for productivity apps specifically.
- The 22 product ideas behind this plan are untested with users.

## On approval

1. Save this plan as `/home/senthan/Documents/ideas/keel/PLAN.md`.
2. Create the Android project there.
3. Build the Phase 0 spikes and report results before any Phase 1 work.

## Changes from Phase 0

Status on 2026-11-06: tested on one Samsung phone, and the exit test is still open. Full results are in `results/PHASE0.md`.

These override anything above where they differ:

- **Foreground detection:** never trust an Accessibility window event alone. Confirm it with the active window or the last resumed app from UsageStats. Keel therefore needs Usage Access even when Accessibility is on.
- **While the overlay is showing:** recheck the foreground app about once a second, and request audio focus again shortly after showing.
- **Overlay design:** fully opaque, so feed content can't show through.
- **Health alarm:** can't warn instantly after a force-stop, which also turns off Accessibility. It reports on the next open, and onboarding must explain this.
- **Keys:** use a raw 256-bit database key from the Keystore. Reopening took 31 ms, against 723 ms with a passphrase.
- **Search:** FTS5 comes from the SQLCipher build. The test phone's built-in SQLite doesn't have it, so don't rely on the platform version.
- **Totals:** cache account totals, because encrypted aggregate queries were about 4x slower.
- **Backup format:** encrypt in 1 MB chunks, and authenticate each chunk's index and an is-last flag. Don't use one large GCM stream.
- **Background work:** use a service or scheduled job, because plain threads freeze when the phone locks.

**Still needed to pass Phase 0:** 2+ more phone brands, Instagram Reels, one cloud storage provider, the real permission flow without `adb`, a reboot test, a battery measurement, and a Play policy review.

**On hold since 2026-11-06:** Redmi testing, because Xiaomi's USB limits and Play Protect both blocked it (see `results/PHASE0.md`).

**Distribution risk found:** Play Protect rejected the sideloaded test APK on a Redmi, probably because of its enhanced fraud protection, though that isn't confirmed. Don't rely on a direct APK as the fallback. Treat Google Play as the main route, so Play's Accessibility review becomes a bigger risk, and keep the mode that works without Accessibility. Before Phase 1, test a Play internal track install and a build without Accessibility.

## Scope decision: personal project, Samsung only (2026-11-06)

The owner chose to build Keel as a personal project for their Samsung phone for now. This overrides the earlier plan where they differ:

- **Phase 0 is closed for this scope.** The 3-brand test, cloud backup, Reels and Play policy review only return if Keel goes beyond this phone.
- **Install:** directly over USB with `adb`, so Play Protect's sideload block and Play review don't apply. Debug signing is fine, but keep one stable signing key so updates don't wipe data.
- **Dropped for now:** the 20-person pilot, business model, Play declarations and open-source packaging.
- **Exit test for Phase 1:** the owner uses it on their own phone for 4 weeks and at least 20% of intercepted opens are abandoned by week 4.
- **Kept:** local-only data, no `INTERNET` permission, and every behaviour rule in this plan.

**Phase 1 status (2026-11-06):** first version built and passing on the Samsung with test data, see `results/PHASE1.md`. The 4-week personal trial hasn't started.

## Scope decision: finance is optional and typed by hand (2026-11-07)

Keel never asks for bank data. Finance plans are optional, and people simply type what they want to track, such as a savings goal and how much is saved so far. This overrides the earlier plan where they differ:

- **Dropped:** bank CSV, OFX and QIF import, SMS and notification parsing, Account Aggregator, merchant categories, and the budget-review unlock.
- **Kept:** savings goals, simple budgets and optional money plans, shown with their real state, and payday prompts that name the goal.
