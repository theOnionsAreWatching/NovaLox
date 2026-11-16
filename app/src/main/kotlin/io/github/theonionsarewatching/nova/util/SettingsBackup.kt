package io.github.theonionsarewatching.nova.util

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.preference.PreferenceManager
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * Saves / restores the app's settings (the default SharedPreferences) as a
 * small JSON file. Value types are kept exactly:
 *
 *   { "format": "nova-settings", "version": 1, "app": "<versionName>",
 *     "prefs": { "<key>": { "t": "b|i|l|f|s|ss", "v": <value> }, ... } }
 *
 * "ss" (string set) stores an array of strings. Unknown types and unknown
 * top-level names are skipped by the reader, so newer files stay readable.
 *
 * Per-install / transient keys are NOT saved (first-run flags, update-check
 * state, learned own numbers, retry queues, drafts and backgrounds tied to
 * conversation ids that change on restore, paths into app storage).
 */
object SettingsBackup {

    const val FORMAT = "nova-settings"
    /** 2 = Resize labels shifted (values unchanged); files of
     *  version 1 without a Resize value had the old default, real 1.0 */
    const val VERSION = 2
    /** Name of the copy stored inside a message-backup zip. */
    const val ZIP_ENTRY = "settings.json"

    private val SKIP_KEYS = setOf(
        "import_done", "permissions_asked", "battery_prompt_shown", "was_default_sms",
        "learned_own_numbers", "learned_numbers_reset_v99", "phantoms_purged",
        "snippet_words_fixed", "bg_legacy_normalized", "broadcast_copy_map",
        "auto_retry_ids", "contacts_refreshed_at",
        // update-check bookkeeping (device-specific, never restored)
        "pending_update_dl_id", "pending_update_tag", "last_notified_update_tag",
        "last_update_check", "zoom_labels_v2"
    )

    private fun skip(context: Context, key: String, value: Any?): Boolean {
        if (key in SKIP_KEYS) return true
        if (key.startsWith("draft_")) return true
        // per-conversation backgrounds: conversation ids change on restore
        if (key.startsWith("chat_bg_") && key != "chat_bg_all") return true
        // a picture inside this app's private storage is not in the file
        if (value is String) {
            val own = context.applicationContext.filesDir.absolutePath
            val data = context.applicationContext.applicationInfo.dataDir
            if (value.startsWith(own) || (data != null && value.startsWith(data))) return true
        }
        return false
    }

    private fun sp(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    /** Writes the settings JSON to [out]. Does NOT close [out]. */
    fun write(context: Context, out: OutputStream) {
        val w = JsonWriter(OutputStreamWriter(NonClosing(out), Charsets.UTF_8))
        w.setIndent(" ")
        w.beginObject()
        w.name("format").value(FORMAT)
        w.name("version").value(VERSION.toLong())
        val app = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
        w.name("app").value(app)
        w.name("saved").value(System.currentTimeMillis())
        w.name("prefs").beginObject()
        for ((key, value) in sp(context).all.toSortedMap()) {
            if (value == null || skip(context, key, value)) continue
            when (value) {
                is Boolean -> { w.name(key).beginObject(); w.name("t").value("b"); w.name("v").value(value); w.endObject() }
                is Int -> { w.name(key).beginObject(); w.name("t").value("i"); w.name("v").value(value.toLong()); w.endObject() }
                is Long -> { w.name(key).beginObject(); w.name("t").value("l"); w.name("v").value(value); w.endObject() }
                is Float -> { w.name(key).beginObject(); w.name("t").value("f"); w.name("v").value(value.toDouble()); w.endObject() }
                is String -> { w.name(key).beginObject(); w.name("t").value("s"); w.name("v").value(value); w.endObject() }
                is Set<*> -> {
                    w.name(key).beginObject(); w.name("t").value("ss"); w.name("v").beginArray()
                    for (item in value) if (item is String) w.value(item)
                    w.endArray(); w.endObject()
                }
                else -> {}
            }
        }
        w.endObject()
        w.endObject()
        w.flush()
    }

    /** Parsed settings, ready to apply. */
    class Parsed(val values: Map<String, Any>, val version: Int = VERSION)

    /** Reads a settings JSON. Returns null when it is not a settings file.
     *  Does NOT close [input]. */
    fun read(input: InputStream): Parsed? {
        val r = JsonReader(InputStreamReader(NonClosingIn(input), Charsets.UTF_8))
        val values = LinkedHashMap<String, Any>()
        var format = ""
        var version = 1
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "format" -> format = r.nextString()
                "version" -> version = try { r.nextInt() } catch (_: Exception) { r.skipValue(); 1 }
                "prefs" -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        val key = r.nextName()
                        var type = ""
                        var value: Any? = null
                        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                        r.beginObject()
                        // "t" is written before "v"; if a hand-edited file has
                        // them the other way round, the raw value is kept and
                        // converted below
                        var raw: Any? = null
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "t" -> type = r.nextString()
                                "v" -> raw = when (r.peek()) {
                                    JsonToken.BEGIN_ARRAY -> {
                                        val set = LinkedHashSet<String>()
                                        r.beginArray()
                                        while (r.hasNext()) {
                                            if (r.peek() == JsonToken.STRING) set.add(r.nextString())
                                            else r.skipValue()
                                        }
                                        r.endArray()
                                        set
                                    }
                                    JsonToken.BOOLEAN -> r.nextBoolean()
                                    JsonToken.NUMBER, JsonToken.STRING -> r.nextString()
                                    else -> { r.skipValue(); null }
                                }
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        value = convert(type, raw)
                        if (value != null) values[key] = value
                    }
                    r.endObject()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (format != FORMAT) return null
        // an old file without a Resize value was made with the old default
        // (real 1.0, labelled "110%" now): keep that look on restore
        if (version < 2 && !values.containsKey("app_zoom")) values["app_zoom"] = "1.0"
        // only 80%..120% exist now: clamp/snap a restored size
        values["app_zoom"]?.let { values["app_zoom"] = ZoomSteps.snapZoom(it.toString()) }
        return Parsed(values, version)
    }

