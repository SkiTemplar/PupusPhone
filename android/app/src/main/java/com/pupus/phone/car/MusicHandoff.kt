package com.pupus.phone.car

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Notification access of the app. It lets [MediaSessionManager.getActiveSessions] see the other
 * media sessions, and the device API reads the active notifications through the bound instance
 * ([connected]) when the assistant is asked for them. It never acts on notifications.
 */
class MusicNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = this
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    companion object {
        /** The bound listener, or null while the system has it unbound. */
        @Volatile
        var connected: MusicNotificationListener? = null
            private set
    }
}

/**
 * The "Música" action of the car card: resumes the most recent other media app (Spotify, YouTube
 * Music...) with its own controller. A media key would not work: once PupusPhone has been the
 * playing session, the system routes the key back to it and it starts listening.
 */
object MusicHandoff {
    private const val TAG = "PupusMusic"

    sealed interface Outcome {
        data class Resumed(val packageName: String) : Outcome
        data object NoAccess : Outcome
        data object NoMusicApp : Outcome
    }

    /** Index of the first session (system priority order) that is not ours; null if none. */
    fun pickTarget(packages: List<String>, ownPackage: String): Int? =
        packages.indexOfFirst { it != ownPackage }.takeIf { it >= 0 }

    fun resume(context: Context): Outcome {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, MusicNotificationListener::class.java)
        val controllers = try {
            manager.getActiveSessions(listener)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification access not granted", e)
            return Outcome.NoAccess
        }
        val index = pickTarget(controllers.map { it.packageName }, context.packageName)
            ?: return Outcome.NoMusicApp
        val target = controllers[index]
        target.transportControls.play()
        Log.i(TAG, "resumed ${target.packageName}")
        return Outcome.Resumed(target.packageName)
    }
}
