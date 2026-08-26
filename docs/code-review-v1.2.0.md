# Remnant v1.2.0 — Pre-release Code Review

Reviewed 26 Aug 2026 (AEST) by a Fable background agent ahead of the Google
Play closed test. Read-only review; nothing was modified. All source under
`app/src/main`, build config, manifest, proguard rules, and root docs were
read in full; `app/build/` and `.gradle/` ignored. Findings ranked
most-severe first.

---

## Must-fix before the closed test

### 1. One missed alarm permanently kills all future alarms (alarm-chain design flaw)
- **Files:** `app\src\main\java\com\remnant\dreams\alarm\AlarmReceiver.kt:14-46` (no reschedule, no content intent), `app\src\main\java\com\remnant\dreams\ui\JournalActivity.kt:220-224` (app open never reschedules)
- **Defect:** `setAlarmClock` is one-shot, and rescheduling happens *only* inside `AlarmActivity`/`DreamCaptureService` lifecycles (verified: the only `rescheduleForTomorrow` call sites). `AlarmReceiver.onReceive` never schedules the next day, the notification it posts has `setFullScreenIntent` but **no `setContentIntent`** (AlarmReceiver.kt:30-38), and opening the app doesn't re-arm the alarm.
- **Failure scenario:** Alarm fires while the tester is actively using their phone (common at 7am). Android suppresses the full-screen intent to a heads-up notification. Tapping it does nothing (no content intent); the tester swipes it away. `AlarmActivity` is never created, so nothing reschedules. Every subsequent morning the alarm silently never fires — until a reboot (BootReceiver) or a Settings toggle. Testers will report "alarm worked once then stopped", which is a killer for a 14-day test of an alarm app. Fix: reschedule tomorrow's alarm directly in `AlarmReceiver.onReceive`, add `setContentIntent`, and defensively call `AlarmScheduler.schedule` in `JournalActivity.onResume`.

### 2. Onboarding hard-locks if any permission is denied twice
- **File:** `app\src\main\java\com\remnant\dreams\ui\OnboardingActivity.kt:32-41, 99-114`
- **Defect:** Progress requires **all** requested permissions (`permissions.all { it.value }`); denial of either `RECORD_AUDIO` or `POST_NOTIFICATIONS` shows a toast (which misleadingly blames the microphone even when only notifications were denied) and stays on onboarding. After a second denial, Android permanently suppresses the prompt, and the launcher then returns "denied" instantly — no skip path, no deep-link to app settings.
- **Failure scenario:** A tester (or Google's pre-launch robo-tester) taps "Don't allow" on notifications twice. The app is now a brick: every tap of Start shows a toast and nothing else, forever. Guaranteed 1-star / dropped tester. Notifications should be optional, and a settings deep-link needed for the permanently-denied mic case.

### 3. Mid-capture process death loses the entire dream
- **File:** `app\src\main\java\com\remnant\dreams\alarm\DreamCaptureService.kt:43 (in-memory buffer), 284-322 (single save at end), 414-421 (serviceScope.cancel() in onDestroy)`
- **Defect:** Up to 10 minutes of transcription accumulates only in `accumulatedTranscription` and is written to Room once, at the very end. There is no incremental persistence. Additionally, `onDestroy` cancels `serviceScope` — if the system destroys the service while the `stopAndSave` insert coroutine is still queued, the Room insert is cancelled and the entry is never written.
- **Failure scenario:** User narrates a long dream; the app crashes, the OS kills the foreground service under memory pressure, or the user force-stops it. The transcript is gone; the half-written m4a has no finalised moov atom (unplayable) and no DB row references it (orphaned file). For an app whose whole promise is "your dream is captured", this is the top data-loss vector. Persist transcript segments to the DB (or a temp file) as each `onResults` arrives.

### 4. Destructive DB migration with no schema history — future wipe of every journal
- **File:** `app\src\main\java\com\remnant\dreams\data\DreamDatabase.kt:24` (`fallbackToDestructiveMigration()`), `:8` (`exportSchema = false`)
- **Defect:** Room is configured to drop-and-recreate on any version mismatch, and `exportSchema=false` means no schema JSON is being captured to author future migrations from.
- **Failure scenario:** Testers install v1.2.0 fresh (schema v2 since the initial commit, so no bite *today*). But the first v1.3 update that bumps the schema without a hand-written `Migration` silently deletes every dream of every user on upgrade — the exact catastrophe for a journal app, and unnoticeable in fresh-install testing. Remove `fallbackToDestructiveMigration` and turn on schema export before any schema ever changes. Flagged now because it must land before the next DB touch, not after.

### 5. Privacy-policy / Play data-safety mismatches (three concrete ones)
- **a. "No audio is sent to cloud services for transcription" is not guaranteed.** `DreamCaptureService.kt:144` only sets `EXTRA_PREFER_OFFLINE` (best-effort), and the companion-mode wake-check recognizer at `AlarmActivity.kt:195-201` sets **no** offline preference at all. On devices without an offline language pack, mic audio goes to Google's recogniser. The policy (`docs\PRIVACY_POLICY.md`, "Speech Recognition") states an absolute that the code doesn't enforce — a data-safety-form landmine if Google compares behaviour to declarations.
- **b. Cloud TTS with the user's name is on by default, policy says opt-in.** `OnboardingActivity.kt:150-176` sends the user's first name to `texttospeech.googleapis.com` automatically at onboarding because the default voice (`VoiceOption.kt:24`) is a cloud voice; the policy frames it as "If you select a Cloud voice". Data-safety answer to "does the app send any data off-device": **yes** — first name to Google TTS (default-on), plus possibly voice audio (5a), plus Play Billing.
- **c. Transcript retention is a no-op.** Settings offers 90-day/1-year transcript retention (`SettingsActivity.kt:252-266`), the privacy policy promises it, but `DreamDao.deleteOlderThan` (`DreamDao.kt:44`) is **never called anywhere** in the codebase. A user sets "90 days" and their transcripts are kept forever — a broken promise about personal data. Either wire it into a worker or remove the setting and the policy line before testers see it.

### 6. Release build logs journal content to logcat
- **File:** `app\src\main\java\com\remnant\dreams\alarm\DreamCaptureService.kt:237`
- **Defect:** `Log.d(TAG, "Transcription segment: ${text.take(50)}...")` — the first 50 characters of the user's dream go to the system log, and `app\proguard-rules.pro` has no `-assumenosideeffects` log-stripping, so this ships in the signed release.
- **Failure scenario:** Any bug report captured with `adb bugreport`, OEM log-upload tooling, or a co-located app with log access exposes fragments of private dream content. One-line fix: drop the content from the log or strip `Log.d/v` in release.

### 7. Google Cloud TTS API key ships inside the public AAB
- **Files:** `app\build.gradle.kts` (`buildConfigField "GOOGLE_CLOUD_TTS_KEY"`), `app\src\main\java\com\remnant\dreams\tts\ApiKeys.kt:6`; a real `AIzaSy...` key is present in `local.properties`
- **Defect:** The key is baked into `BuildConfig` and trivially extractable from any APK pulled off a test device; it's also sent as a URL query param (`CloudTtsGenerator.kt:26`).
- **Failure scenario:** Anyone decompiles the tester build and burns TTS quota against Sean's Google Cloud billing. Before upload, confirm in the Cloud console that this key is restricted to (1) the Android app by package name + signing SHA-1 (note: with Play App Signing, that must be **Google's app-signing cert**, not the upload key) and (2) the Text-to-Speech API only. Unrestricted, this is an open wallet.

