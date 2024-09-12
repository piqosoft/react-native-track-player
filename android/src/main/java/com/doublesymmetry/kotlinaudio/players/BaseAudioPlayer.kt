package com.doublesymmetry.kotlinaudio.players

import android.content.Context
import android.media.AudioManager
import android.media.AudioManager.AUDIOFOCUS_LOSS
import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.annotation.CallSuper
import androidx.core.content.ContextCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioAttributesCompat.CONTENT_TYPE_MUSIC
import androidx.media.AudioAttributesCompat.USAGE_MEDIA
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import androidx.media.AudioManagerCompat.AUDIOFOCUS_GAIN
import androidx.media.utils.MediaConstants
import com.doublesymmetry.kotlinaudio.event.EventHolder
import com.doublesymmetry.kotlinaudio.event.NotificationEventHolder
import com.doublesymmetry.kotlinaudio.event.PlayerEventHolder
import com.doublesymmetry.kotlinaudio.models.AudioContentType
import com.doublesymmetry.kotlinaudio.models.AudioItem
import com.doublesymmetry.kotlinaudio.models.AudioItemHolder
import com.doublesymmetry.kotlinaudio.models.AudioItemTransitionReason
import com.doublesymmetry.kotlinaudio.models.AudioPlayerState
import com.doublesymmetry.kotlinaudio.models.BufferConfig
import com.doublesymmetry.kotlinaudio.models.CacheConfig
import com.doublesymmetry.kotlinaudio.models.DefaultPlayerOptions
import com.doublesymmetry.kotlinaudio.models.MediaSessionCallback
import com.doublesymmetry.kotlinaudio.models.AAMediaSessionCallBack
import com.doublesymmetry.kotlinaudio.models.MediaType
import com.doublesymmetry.kotlinaudio.models.PlayWhenReadyChangeData
import com.doublesymmetry.kotlinaudio.models.PlaybackError
import com.doublesymmetry.kotlinaudio.models.PlayerConfig
import com.doublesymmetry.kotlinaudio.models.PlayerOptions
import com.doublesymmetry.kotlinaudio.models.PositionChangedReason
import com.doublesymmetry.kotlinaudio.models.WakeMode
import com.doublesymmetry.kotlinaudio.notification.NotificationManager
import com.doublesymmetry.kotlinaudio.players.components.PlayerCache
import com.doublesymmetry.kotlinaudio.players.components.getAudioItemHolder
import com.doublesymmetry.kotlinaudio.utils.isUriLocalFile
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultLoadControl.Builder
import androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS
import androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
import androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS
import androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MAX_BUFFER_MS
import androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MIN_BUFFER_MS
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.Listener
import androidx.media3.common.AudioAttributes
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.smoothstreaming.DefaultSsChunkSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultDataSourceFactory
import androidx.media3.session.MediaSession
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.TimeUnit


