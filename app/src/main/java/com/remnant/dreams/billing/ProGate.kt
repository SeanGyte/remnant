package com.remnant.dreams.billing

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.remnant.dreams.ui.SettingsActivity

/**
 * Small helper for Pro feature gates. The free tier (alarm, capture, transcription,
 * unlimited history) is never gated -- only search, export, and future Pro features
 * pass through here.
 */
object ProGate {

    /**
     * Runs [onUnlocked] if Pro is unlocked, otherwise shows an upgrade dialog that
     * routes to the Pro section in Settings. Returns true when the feature ran.
     */
    fun requirePro(activity: Activity, featureName: String, onUnlocked: () -> Unit): Boolean {
        if (BillingManager.isProUnlocked(activity)) {
            onUnlocked()
            return true
        }
        showUpgradeDialog(activity, featureName)
        return false
    }

    fun showUpgradeDialog(activity: Activity, featureName: String) {
        AlertDialog.Builder(activity)
            .setTitle("$featureName is part of Remnant Pro")
            .setMessage(
                "Remnant Pro is a one-time purchase -- no subscription. " +
                    "It unlocks search across all your dreams and journal export, " +
                    "plus every Pro feature we add later."
            )
            .setPositiveButton("See Pro") { _, _ ->
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
            }
            .setNegativeButton("Not now", null)
            .show()
    }
}
