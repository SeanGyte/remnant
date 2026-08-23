# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-dontwarn androidx.room.paging.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# SpeechRecognizer / RecognitionListener
-keep class android.speech.** { *; }

# Keep data classes used by Room
-keep class com.remnant.dreams.data.DreamEntry { *; }

# WorkManager
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Keep enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Google Play Billing
# The billing library ships its own consumer rules; these are a defensive belt for
# the AIDL-generated Play Store service interface it depends on.
-keep class com.android.vending.billing.** { *; }
-dontwarn com.android.billingclient.**
