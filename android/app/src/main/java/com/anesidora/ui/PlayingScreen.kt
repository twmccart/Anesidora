package com.anesidora.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anesidora.PlaybackPosition
import com.anesidora.Radio
import com.anesidora.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlayingScreen(onChooseStation: () -> Unit) {
    val track by Radio.currentTrack.collectAsState()
    val currentTrack = track
    if (currentTrack == null) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Nothing playing yet.", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onChooseStation) { Text("Choose a station") }
        }
        return
    }

    var position by remember { mutableStateOf(Radio.playbackPosition()) }
    LaunchedEffect(Unit) {
        while (true) {
            position = Radio.playbackPosition()
            delay(500)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            Radio.stationName(currentTrack.stationId) ?: "",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))
        CoverArt(
            currentTrack.albumArtUrl,
            Modifier.weight(1f, fill = false).widthIn(max = 480.dp).aspectRatio(1f),
            cornerRadius = 8,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            currentTrack.songName,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(currentTrack.artistName, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            currentTrack.albumName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        SeekBar(position)
        TransportControls(currentTrack, position.playing)
        Spacer(Modifier.height(4.dp))
        SecondaryControls(currentTrack)
    }
}

@Composable
private fun SeekBar(position: PlaybackPosition) {
    // While dragging, show where the thumb is rather than where playback is.
    var draggingTo by remember { mutableStateOf<Float?>(null) }
    val duration = position.durationMilliseconds.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = draggingTo ?: (position.positionMilliseconds.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { draggingTo = it },
            onValueChangeFinished = {
                draggingTo?.let { Radio.seekTo((it * duration).toLong()) }
                draggingTo = null
            },
            enabled = position.durationMilliseconds > 0,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = draggingTo?.let { (it * duration).toLong() } ?: position.positionMilliseconds
            Text(formatTime(shown), style = MaterialTheme.typography.labelSmall)
            Text(formatTime(position.durationMilliseconds), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TransportControls(track: Track, playing: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbButton(track, positive = false)
        IconButton(onClick = Radio::back, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", Modifier.size(36.dp))
        }
        FilledIconButton(onClick = Radio::togglePlayPause, modifier = Modifier.size(72.dp)) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                modifier = Modifier.size(40.dp),
            )
        }
        IconButton(onClick = Radio::skip, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Next", Modifier.size(36.dp))
        }
        ThumbButton(track, positive = true)
    }
}

@Composable
private fun SecondaryControls(track: Track) {
    var showExplanation by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        IconButton(onClick = Radio::sleepCurrentSong) {
            Icon(Icons.Filled.Snooze, contentDescription = "Tired of this song")
        }
        IconButton(onClick = { showExplanation = true }) {
            Icon(Icons.Outlined.Info, contentDescription = "Why this song?")
        }
        DownloadButton(track)
    }
    if (showExplanation) {
        ExplanationDialog(track, onDismiss = { showExplanation = false })
    }
}

@Composable
private fun ExplanationDialog(track: Track, onDismiss: () -> Unit) {
    var explanation by remember(track) { mutableStateOf<Result<List<String>>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(track) {
        scope.launch { explanation = Radio.explain(track) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text("Why this song?") },
        text = {
            val result = explanation
            when {
                result == null -> CircularProgressIndicator()
                result.isFailure -> Text(result.exceptionOrNull()?.message ?: "Couldn't ask Pandora.")
                result.getOrThrow().isEmpty() -> Text("Pandora didn't say.")
                else -> Text(
                    "\"${track.songName}\" features " +
                        result.getOrThrow().joinToString(", ").lowercase() + "."
                )
            }
        },
    )
}
