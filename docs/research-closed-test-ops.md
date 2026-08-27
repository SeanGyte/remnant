# Closed-test operations manual (2025-26 rules) — 27 Aug 2026

Sonnet scout; ground truth = Google's own Play Console help pages (cited
at bottom). Third-party tester-marketplace claims flagged as folklore.

## 1. Setup mechanics

- Tester sources: email lists (200 lists x 2,000), Google Group, or the
  **opt-in link** (Testing → Closed testing → track → Testers tab →
  "How testers join your test"). Use the opt-in link.
- Being listed ≠ opted in: testers must open the link signed into the
  right Google account, click "Become a tester", then install FROM THE
  LINK (closed-test apps are not searchable on Play).
- Drop-off points: wrong Google account in browser; searching Play instead
  of using the link; country availability not covering the tester;
  Workspace accounts with policy blocks.

## 2. The 12/14 rule, precisely (official)

- "Minimum of 12 testers opted in continuously for at least 14 days."
- Opt-out mid-window zeroes that tester; re-opt-in restarts their 14 days.
- Official wording is about ENROLMENT continuity, not documented daily
  opens. Third-party sellers claim 2026 "AI engagement analysis" (daily
  opens, real devices, session length) — NOT in Google's docs; treat as
  folklore but behave conservatively anyway (real people, real phones,
  real usage — costs nothing).
- Progress: Testers tab bottom counter = source of truth (not invite list).
- Uploading new builds mid-test does NOT reset the clock — shipping a
  feedback-driven fix mid-test is encouraged evidence.
- Production-access questionnaire (after threshold): (1) about the test —
  recruitment, engagement, feedback collected; (2) about the app —
  audience, value, install estimate; (3) production readiness — **what
  you changed in response to feedback**. Vague Part-3 answers are the
  most-cited "more testing required" trigger; write a concrete
  bug→feedback→fix narrative.

## 3. Pre-launch report / Robo crawler

- Firebase Test Lab Robo crawler on real devices; checks crashes/ANRs,
  perf, accessibility, security. **Purely advisory — blocks nothing.**
- Expect false-positive "crashes"/stalls where the crawler hits the mic
  permission prompt, exact-alarm settings redirect, or battery-exemption
  dialog. Discount those; mine the report for real crashes, contrast/
  touch-target flags, CPU/memory spikes.

## 4. Declarations Remnant's permission set triggers (Play Console → App content)

- **USE_EXACT_ALARM**: restricted to apps whose CORE function is
  alarm/timer/calendar. Remnant qualifies; declaration wording: waking the
  user at the scheduled time to capture the dream before it fades IS the
  product.
- **USE_FULL_SCREEN_INTENT** (Android 14+): auto-grant only for alarm/
  calling core function — file the core-functionality declaration
  explicitly (required since May 2024; unfiled apps must fall back to a
  runtime consent prompt).
- **REQUEST_IGNORE_BATTERY_OPTIMIZATIONS**: alarm apps are an explicitly
  legitimate case; use the direct-prompt intent (we do); justification:
  alarms must fire under Doze.
- **Foreground service TYPE_MICROPHONE**: declaration requires a
  description, user impact, AND **A DEMO VIDEO showing the user steps
  that trigger recording** — easy to miss, prepare a screen recording.
  RECORD_AUDIO must be granted before the service starts, and the service
  must never start from boot/background — only from a user-initiated
  foreground action (our post-alarm flow qualifies; never arm mic from
  BootReceiver).
- **Data safety form**: declare "Name" (+greeting text) as
  collected+shared with third party (Google TTS), purpose app
  functionality, encrypted in transit, "processed ephemerally" IF Google's
  TTS retention terms confirm non-persistence (check before declaring).
  Journal text/audio: NOT declared as collected — but audit every bundled
  SDK (billing, any crash/analytics) for default telemetry first; Google
  binary-scans for undeclared network destinations and mismatches are the
  top rejection cause. Privacy policy must match the form line for line.

## 5. Common first-submission rejections for this category

1. Permission/declaration mismatch → file all declarations pre-submit.
2. Data-safety vs actual behaviour mismatch (incl. SDK telemetry).
3. Overbroad permissions without visible in-product reason → prompt for
   mic at the capture moment (we do, post-onboarding rework).
4. "More testing required" → vague questionnaire answers / bot-like
   testers.
5. Missing TYPE_MICROPHONE demo video; mic service started from
   background.
6. Not covered this pass: Play Billing one-off IAP declaration specifics —
   follow-up before submission.

## Action list before submission

- Opt-in link + explicit tester click-through briefing.
- 14-16 real testers, real phones, genuine daily-ish use.
- Ship one feedback-driven fix mid-test; collect feedback for Part 3.
- Pre-file: exact-alarm, full-screen-intent, battery-exemption,
  TYPE_MICROPHONE (with video).
- Data-safety: name→TTS declared; SDK telemetry audit; policy in lockstep.
- Read pre-launch report, discount permission-gate false positives.

Sources: support.google.com/googleplay/android-developer answers
14151465, 9845334, 9842757, 9844487, 16558241, 10787469, 13392821,
16965181; developer.android.com Android 14 behaviour changes.
