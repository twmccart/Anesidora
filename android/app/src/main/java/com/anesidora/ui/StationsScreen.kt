package com.anesidora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anesidora.Radio
import com.anesidora.Station
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun StationsScreen(onStationChosen: () -> Unit) {
    val stations by Radio.stations.collectAsState()
    val refreshing by Radio.refreshingStations.collectAsState()
    val currentStationToken by Radio.currentStationToken.collectAsState()
    val account by Radio.account.collectAsState()
    var filter by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Search stations") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = Radio::refreshStations, enabled = !refreshing && account.email != null) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh stations")
                }
            }
        }

        val shown = stations.filter { filter.isBlank() || it.stationName.contains(filter, ignoreCase = true) }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        account.email == null && !account.loggingIn -> "Log in on the Account tab to see your stations."
                        refreshing || account.loggingIn -> "Loading stations…"
                        stations.isEmpty() -> "No stations yet. Create some on pandora.com, then refresh."
                        else -> "No matches."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // Reordering a filtered list would be ambiguous, so drag handles only show unfiltered.
            val reorderable = filter.isBlank()
            val listState = rememberLazyListState()
            val reorderState = rememberReorderableLazyListState(listState) { from, to ->
                Radio.moveStation(from.index, to.index)
            }
            LazyColumn(state = listState) {
                items(shown, key = { it.stationToken }) { station ->
                    ReorderableItem(reorderState, key = station.stationToken, enabled = reorderable) { dragging ->
                        StationRow(
                            station,
                            current = station.stationToken == currentStationToken,
                            dragging = dragging,
                            onClick = {
                                Radio.playStation(station.stationToken)
                                onStationChosen()
                            },
                            dragHandle = if (reorderable) {
                                {
                                    Icon(
                                        Icons.Filled.DragHandle,
                                        contentDescription = "Drag to reorder",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .draggableHandle(onDragStopped = { Radio.saveStationOrder() })
                                            .padding(12.dp),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StationRow(
    station: Station,
    current: Boolean,
    dragging: Boolean,
    onClick: () -> Unit,
    dragHandle: (@Composable () -> Unit)?,
) {
    val background = when {
        dragging -> MaterialTheme.colorScheme.surfaceContainerHighest
        current -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (station.isQuickMix) {
            Box(
                Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Shuffle, contentDescription = null)
            }
        } else {
            CoverArt(station.artUrl, Modifier.size(48.dp))
        }
        Text(
            station.stationName,
            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        dragHandle?.invoke()
    }
}
