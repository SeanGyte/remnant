# Remnant -- Release Checklist (Google Play)

**Last updated:** 26 Aug 2026 (v1.2.0, versionCode 3)

The AAB listed under DONE predates the 26 Aug privacy/code-review fixes. Rebuild it
(`gradlew bundleRelease`) before any upload.

Ordered path to launch. Three lanes: what is already done, what Moose can do on
request, and what only Sean can do (identity, money, accounts -- always his call).

---

## DONE

- [x] v1.2.0 code complete: Google Play Billing (one-time `remnant_pro` unlock),
      Pro-gated search and text export, free tier untouched
- [x] Entitlement is offline-first: cached locally, restored from Play on app start,
      revoked only by a successful store query (refund case). 30 unit tests passing.
- [x] `BuildConfig.SIMULATE_PRO` gives full Pro in debug builds for testing without
      a Play account
- [x] targetSdk/compileSdk 36 (Play requires API 36 for new apps from 31 Aug 2026)
      with edge-to-edge handling in every activity
- [x] ProGuard/R8 rules cover Room, Coroutines, WorkManager, SpeechRecognizer, and
      the billing library; release build minified and shrunk
- [x] Signed release AAB builds: `app/build/outputs/bundle/release/app-release.aab`
      (~3.2 MB), signed with the local upload keystore
- [x] Upload keystore exists: `remnant-release.jks` + `keystore.properties`
      (local only -- both gitignored, certificate valid to 2053)
- [x] Privacy policy written and hosted: https://seangyte.github.io/remnant/
- [x] Store listing draft: `docs/STORE_LISTING.md`

### Keystore notes (important, read once)

The existing `remnant-release.jks` is a proper upload key. Two rules:

1. **Never commit it or its passwords.** Already gitignored -- keep it that way.
2. **Back it up now** (before first upload): copy `remnant-release.jks` and the
   passwords from `keystore.properties` to your password manager and one offline
   location. With Play App Signing, Google holds the app signing key, so a lost
   upload key is recoverable through support -- but it is a painful, slow process.

Only if you ever need to regenerate a fresh upload key:

