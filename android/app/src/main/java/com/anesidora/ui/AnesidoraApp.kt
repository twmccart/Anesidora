package com.anesidora.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.anesidora.Radio

enum class Screen(val title: String, val icon: ImageVector) {
    History("History", Icons.Filled.History),
    Playing("Playing", Icons.Filled.PlayCircle),
    Stations("Stations", Icons.Filled.Radio),
    Account("Account", Icons.Filled.AccountCircle),
}

// The extension's "cozy" accent colors, used when the device has no wallpaper-based palette.
private val cozyDark = darkColorScheme(primary = Color(0xFFFFB306), secondary = Color(0xFFFFB306))
private val cozyLight = lightColorScheme(primary = Color(0xFFEE852B), secondary = Color(0xFF4586CA))

@Composable
fun AnesidoraTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> cozyDark
        else -> cozyLight
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable
fun AnesidoraApp() {
    val account by Radio.account.collectAsState()
    var screen by rememberSaveable {
        mutableStateOf(
            when {
                account.email == null && !account.loggingIn -> Screen.Account
                Radio.currentTrack.value != null -> Screen.Playing
                else -> Screen.Stations
            }
        )
    }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        Radio.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = screen == entry,
                        onClick = { screen = entry },
                        icon = { Icon(entry.icon, contentDescription = null) },
                        label = { Text(entry.title) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.History -> HistoryScreen()
                Screen.Playing -> PlayingScreen(onChooseStation = { screen = Screen.Stations })
                Screen.Stations -> StationsScreen(onStationChosen = { screen = Screen.Playing })
                Screen.Account -> AccountScreen(onLoggedIn = { screen = Screen.Stations })
            }
        }
    }
}
