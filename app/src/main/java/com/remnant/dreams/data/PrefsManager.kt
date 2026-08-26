package com.remnant.dreams.data

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("remnant_prefs", Context.MODE_PRIVATE)

    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    var alarmHour: Int
        get() = prefs.getInt(KEY_ALARM_HOUR, 7)
        set(value) = prefs.edit().putInt(KEY_ALARM_HOUR, value).apply()

    var alarmMinute: Int
        get() = prefs.getInt(KEY_ALARM_MINUTE, 0)
        set(value) = prefs.edit().putInt(KEY_ALARM_MINUTE, value).apply()

    var alarmEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALARM_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ALARM_ENABLED, value).apply()

    var onboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, value).apply()

    var totalCaptures: Int
        get() = prefs.getInt(KEY_TOTAL_CAPTURES, 0)
        set(value) = prefs.edit().putInt(KEY_TOTAL_CAPTURES, value).apply()

    var currentStreak: Int
        get() = prefs.getInt(KEY_CURRENT_STREAK, 0)
        set(value) = prefs.edit().putInt(KEY_CURRENT_STREAK, value).apply()

    var lastCaptureDate: String
        get() = prefs.getString(KEY_LAST_CAPTURE_DATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_CAPTURE_DATE, value).apply()

    var hasAskedForReview: Boolean
        get() = prefs.getBoolean(KEY_ASKED_REVIEW, false)
        set(value) = prefs.edit().putBoolean(KEY_ASKED_REVIEW, value).apply()

    var isPro: Boolean
        get() = prefs.getBoolean(KEY_IS_PRO, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_PRO, value).apply()

    var selectedVoiceId: String
        get() = prefs.getString(KEY_VOICE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_VOICE_ID, value).apply()

    var promptCacheKey: String
        get() = prefs.getString(KEY_PROMPT_CACHE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROMPT_CACHE, value).apply()

    /**
     * Whether the user has opted in to the Cloud voice, which sends their first name to
     * Google's text-to-speech service. Off unless they ask for it.
     */
    var cloudVoiceOptIn: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_VOICE_OPT_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_CLOUD_VOICE_OPT_IN, value).apply()

    /** Whether the next alarm should fire in companion mode (another alarm detected nearby). */
    var companionMode: Boolean
        get() = prefs.getBoolean(KEY_COMPANION_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_COMPANION_MODE, value).apply()

    /** Days to keep audio recordings. 7, 30, 90, or 0 (forever). Default 30. */
    var audioRetentionDays: Int
        get() = prefs.getInt(KEY_AUDIO_RETENTION, 30)
        set(value) = prefs.edit().putInt(KEY_AUDIO_RETENTION, value).apply()

    /** Days to keep transcripts. 90, 365, or 0 (forever). Default 0 (forever). */
    var transcriptRetentionDays: Int
        get() = prefs.getInt(KEY_TRANSCRIPT_RETENTION, 0)
        set(value) = prefs.edit().putInt(KEY_TRANSCRIPT_RETENTION, value).apply()

    companion object {
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_ALARM_HOUR = "alarm_hour"
        private const val KEY_ALARM_MINUTE = "alarm_minute"
        private const val KEY_ALARM_ENABLED = "alarm_enabled"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_TOTAL_CAPTURES = "total_captures"
        private const val KEY_CURRENT_STREAK = "current_streak"
        private const val KEY_LAST_CAPTURE_DATE = "last_capture_date"
        private const val KEY_ASKED_REVIEW = "asked_review"
        private const val KEY_IS_PRO = "is_pro"
        private const val KEY_VOICE_ID = "voice_id"
        private const val KEY_PROMPT_CACHE = "prompt_cache_key"
        private const val KEY_CLOUD_VOICE_OPT_IN = "cloud_voice_opt_in"
        private const val KEY_COMPANION_MODE = "companion_mode"
        private const val KEY_AUDIO_RETENTION = "audio_retention_days"
        private const val KEY_TRANSCRIPT_RETENTION = "transcript_retention_days"
    }
}
