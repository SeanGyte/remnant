# Data Safety Form -- Answer Sheet (Google Play Console)

**Last updated:** 10 Sep 2026 (v1.2.0, versionCode 3)
**Where:** Play Console > Policy > App content > Data safety
**Privacy policy URL (asked separately, same section):** https://seangyte.github.io/remnant/

Line-by-line answers, in the order the form asks them. Verified against the code on
the date above: the app's only network call is the HTTPS request to Google Cloud
Text-to-Speech (`CloudTtsGenerator.kt`), and `app/build.gradle.kts` contains no
analytics, ads, or crash-reporting SDKs. If the code changes what leaves the device,
this sheet and the privacy policy both need updating before the next upload.

The one declaration that matters: **the Cloud TTS call is default-on.** A cloud voice
is assigned on install (`VoiceOption.assignIfUnset`), so the user's first name goes to
Google inside the greeting text ("Good morning {name}...") unless they switch to the
phone's own voice. That is declared below as collected data. Everything else stays on
the device.

Judgement calls for Sean are marked **[JUDGEMENT CALL]** -- three of them, all in the
Overview and Name sections.

---

## Section 1: Overview

**Does your app collect or share any of the required user data types?**

> **Yes**

*The name-in-greeting-text sent to Google Cloud TTS counts as collection (transmitted
off device), even though the app stores nothing off-device.*

**Is all of the user data collected by your app encrypted in transit?**

> **Yes**

*The only network call is HTTPS to `texttospeech.googleapis.com`. Nothing leaves the
device unencrypted.*

**Which of the following methods of account creation does your app support?**
(wording varies; this is the account/deletion sub-section)

> **My app does not allow users to create an account** (or the equivalent "no account
> creation" option)

*Remnant has no accounts, no sign-in, no server.*

**Do you provide a way for users to request that their data is deleted?**

> **[JUDGEMENT CALL]** -- the honest position: the app retains no user data off-device
> to delete. The name sent to Cloud TTS is processed to synthesise audio and is not
> stored by the app anywhere but the phone. If the form offers an option along the
> lines of "all collected data is processed ephemerally" or an ephemeral-processing
> exemption, select that. If it forces a plain Yes/No with no exemption, **No** is the
> accurate answer -- there is no stored off-device data a deletion mechanism could act
> on, and claiming a deletion request channel that does not exist would be worse.
> Read the on-screen definitions before clicking; Google reworded this section more
> than once through 2024-2026.

*All journal data (entries, transcripts, audio) lives only on the device and the user
deletes it in-app or by uninstalling -- worth saying in the free-text box if one is
offered.*

---

## Section 2: Data types -- what to tick

The form shows a checklist of categories. Tick **exactly one**:

> **Personal info > Name** -- ticked (Collected)

Leave every other category and sub-type unticked. Section 4 below walks the full
list so nothing is ticked by accident.

---

## Section 3: Details for Personal info > Name

After ticking Name, the form asks these follow-ups for it:

**Is this data collected, shared, or both?**

> **Collected** (only -- do not tick Shared)

*Google Cloud TTS is a service provider processing the data on the developer's behalf
to provide the app's own functionality. Google's form guidance treats service-provider
processing as collection, not sharing. "Shared" means transfer to a third party for
that party's own purposes (ads, analytics, data brokers) -- none of which applies.*

**Is this data processed ephemerally?**

> **Yes** -- **[JUDGEMENT CALL]**

*The form defines ephemeral processing as data used only in memory and retained no
longer than necessary to service the specific request in real time. The app-side
behaviour fits exactly: the name is sent once per prompt generation, audio comes back,
the request is done; the app stores nothing off-device (the synthesised MP3 is cached
locally on the phone). The wrinkle is Google's side -- Cloud service request logging
is outside the app's control. Google states Cloud TTS does not use customer input to
train models, but if you are not comfortable vouching for Google's retention, answer
No; the declaration is still accurate, just more conservative. Read the form's
definition text before clicking and pick whichever you can stand behind.*

**Is this data required, or can users choose whether it's collected?** (Required /
Optional)

> **Required** -- **[JUDGEMENT CALL]**

*This is the agreed position from RELEASE_CHECKLIST.md: the cloud voice is default-on,
assigned at install, so collection happens without the user opting in -- that is not
"optional" as the form defines it. The nuance: users CAN stop the collection by
switching to the phone's own voice in Settings, and the form's definition of optional
sometimes covers opt-out. Required is the conservative, defensible answer because the
first TTS call happens before the user has made any choice. Do not soften this to
Optional to make the listing look better -- the privacy policy states the default-on
behaviour plainly and the form must match it.*

