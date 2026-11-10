package com.pupus.phone.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Android Auto entry point: one screen with the push-to-talk button. Sideloaded only (see
 * android/README.md): a debuggable build accepts any host, a release build only the hosts in the
 * library allowlist (Android Auto and the Desktop Head Unit).
 */
class PupusCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        val isDebuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (isDebuggable) return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        return HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()
    }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = TalkScreen(carContext)
    }
}
