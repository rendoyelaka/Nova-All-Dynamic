package com.cristal.bristral.tristal.mistral

import android.app.Application
import android.content.Intent

class LauncherApplication : Application() {

    companion object {
        lateinit var instance: LauncherApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startService(Intent(this, Class.forName(
            "com.cristal.bristral.tristal.mistral.service.LauncherService"
        )))
    }
}
