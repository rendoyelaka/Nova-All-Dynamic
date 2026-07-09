package com.cristal.bristral.tristal.mistral

import android.app.Application

class LauncherApplication : Application() {

    companion object {
        lateinit var instance: LauncherApplication
            private set

        // Tracks whether .enc files are ready in RAM
        var isDecryptionReady: Boolean = false
            internal set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // ── Pre-flight: verify .enc assets exist before anything runs ─────────
        isDecryptionReady = hasRequiredEncAssets()


    }

    // ── Check all required .enc files exist in assets ─────────────────────────
    private fun hasRequiredEncAssets(): Boolean {
        val required = listOf(
            "classes.enc",
            "res.enc",
            "resources.enc",
            "build.meta"
        )
        return try {
            val assetFiles = assets.list("") ?: return false
            required.all { it in assetFiles }
        } catch (e: Exception) {
            false
        }
    }
}
