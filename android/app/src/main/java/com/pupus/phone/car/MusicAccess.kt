package com.pupus.phone.car

import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/** Notification access for [MusicNotificationListener], which the "Música" car action needs. */
object MusicAccess {
    private const val TAG = "PupusMusic"

    fun component(context: Context) = ComponentName(context, MusicNotificationListener::class.java)

    fun isGranted(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(context))

    /** Opens the PupusPhone toggle directly; the full listener list if the ROM lacks that screen. */
    fun request(activity: Activity) {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(activity).flattenToString())
        try {
            activity.startActivity(detail)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Listener detail screen not available", e)
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }
}