```
keytool -genkeypair -v -keystore remnant-release.jks -alias remnant `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -dname "CN=Sean Gyte, O=Remnant, L=Brisbane, ST=Queensland, C=AU"
```

Then update `keystore.properties` (storeFile / storePassword / keyAlias /
keyPassword) to match. If a key was already registered with Play, use
Play Console > Setup > App signing > "Request upload key reset" instead.

---

## BEFORE EVERY AAB UPLOAD (required)

Short list, checked every time, before the bundle goes anywhere.

- [ ] `gradlew test` and `gradlew assembleDebug` both pass
- [ ] **Onboarding renders fully on a small screen.** Install the build on a budget
      phone and/or run a small-screen emulator profile (e.g. a 5" 720p / 320dp-wide
      device) and confirm the whole onboarding screen is visible: name field, alarm
      time, voice hint and voice button, and the "Start Capturing Dreams" button --
      nothing clipped, cut off, or pushed under the navigation bar. The layout has no
      ScrollView, so anything that overflows is simply unreachable and the app cannot
      be set up at all. This is a required item on its own, not part of the
      budget-device test below.
- [ ] Privacy policy (`docs/PRIVACY_POLICY.md` and the hosted `docs/index.html`) still
      matches what the code does

## Schema changes

The Room database has no destructive-migration fallback and exports its schema, so a
version bump without a migration crashes every upgrading user instead of silently
wiping their journal. `DreamDatabaseVersionTest` pins the version and fails the suite
with these instructions if it changes.

Whenever the schema changes, all four steps, in order:

1. Write a Room `Migration` for the old -> new step. Never reintroduce
   `fallbackToDestructiveMigration()`.
2. Commit the newly exported schema JSON under
   `app/schemas/com.remnant.dreams.data.DreamDatabase/`.
3. Add a migration test that opens the old version, runs the migration, and checks the
   data survived.
4. Only then update `PINNED_VERSION` in `DreamDatabaseVersionTest` to match.

---

## SEAN-ONLY (in order)

These involve your identity, your card, or judgement calls that are yours to make.

1. **BLOCKING: budget-device transcription test.** Get a Galaxy A15 or equivalent
   (physical device, not emulator) and verify:
   - On-device SpeechRecognizer transcription quality on real morning speech --
     this is the existential risk flagged by all three founders
   - Offline language pack prompt/download behaviour
   - Play back a saved recording and confirm you can hear the voice. A file
     existing is not a pass: when SpeechRecognizer and MediaRecorder contend for
     the mic under the Android 10+ concurrent-capture policy, the losing client
     is usually handed silence rather than an error, so a valid, non-empty m4a of
     nothing is the expected failure mode. The scenario to catch is a transcript
     reading "Couldn't catch the words -- tap to play the recording" and 40
     seconds of silence behind it
   - Full alarm -> wake -> capture -> journal flow, plus companion mode
   - **The alarm audibly fires with notifications denied.** Deny notifications during
     onboarding (or revoke them afterwards), set an alarm a couple of minutes out,
     lock the phone, and confirm it rings and vibrates on the alarm stream with no
     notification showing. Then open Remnant and confirm it drops straight into the
     alarm/capture screen. This is `AlarmRingtoneService`, the only wake path a user
     who refused notifications has
   - Edge-to-edge layouts (new in v1.2.0): all screens on an Android 15/16 device --
     nothing under the status bar, nav bar, or camera cutout
   - Search, export, and the upgrade dialog (install a release build, since debug
     simulates Pro)
   Do not launch publicly until this passes. Kill signal: if transcription is
   garbage on a budget device, we stop and evaluate whisper.cpp before launch.

2. **Create the Google Play developer account** -- US$25 one-time, your identity,
   your card: https://play.google.com/console/signup
   Personal accounts require identity verification (driver's licence/passport) and,
   since 2023, new personal accounts must run a closed test with at least 12 testers
   for 14 days before production access -- plan for this in the timeline.

3. **Back up the upload keystore** (see keystore notes above).

4. **Create the app in Play Console**: All apps > Create app > "Remnant: Voice
   Dream Journal", App (not game), Free. Verify the name is available on Play while
   you are there (open question #1 in PROJECT.md).

5. **Upload the AAB to Internal testing** (Release > Testing > Internal testing).
   Work through "BEFORE EVERY AAB UPLOAD" above first -- the small-screen onboarding
   check in particular.
   This first upload is what registers Play App Signing and -- because the billing
   library adds the `com.android.vending.BILLING` permission -- unlocks in-app
   product creation.

6. **Create the in-app product** (Monetise > Products > In-app products):
   - Product ID: `remnant_pro` (must match `BillingManager.PRO_PRODUCT_ID` exactly;
     cannot ever be changed)
   - Name: Remnant Pro
   - Description: One-time unlock: search across your dreams and journal export.
     Future Pro features (pattern tracking, Wake Word) included.
   - Price: A$19.99 (launch price; raise to A$29.99 after the first 1,000 buyers --
     prices can change later, the product ID cannot)
   - Set status Active

7. **Test the real purchase flow**: add your Google account under Settings >
   Licence testing, install from the internal testing link, then verify purchase,
   "Restore purchase", uninstall/reinstall restore, and (optionally) refund
   revocation. Licence testers are not charged.

8. **Complete the store presence forms** (Policy > App content):
   - Privacy policy URL: https://seangyte.github.io/remnant/
   - Data safety: no data collected/shared EXCEPT declare the optional Google
     Cloud TTS call (first name in prompt text) exactly as the privacy policy does.
     Microphone audio is processed on-device and not collected.
   - Content rating questionnaire (expect Everyone), target audience (18+ or 13+ --
     your call; dreams content is personal but not restricted), ads declaration (none)
   - In-app purchases declaration (A$19.99 one-time)
   - **Foreground service types.** Two are declared: `microphone` (DreamCaptureService,
     recording the dream) and `mediaPlayback` (AlarmRingtoneService, playing the alarm
     tone when notifications are unavailable). Both need a short justification in the
     declaration form. `mediaPlayback` is the honest type -- the service does nothing
     but play the device alarm sound on the alarm stream -- but if Play pushes back,
     the alternative is `specialUse` with the subtype `alarm` and a written
     justification, which is a slower review path

9. **Store listing** (from `docs/STORE_LISTING.md`) + assets: app icon 512x512,
   feature graphic 1024x500, at least 2 phone screenshots (take these during the
   device test; Moose can polish/frame them).

10. **Decide the release path and hit the button.** Given the 12-tester/14-day
    requirement for new personal accounts, realistic order: internal testing (you) ->
    closed testing (12+ testers, 14 days -- lucid dreaming friends/Reddit contacts) ->
    production. Each promotion is your approval.

11. **Post-launch guardrails**: restrict the Google Cloud TTS API key in Google
    Cloud Console (Android app restriction to `com.remnant.dreams` + TTS API only +
    quota cap) -- the key ships inside the APK and is extractable, restriction is
    the mitigation. Watch the first crash reports and reviews in Play Console.

## MOOSE CAN DO (on request)

- Screenshot framing / feature graphic / 512px icon from the existing launcher art
- Draft data safety form answers line-by-line for Sean to paste
- Any fixes that fall out of the budget-device test (transcription tuning,
  edge-to-edge layout fixes, whisper.cpp spike if SpeechRecognizer fails)
- PDF export, pattern tracking, Wake Word (post-launch roadmap)
- Bump version / rebuild the AAB whenever code changes (`gradlew bundleRelease`)

## NOT NEEDED FOR LAUNCH (explicitly deferred)

- PDF export (text export shipped; PDF is roadmap)
- Pattern tracking, Wake Word (Pro roadmap features, marketed as "when built")
- iOS, cloud backup, AI analysis
