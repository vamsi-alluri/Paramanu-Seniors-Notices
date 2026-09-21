package org.paramanuseniorshealth.notices.fcm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the connection is, for [FetchPolicy] to judge.
 *
 * Nothing here decides anything. Every branch that matters lives in [FetchPolicy], which has no
 * Android imports and is unit-tested; this file exists only because the framework will not answer
 * these three questions on the JVM. If a policy question appears here, it is in the wrong file.
 */
object NetworkStatus {

    private const val TAG = "NetworkStatus"

    /** Absent or unknown network reads as metered: the cautious answer costs one tap. */
    fun isMetered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return manager.isActiveNetworkMetered
    }

    /**
     * `NET_CAPABILITY_NOT_ROAMING` arrived in API 28 and this app's minSdk is 26, so 26 and 27 use
     * the telephony answer instead. Neither needs a permission.
     *
     * Unknown reads as not roaming. Roaming stops every automatic fetch, so guessing "yes" would
     * leave a user with a permanently blank card for a reason they cannot see and the app cannot
     * explain.
     */
    fun isRoaming(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val manager = context.getSystemService(ConnectivityManager::class.java)
                ?: return@runCatching false
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
                ?: return@runCatching false
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(TelephonyManager::class.java)?.isNetworkRoaming ?: false
        }
    }.onFailure { Log.w(TAG, "Could not read roaming state", it) }.getOrDefault(false)

    /**
     * The size of [url] without downloading it, via HEAD.
     *
     * Returns null when the server does not answer HEAD, sends no `Content-Length`, or is
     * unreachable. [FetchPolicy] treats null as large, so an uncooperative server costs the user a
     * tap rather than an unannounced 5.6MB.
     *
     * Once the publishing pipeline ships, `pdfBytes` arrives in the payload and this is not called
     * for circulars at all -- which is the point of that field: a metered phone then skips the
     * download without making any request whatsoever.
     */
    fun remoteSize(url: String): Long? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 5_000
            readTimeout = 5_000
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.contentLengthLong.takeIf { it >= 0 }
        } finally {
            connection.disconnect()
        }
    }.onFailure { Log.i(TAG, "No size for $url: ${it.message}") }.getOrNull()
}
