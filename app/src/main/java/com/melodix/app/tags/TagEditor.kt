package com.melodix.app.tags

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.os.Build
import android.provider.MediaStore
import com.melodix.app.data.Track
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

data class TagData(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val year: String = "",
    val genre: String = "",
    val track: String = "",
    val disc: String = "",
    val composer: String = "",
    val comment: String = "",
    val lyrics: String = "",
    val artwork: ByteArray? = null,
    val artworkChanged: Boolean = false,
    // только чтение
    val format: String = "",
    val bitrate: String = "",
    val sampleRate: String = "",
    val channels: String = "",
    val lossless: Boolean = false,
)

sealed class SaveResult {
    object Ok : SaveResult()
    class NeedPermission(val intentSender: IntentSender) : SaveResult()
    class Error(val message: String) : SaveResult()
}

/**
 * Редактор тегов на базе JAudioTagger: MP3 (ID3v1/v2.3/v2.4), FLAC, OGG Vorbis, Opus,
 * M4A/MP4/AAC/ALAC, WMA, WAV, AIFF, DSF.
 * Работаем с копией в кэше, затем записываем обратно (так работает и с SAF, и с MediaStore).
 */
object TagEditor {
    init {
        TagOptionSingleton.getInstance().setAndroid(true)
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    private fun workCopy(ctx: Context, t: Track): File {
        val f = File(ctx.cacheDir, "tag_${System.nanoTime()}.${t.extension}")
        ctx.contentResolver.openInputStream(t.uri)?.use { i -> f.outputStream().use { i.copyTo(it, 1 shl 16) } }
            ?: error("Не удалось открыть файл")
        return f
    }

    fun read(ctx: Context, t: Track): TagData {
        val f = workCopy(ctx, t)
        try {
            val af = AudioFileIO.read(f)
            val tag = af.tag
            val h = af.audioHeader
            fun g(k: FieldKey) = runCatching { tag?.getFirst(k) ?: "" }.getOrDefault("")
            return TagData(
                title = g(FieldKey.TITLE), artist = g(FieldKey.ARTIST), album = g(FieldKey.ALBUM),
                albumArtist = g(FieldKey.ALBUM_ARTIST), year = g(FieldKey.YEAR), genre = g(FieldKey.GENRE),
                track = g(FieldKey.TRACK), disc = g(FieldKey.DISC_NO), composer = g(FieldKey.COMPOSER),
                comment = g(FieldKey.COMMENT), lyrics = g(FieldKey.LYRICS),
                artwork = runCatching { tag?.firstArtwork?.binaryData }.getOrNull(),
                format = h.format ?: t.extension.uppercase(),
                bitrate = (h.bitRate ?: "?") + " кбит/с",
                sampleRate = (h.sampleRate ?: "?") + " Гц",
                channels = h.channels ?: "",
                lossless = runCatching { h.isLossless }.getOrDefault(false),
            )
        } finally {
            f.delete()
        }
    }

    fun write(ctx: Context, t: Track, d: TagData): SaveResult {
        val f = try { workCopy(ctx, t) } catch (e: Exception) { return SaveResult.Error(e.message ?: "Ошибка чтения") }
        try {
            val af = AudioFileIO.read(f)
            val tag = af.tagOrCreateAndSetDefault
            fun s(k: FieldKey, v: String) {
                if (v.isBlank()) runCatching { tag.deleteField(k) } else tag.setField(k, v.trim())
            }
            s(FieldKey.TITLE, d.title); s(FieldKey.ARTIST, d.artist); s(FieldKey.ALBUM, d.album)
            s(FieldKey.ALBUM_ARTIST, d.albumArtist); s(FieldKey.YEAR, d.year); s(FieldKey.GENRE, d.genre)
            s(FieldKey.TRACK, d.track); s(FieldKey.DISC_NO, d.disc); s(FieldKey.COMPOSER, d.composer)
            s(FieldKey.COMMENT, d.comment); s(FieldKey.LYRICS, d.lyrics)
            if (d.artworkChanged) {
                runCatching { tag.deleteArtworkField() }
                d.artwork?.let { bytes ->
                    val art = ArtworkFactory.getNew()
                    art.setBinaryData(bytes)
                    art.setMimeType(if (bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) "image/png" else "image/jpeg")
                    art.setDescription("")
                    art.setPictureType(3)
                    tag.setField(art)
                }
            }
            af.commit()
        } catch (e: Exception) {
            f.delete()
            return SaveResult.Error("Не удалось записать теги: ${e.message ?: e.javaClass.simpleName}")
        }

        // Запись обратно
        try {
            val path = t.path
            if (path != null && File(path).canWrite()) {
                f.copyTo(File(path), overwrite = true)
            } else {
                ctx.contentResolver.openOutputStream(t.uri, "wt")?.use { out -> f.inputStream().use { it.copyTo(out, 1 shl 16) } }
                    ?: return SaveResult.Error("Нет доступа к файлу")
            }
        } catch (e: SecurityException) {
            f.delete()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && t.key.startsWith("ms:")) {
                val pi = MediaStore.createWriteRequest(ctx.contentResolver, listOf(t.uri))
                return SaveResult.NeedPermission(pi.intentSender)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                return SaveResult.NeedPermission(e.userAction.actionIntent.intentSender)
            }
            return SaveResult.Error("Нет прав на запись. Разрешите «Доступ ко всем файлам» в настройках.")
        } catch (e: Exception) {
            f.delete()
            return SaveResult.Error(e.message ?: "Ошибка записи")
        }
        f.delete()
        t.path?.let { MediaScannerConnection.scanFile(ctx, arrayOf(it), null, null) }
        return SaveResult.Ok
    }
}
