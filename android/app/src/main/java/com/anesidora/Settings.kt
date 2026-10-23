package com.anesidora

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Persistent preferences, the Android counterpart of config.js and the extension's localStorage.
 * Each property is Compose-observable, so the settings screen updates as soon as one changes.
 */
class Settings(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences("anesidora", Context.MODE_PRIVATE)

    // Like the extension, the password is kept so we can log in again when the auth token
    // expires: Pandora offers no way to refresh a token without it.
    var email by stringPreference("email")
    var password by stringPreference("password")
    var lastStationToken by stringPreference("lastStationToken")

    // Shown on the "resume" card in the notification shade, even after a reboot.
    var lastSongName by stringPreference("lastSongName")
    var lastArtistName by stringPreference("lastArtistName")
    var lastAlbumArtUrl by stringPreference("lastAlbumArtUrl")

    var skipAfterDislike by booleanPreference("skipAfterDislike", true)
    var doRewinds by booleanPreference("doRewinds", true)
    var rewindSeconds by intPreference("rewindSeconds", 5)
    var pauseAfterInactivity by booleanPreference("pauseAfterInactivity", true)
    var inactivityMinutes by intPreference("inactivityMinutes", 60 * 5)
    var tagDownloads by booleanPreference("tagDownloads", true)
    var downloadNameFormat by stringPreference("downloadNameFormat", "%artistname% - %trackname%")

    /** Station ids in the user's chosen order. Kept per account, and across logouts. */
    fun stationOrder(email: String): List<String> =
        preferences.getString("stationOrder:$email", null)?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()

    fun setStationOrder(email: String, stationIds: List<String>) {
        preferences.edit().putString("stationOrder:$email", stationIds.joinToString("\n")).apply()
    }

    val credentials: Pair<String, String>?
        get() {
            val savedEmail = email
            val savedPassword = password
            return if (savedEmail.isNullOrEmpty() || savedPassword.isNullOrEmpty()) null
            else savedEmail to savedPassword
        }

    private fun stringPreference(key: String, default: String? = null) =
        preference(key, preferences.getString(key, default)) { editor, value -> editor.putString(key, value) }

    private fun booleanPreference(key: String, default: Boolean) =
        preference(key, preferences.getBoolean(key, default)) { editor, value -> editor.putBoolean(key, value) }

    private fun intPreference(key: String, default: Int) =
        preference(key, preferences.getInt(key, default)) { editor, value -> editor.putInt(key, value) }

    private fun <T> preference(
        key: String,
        initialValue: T,
        write: (SharedPreferences.Editor, T) -> Unit,
    ): ReadWriteProperty<Any?, T> = object : ReadWriteProperty<Any?, T> {
        val state: MutableState<T> = mutableStateOf(initialValue)

        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            preferences.edit().also { editor ->
                if (value == null) editor.remove(key) else write(editor, value)
            }.apply()
        }
    }
}