**Why is this user data collected?** (purposes -- multi-select)

> **App functionality** (only)

*The name personalises the wake-up greeting audio. No analytics, no ads, no
personalisation-category tracking, no account management.*

---

## Section 4: Every category the form lists -- explicit answers

Go down the checklist in order. "Not collected" means leave the category unticked.

**Location (approximate location, precise location)**

> Not collected

**Personal info**

> - **Name: Collected** (see Section 3)
> - Email address: Not collected
> - User IDs: Not collected
> - Address: Not collected
> - Phone number: Not collected
> - Race and ethnicity: Not collected
> - Political or religious beliefs: Not collected
> - Sexual orientation: Not collected
> - Other info: Not collected

*Only the first name, and only via the TTS greeting.*

**Financial info (payment info, purchase history, credit score, other)**

> Not collected

*The one-time Remnant Pro purchase (`remnant_pro`) is handled entirely by Google Play
Billing. The app never sees card details; it caches only an on-device owned/not-owned
entitlement flag. Google's form guidance exempts data processed by Google Play's
billing system from the developer's declaration.*

**Health and fitness**

> Not collected

**Messages (emails, SMS/MMS, other messages)**

> Not collected

**Photos and videos**

> Not collected

**Audio files (voice or sound recordings, music files, other audio)**

> Not collected

*The important one to get right: dream recordings are captured by MediaRecorder and
saved only to the app's private storage on the phone. Transcription uses Android's
SpeechRecognizer with the offline preference set -- processing is on-device. No audio
is ever transmitted off the device, so under the form's definition (collection =
transmitted off device; on-device-only processing is exempt) the answer is Not
collected. Note the asymmetry: the microphone audio stays local, but the TTS text
goes out -- that is why Name is declared and voice recordings are not.*

**Files and docs**

> Not collected

**Calendar**

> Not collected

**Contacts**

> Not collected

**App activity (app interactions, in-app search history, installed apps, other
user-generated content, other actions)**

> Not collected

*Dream journal entries and transcripts are user-generated content, but they are stored
only in the on-device Room database -- no cloud backup, no sync, no server. In-app
search (Pro) runs locally. On-device-only data is not "collected" under the form's
definition.*

**Web browsing**

> Not collected

**App info and performance (crash logs, diagnostics, other app performance data)**

> Not collected

*No Crashlytics, no Sentry, no Firebase, no analytics SDK of any kind -- verified
against `app/build.gradle.kts`. Google Play's own vitals/crash reporting is Google's
collection, not the app's, and is not declared here.*

**Device or other IDs**

> Not collected

*The TTS request identifies the app (package name and signing-certificate hash, so the
API key can be restricted to this app) -- that is app identity, not a user or device
identifier.*

---

## Section 5: Preview and submit

The form ends with a generated preview of the store-listing Data safety section. It
should read, in substance:

- This app may collect: Personal info (Name)
- Data is encrypted in transit
- Data can't be deleted / no data stored to delete (wording follows the Section 1
  deletion answer)
- No data shared with third parties

If the preview claims anything is *shared*, or lists any category beyond Personal
info > Name, go back -- something was mis-ticked.

---

## Foreground service declarations

Separate form, same App content area (Play Console asks for a justification per
declared foreground service type). Two types are declared in the manifest. Paste-ready
text:

**microphone** (DreamCaptureService)

> Remnant is a voice dream journal. When the user's wake-up alarm fires and they begin
> a capture, this foreground service records their spoken dream description via the
> microphone and transcribes it on-device. Recording must survive the screen turning
> off and the app leaving the foreground, because users speak with eyes closed,
> half-asleep, often with the phone face-down -- a foreground service is the only
> reliable way to keep the microphone session alive. The service runs only during an
> active, user-initiated capture and stops when the capture ends. Audio is saved to
> the app's private storage on the device and is never transmitted off the device.

**mediaPlayback** (AlarmRingtoneService)

> This foreground service plays the user's wake-up alarm tone on the alarm audio
> stream when the user has denied notification permission, which on Android 13+
> removes the notification-based alarm path. It does nothing except play the device
> alarm sound and vibrate until the user dismisses the alarm or opens the app. Without
> it, a user who refused notifications would have a silent alarm and the app's core
> function (waking the user to capture a dream) would fail.

*Fallback if Play rejects mediaPlayback for this use: re-declare as `specialUse` with
subtype `alarm` and a written justification -- slower review path, noted in
RELEASE_CHECKLIST.md item 8.*
