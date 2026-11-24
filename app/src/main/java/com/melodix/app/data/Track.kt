package com.melodix.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

data class Track(
    val key: String,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val path: String?,
    val mime: String,
    val size: Long,
    val dateAdded: Long,
    val displayName: String,
) {
    val folder: String get() = path?.let { File(it).parentFile?.name } ?: "Другое"
    val extension: String
        get() = displayName.substringAfterLast('.', "").lowercase().ifEmpty {
            when {
                mime.contains("mpeg") -> "mp3"
                mime.contains("flac") -> "flac"
                mime.contains("ogg") -> "ogg"
                mime.contains("wav") -> "wav"
                mime.contains("mp4") || mime.contains("aac") -> "m4a"
                else -> "bin"
            }
        }
}

object MusicRepository {

    fun load(context: Context): List<Track> {
        val list = ArrayList<Track>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        runCatching {
            context.contentResolver.query(collection, projection, null, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val iDate = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val dur = c.getLong(iDur)
                    if (dur in 1..2999) continue // рингтоны/звуки системы
                    val name = c.getString(iName) ?: ""
                    val artist = c.getString(iArtist).let { if (it.isNullOrBlank() || it == "<unknown>") "Неизвестный исполнитель" else it }
                    list += Track(
                        key = "ms:$id",
                        uri = ContentUris.withAppendedId(collection, id),
                        title = c.getString(iTitle)?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
                        artist = artist,
                        album = c.getString(iAlbum)?.takeIf { it.isNotBlank() } ?: "Без альбома",
                        albumId = c.getLong(iAlbumId),
                        durationMs = dur,
                        path = c.getString(iData),
                        mime = c.getString(iMime) ?: "audio/*",
                        size = c.getLong(iSize),
                        dateAdded = c.getLong(iDate),
                        displayName = name,
                    )
                }
            }
        }
        return list
    }

    /** Трек из произвольного файла (открыт через проводник/«Открыть файл»). */
    fun fromUri(context: Context, uri: Uri): Track {
        var name = uri.lastPathSegment ?: "audio"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    size = c.getLong(1)
                }
            }
        }
        var title = name.substringBeforeLast('.')
        var artist = "Неизвестный исполнитель"
        var album = ""
        var duration = 0L
        runCatching {
            val r = android.media.MediaMetadataRetriever()
            r.setDataSource(context, uri)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { album = it }
            duration = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.release()
        }
        val path = if (uri.scheme == "file") uri.path else null
        return Track(
            key = "ext:" + uri.toString().hashCode().toUInt().toString(16),
            uri = uri, title = title, artist = artist, album = album, albumId = -1,
            durationMs = duration, path = path, mime = context.contentResolver.getType(uri) ?: "audio/*",
            size = size, dateAdded = System.currentTimeMillis() / 1000, displayName = name,
        )
    }
}
