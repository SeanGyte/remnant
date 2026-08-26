# Remnant -- Backlog

Ideas that have been thought about and deliberately not built yet. Nothing here is
scheduled work. Newest at the top.

## Future options

### Notification-independent wake path

Today the alarm reaches the user as a notification, so a user who declines
POST_NOTIFICATIONS has no alarm at all -- onboarding lets them through, but the app
cannot do the one thing it exists for. A wake path that does not depend on the
notification permission (the exact alarm still fires the receiver either way) could
close that gap. Not current work: the point of the app is waking the user, so the
realistic answer to "notifications off" is to ask for them, and the fallback is an
option for someday rather than a feature to design around.
