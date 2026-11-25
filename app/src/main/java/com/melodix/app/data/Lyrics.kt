package com.melodix.app.data

import android.content.Context
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import java.security.MessageDigest

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: String, val raw: String)

/** Разбор LRC: [mm:ss.xx]текст, несколько меток в строке, [offset:±мс], слова <mm:ss.xx> игнорируются. */
object LrcParser {
    private val stamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val wordStamp = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
    private val offsetRe = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val metaRe = Regex("""^\[(ar|ti|al|au|by|re|ve|length|offset|#):.*]$""", RegexOption.IGNORE_CASE)

    fun parse(text: String, source: String): Lyrics? {
        val clean = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (clean.isEmpty()) return null
        val offset = offsetRe.find(clean)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val timed = ArrayList<LyricLine>()
        for (line in clean.lines()) {
            val l = line.trim()
            if (metaRe.matches(l)) continue
            val stamps = stamp.findAll(l).toList()
            if (stamps.isEmpty()) continue
            val body = l.substring(stamps.last().range.last + 1).replace(wordStamp, "").trim()
            for (m in stamps) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                timed += LyricLine((min * 60_000 + sec * 1000 + ms - offset).coerceAtLeast(0), body)
            }
        }
        if (timed.size >= 2) {
            return Lyrics(timed.sortedBy { it.timeMs }, true, source, text)
        }
        val plain = clean.lines().map { it.trim() }.filter { !metaRe.matches(it) }
        return Lyrics(plain.map { LyricLine(-1, it) }, false, source, text)
    }
}

/**
 * Источники текста (по приоритету):
 *  1) текст, добавленный в приложении (вставлен или импортирован .lrc);
 *  2) файл рядом с песней: «Название.lrc» / «Название.txt»;
 *  3) встроенный тег LYRICS (USLT в MP3, LYRICS в FLAC/OGG, ©lyr в M4A) — может содержать LRC-метки.
 */
object LyricsRepository {
    private val cache = HashMap<String, Lyrics?>()

    private fun id(t: Track): String {
        val d = MessageDigest.getInstance("MD5").digest((t.path ?: t.uri.toString()).toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun customFile(ctx: Context, t: Track) = File(File(ctx.filesDir, "lyrics").apply { mkdirs() }, id(t) + ".lrc")

    fun invalidate(t: Track) { synchronized(cache) { cache.remove(t.key) } }

    fun hasCustom(ctx: Context, t: Track) = customFile(ctx, t).exists()

    fun saveCustom(ctx: Context, t: Track, text: String) {
        customFile(ctx, t).writeText(text)
        invalidate(t)
    }

    fun deleteCustom(ctx: Context, t: Track) {
        customFile(ctx, t).delete()
        invalidate(t)
    }

    /** Вызывать не из главного потока. */
    fun load(ctx: Context, t: Track): Lyrics? {
        synchronized(cache) { if (cache.containsKey(t.key)) return cache[t.key] }
        val result = runCatching { loadUncached(ctx, t) }.getOrNull()
        synchronized(cache) { cache[t.key] = result }
        return result
    }

    private fun loadUncached(ctx: Context, t: Track): Lyrics? {
        customFile(ctx, t).takeIf { it.exists() }?.let { f ->
            LrcParser.parse(f.readText(), "Добавлен в Melodix")?.let { return it }
        }
        t.path?.let { p ->
            val base = p.substringBeforeLast('.')
            for (ext in listOf(".lrc", ".LRC", ".txt", ".TXT")) {
                val f = File(base + ext)
                if (runCatching { f.exists() && f.canRead() }.getOrDefault(false)) {
                    LrcParser.parse(f.readText(), "Файл ${f.name}")?.let { return it }
                }
            }
        }
        val embedded = readEmbedded(ctx, t)
        if (!embedded.isNullOrBlank()) return LrcParser.parse(embedded, "Встроен в файл")
        return null
    }

    private fun readEmbedded(ctx: Context, t: Track): String? {
        TagOptionSingleton.getInstance().setAndroid(true)
        val direct = t.path?.let { File(it) }?.takeIf { runCatching { it.canRead() }.getOrDefault(false) }
        if (direct != null) {
            runCatching { return AudioFileIO.read(direct).tag?.getFirst(FieldKey.LYRICS) }
        }
        // Нет прямого доступа к пути — читаем через временную копию
        val tmp = File(ctx.cacheDir, "lyr_${System.nanoTime()}.${t.extension}")
        return try {
            ctx.contentResolver.openInputStream(t.uri)?.use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 16) } } ?: return null
            AudioFileIO.read(tmp).tag?.getFirst(FieldKey.LYRICS)
        } catch (e: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }
}
