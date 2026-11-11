package com.pupus.phone.car

import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.media.MediaBrowserService
import android.util.Log
import com.pupus.phone.R
import com.pupus.phone.voice.VoiceBus
import com.pupus.phone.voice.VoiceLauncher

/**
 * PupusPhone as an Android Auto media source, so it gets the media card next to the map (where
 * Spotify sits) and the steering-wheel play/pause button. It plays no audio: play/pause is a
 * bubble tap, the title is the last reply, the subtitle the state and the artwork its colour.
 * The "Música" custom action hands the card back to the last music app ([MusicHandoff]).
 * Framework APIs only: the app has no AndroidX dependency.
 */
class PupusMediaService : MediaBrowserService() {
    private lateinit var session: MediaSession
    private lateinit var artwork: StateArtwork
    private val voiceListener = VoiceBus.Listener { _, _ -> render() }
    private val detailsListener = VoiceBus.DetailsListener { _, _ -> render() }

    override fun onCreate() {
        super.onCreate()
        artwork = StateArtwork(this)
        session = MediaSession(this, TAG).apply {
            setCallback(SessionCallback())
            isActive = true
        }
        sessionToken = session.sessionToken
        VoiceBus.addListener(voiceListener)
        VoiceBus.addDetailsListener(detailsListener)
    }

    override fun onDestroy() {
        VoiceBus.removeListener(voiceListener)
        VoiceBus.removeDetailsListener(detailsListener)
        session.release()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(ROOT_ID, null)

    /** One playable item, so opening PupusPhone from the car launcher has something to press. */
    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        if (parentId != ROOT_ID) {
            result.sendResult(mutableListOf())
            return
        }
        val talk = MediaDescription.Builder()
            .setMediaId(TALK_MEDIA_ID)
            .setTitle(getString(R.string.car_media_talk_item))
            .setIconBitmap(artwork.of(CarMediaTexts.of(VoiceBus.state, VoiceBus.isBridgeConnected, "").style))
            .build()
        result.sendResult(mutableListOf(MediaBrowser.MediaItem(talk, MediaBrowser.MediaItem.FLAG_PLAYABLE)))
    }

    private fun render(errorRes: Int? = null) {
        val view = CarMediaTexts.of(VoiceBus.state, VoiceBus.isBridgeConnected, VoiceBus.reply)
        val stateText = getString(view.stateRes)
        val title = view.reply ?: getString(R.string.car_media_hint)
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, stateText)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, stateText)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork.of(view.style))
                .build(),
        )
        session.setPlaybackState(playbackState(view.isPlaying, errorRes?.let(::getString)))
    }

    /** [error] is non-fatal: Android Auto shows it as a message and keeps the card. */
    private fun playbackState(isPlaying: Boolean, error: String?): PlaybackState {
        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val music = PlaybackState.CustomAction
            .Builder(ACTION_MUSIC, getString(R.string.car_action_music), R.drawable.ic_music)
            .build()
        return PlaybackState.Builder()
            .setActions(SUPPORTED_ACTIONS)
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
            .addCustomAction(music)
            .setErrorMessage(error)
            .build()
    }

    private fun onTransport(isPlayCommand: Boolean) {
        val state = VoiceBus.state
        if (CarMediaTexts.shouldTap(state, isPlayCommand)) VoiceLauncher.tap(this)
        else Log.i(TAG, "ignored ${if (isPlayCommand) "play" else "pause"} in $state")
    }

    /**
     * Resumes the last music app. A turn in progress gets a bubble tap first: it interrupts
     * thinking or speaking and sends what is being dictated while listening.
     */
    private fun resumeMusic() {
        if (CarMediaTexts.isPlaying(VoiceBus.state)) VoiceLauncher.tap(this)
        when (MusicHandoff.resume(this)) {
            is MusicHandoff.Outcome.Resumed -> Unit
            MusicHandoff.Outcome.NoAccess -> render(R.string.music_access_missing)
            MusicHandoff.Outcome.NoMusicApp -> render(R.string.music_none)
        }
    }

    private inner class SessionCallback : MediaSession.Callback() {
        override fun onPlay() = onTransport(isPlayCommand = true)

        override fun onPause() = onTransport(isPlayCommand = false)

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) = onTransport(isPlayCommand = true)

        override fun onCustomAction(action: String, extras: Bundle?) {
            if (action == ACTION_MUSIC) resumeMusic() else Log.w(TAG, "unknown custom action $action")
        }
    }

    private companion object {
        const val TAG = "PupusMedia"
        const val ROOT_ID = "pupus_root"
        const val TALK_MEDIA_ID = "pupus_talk"
        const val ACTION_MUSIC = "com.pupus.phone.action.MUSIC"
        const val SUPPORTED_ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
    }
}
