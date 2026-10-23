package com.anesidora

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Saves songs into Music/Anesidora, tagging MP3s like downloadRichSong in anesidora.js. */
object Downloads {
    private const val MAXIMUM_FILENAME_LENGTH = 100

    suspend fun save(context: Context, track: Track, settings: Settings): String = withContext(Dispatchers.IO) {
        val (audio, contentType) = fetch(track.audioUrl)
        val isMp3 = contentType.contains("mpeg") || looksLikeMp3(audio)
        val (extension, mimeType) = when {
            isMp3 -> "mp3" to "audio/mpeg"
            contentType.contains("mp4") -> "m4a" to "audio/mp4"
            else -> "aac" to "audio/aac"
        }

        val bytes = if (isMp3 && settings.tagDownloads) {
            val artwork = track.albumArtUrl?.let { artUrl ->
                runCatching { fetch(artUrl.replace("1080W_1080H", "500W_500H")) }
                    .getOrNull()
                    ?.takeIf { (_, artType) -> artType.contains("jpeg") }
                    ?.first
            }
            Id3Tag.build(track, artwork) + Id3Tag.stripExisting(audio)
        } else {
            audio
        }

        val fileName = "${
            baseFileName(
                track.songName, track.artistName, track.albumName, track.musicId,
                settings.downloadNameFormat, settings.downloadNameUnderscores,
            )
        }.$extension"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Anesidora")
            put(MediaStore.Audio.Media.TITLE, track.songName)
            put(MediaStore.Audio.Media.ARTIST, track.artistName)
            put(MediaStore.Audio.Media.ALBUM, track.albumName)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("Couldn't create the file.")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("Couldn't write the file.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (exception: Exception) {
            resolver.delete(uri, null, null)
            throw exception
        }
        fileName
    }

    private fun fetch(url: String): Pair<ByteArray, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            return bytes to (connection.contentType ?: "")
        } finally {
            connection.disconnect()
        }
    }

    private fun looksLikeMp3(audio: ByteArray): Boolean =
        audio.size > 3 && (
            (audio[0] == 'I'.code.toByte() && audio[1] == 'D'.code.toByte() && audio[2] == '3'.code.toByte()) ||
                (audio[0] == 0xFF.toByte() && (audio[1].toInt() and 0xE0) == 0xE0 && (audio[1].toInt() and 0x06) != 0)
            )

    /** The download's name without its extension; also used for the preview in settings. */
    fun baseFileName(
        songName: String,
        artistName: String,
        albumName: String,
        musicId: String?,
        format: String?,
        useUnderscores: Boolean,
    ): String {
        val name = (format ?: Settings.DEFAULT_DOWNLOAD_NAME_FORMAT)
            .replace("%trackname%", songName)
            .replace("%artistname%", artistName)
            .replace("%albumname%", albumName)
            .replace("%trackid%", musicId ?: "")
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_")
            .trim()
        return (if (useUnderscores) name.replace(Regex("""\s+"""), "_") else name)
            .take(MAXIMUM_FILENAME_LENGTH)
            .ifEmpty { "Anesidora download" }
    }
}

/** A minimal ID3v2.3 writer: title, artist, album, and front cover. */
private object Id3Tag {
    fun build(track: Track, jpegArtwork: ByteArray?): ByteArray {
        val frames = ByteArrayOutputStream()
        frames.writeTextFrame("TIT2", track.songName)
        frames.writeTextFrame("TPE1", track.artistName)
        frames.writeTextFrame("TALB", track.albumName)
        if (jpegArtwork != null) {
            val pictureHeader = byteArrayOf(0) + // ISO-8859-1 description
                "image/jpeg".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
                byteArrayOf(3) + // picture type: front cover
                byteArrayOf(0) // empty description
            frames.writeFrame("APIC", pictureHeader + jpegArtwork)
        }
        val body = frames.toByteArray()
        return "ID3".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(3, 0, 0) + // version 2.3.0, no flags
            synchsafe(body.size) +
            body
    }

    /** Drops an ID3v2 tag the file already has, so we don't end up with two. */
    fun stripExisting(audio: ByteArray): ByteArray {
        if (audio.size < 10 || String(audio, 0, 3, Charsets.ISO_8859_1) != "ID3") {
            return audio
        }
        val size = (audio[6].toInt() and 0x7F shl 21) or
            (audio[7].toInt() and 0x7F shl 14) or
            (audio[8].toInt() and 0x7F shl 7) or
            (audio[9].toInt() and 0x7F)
        val hasFooter = (audio[5].toInt() and 0x10) != 0
        val tagLength = 10 + size + if (hasFooter) 10 else 0
        return if (tagLength < audio.size) audio.copyOfRange(tagLength, audio.size) else audio
    }

    private fun ByteArrayOutputStream.writeTextFrame(id: String, text: String) {
        if (text.isEmpty()) {
            return
        }
        // Encoding 1 is UTF-16 with a byte order mark, which handles any song title.
        val content = byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        writeFrame(id, content)
    }

    private fun ByteArrayOutputStream.writeFrame(id: String, content: ByteArray) {
        write(id.toByteArray(Charsets.ISO_8859_1))
        write(bigEndian(content.size))
        write(byteArrayOf(0, 0)) // frame flags
        write(content)
    }

    private fun bigEndian(value: Int) = byteArrayOf(
        (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte(),
    )

    private fun synchsafe(value: Int) = byteArrayOf(
        (value shr 21 and 0x7F).toByte(), (value shr 14 and 0x7F).toByte(),
        (value shr 7 and 0x7F).toByte(), (value and 0x7F).toByte(),
    )
}
