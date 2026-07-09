package com.cristal.bristral.tristal.mistral

import android.app.Application

class LauncherApplication : Application() {

    companion object {
        lateinit var instance: LauncherApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
