package com.pupus.phone.device

import android.Manifest
import android.app.Notification
import android.app.Person
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.MediaStore
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.pupus.phone.api.CallRequest
import com.pupus.phone.api.Outcome
import com.pupus.phone.api.PlaySearchRequest
import com.pupus.phone.car.MusicNotificationListener
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "PupusPersonal"
private val NOTIFICATION_ACCESS_MISSING = Outcome.Failure(403, "notification_access_missing")

/** Media sessions of other apps (needs the notification access of [MusicNotificationListener]). */
class MediaSessions(private val context: Context) {

    private fun controllers(): List<MediaController>? = try {
        context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(context, MusicNotificationListener::class.java))
    } catch (e: SecurityException) {
        Log.w(TAG, "notification access not granted", e)
        null
    }

    fun state(): Outcome {
        val sessions = controllers() ?: return NOTIFICATION_ACCESS_MISSING
        val items = sessions.filter { it.packageName != context.packageName }.map { c ->
            val meta = c.metadata
            val state = c.playbackState?.state
            JSONObject()
                .put("package", c.packageName)
                .put("playing", state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING)
                .put("title", meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: JSONObject.NULL)
                .put("artist", meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: JSONObject.NULL)
                .put("album", meta?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: JSONObject.NULL)
        }
        return Outcome.Ok(JSONObject().put("sessions", JSONArray(items)))
    }

    /**
     * playFromSearch on a live session (works with the screen locked). Some apps (Spotify) ignore it from
     * untrusted callers: if nothing starts playing, fall back to the app's play-from-search activity.
     */
    fun playSearch(request: PlaySearchRequest): Outcome {
        val sessions = controllers() ?: return NOTIFICATION_ACCESS_MISSING
        val target = request.pkg ?: firstPackage(sessions, request)
        if (playOnSession(sessions, request) && target != null && waitForPlaying(target)) return played(target, "session")
        val pkg = request.pkg ?: target ?: return Outcome.Failure(409, "no_media_session")
        if (!startSearchActivity(pkg, request.query)) return played(null, null, ok = false)
        // Give the app time to start playing; the caller confirms with /media/state.
        waitForPlaying(pkg)
        return played(pkg, "intent")
    }

    private fun waitForPlaying(pkg: String): Boolean {
        repeat(PLAYING_WAIT_STEPS) {
            SystemClock.sleep(SESSION_WAIT_STEP_MS)
            val playing = controllers().orEmpty().any {
                it.packageName == pkg && it.playbackState?.state in PLAYING_STATES
            }
            if (playing) return true
        }
        return false
    }

    private fun views(sessions: List<MediaController>) = sessions.map {
        SessionView(it.packageName, ((it.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_SEARCH) != 0L)
    }

    private fun firstPackage(sessions: List<MediaController>, request: PlaySearchRequest): String? =
        SessionPicker.pick(views(sessions), request.pkg, context.packageName)?.let { sessions[it].packageName }

    private fun playOnSession(sessions: List<MediaController>, request: PlaySearchRequest): Boolean {
        val index = SessionPicker.pick(views(sessions), request.pkg, context.packageName) ?: return false
        sessions[index].transportControls.playFromSearch(request.query, Bundle())
        Log.i(TAG, "playFromSearch on ${sessions[index].packageName}")
        return true
    }

    private fun startSearchActivity(pkg: String, query: String): Boolean = try {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(pkg)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "$pkg has no play-from-search activity", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "not allowed to start $pkg", e)
        false
    }

    private fun played(pkg: String?, via: String?, ok: Boolean = true) = Outcome.Ok(
        JSONObject().put("ok", ok).put("package", pkg ?: JSONObject.NULL).put("via", via ?: JSONObject.NULL),
    )

    private companion object {

        const val PLAYING_WAIT_STEPS = 4
        const val SESSION_WAIT_STEP_MS = 500L
        val PLAYING_STATES = setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING)
    }
}

/** Active notifications through the connected [MusicNotificationListener]. */
class NotificationReader(private val context: Context) {
    private val labels = mutableMapOf<String, String>()

    fun read(pkg: String?): Outcome {
        val listener = MusicNotificationListener.connected ?: run {
            NotificationListenerService.requestRebind(ComponentName(context, MusicNotificationListener::class.java))
            return NOTIFICATION_ACCESS_MISSING
        }
        val active = try {
            listener.activeNotifications.orEmpty()
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot read notifications", e)
            return NOTIFICATION_ACCESS_MISSING
        }
        return Outcome.Ok(NotificationDigest.toJson(active.map(::fieldsOf), context.packageName, pkg))
    }

    private fun fieldsOf(sbn: StatusBarNotification): NotificationFields {
        val n = sbn.notification
        val extras = n.extras
        return NotificationFields(
            key = sbn.key,
            pkg = sbn.packageName,
            app = labelOf(sbn.packageName),
            title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().map { it.toString() },
            messages = messagesOf(extras),
            postedAt = sbn.postTime,
            ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            groupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
        )
    }

    /** MessagingStyle stores each message as a Bundle with "text" and "sender" or "sender_person". */
    @Suppress("DEPRECATION")
    private fun messagesOf(extras: Bundle): List<MessageLine> =
        extras.getParcelableArray(Notification.EXTRA_MESSAGES).orEmpty().mapNotNull { item ->
            val bundle = item as? Bundle ?: return@mapNotNull null
            val text = bundle.getCharSequence("text")?.toString() ?: return@mapNotNull null
            val sender = bundle.getCharSequence("sender") ?: bundle.getParcelable<Person>("sender_person")?.name
            MessageLine(sender?.toString(), text)
        }

    private fun labelOf(pkg: String): String = labels.getOrPut(pkg) {
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }
}

/** Contacts search and phone calls. */
class PhoneActions(private val context: Context) {

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun contacts(query: String): Outcome {
        if (!granted(Manifest.permission.READ_CONTACTS)) return Outcome.Failure(403, "contacts_permission_missing")
        val uri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(query))
        val columns = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL)
        val rows = mutableListOf<PhoneRow>()
        context.contentResolver.query(uri, columns, null, null, "${Phone.DISPLAY_NAME} ASC")?.use { c ->
            while (c.moveToNext() && rows.size < MAX_ROWS) {
                val label = Phone.getTypeLabel(context.resources, c.getInt(3), c.getString(4)).toString()
                rows += PhoneRow(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), label)
            }
        }
        return Outcome.Ok(ContactGrouping.toJson(rows))
    }

    /** Places the call when CALL_PHONE is granted; otherwise opens the dialer with the number. */
    fun call(request: CallRequest): Outcome {
        val direct = granted(Manifest.permission.CALL_PHONE)
        val action = if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.fromParts("tel", request.number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no dialer", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "call not allowed", e)
            false
        }
        return Outcome.Ok(JSONObject().put("ok", ok).put("mode", if (direct) "call" else "dial"))
    }

    private companion object {
        const val MAX_ROWS = 50
    }
}
