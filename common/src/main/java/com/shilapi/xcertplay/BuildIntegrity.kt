package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import java.security.MessageDigest

/**
 * Detects whether this APK still carries the official signing certificate.
 *
 * Why: anyone who wants to resell this build under another name has to re-sign it, because the
 * private key behind the official certificate is not distributed. Re-signing changes the
 * certificate, so the digest below stops matching and the build announces itself as unofficial.
 * That is a deterrent and a way for owners to verify a copy, not a lock: a determined person can
 * patch the constant. Keep `isMinifyEnabled` on so the comparison is not trivial to locate.
 *
 * Debug builds are signed with the shared Android debug key, so they are treated as official —
 * otherwise the warning would be on screen during every development run. The debuggable flag is
 * read at runtime instead of using BuildConfig because the `common` library module does not
 * generate one.
 */
object BuildIntegrity {
    private const val TAG = "DiPlay-BuildIntegrity"

    /**
     * SHA-256 of the official signing certificate (`02-签名/my_keywsz1.jks`, alias `key0`,
     * CN=qingshen). Matches `keytool -list -v` output for that entry.
     */
    private const val OFFICIAL_CERT_SHA256 =
        "3a13d8ff79a53beb1983a6826b3d801ca1a1dc9df32b4e492e4db2f62bcb0da1"

    @Volatile
    private var cached: Boolean? = null

    fun isOfficial(context: Context): Boolean {
        cached?.let { return it }
        val debuggable =
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val result = if (debuggable) true else evaluate(context)
        cached = result
        return result
    }

    private fun evaluate(context: Context): Boolean {
        val signatures = try {
            @Suppress("DEPRECATION")
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures
        } catch (error: Throwable) {
            Log.w(TAG, "could not read the package signature", error)
            return false
        }
        if (signatures.isNullOrEmpty()) return false
        return signatures.map { fingerprint(it.toByteArray()) }.any { it == OFFICIAL_CERT_SHA256 }
    }

    private fun fingerprint(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificate)
            .joinToString("") { byte -> "%02x".format(byte) }
}
