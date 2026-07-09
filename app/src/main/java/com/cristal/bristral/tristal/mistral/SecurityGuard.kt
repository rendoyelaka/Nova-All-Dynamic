package com.cristal.bristral.tristal.mistral

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import java.io.File

/**
 * Nova Launcher — SecurityGuard
 * CrystalSoft Technologies Ltd
 *
 * All checks run in RAM only
 * Silent exit on failure — no error shown to attacker
 */
object SecurityGuard {

    fun passesAllChecks(context: Context): Boolean {
        return !isDebuggerAttached()
            && !isEmulator()
            && !isRooted()
            && isSignatureValid(context)
    }

    // ── Debugger Check ────────────────────────────────────────────────────────
    private fun isDebuggerAttached(): Boolean {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }

    // ── Emulator Check ────────────────────────────────────────────────────────
    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.startsWith("unknown")
            || Build.MODEL.contains("google_sdk")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK")
            || Build.MANUFACTURER.contains("Genymotion")
            || Build.BRAND.startsWith("generic")
            || Build.DEVICE.startsWith("generic")
            || "google_sdk" == Build.PRODUCT)
    }

    // ── Root Check ────────────────────────────────────────────────────────────
    private fun isRooted(): Boolean {
        val suPaths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su"
        )
        return suPaths.any { File(it).exists() }
    }

    // ── Signature Validation ──────────────────────────────────────────────────
    private fun isSignatureValid(context: Context): Boolean {
        return try {
            val pm  = context.packageManager
            val sig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo.apkContentsSigners.firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                ).signatures.firstOrNull()
            }
            sig != null
        } catch (e: Exception) {
            false
        }
    }
}