---

## Should-fix (real bugs, lower blast radius)

### 8. AlarmReceiver's notification-permission fallback is dead code
- **File:** `app\src\main\java\com\remnant\dreams\alarm\AlarmReceiver.kt:40-45`
- **Defect:** `notify()` without `POST_NOTIFICATIONS` does not throw `SecurityException` — it silently drops the notification — so the `catch` never runs; and even if it did, `context.startActivity` from a receiver is blocked by background-activity-launch restrictions on Android 10+.
- **Failure scenario:** Tester revokes notifications after onboarding; alarm day arrives, receiver fires, nothing visible happens, and (per finding 1) the chain also dies. The fallback gives false confidence in review; remove it and handle the permission state explicitly.

### 9. Audio-cleanup transcript rewrite never matches (byte-verified)
- **Files:** `app\src\main\java\com\remnant\dreams\alarm\DreamCaptureService.kt:301` writes `"Couldn't catch the words -- tap to play the recording."` (ASCII `--`); `app\src\main\java\com\remnant\dreams\worker\AudioCleanupWorker.kt:28-29` matches the em-dash variant
- **Defect:** The strings differ at the byte level, so the "tap to play the recording" placeholder is never rewritten when the recording is deleted.
- **Failure scenario:** Tester with 7-day audio retention opens an old fragment: the entry text says "tap to play the recording" while the UI shows "audio expired" — looks like the app lost their dream. Compare by a shared constant.

### 10. Audio compression can destroy the only copy of a recording; one path retries forever
- **File:** `app\src\main\java\com\remnant\dreams\worker\AudioCompressionWorker.kt:67-69` and `:118-123`
- **Defect:** After `sourceFile.delete()` succeeds, the `tempFile.renameTo(sourceFile)` return value is ignored — if rename fails the original is already gone, yet the entry is marked `isCompressed=true` with `audioPath` pointing at a now-missing file. Separately, the "already small enough" branch returns `true` without producing `tempFile`, so the caller (`:65`) treats it as failure and re-attempts the re-encode every single day.
- **Failure scenario:** (a) Rename fails (disk full at the wrong moment) → user's dream audio is silently destroyed. Rename first, delete after. (b) A small file makes the daily worker burn battery re-decoding it forever.

