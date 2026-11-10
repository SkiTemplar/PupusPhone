package com.pupus.phone.device

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.pupus.phone.api.IntentRequest
import com.pupus.phone.api.MediaAction
import com.pupus.phone.api.Outcome
import org.json.JSONArray
import org.json.JSONObject

/** Launchable apps, intents and media keys. */
class SystemActions(private val context: Context) {

    @Suppress("DEPRECATION")
    fun apps(): Outcome {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val array = JSONArray()
        apps.forEach { (pkg, label) -> array.put(JSONObject().put("package", pkg).put("label", label)) }
        return Outcome.Ok(JSONObject().put("apps", array))
    }

    fun launch(pkg: String): Outcome {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Log.w(TAG, "No launch intent for $pkg")
            return Outcome.ok(false)
        }
        return start(intent)
    }

    fun intent(request: IntentRequest): Outcome {
        val intent = Intent(request.action)
        request.data?.let { intent.data = Uri.parse(it) }
        request.pkg?.let { intent.setPackage(it) }
        request.extras.forEach { (key, value) -> intent.putExtra(key, value) }
        return start(intent)
    }

    private fun start(intent: Intent): Outcome = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Outcome.ok(true)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No activity for $intent", e)
        Outcome.ok(false)
    } catch (e: SecurityException) {
        Log.w(TAG, "Not allowed to start $intent", e)
        Outcome.ok(false)
    }

    fun media(action: MediaAction): Outcome {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return Outcome.Failure(500, "audio_service_unavailable")
        when (action) {
            MediaAction.VOLUME_UP -> adjust(audio, AudioManager.ADJUST_RAISE)
            MediaAction.VOLUME_DOWN -> adjust(audio, AudioManager.ADJUST_LOWER)
            else -> sendKey(audio, keyCodeOf(action))
        }
        return Outcome.ok(true)
    }

    private fun adjust(audio: AudioManager, direction: Int) =
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)

    private fun sendKey(audio: AudioManager, keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun keyCodeOf(action: MediaAction): Int = when (action) {
        MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
        MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
        MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
        MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    }

    private companion object {
        const val TAG = "PupusSystem"
    }
}
