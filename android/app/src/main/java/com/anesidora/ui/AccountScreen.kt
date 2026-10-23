package com.anesidora.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.anesidora.Downloads
import com.anesidora.Radio

@Composable
fun AccountScreen(onLoggedIn: () -> Unit) {
    val account by Radio.account.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        if (account.email == null) {
            LoginForm(account.loggingIn, account.error, onLoggedIn)
        } else {
            Text("Logged in as", style = MaterialTheme.typography.labelMedium)
            Text(account.email ?: "", style = MaterialTheme.typography.titleMedium)
            account.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = Radio::logout) { Text("Log out") }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        SettingsSection()
    }
}

@Composable
private fun LoginForm(loggingIn: Boolean, error: String?, onLoggedIn: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    val account by Radio.account.collectAsState()
    val autofillManager = LocalAutofillManager.current

    LaunchedEffect(account.email) {
        if (submitted && account.email != null) {
            // Tells the password manager the login worked, so it can offer to save it.
            autofillManager?.commit()
            onLoggedIn()
        }
    }

    Text("Log in with your Pandora account", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        // Lets password managers like Bitwarden recognize and fill the login.
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username + ContentType.EmailAddress },
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
    )
    error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = {
            submitted = true
            Radio.login(email.trim(), password)
        },
        enabled = !loggingIn && email.isNotBlank() && password.isNotEmpty(),
    ) {
        if (loggingIn) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text("Log in")
    }
}

@Composable
private fun SettingsSection() {
    val settings = Radio.settings

    Text("Settings", style = MaterialTheme.typography.titleMedium)
    SettingSwitch("Skip a song when you thumbs it down", settings.skipAfterDislike) {
        settings.skipAfterDislike = it
    }
    SettingSwitch("\"Previous\" restarts the song if it's a few seconds in", settings.doRewinds) {
        settings.doRewinds = it
    }
    if (settings.doRewinds) {
        NumberSetting("Seconds in before \"previous\" restarts", settings.rewindSeconds) {
            settings.rewindSeconds = it
        }
    }
    SettingSwitch("Pause after a long time with no interaction", settings.pauseAfterInactivity) {
        settings.pauseAfterInactivity = it
        Radio.interactionHappened()
    }
    if (settings.pauseAfterInactivity) {
        NumberSetting("Minutes before pausing", settings.inactivityMinutes) {
            settings.inactivityMinutes = it
            Radio.interactionHappened()
        }
    }
    SettingSwitch("Add title, artist, album, and cover art to downloaded MP3s", settings.tagDownloads) {
        settings.tagDownloads = it
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = settings.downloadNameFormat ?: "",
        onValueChange = { settings.downloadNameFormat = it },
        label = { Text("Download file name") },
        supportingText = {
            val example = Downloads.baseFileName(
                songName = "Johnny Got A Boom Boom (Radio Edit)",
                artistName = "Imelda May",
                albumName = "More Mayhem",
                musicId = "S1234567",
                format = settings.downloadNameFormat,
                useUnderscores = settings.downloadNameUnderscores,
            )
            Text("%artistname%, %trackname%, %albumname%, %trackid%\nFor example: $example.mp3")
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    SettingSwitch("Use underscores instead of spaces in file names", settings.downloadNameUnderscores) {
        settings.downloadNameUnderscores = it
    }
    Spacer(Modifier.height(16.dp))
    Text(
        "Ads are always skipped. Downloads are saved to Music/Anesidora. " +
            "Anesidora is unofficial and not affiliated with Pandora.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NumberSetting(label: String, value: Int, onValueChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText.filter { it.isDigit() }.take(5)
            text.toIntOrNull()?.takeIf { it > 0 }?.let(onValueChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
