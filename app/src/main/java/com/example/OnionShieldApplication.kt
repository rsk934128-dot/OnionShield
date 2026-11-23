package com.example

import android.app.Application

class OnionShieldApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Firebase & Firestore initialization is deferred until MainActivity.onCreate()
        // after configureAppCheck(this, intent) so App Check providers are always
        // installed before any Firebase SDK touches App Check.
    }
}
