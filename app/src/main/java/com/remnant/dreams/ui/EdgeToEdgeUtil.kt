package com.remnant.dreams.ui

import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Edge-to-edge support, required since targetSdk 35+ enforces it on Android 15+
 * (and Google Play requires targetSdk 36 for new apps from 31 Aug 2026).
 *
 * Remnant is permanently dark, so system bars are forced to the dark style
 * (light icons) regardless of the device theme -- SystemBarStyle.auto would render
 * dark icons over our dark background when the device is in light mode.
 *
 * Call from onCreate BEFORE setContentView, then pad the root view with
 * [applySystemBarInsets] AFTER setContentView.
 */
object EdgeToEdgeUtil {

    fun enable(activity: ComponentActivity) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
    }

    /**
     * Pads the root view by the system bar + display cutout insets so content never
     * sits under the status bar, navigation bar, or a camera cutout. The root's own
     * background paints the inset areas, keeping the night-sky look full-bleed.
     */
    fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}