    private fun convert(type: String, raw: Any?): Any? = try {
        when (type) {
            "b" -> raw as? Boolean ?: raw?.toString()?.toBooleanStrictOrNull()
            "i" -> raw?.toString()?.toInt()
            "l" -> raw?.toString()?.toLong()
            "f" -> raw?.toString()?.toFloat()
            "s" -> raw?.toString()
            "ss" -> (raw as? Set<*>)?.filterIsInstance<String>()?.toSet()
            else -> null
        }
    } catch (_: Exception) { null }

    /** Applies the values (keys not in the file are left alone). Uses
     *  commit() so the values are on disk before the screen is rebuilt. */
    fun apply(context: Context, parsed: Parsed): Int {
        val e = sp(context).edit()
        var n = 0
        for ((key, v) in parsed.values) {
            if (skip(context, key, v)) continue
            when (v) {
                is Boolean -> e.putBoolean(key, v)
                is Int -> e.putInt(key, v)
                is Long -> e.putLong(key, v)
                is Float -> e.putFloat(key, v)
                is String -> e.putString(key, v)
                is Set<*> -> e.putStringSet(key, v.filterIsInstance<String>().toSet())
                else -> continue
            }
            n++
        }
        e.commit()
        // things that cache settings outside SharedPreferences
        runCatching {
            io.github.theonionsarewatching.nova.notify.NotificationHelper.refreshAllChannels(context)
        }
        runCatching { MmsUserAgent.applyToConfig(context) }
        // restored theme / accent must reach EVERY screen at once
        // (same late-theme bug as at start-up): set the night mode on the main
        // thread and make each open screen rebuild when it is shown again
        val theme = Prefs.get(context).theme
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            io.github.theonionsarewatching.nova.ui.ThemeUtils.bumpThemeGeneration()
            runCatching {
                io.github.theonionsarewatching.nova.ui.ThemeUtils.applyNightModeValue(theme)
            }
        }
        return n
    }

    /** Reads settings from a picked file: a settings .json, or a message
     *  backup .zip that carries a settings.json inside. */
    fun readFromUri(context: Context, uri: Uri): Parsed? {
        val resolver = context.contentResolver
        // peek at the first bytes: "PK" = zip
        val isZip = resolver.openInputStream(uri)?.use { s ->
            val b = ByteArray(2)
            s.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()
        } ?: throw java.io.FileNotFoundException(uri.toString())
        return resolver.openInputStream(uri)?.use { raw ->
            if (!isZip) return@use read(raw.buffered())
            java.util.zip.ZipInputStream(raw.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == ZIP_ENTRY) return@use read(zip)
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                null
            }
        }
    }

    fun fileName(): String = "nova-settings-" + java.text.SimpleDateFormat(
        "yyyyMMdd-HHmm", java.util.Locale.US
    ).format(java.util.Date()) + ".json"

    private class NonClosing(private val inner: OutputStream) : OutputStream() {
        override fun write(b: Int) = inner.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = inner.write(b, off, len)
        override fun flush() = inner.flush()
        override fun close() { inner.flush() }
    }

    private class NonClosingIn(private val inner: InputStream) : InputStream() {
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun close() {}
    }
}
