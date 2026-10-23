package com.anesidora

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps music playing in the background and provides the media notification, lock screen,
 * and Bluetooth/headset controls: what navigator.mediaSession does for the extension.
 *
 * It's a library service (rather than a plain session service) only because Android's
 * "resume" card in the notification shade browses the app for its most recent item.
 */
class PlaybackService : MediaLibraryService() {
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaLibrarySession? = null
    private val scope = MainScope()

    private val thumbsUpCommand = SessionCommand(ACTION_THUMBS_UP, Bundle.EMPTY)
    private val thumbsDownCommand = SessionCommand(ACTION_THUMBS_DOWN, Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        Radio.initialize(this)

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exoPlayer = player
        Radio.attachPlayer(player)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val session = MediaLibrarySession.Builder(this, RadioPlayer(player), SessionCallback())
            .setSessionActivity(openApp)
            .build()
        mediaSession = session

        scope.launch {
            combine(Radio.currentTrack, Radio.ratingRevision) { track, _ -> track }
                .collect { track -> session.setCustomLayout(ratingButtons(track)) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        exoPlayer?.let { player ->
            Radio.detachPlayer(player)
            player.release()
        }
        mediaSession?.release()
        mediaSession = null
        exoPlayer = null
        super.onDestroy()
    }

    private fun ratingButtons(track: Track?): ImmutableList<CommandButton> {
        if (track == null || !track.allowFeedback) {
            return ImmutableList.of()
        }
        return ImmutableList.of(
            CommandButton.Builder()
                .setDisplayName("Thumbs down")
                .setIconResId(if (track.songRating == -1) R.drawable.thumb_down_filled else R.drawable.thumb_down_outlined)
                .setSessionCommand(thumbsDownCommand)
                .build(),
            CommandButton.Builder()
                .setDisplayName("Thumbs up")
                .setIconResId(if (track.songRating == 1) R.drawable.thumb_up_filled else R.drawable.thumb_up_outlined)
                .setSessionCommand(thumbsUpCommand)
                .build(),
        )
    }

    /** The last song played, as the shade's resume card shows it. Saved, so it survives reboots. */
    private fun lastPlayedItem(): MediaItem? {
        val settings = Radio.settings
        val songName = settings.lastSongName ?: return null
        return MediaItem.Builder()
            .setMediaId(RESUME_ITEM_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(songName)
                    .setArtist(settings.lastArtistName)
                    .setArtworkUri(settings.lastAlbumArtUrl?.let(Uri::parse))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }

    private fun resumptionFuture(): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch {
            val resumption = try {
                Radio.prepareResumption()
            } catch (exception: Exception) {
                null
            }
            if (resumption == null) {
                future.setException(IllegalStateException("Nothing to resume."))
            } else {
                val (item, startPosition) = resumption
                future.set(MediaSession.MediaItemsWithStartPosition(listOf(item), 0, startPosition))
            }
        }
        return future
    }

    private inner class SessionCallback : MediaLibrarySession.Callback {
        @OptIn(UnstableApi::class)
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .add(thumbsUpCommand)
                        .add(thumbsDownCommand)
                        .build()
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val track = Radio.currentTrack.value
            if (track != null) {
                when (customCommand.customAction) {
                    ACTION_THUMBS_UP -> Radio.rate(track, positive = true)
                    ACTION_THUMBS_DOWN -> Radio.rate(track, positive = false)
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /** Called when something asks to play while nothing is loaded, e.g. after the app was closed. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = resumptionFuture()

        /** The shade's resume card may ask for its item by id rather than just pressing play. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            if (mediaItems.any { it.mediaId == RESUME_ITEM_ID }) {
                resumptionFuture()
            } else {
                super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).build())
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val children = lastPlayedItem()?.let { ImmutableList.of(it) } ?: ImmutableList.of()
            return Futures.immediateFuture(LibraryResult.ofItemList(children, params))
        }
    }

    /**
     * The player only ever holds the current song, so next/previous wouldn't normally be offered.
     * This advertises them anyway and routes them (and play/pause) through [Radio].
     */
    @OptIn(UnstableApi::class)
    private class RadioPlayer(player: Player) : ForwardingPlayer(player) {
        private val radioCommands = intArrayOf(
            COMMAND_SEEK_TO_NEXT,
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().addAll(*radioCommands).build()

        override fun isCommandAvailable(command: Int): Boolean =
            command in radioCommands || super.isCommandAvailable(command)

        override fun play() = Radio.play()
        override fun pause() = Radio.pause()
        override fun seekToNext() = Radio.skip()
        override fun seekToNextMediaItem() = Radio.skip()
        override fun seekToPrevious() = Radio.back()
        override fun seekToPreviousMediaItem() = Radio.back()
    }

    companion object {
        private const val ACTION_THUMBS_UP = "com.anesidora.THUMBS_UP"
        private const val ACTION_THUMBS_DOWN = "com.anesidora.THUMBS_DOWN"
        private const val ROOT_ID = "root"
        private const val RESUME_ITEM_ID = "resume"
    }
}
