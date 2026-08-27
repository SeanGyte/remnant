package com.remnant.dreams.tts

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.remnant.dreams.BuildConfig
import java.net.HttpURLConnection
import java.security.MessageDigest

/**
 * The app-identity headers Google's API-key servers use to enforce an "Android apps"
 * key restriction: the calling package name and the SHA-1 of the certificate the APK
 * was signed with. A plain REST call carries neither, so a key restricted that way
 * would be rejected -- these headers are what make the restriction usable.
 *
 * Nothing here is a security boundary on its own. The headers are trivially forgeable
 * by anyone holding the key; the point is that the console-side restriction can then be
 * switched on, which stops a scraped key being used from someone else's app.
 *
 * The digest is read once and cached, and every failure path returns null: a call with
 * no headers behaves exactly as it did before this existed, which is the right way to
 * be wrong on the text-to-speech path.
 */
object GoogleApiIdentity {

    private const val TAG = "GoogleApiIdentity"

    private const val HEADER_PACKAGE = "X-Android-Package"
    private const val HEADER_CERT = "X-Android-Cert"

    /**
     * The application id of the running build, so debug's `.debug` suffix is declared
     * honestly and the console can be given both entries.
     */
    val packageName: String get() = BuildConfig.APPLICATION_ID

    @Volatile
    private var cachedCert: String? = null

    @Volatile
    private var certRead = false

    /**
     * Stamp [connection] with the identity headers, or leave it untouched if the
     * certificate could not be read. Both headers go on together -- one without the
     * other tells Google nothing it can check.
     */
    fun applyTo(connection: HttpURLConnection, context: Context) {
        val cert = signingCertSha1(context) ?: return
        connection.setRequestProperty(HEADER_PACKAGE, packageName)
        connection.setRequestProperty(HEADER_CERT, cert)
    }

    /**
     * Uppercase hex SHA-1 of this install's signing certificate, or null if it could
     * not be read. Computed on first use and cached for the process, including a failed
     * lookup -- a broken read is not going to start working later in the same process.
     */
    fun signingCertSha1(context: Context): String? {
        if (certRead) return cachedCert
        synchronized(this) {
            if (!certRead) {
                cachedCert = readSigningCertSha1(context)
                certRead = true
            }
        }
        return cachedCert
    }

    private fun readSigningCertSha1(context: Context): String? = try {
        val packageManager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                )
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        }
        // apkContentsSigners is the certificate the installed APK is actually signed
        // with, which is the one Google checks -- unlike the rotation history, it has no
        // ambiguity about which entry is current.
        val signature = info.signingInfo?.apkContentsSigners?.firstOrNull()
        if (signature == null) {
            Log.w(TAG, "No signing certificate found; sending without identity headers")
            null
        } else {
            toHex(MessageDigest.getInstance("SHA-1").digest(signature.toByteArray()))
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the signing certificate: ${e.message}")
        null
    }

    /**
     * Uppercase hex with no separators -- the form Google's key restriction expects,
     * as opposed to the colon-separated form keytool and the Play Console print.
     */
    fun toHex(digest: ByteArray): String {
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4])
            out.append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}
