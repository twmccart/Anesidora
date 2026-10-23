package com.anesidora

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class AccountState(
    /** Set once credentials have been accepted (or restored from a previous session). */
    val email: String? = null,
    val loggingIn: Boolean = false,
    val error: String? = null,
)

data class PlaybackPosition(
    val playing: Boolean,
    val positionMilliseconds: Long,
    val durationMilliseconds: Long,
)

/**
 * The station queue, history, and Pandora actions: the Android counterpart of
 * common/js/background.js plus the station and feedback parts of anesidora.js.
 *
 * Everything here runs on the main thread. [PlaybackService] owns the actual player
 * and attaches it here; the UI and the media notification both drive playback through this object.
 */
object Radio {
    private const val MAX_HISTORY_ENTRIES = 20
    private const val ERROR_SKIP_DELAY_MILLISECONDS = 1000L
    private const val PLAYLIST_FETCH_ATTEMPTS = 3

    lateinit var settings: Settings
        private set
    private lateinit var client: PandoraClient
    private lateinit var applicationContext: Context
    private val scope = MainScope()

    private val mutableAccount = MutableStateFlow(AccountState())
    val account: StateFlow<AccountState> = mutableAccount.asStateFlow()

    private val mutableStations = MutableStateFlow<List<Station>>(emptyList())
    val stations: StateFlow<List<Station>> = mutableStations.asStateFlow()

    private val mutableRefreshingStations = MutableStateFlow(false)
    val refreshingStations: StateFlow<Boolean> = mutableRefreshingStations.asStateFlow()

    private val mutableCurrentStationToken = MutableStateFlow<String?>(null)
    val currentStationToken: StateFlow<String?> = mutableCurrentStationToken.asStateFlow()

    private val mutableCurrentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = mutableCurrentTrack.asStateFlow()

    private val mutableHistory = MutableStateFlow<List<Track>>(emptyList())
    val history: StateFlow<List<Track>> = mutableHistory.asStateFlow()

    private val mutableDownloadingTrackTokens = MutableStateFlow<Set<String>>(emptySet())
    val downloadingTrackTokens: StateFlow<Set<String>> = mutableDownloadingTrackTokens.asStateFlow()

    /** Bumped whenever any track's rating changes, so the notification can redraw its thumbs. */
    private val mutableRatingRevision = MutableStateFlow(0)
    val ratingRevision: StateFlow<Int> = mutableRatingRevision.asStateFlow()

    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = mutableMessages.asSharedFlow()

    private val upcomingTracks = ArrayDeque<Track>()
    private var playlistFetch: Deferred<Boolean>? = null
    private var playlistFetchStationToken: String? = null
    private val queueMutex = Mutex()

