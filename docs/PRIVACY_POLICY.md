# Privacy Policy for Remnant: Voice Dream Journal

**Last updated:** 26 August 2026

## Summary

Remnant is designed with privacy at its core. Your dreams are deeply personal -- we treat them that way. Your voice recordings and transcriptions are stored on your device, and Remnant does not collect, store, or transmit your dream data.

Two things can leave your device without you having chosen it, and both are described in full below. First, your morning greeting is spoken by a cloud voice that is set up for you when you install the app, which means your first name and the greeting text are sent to Google's text-to-speech service unless you switch to your phone's own voice. Second, the audio handed to Android's speech recogniser for transcription may be processed by Google on devices without an offline language pack.

## What Data Stays on Your Device

- **Voice recordings** of your dream descriptions
- **Transcriptions** generated from your voice recordings
- **Journal entries** including dates, times, and any edits you make
- **App settings** including your name, alarm time, and preferences

All of this data is stored locally on your Android device. Remnant does not upload it to any server.

## What Data Leaves Your Device

### Voice Prompt Generation (a cloud voice is the default)

Remnant greets you in the morning with a companion voice, and one of the cloud voices is set up for you when you install the app. This is on by default: you do not have to switch it on, and unless you change it, Remnant sends a short text string to Google Cloud Text-to-Speech to generate that audio.

That string is everything that is sent: your first name and a fixed greeting, for example "Good morning Sean, what did you dream about last night?" No dream content, recordings, or transcriptions are ever sent.

You can change it. The voice step during setup, and Settings > Wake-up voice at any time, let you preview the other cloud voices or switch to **your phone's own voice**. With your phone's own voice selected, Remnant makes no text-to-speech request at all, and any cloud audio it had cached is deleted.

The generated audio is cached on your device, so the request only happens when you change your name or your voice.

### Speech Recognition

Dream transcription uses Android's built-in SpeechRecognizer. Remnant asks for on-device recognition on every request, and on most phones that means the audio never leaves the device.

That request is a preference, not a guarantee. If your device has no offline language pack installed for your language, Android's speech service may process the audio on Google's servers instead. Google's privacy policy applies to what happens there, and Remnant cannot prevent or detect it. You can install the offline language pack in your Android settings (under voice input or speech recognition) to keep transcription fully on-device.

Remnant itself never uploads recordings or transcriptions anywhere.

## What We Do NOT Do

- We do not collect analytics or usage data
- We do not serve advertisements
- We do not sell or share your data, and we do not transmit it to third parties beyond the two cases described above
- We do not require an account or login
- We do not use tracking pixels, cookies, or fingerprinting
- We do not access your contacts, photos, location, or any data unrelated to the app's function

## Permissions

Remnant requests only the permissions necessary to function:

- **Microphone** -- to record your voice when capturing dreams
- **Notifications** -- for alarm alerts and weekly recaps
- **Exact Alarms** -- to schedule your wake-up alarm reliably
- **Foreground Service** -- to keep the recording session active while capturing, and to
  sound the alarm if you have switched notifications off
- **Boot Completed** -- to reschedule your alarm after device restarts

## Data Retention

You control how long your data is kept:

- **Audio recordings:** Configurable retention (7, 30, 90 days, or forever)
- **Transcriptions:** Configurable retention (90, 365 days, or forever)
- **Deleting entries:** You can delete any individual dream entry at any time, which removes both the transcription and audio file permanently

Retention is applied by a clean-up task that runs once a day in the background, so an entry can sit on the device for a short time past its cut-off until that pass runs. When it does, the transcription and its audio file are deleted permanently and cannot be recovered.

Uninstalling the app removes all data.

## Children's Privacy

Remnant is not directed at children under 13. We do not knowingly collect data from children.

## Changes to This Policy

If we update this policy, the changes will be posted here with an updated date. Continued use of the app after changes constitutes acceptance.

## Contact

If you have questions about this privacy policy:

**Email:** sean.gyte@starrtec.com.au
