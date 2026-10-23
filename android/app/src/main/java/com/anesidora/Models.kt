package com.anesidora

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class Station(
    val stationToken: String,
    val stationId: String,
    val stationName: String,
    val artUrl: String?,
    val isQuickMix: Boolean,
) {
    companion object {
        fun fromJson(json: JSONObject) = Station(
            stationToken = json.getString("stationToken"),
            stationId = json.optString("stationId", json.getString("stationToken")),
            stationName = json.optString("stationName"),
            artUrl = json.optStringOrNull("artUrl")?.let(::toHttps),
            isQuickMix = json.optBoolean("isQuickMix"),
        )
    }
}

/**
 * One song from a station playlist. The rating is observable so that the history list,
 * the player screen, and the notification all agree on it without copying tracks around.
 */
class Track(
    val trackToken: String,
    val songName: String,
    val artistName: String,
    val albumName: String,
    val albumArtUrl: String?,
    val audioUrl: String,
    val stationId: String,
    val songIdentity: String?,
    val musicId: String?,
    val songDetailUrl: String?,
    val allowFeedback: Boolean,
    songRating: Int,
) {
    /** 1 for thumbs up, -1 for thumbs down, 0 for unrated. */
    var songRating by mutableIntStateOf(songRating)

    var feedbackId: String? = null

    companion object {
        private val preferredAudioFormats = listOf("HTTP_128_MP3", "HTTP_64_AACPLUS_ADTS")
        val additionalAudioUrlParameter = preferredAudioFormats.joinToString(",")

        /** Returns null for ads and for anything we cannot play. */
        fun fromJson(json: JSONObject): Track? {
            if (json.has("adToken")) {
                return null
            }
            val audioUrl = chooseAudioUrl(json) ?: return null
            return Track(
                trackToken = json.getString("trackToken"),
                songName = json.optString("songName"),
                artistName = json.optString("artistName"),
                albumName = json.optString("albumName"),
                albumArtUrl = json.optStringOrNull("albumArtUrl")?.let(::toHttps),
                audioUrl = toHttps(audioUrl),
                stationId = json.optString("stationId"),
                songIdentity = json.optStringOrNull("songIdentity"),
                musicId = json.optStringOrNull("musicId"),
                songDetailUrl = json.optStringOrNull("songDetailUrl")?.let(::toHttps),
                allowFeedback = json.optBoolean("allowFeedback", true),
                songRating = json.optInt("songRating", 0),
            )
        }

        private fun chooseAudioUrl(json: JSONObject): String? {
            when (val additional = json.opt("additionalAudioUrl")) {
                is JSONArray -> additional.optString(0).takeIf { it.isNotEmpty() }?.let { return it }
                is String -> if (additional.isNotEmpty()) return additional
            }
            val audioUrlMap = json.optJSONObject("audioUrlMap") ?: return null
            return listOf("highQuality", "mediumQuality", "lowQuality")
                .firstNotNullOfOrNull { quality ->
                    audioUrlMap.optJSONObject(quality)?.optStringOrNull("audioUrl")
                }
        }
    }
}

fun toHttps(url: String): String = url.replaceFirst(Regex("^http:"), "https:")

fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
