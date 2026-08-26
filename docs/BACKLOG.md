# Remnant -- Backlog

Ideas that have been thought about and deliberately not built yet. Nothing here is
scheduled work. Newest at the top.

## Future options

### A visual alarm without notifications (partly done)

The sound half is built: `AlarmRingtoneService` rings and vibrates from the exact-alarm
broadcast when notifications are unavailable, and opening the app inside the capture
window drops the user into the alarm screen. What is still missing is the screen itself
at alarm time -- a full-screen intent needs a notification to ride on, so a user with
notifications off is woken by sound alone and sees nothing until they open Remnant
themselves. Not current work: the honest answer to "notifications off" is still to ask
for them, and the fallback exists so that refusing does not silently break the alarm.
