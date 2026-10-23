package com.anesidora

import android.app.Application

class AnesidoraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Radio.initialize(this)
    }
}