### 11. No reaction to clock/timezone changes or exact-alarm revocation
- **File:** `app\src\main\java\com\remnant\dreams\alarm\AlarmScheduler.kt:24-27, 35-45`; manifest has no `TIME_SET`/`TIMEZONE_CHANGED` receiver
- **Defect:** `setAlarmClock` stores an absolute epoch computed from the wall clock at schedule time; nothing reschedules on `ACTION_TIME_CHANGED`/`ACTION_TIMEZONE_CHANGED`, and if `canScheduleExactAlarms()` is false (revocable on API 31-32; `USE_EXACT_ALARM` only auto-grants on 33+) `schedule()` silently returns with no retry when permission comes back.
- **Failure scenario:** Tester flies AEST→AWST, or a skewed device clock gets corrected: the alarm fires 2-3 hours off local time — or, post-revocation, never — with the UI still claiming it's set. Directly relevant to a wake-time-critical app.

### 12. Concurrent SpeechRecognizer + MediaRecorder likely yields silent audio files
- **File:** `app\src\main\java\com\remnant\dreams\alarm\DreamCaptureService.kt:72-83, 88-118`
- **Defect:** The code assumes MediaRecorder either works or throws. Under Android 10+ concurrent-capture policy, the losing client frequently gets **silence delivered**, not an exception — so a non-empty, fully-valid m4a of silence is saved as `audioPath`.
- **Failure scenario:** Tester's transcript reads "Couldn't catch the words — tap to play the recording", they tap play, and hear 40 seconds of nothing. This is exactly what the release checklist's "budget-device test" should probe; put it explicitly on that test script (verify recorded audio actually contains voice, not just that a file exists).

### 13. Onboarding voice-prompt caching is cancelled at birth
- **File:** `app\src\main\java\com\remnant\dreams\ui\OnboardingActivity.kt:161-175` vs `:132-133`
- **Defect:** `cacheVoicePrompt()` launches in `lifecycleScope`, then `completeOnboarding` immediately calls `finish()`, cancelling the scope. The first blocking TTS call may still complete (no suspension points inside), but the wake-check prompt and `promptCacheKey` assignment after the suspend boundary are cancelled.
- **Failure scenario:** Tester picks a companion voice, first companion-mode morning uses the generic robotic device TTS instead — the app's signature feature looks broken on day one. Move the work to a `CoroutineWorker` or application-scoped coroutine.

---

## Play-policy / packaging notes (no code change, Sean-side checks)

- **Signing secrets:** `remnant-release.jks` and `keystore.properties` (with plaintext passwords) sit in the repo folder but are **gitignored and untracked** (verified via `git ls-files`) — fine, but note the passwords are on disk next to the key; the checklist's "back up then keep out of the repo" advice stands. The stray `Remnant-v1.0.0/1.1.0` APKs at the root are also untracked.
- **Exact-alarm permissions:** both `SCHEDULE_EXACT_ALARM` and `USE_EXACT_ALARM` are declared (`AndroidManifest.xml:6-7`). `USE_EXACT_ALARM` is policy-restricted to apps whose *core function* is an alarm — Remnant qualifies, but that must be stated in the Play Console declaration form or the review bot will flag it. Same for `USE_FULL_SCREEN_INTENT` (Android 14 grants FSI by default only to alarm/calling apps) and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (`OnboardingActivity.kt:178-188` uses the direct-prompt intent, which is the scrutinised form — defensible for an alarm app, but have the justification ready).
- **Backup excludes the journal:** `backup_rules.xml`/`data_extraction_rules.xml` include only shared prefs — the Room DB and audio are *not* cloud-backed-up or device-transferred. Defensible privacy stance, but it means a lost/new phone = journal gone with prefs (streak, "onboarded") intact, which will look like corruption. Confirm it's a deliberate decision and consider saying so in the policy.
- **SDK levels:** compile/target 36, minSdk 29, versionCode 3 — meets the Play requirement cited in the checklist. Billing 8.3.0 is inside the minimum-billing-library window. Exported components are correct (only launcher activity and BOOT_COMPLETED receiver exported; no WebView anywhere).
- **Oddity:** `docs\PRIVACY_POLICY.md` says "Last updated: 7 May 2026" — before the project existed (23 Aug 2026). Likely clock-skew residue; fix the date before it goes on the public site.

---

## Verdict

The codebase is genuinely well-built for a v1.2.0 — clean architecture, offline-first billing with real unit tests, honest copy, no exported-component or WebView issues, and secrets correctly kept out of git. But it is **not ready to put in front of 12 testers as-is**. Three things will visibly burn testers in week one: the alarm chain silently dying after a single missed/ignored alarm (#1), the onboarding permission hard-lock (#2), and mid-capture data loss (#3) — for an alarm-driven journal, #1 alone would sink the 14-day test. Alongside those, the privacy-policy/data-safety mismatches (#5) and the shipped-key restriction check (#7) must be sorted before upload because they're compliance/financial exposure rather than mere bugs, and the destructive-migration config (#4) must be fixed before any future schema change even though it can't bite this particular build. Fix #1, #2, #3, #5, #6 and verify #7, rebuild the AAB, and it's a solid closed-test candidate; the should-fix list can ride along during the 14 days.