@UnstableApi
abstract class BaseAudioPlayer internal constructor(
    internal val context: Context,
    playerConfig: PlayerConfig,
    private val bufferConfig: BufferConfig?,
    private val cacheConfig: CacheConfig?,
    mediaSessionCallback: AAMediaSessionCallBack
) : AudioManager.OnAudioFocusChangeListener {
    protected val exoPlayer: ExoPlayer

    private var cache: SimpleCache? = null
    private val scope = MainScope()
    private var playerConfig: PlayerConfig = playerConfig
    var mediaSessionCallBack: AAMediaSessionCallBack = mediaSessionCallback

    val notificationManager: NotificationManager

    open val playerOptions: PlayerOptions = DefaultPlayerOptions()

    open val currentItem: AudioItem?
        get() = exoPlayer.currentMediaItem?.getAudioItemHolder()?.audioItem

    var playbackError: PlaybackError? = null
    var playerState: AudioPlayerState = AudioPlayerState.IDLE
        private set(value) {
            if (value != field) {
                field = value
                playerEventHolder.updateAudioPlayerState(value)
                if (!playerConfig.handleAudioFocus) {
                    when (value) {
                        AudioPlayerState.IDLE,
                        AudioPlayerState.ERROR -> abandonAudioFocusIfHeld()
                        AudioPlayerState.READY -> requestAudioFocus()
                        else -> {}
                    }
                }
            }
        }

    var playWhenReady: Boolean
        get() = exoPlayer.playWhenReady
        set(value) {
            exoPlayer.playWhenReady = value
        }

    val duration: Long
        get() {
            return if (exoPlayer.duration == C.TIME_UNSET) 0
            else exoPlayer.duration
        }

    val isCurrentMediaItemLive: Boolean
        get() = exoPlayer.isCurrentMediaItemLive

    private var oldPosition = 0L

    val position: Long
        get() {
            return if (exoPlayer.currentPosition == C.POSITION_UNSET.toLong()) 0
            else exoPlayer.currentPosition
        }

    val bufferedPosition: Long
        get() {
            return if (exoPlayer.bufferedPosition == C.POSITION_UNSET.toLong()) 0
            else exoPlayer.bufferedPosition
        }

    var volume: Float
        get() = exoPlayer.volume
        set(value) {
            exoPlayer.volume = value * volumeMultiplier
        }

    /**
     * fade volume of the current exoPlayer by a simple linear function.
     */
    fun fadeVolume(volume: Float = 1f, duration: Long = 500, interval: Long = 20L, callback: () -> Unit = { }): Deferred<Unit> {
        return scope.async {
            val volumeDiff = (volume - exoPlayer.volume) * interval / duration
            var fadeInDuration = duration
            while (fadeInDuration > 0) {
                fadeInDuration -= interval
                exoPlayer.volume += volumeDiff
                delay(interval)
            }
            exoPlayer.volume = volume
            callback()
            return@async
        }
    }

    /**
     * a simple fade in/out wrapper. for KA-example only, do not use.
     */
    fun simpleFadeDemo(callback: () -> Unit = { }) {
        fadeVolume(0f, callback={
            callback()
            fadeVolume()
        })
    }

    var playbackSpeed: Float
        get() = exoPlayer.playbackParameters.speed
        set(value) {
            exoPlayer.setPlaybackSpeed(value)
        }

    var automaticallyUpdateNotificationMetadata: Boolean = true

    private var volumeMultiplier = 1f
        private set(value) {
            field = value
            volume = volume
        }

    val isPlaying
        get() = exoPlayer.isPlaying

    private val notificationEventHolder = NotificationEventHolder()
    private val playerEventHolder = PlayerEventHolder()

    val event = EventHolder(notificationEventHolder, playerEventHolder)

    private var focus: AudioFocusRequestCompat? = null
    private var hasAudioFocus = false
    private var wasDucking = false

    private val mediaSession = MediaSessionCompat(context, "KotlinAudioPlayer")

    init {
        if (cacheConfig != null) {
            cache = PlayerCache.getInstance(context, cacheConfig)
        }

        exoPlayer = ExoPlayer.Builder(context)
            .setHandleAudioBecomingNoisy(playerConfig.handleAudioBecomingNoisy)
            .setWakeMode(
                when (playerConfig.wakeMode) {
                    WakeMode.NONE -> C.WAKE_MODE_NONE
                    WakeMode.LOCAL -> C.WAKE_MODE_LOCAL
                    WakeMode.NETWORK -> C.WAKE_MODE_NETWORK
                }
            )
            .apply {
                if (bufferConfig != null) setLoadControl(setupBuffer(bufferConfig))
            }
            .build()

        val playerToUse =
            if (playerConfig.interceptPlayerActionsTriggeredExternally) createForwardingPlayer() else exoPlayer
        mediaSession.setCallback(object: MediaSessionCompat.Callback() {
            override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                Timber.tag("GVATest").d("playing from mediaID: %s", mediaId)
                mediaSessionCallback.handlePlayFromMediaId(mediaId, extras)
            }

            override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                super.onPlayFromSearch(query, extras)
                Timber.tag("GVATest").d("playing from query: %s", query)
                mediaSessionCallback.handlePlayFromSearch(query, extras)
            }
            // https://stackoverflow.com/questions/53837783/selecting-media-item-in-android-auto-queue-does-nothing
            override fun onSkipToQueueItem(id: Long) {
                mediaSessionCallback.handleSkipToQueueItem(id)
            }
            // TODO: what's missing?
            override fun onPlay() {
                playerToUse.play()
            }

            override fun onPause() {
                playerToUse.pause()
            }

            override fun onSkipToNext() {
                playerToUse.seekToNext()
            }

            override fun onSkipToPrevious() {
                playerToUse.seekToPrevious()
            }

            override fun onFastForward() {
                playerToUse.seekForward()
            }

            override fun onRewind() {
                playerToUse.seekBack()
            }

            override fun onStop() {
                playerToUse.stop()
            }

            override fun onSeekTo(pos: Long) {
                playerToUse.seekTo(pos)
            }

            override fun onSetRating(rating: RatingCompat?) {
                if (rating == null) return
                playerEventHolder.updateOnPlayerActionTriggeredExternally(
                    MediaSessionCallback.RATING(
                        rating, null
                    )
                )
            }

            override fun onSetRating(rating: RatingCompat?, extras: Bundle?) {
                if (rating == null) return
                playerEventHolder.updateOnPlayerActionTriggeredExternally(
                    MediaSessionCallback.RATING(
                        rating,
                        extras
                    )
                )
            }
            // see NotificationManager.kt. onRewind, onFastForward and onStop do not trigger.
            override fun onCustomAction(action: String?, extras: Bundle?) {
                when (action) {
                    NotificationManager.REWIND -> playerToUse.seekBack()
                    NotificationManager.FORWARD -> playerToUse.seekForward()
                    NotificationManager.STOP-> playerToUse.stop()
                    else -> playerEventHolder.updateOnPlayerActionTriggeredExternally(
                            MediaSessionCallback.CUSTOMACTION(
                                action ?: "NO_ACTION_CODE_PROVIDED"
                            )
                        )
                }
            }
        })


        notificationManager = NotificationManager(
            context,
            playerToUse,
            mediaSession,
            notificationEventHolder,
            playerEventHolder
        )

        exoPlayer.addListener(PlayerListener())

        scope.launch {
            // Whether ExoPlayer should manage audio focus for us automatically
            // see https://medium.com/google-exoplayer/easy-audio-focus-with-exoplayer-a2dcbbe4640e
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(
                    when (playerConfig.audioContentType) {
                        AudioContentType.MUSIC -> C.AUDIO_CONTENT_TYPE_MUSIC
                        AudioContentType.SPEECH -> C.AUDIO_CONTENT_TYPE_SPEECH
                        AudioContentType.SONIFICATION -> C.AUDIO_CONTENT_TYPE_SONIFICATION
                        AudioContentType.MOVIE -> C.AUDIO_CONTENT_TYPE_MOVIE
                        AudioContentType.UNKNOWN -> C.AUDIO_CONTENT_TYPE_UNKNOWN
                    }
                )
                .build();
            exoPlayer.setAudioAttributes(audioAttributes, playerConfig.handleAudioFocus);
        }

        playerEventHolder.updateAudioPlayerState(AudioPlayerState.IDLE)
    }

    public fun getMediaSessionToken(): MediaSessionCompat.Token {
        return mediaSession.sessionToken
    }

    public fun setMetaDataMediaID(mediaId: String) {
        // https://developer.android.com/training/cars/media#browse-progress-bar
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaId)
                // ...and any other setters.
                .build())
    }

    public fun setPlaybackState(mediaId: String) {
        // https://developer.android.com/training/cars/media#browse-progress-bar

        val playbackStateExtras = Bundle()
        playbackStateExtras.putString(
            MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_MEDIA_ID, mediaId)
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setExtras(playbackStateExtras)
                // ...and any other setters.
                .build())
    }

    private fun createForwardingPlayer(): ForwardingPlayer {
        return object : ForwardingPlayer(exoPlayer) {
            override fun play() {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.PLAY)
            }

            override fun pause() {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.PAUSE)
            }

            override fun seekToNext() {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.NEXT)
            }

            override fun seekToPrevious() {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.PREVIOUS)
            }

            override fun seekForward() {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.FORWARD)
            }

            override fun seekBack() {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.REWIND)
            }

            override fun stop() {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.STOP)
            }

            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(
                    MediaSessionCallback.SEEK(
                        positionMs
                    )
                )
            }

            override fun seekTo(positionMs: Long) {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(
                    MediaSessionCallback.SEEK(
                        positionMs
                    )
                )
            }
        }
    }

    internal fun updateNotificationIfNecessary(overrideAudioItem: AudioItem? = null) {
        if (automaticallyUpdateNotificationMetadata) {
            notificationManager.overrideAudioItem = overrideAudioItem
        }
    }

    private fun setupBuffer(bufferConfig: BufferConfig): DefaultLoadControl {
        bufferConfig.apply {
            val multiplier =
                DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS / DEFAULT_BUFFER_FOR_PLAYBACK_MS
            val minBuffer =
                if (minBuffer != null && minBuffer != 0) minBuffer else DEFAULT_MIN_BUFFER_MS
            val maxBuffer =
                if (maxBuffer != null && maxBuffer != 0) maxBuffer else DEFAULT_MAX_BUFFER_MS
            val playBuffer =
                if (playBuffer != null && playBuffer != 0) playBuffer else DEFAULT_BUFFER_FOR_PLAYBACK_MS
            val backBuffer =
                if (backBuffer != null && backBuffer != 0) backBuffer else DEFAULT_BACK_BUFFER_DURATION_MS

            return Builder()
                .setBufferDurationsMs(minBuffer, maxBuffer, playBuffer, playBuffer * multiplier)
                .setBackBuffer(backBuffer, false)
                .build()
        }
    }

    /**
     * Will replace the current item with a new one and load it into the player.
     * @param item The [AudioItem] to replace the current one.
     * @param playWhenReady Whether playback starts automatically.
     */
    open fun load(item: AudioItem, playWhenReady: Boolean = true) {
        exoPlayer.playWhenReady = playWhenReady
        load(item)
    }

    /**
     * Will replace the current item with a new one and load it into the player.
     * @param item The [AudioItem] to replace the current one.
     */
    open fun load(item: AudioItem) {
        val mediaSource = getMediaSourceFromAudioItem(item)
        exoPlayer.addMediaSource(mediaSource)
        exoPlayer.prepare()
    }

    fun togglePlaying() {
        if (exoPlayer.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    var skipSilence: Boolean
        get() = exoPlayer.skipSilenceEnabled
        set(value) {
            exoPlayer.skipSilenceEnabled = value;
        }

    fun play() {
        exoPlayer.play()
        if (currentItem != null) {
            exoPlayer.prepare()
        }
    }

    fun prepare() {
        if (currentItem != null) {
            exoPlayer.prepare()
        }
    }

    fun pause() {
        exoPlayer.pause()
    }

    /**
     * Stops playback, without clearing the active item. Calling this method will cause the playback
     * state to transition to AudioPlayerState.IDLE and the player will release the loaded media and
     * resources required for playback.
     */
    @CallSuper
    open fun stop() {
        playerState = AudioPlayerState.STOPPED
        exoPlayer.playWhenReady = false
        exoPlayer.stop()
    }

    @CallSuper
    open fun clear() {
        exoPlayer.clearMediaItems()
    }

    /**
     * Pause playback whenever an item plays to its end.
     */
    fun setPauseAtEndOfItem(pause: Boolean) {
        exoPlayer.pauseAtEndOfMediaItems = pause
    }

    /**
     * Stops and destroys the player. Only call this when you are finished using the player, otherwise use [pause].
     */
    @CallSuper
    open fun destroy() {
        abandonAudioFocusIfHeld()
        stop()
        notificationManager.destroy()
        exoPlayer.release()
        cache?.release()
        cache = null
        mediaSession.isActive = false
    }

    open fun seek(duration: Long, unit: TimeUnit) {
        val positionMs = TimeUnit.MILLISECONDS.convert(duration, unit)
        exoPlayer.seekTo(positionMs)
    }

    open fun seekBy(offset: Long, unit: TimeUnit) {
        val positionMs = exoPlayer.currentPosition + TimeUnit.MILLISECONDS.convert(offset, unit)
        exoPlayer.seekTo(positionMs)
    }

    protected fun getMediaSourceFromAudioItem(audioItem: AudioItem): MediaSource {
        val uri = Uri.parse(audioItem.audioUrl)
        val mediaItem = MediaItem.Builder()
            .setUri(audioItem.audioUrl)
            .setTag(AudioItemHolder(audioItem))
            .build()

        val userAgent =
            if (audioItem.options == null || audioItem.options!!.userAgent.isNullOrBlank()) {
                Util.getUserAgent(context, APPLICATION_NAME)
            } else {
                audioItem.options!!.userAgent
            }

        val factory: DataSource.Factory = when {
            audioItem.options?.resourceId != null -> {
                val raw = RawResourceDataSource(context)
                raw.open(DataSpec(uri))
                DataSource.Factory { raw }
            }
            isUriLocalFile(uri) -> {
                DefaultDataSourceFactory(context, userAgent)
            }
            else -> {
                val tempFactory = DefaultHttpDataSource.Factory().apply {
                    setUserAgent(userAgent)
                    setAllowCrossProtocolRedirects(true)

                    audioItem.options?.headers?.let {
                        setDefaultRequestProperties(it.toMap())
                    }
                }

                enableCaching(tempFactory)
            }
        }

        return when (audioItem.type) {
            MediaType.DASH -> createDashSource(mediaItem, factory)
            MediaType.HLS -> createHlsSource(mediaItem, factory)
            MediaType.SMOOTH_STREAMING -> createSsSource(mediaItem, factory)
            else -> createProgressiveSource(mediaItem, factory)
        }
    }

    private fun createDashSource(mediaItem: MediaItem, factory: DataSource.Factory?): MediaSource {
        return DashMediaSource.Factory(DefaultDashChunkSource.Factory(factory!!), factory)
            .createMediaSource(mediaItem)
    }

    private fun createHlsSource(mediaItem: MediaItem, factory: DataSource.Factory?): MediaSource {
        return HlsMediaSource.Factory(factory!!)
            .createMediaSource(mediaItem)
    }

    private fun createSsSource(mediaItem: MediaItem, factory: DataSource.Factory?): MediaSource {
        return SsMediaSource.Factory(DefaultSsChunkSource.Factory(factory!!), factory)
            .createMediaSource(mediaItem)
    }

    private fun createProgressiveSource(
        mediaItem: MediaItem,
        factory: DataSource.Factory
    ): ProgressiveMediaSource {
        return ProgressiveMediaSource.Factory(
            factory, DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
        )
            .createMediaSource(mediaItem)
    }

    private fun enableCaching(factory: DataSource.Factory): DataSource.Factory {
        return if (cache == null || cacheConfig == null || (cacheConfig.maxCacheSize ?: 0) <= 0) {
            factory
        } else {
            CacheDataSource.Factory().apply {
                setCache(this@BaseAudioPlayer.cache!!)
                setUpstreamDataSourceFactory(factory)
                setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            }
        }
    }

    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        Timber.d("Requesting audio focus...")

        val manager = ContextCompat.getSystemService(context, AudioManager::class.java)

        focus = AudioFocusRequestCompat.Builder(AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener(this)
            .setAudioAttributes(
                AudioAttributesCompat.Builder()
                    .setUsage(USAGE_MEDIA)
                    .setContentType(CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setWillPauseWhenDucked(playerOptions.alwaysPauseOnInterruption)
            .build()

        val result: Int = if (manager != null && focus != null) {
            AudioManagerCompat.requestAudioFocus(manager, focus!!)
        } else {
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }

        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    private fun abandonAudioFocusIfHeld() {
        if (!hasAudioFocus) return
        Timber.d("Abandoning audio focus...")

        val manager = ContextCompat.getSystemService(context, AudioManager::class.java)

        val result: Int = if (manager != null && focus != null) {
            AudioManagerCompat.abandonAudioFocusRequest(manager, focus!!)
        } else {
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }

        hasAudioFocus = (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    override fun onAudioFocusChange(focusChange: Int) {
        Timber.d("Audio focus changed")
        val isPermanent = focusChange == AUDIOFOCUS_LOSS
        val isPaused = when (focusChange) {
            AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> true
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> playerOptions.alwaysPauseOnInterruption
            else -> false
        }
        if (!playerConfig.handleAudioFocus) {
            if (isPermanent) abandonAudioFocusIfHeld()

            val isDucking = focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                    && !playerOptions.alwaysPauseOnInterruption
            if (isDucking) {
                volumeMultiplier = 0.5f
                wasDucking = true
            } else if (wasDucking) {
                volumeMultiplier = 1f
                wasDucking = false
            }
        }

        playerEventHolder.updateOnAudioFocusChanged(isPaused, isPermanent)
    }

    fun setMediaSessionError(message: String, errorCode: Int = PlaybackStateCompat.ERROR_CODE_APP_ERROR) {
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_ERROR, 0, 1f)
                .setErrorMessage(errorCode, message)
                .build())
    }

    companion object {
        const val APPLICATION_NAME = "react-native-track-player"
    }

    inner class PlayerListener : Listener {
        /**
         * Called when there is metadata associated with the current playback time.
         */
        override fun onMetadata(metadata: Metadata) {
            playerEventHolder.updateOnTimedMetadata(metadata)
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            playerEventHolder.updateOnCommonMetadata(mediaMetadata)
        }

        /**
         * A position discontinuity occurs when the playing period changes, the playback position
         * jumps within the period currently being played, or when the playing period has been
         * skipped or removed.
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            this@BaseAudioPlayer.oldPosition = oldPosition.positionMs

            when (reason) {
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.AUTO(oldPosition.positionMs, newPosition.positionMs)
                )
                Player.DISCONTINUITY_REASON_SEEK -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.SEEK(oldPosition.positionMs, newPosition.positionMs)
                )
                Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.SEEK_FAILED(
                        oldPosition.positionMs,
                        newPosition.positionMs
                    )
                )
                Player.DISCONTINUITY_REASON_REMOVE -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.QUEUE_CHANGED(
                        oldPosition.positionMs,
                        newPosition.positionMs
                    )
                )
                Player.DISCONTINUITY_REASON_SKIP -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.SKIPPED_PERIOD(
                        oldPosition.positionMs,
                        newPosition.positionMs
                    )
                )
                Player.DISCONTINUITY_REASON_INTERNAL -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.UNKNOWN(oldPosition.positionMs, newPosition.positionMs)
                )
            }
        }

        /**
         * Called when playback transitions to a media item or starts repeating a media item
         * according to the current repeat mode. Note that this callback is also called when the
         * playlist becomes non-empty or empty as a consequence of a playlist change.
         */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.AUTO(oldPosition)
                )
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.QUEUE_CHANGED(oldPosition)
                )
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.REPEAT(oldPosition)
                )
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.SEEK_TO_ANOTHER_AUDIO_ITEM(oldPosition)
                )
            }

            updateNotificationIfNecessary()
        }

        /**
         * Called when the value returned from Player.getPlayWhenReady() changes.
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val pausedBecauseReachedEnd = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            playerEventHolder.updatePlayWhenReadyChange(PlayWhenReadyChangeData(playWhenReady, pausedBecauseReachedEnd))
        }

        /**
         * The generic onEvents callback provides access to the Player object and specifies the set
         * of events that occurred together. It’s always called after the callbacks that correspond
         * to the individual events.
         */
        override fun onEvents(player: Player, events: Player.Events) {
            // Note that it is necessary to set `playerState` in order, since each mutation fires an
            // event.
            for (i in 0 until events.size()) {
                when (events[i]) {
                    Player.EVENT_PLAYBACK_STATE_CHANGED -> {
                        val state = when (player.playbackState) {
                            Player.STATE_BUFFERING -> AudioPlayerState.BUFFERING
                            Player.STATE_READY -> AudioPlayerState.READY
                            Player.STATE_IDLE ->
                                // Avoid transitioning to idle from error or stopped
                                if (
                                    playerState == AudioPlayerState.ERROR ||
                                    playerState == AudioPlayerState.STOPPED
                                )
                                    null
                                else
                                    AudioPlayerState.IDLE
                            Player.STATE_ENDED ->
                                if (player.mediaItemCount > 0) AudioPlayerState.ENDED
                                else AudioPlayerState.IDLE
                            else -> null // noop
                        }
                        if (state != null && state != playerState) {
                            playerState = state
                        }
                    }
                    Player.EVENT_MEDIA_ITEM_TRANSITION -> {
                        playbackError = null
                        if (currentItem != null) {
                            playerState = AudioPlayerState.LOADING
                            if (isPlaying) {
                                playerState = AudioPlayerState.READY
                                playerState = AudioPlayerState.PLAYING
                            }
                        }
                    }
                    Player.EVENT_PLAY_WHEN_READY_CHANGED -> {
                        if (!player.playWhenReady && playerState != AudioPlayerState.STOPPED) {
                            playerState = AudioPlayerState.PAUSED
                        }
                    }
                    Player.EVENT_IS_PLAYING_CHANGED -> {
                        if (player.isPlaying) {
                            playerState = AudioPlayerState.PLAYING
                        }
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val _playbackError = PlaybackError(
                error.errorCodeName
                    .replace("ERROR_CODE_", "")
                    .lowercase(Locale.getDefault())
                    .replace("_", "-"),
                error.message
            )
            playerEventHolder.updatePlaybackError(_playbackError)
            playbackError = _playbackError
            playerState = AudioPlayerState.ERROR
        }
    }
}