    private var player: Player? = null
    private var playerAttached = CompletableDeferred<Player>()
    private var resumePositionMilliseconds = 0L
    private var errorSkipJob: Job? = null
    private var inactivityJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                scope.launch { nextSong() }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Usually an expired audio URL. Skip ahead, but debounce so a run of failures
            // doesn't burn through the whole queue at once.
            errorSkipJob?.cancel()
            errorSkipJob = scope.launch {
                delay(ERROR_SKIP_DELAY_MILLISECONDS)
                nextSong()
            }
        }
    }

    fun initialize(context: Context) {
        if (::client.isInitialized) {
            return
        }
        applicationContext = context.applicationContext
        settings = Settings(applicationContext)
        client = PandoraClient { settings.credentials }
        mutableCurrentStationToken.value = settings.lastStationToken

        settings.credentials?.let { (email, password) ->
            mutableAccount.value = AccountState(email = email, loggingIn = true)
            scope.launch {
                try {
                    client.login(email, password)
                    mutableAccount.value = AccountState(email = email)
                    refreshStations()
                } catch (exception: PandoraException) {
                    if (exception.code == 1002) {
                        forgetCredentials()
                        mutableAccount.value = AccountState(error = exception.message)
                    } else {
                        // Probably offline. Keep the saved login; the next request will retry it.
                        mutableAccount.value = AccountState(email = email, error = exception.message)
                    }
                }
            }
        }
    }

    // Player lifecycle (called by PlaybackService)

    fun attachPlayer(newPlayer: Player) {
        player = newPlayer
        newPlayer.addListener(playerListener)
        playerAttached.complete(newPlayer)
    }

    fun detachPlayer(oldPlayer: Player) {
        oldPlayer.removeListener(playerListener)
        if (oldPlayer.mediaItemCount > 0) {
            resumePositionMilliseconds = oldPlayer.currentPosition
        }
        if (player === oldPlayer) {
            player = null
            playerAttached = CompletableDeferred()
        }
    }

    private suspend fun awaitPlayer(): Player? =
        player ?: withTimeoutOrNull(5_000) { playerAttached.await() }

    // Account

    fun login(email: String, password: String) {
        mutableAccount.value = AccountState(loggingIn = true)
        scope.launch {
            try {
                client.login(email, password)
                settings.email = email
                settings.password = password
                mutableAccount.value = AccountState(email = email)
                refreshStations()
            } catch (exception: PandoraException) {
                mutableAccount.value = AccountState(error = exception.message)
            }
        }
    }

    fun logout() {
        client.logout()
        forgetCredentials()
        settings.lastStationToken = null
        settings.lastSongName = null
        settings.lastArtistName = null
        settings.lastAlbumArtUrl = null
        player?.stop()
        player?.clearMediaItems()
        upcomingTracks.clear()
        playlistFetch = null
        mutableCurrentTrack.value = null
        mutableHistory.value = emptyList()
        mutableStations.value = emptyList()
        mutableCurrentStationToken.value = null
        mutableAccount.value = AccountState()
    }

    private fun forgetCredentials() {
        settings.email = null
        settings.password = null
    }

    // Stations

    fun refreshStations() {
        if (mutableRefreshingStations.value) {
            return
        }
        mutableRefreshingStations.value = true
        scope.launch {
            try {
                val result = client.call("user.getStationList", JSONObject().put("includeStationArtUrl", true))
                val stationsJson = result.getJSONArray("stations")
                mutableStations.value = (0 until stationsJson.length()).map { index ->
                    Station.fromJson(stationsJson.getJSONObject(index))
                }
            } catch (exception: PandoraException) {
                showMessage("Couldn't load stations: ${exception.message}")
            } finally {
                mutableRefreshingStations.value = false
            }
        }
    }

    fun stationName(stationId: String?): String? =
        mutableStations.value.firstOrNull { it.stationId == stationId || it.stationToken == stationId }?.stationName

    fun playStation(stationToken: String) {
        interactionHappened()
        scope.launch {
            if (stationToken == mutableCurrentStationToken.value && mutableCurrentTrack.value != null) {
                awaitPlayer()?.play()
                return@launch
            }
            queueMutex.withLock {
                mutableCurrentStationToken.value = stationToken
                settings.lastStationToken = stationToken
                upcomingTracks.clear()
                playlistFetch = null
            }
            nextSong()
        }
    }

    // Transport controls

    fun play() {
        interactionHappened()
        scope.launch {
            val activePlayer = awaitPlayer() ?: return@launch
            val current = mutableCurrentTrack.value
            when {
                activePlayer.mediaItemCount > 0 -> {
                    if (activePlayer.playbackState == Player.STATE_IDLE) {
                        activePlayer.prepare()
                    }
                    activePlayer.play()
                }
                current != null -> startTrack(current)
                mutableCurrentStationToken.value != null -> nextSong()
            }
        }
    }

    fun pause() {
        interactionHappened()
        player?.pause()
    }

    /** Polled by the player screen; the player doesn't publish position changes as events. */
    fun playbackPosition(): PlaybackPosition {
        val activePlayer = player ?: return PlaybackPosition(false, 0, 0)
        return PlaybackPosition(
            playing = activePlayer.playWhenReady && activePlayer.mediaItemCount > 0,
            positionMilliseconds = activePlayer.currentPosition,
            durationMilliseconds = activePlayer.duration.takeIf { it != C.TIME_UNSET } ?: 0,
        )
    }

    fun togglePlayPause() {
        val activePlayer = player
        if (activePlayer != null && activePlayer.playWhenReady && activePlayer.mediaItemCount > 0) {
            pause()
        } else {
            play()
        }
    }

    fun seekTo(positionMilliseconds: Long) {
        interactionHappened()
        player?.seekTo(positionMilliseconds)
    }

    fun skip() {
        interactionHappened()
        scope.launch { nextSong() }
    }

    /** Restarts the song if it's a few seconds in; otherwise goes back to the previous song. */
    fun back() {
        interactionHappened()
        val activePlayer = player
        if (
            settings.doRewinds &&
            activePlayer != null &&
            activePlayer.currentPosition > settings.rewindSeconds * 1000L
        ) {
            activePlayer.seekTo(0)
            return
        }
        val previous = mutableHistory.value.firstOrNull() ?: return
        scope.launch { replayTrack(previous, pushForwards = true) }
    }

    /** Plays a song picked out of the history list. */
    fun replay(track: Track) {
        interactionHappened()
        scope.launch { replayTrack(track, pushForwards = false) }
    }

    private suspend fun replayTrack(track: Track, pushForwards: Boolean) = queueMutex.withLock {
        val current = mutableCurrentTrack.value
        if (current != null && current !== track) {
            // Stepping back linearly puts the current song at the front of the queue, so that
            // skipping forward again plays it next. Picking from history just files it in history.
            if (pushForwards) {
                if (upcomingTracks.firstOrNull() !== current) {
                    upcomingTracks.addFirst(current)
                }
            } else {
                pushHistory(current)
            }
        }
        mutableHistory.value = mutableHistory.value.filter { it !== track }
        startTrack(track)
    }

    private suspend fun nextSong() = queueMutex.withLock {
        errorSkipJob?.cancel()
        val stationToken = mutableCurrentStationToken.value ?: return@withLock

        // When one song will be left after this one, refill in the background.
        // When we're about to run dry, wait for the refill.
        if (upcomingTracks.size == 2) {
            scope.launch { fetchPlaylist(stationToken) }
        } else if (upcomingTracks.size < 2) {
            for (attempt in 1..PLAYLIST_FETCH_ATTEMPTS) {
                if (fetchPlaylist(stationToken) || upcomingTracks.isNotEmpty()) {
                    break
                }
            }
        }

        val next = upcomingTracks.removeFirstOrNull()
        if (next == null) {
            showMessage("Couldn't get songs from Pandora.")
            return@withLock
        }
        mutableCurrentTrack.value?.let(::pushHistory)
        startTrack(next)
    }

    /** Fetches more songs into the queue; concurrent callers share one request. */
    private suspend fun fetchPlaylist(stationToken: String): Boolean {
        val running = playlistFetch?.takeIf { it.isActive && playlistFetchStationToken == stationToken }
        val fetch = running ?: scope.async {
            val tracks = requestPlaylist(stationToken)
            // The user may have changed stations while we waited.
            if (mutableCurrentStationToken.value == stationToken) {
                upcomingTracks.addAll(tracks)
            }
            tracks.isNotEmpty()
        }.also {
            playlistFetch = it
            playlistFetchStationToken = stationToken
        }
        return fetch.await()
    }

    private suspend fun requestPlaylist(stationToken: String): List<Track> = try {
        val result = client.call(
            "station.getPlaylist",
            JSONObject()
                .put("stationToken", stationToken)
                .put("additionalAudioUrl", Track.additionalAudioUrlParameter),
        )
        val items = result.getJSONArray("items")
        // Ads come back as items with an adToken; Track.fromJson drops them.
        (0 until items.length()).mapNotNull { index -> Track.fromJson(items.getJSONObject(index)) }
    } catch (exception: PandoraException) {
        showMessage("Couldn't get songs: ${exception.message}")
        emptyList()
    }

    private suspend fun startTrack(track: Track) {
        setCurrentTrack(track)
        val activePlayer = awaitPlayer() ?: return
        activePlayer.setMediaItem(track.toMediaItem())
        activePlayer.prepare()
        activePlayer.play()
    }

    private fun setCurrentTrack(track: Track) {
        mutableCurrentTrack.value = track
        resumePositionMilliseconds = 0
        settings.lastSongName = track.songName
        settings.lastArtistName = track.artistName
        settings.lastAlbumArtUrl = track.albumArtUrl
    }

    /**
     * What to play when Android asks us to resume while nothing is loaded: from the shade's
     * resume card, or a headset play button after the app was closed. Continues the current song
     * if we still have it; otherwise starts the last station fresh, since old audio URLs expire.
     * Returns the item and the position to start from, or null if there's nothing to resume.
     */
    suspend fun prepareResumption(): Pair<MediaItem, Long>? {
        mutableCurrentTrack.value?.let { current ->
            return current.toMediaItem() to resumePositionMilliseconds
        }
        val track = queueMutex.withLock {
            val stationToken = mutableCurrentStationToken.value ?: return null
            if (upcomingTracks.isEmpty()) {
                fetchPlaylist(stationToken)
            }
            upcomingTracks.removeFirstOrNull()
        } ?: return null
        setCurrentTrack(track)
        return track.toMediaItem() to 0L
    }

    private fun pushHistory(track: Track) {
        if (mutableHistory.value.firstOrNull() === track) {
            return
        }
        mutableHistory.value = (listOf(track) + mutableHistory.value.filter { it !== track })
            .take(MAX_HISTORY_ENTRIES)
    }

    private fun Track.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(trackToken)
            .setUri(audioUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(songName)
                    .setArtist(artistName)
                    .setAlbumTitle(albumName)
                    .setStation(stationName(stationId))
                    .setArtworkUri(albumArtUrl?.let(Uri::parse))
                    .build()
            )
            .build()

    // Feedback

    /** Thumbs up or down; tapping the thumb that's already set removes the rating. */
    fun rate(track: Track, positive: Boolean) {
        interactionHappened()
        if (!track.allowFeedback) {
            return
        }
        scope.launch {
            val wantedRating = if (positive) 1 else -1
            val previousRating = track.songRating
            // Optimistic, like the extension: correct the UI afterwards if the request fails.
            setRating(track, if (previousRating == wantedRating) 0 else wantedRating)
            try {
                if (previousRating == wantedRating) {
                    deleteFeedback(track, previousRating)
                } else {
                    val result = client.call(
                        "station.addFeedback",
                        JSONObject()
                            .put("trackToken", track.trackToken)
                            // For now, every station token is just the station id.
                            .put("stationToken", track.stationId)
                            .put("isPositive", positive),
                    )
                    track.feedbackId = result.optStringOrNull("feedbackId")
                    if (!positive && settings.skipAfterDislike && track === mutableCurrentTrack.value) {
                        nextSong()
                    }
                }
            } catch (exception: PandoraException) {
                setRating(track, previousRating)
                showMessage("Couldn't rate song: ${exception.message}")
            }
        }
    }

    private fun setRating(track: Track, rating: Int) {
        track.songRating = rating
        mutableRatingRevision.value++
    }

    private suspend fun deleteFeedback(track: Track, previousRating: Int) {
        val knownFeedbackId = track.feedbackId
        try {
            val feedbackId = knownFeedbackId ?: findFeedbackId(track, previousRating)
            client.call("station.deleteFeedback", JSONObject().put("feedbackId", feedbackId))
        } catch (exception: PandoraException) {
            // A remembered feedback id can go stale; look it up fresh once before giving up.
            if (knownFeedbackId == null) {
                throw exception
            }
            val feedbackId = findFeedbackId(track, previousRating)
            client.call("station.deleteFeedback", JSONObject().put("feedbackId", feedbackId))
        }
        track.feedbackId = null
    }

    private suspend fun findFeedbackId(track: Track, rating: Int): String {
        val station = client.call(
            "station.getStation",
            JSONObject()
                .put("stationToken", track.stationId)
                .put("includeExtendedAttributes", true),
        )
        val feedbackList = station.optJSONObject("feedback")
            ?.optJSONArray(if (rating == 1) "thumbsUp" else "thumbsDown")
        if (feedbackList != null) {
            for (index in 0 until feedbackList.length()) {
                val feedback = feedbackList.getJSONObject(index)
                if (feedback.optString("songIdentity") == track.songIdentity) {
                    return feedback.getString("feedbackId")
                }
            }
        }
        throw PandoraException("Couldn't find that rating on Pandora.")
    }

    /** "I'm tired of this song": Pandora won't play it for a month. */
    fun sleepCurrentSong() {
        interactionHappened()
        val track = mutableCurrentTrack.value ?: return
        scope.launch {
            try {
                client.call("user.sleepSong", JSONObject().put("trackToken", track.trackToken))
                showMessage("You won't hear \"${track.songName}\" for a month.")
            } catch (exception: PandoraException) {
                showMessage("Couldn't do that: ${exception.message}")
            }
            nextSong()
        }
    }

    /** Pandora's reasons for picking a song, e.g. "mellow rock instrumentation". */
    suspend fun explain(track: Track): Result<List<String>> = try {
        val result = client.call("track.explainTrack", JSONObject().put("trackToken", track.trackToken))
        val explanations = result.optJSONArray("explanations")
        Result.success(
            (0 until (explanations?.length() ?: 0))
                .mapNotNull { index -> explanations?.getJSONObject(index)?.optStringOrNull("focusTraitName") }
        )
    } catch (exception: PandoraException) {
        Result.failure(exception)
    }

    // Downloads

    fun download(track: Track) {
        interactionHappened()
        if (track.trackToken in mutableDownloadingTrackTokens.value) {
            return
        }
        mutableDownloadingTrackTokens.value += track.trackToken
        scope.launch {
            try {
                val fileName = Downloads.save(applicationContext, track, settings)
                showMessage("Saved $fileName to Music/Anesidora")
            } catch (exception: Exception) {
                showMessage("Download failed: ${exception.message}")
            } finally {
                mutableDownloadingTrackTokens.value -= track.trackToken
            }
        }
    }

    // Inactivity

    /** Restarts the "are you still listening?" timer, which pauses playback after a long silence. */
    fun interactionHappened() {
        inactivityJob?.cancel()
        if (!::settings.isInitialized || !settings.pauseAfterInactivity) {
            return
        }
        inactivityJob = scope.launch {
            delay(settings.inactivityMinutes * 60_000L)
            player?.pause()
        }
    }

    private fun showMessage(message: String) {
        mutableMessages.tryEmit(message)
    }
}
