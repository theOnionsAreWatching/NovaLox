package io.github.theonionsarewatching.nova.data

import android.content.Context
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.util.Prefs

/**
 * "Note to self" — a private notes chat that sends NOTHING.
 *
 * - One conversation with the reserved key [KEY] (never a phone number, so
 *   no incoming SMS/MMS and no own-number pairing can ever land in it).
 * - Send only stores the text in the app's own database with status
 *   [MsgStatus.NOTE] (no "Sent" label, never retried, never written to the
 *   system SMS store). Notes are saved as read: no notification, no badge.
 * - Settings > Messages > "Notes chat" switch (key "notes_chat", default OFF).
 *   Off hides the chat everywhere; the notes stay in the database.
 * - Attachments (pictures, video, audio, files, contacts) are kept too, in
 *   their own folder parts/notes/ (inside the shared "parts" FileProvider
 *   path, so Open with / Save work; the "wipe parts" steps skip folders).
 *   Never sent, never shrunk, no MMS size limit.
 * - Kept through backup / restore and "re-import all" (see BackupHelper and
 *   Repo.reimportAll).
 */
object NotesChat {
    const val KEY = "dsms:notes"

    fun isNotes(c: ConversationEntity?): Boolean = c?.convoKey == KEY

    fun enabled(context: Context): Boolean = Prefs.get(context).notesEnabled

    /** The notes chat, created on first use. */
    suspend fun ensure(context: Context, db: AppDb): ConversationEntity {
        db.conversations().byKey(KEY)?.let { return it }
        val c = ConversationEntity(
            convoKey = KEY, addresses = KEY,
            customName = context.getString(R.string.notes_chat_name),
            // an empty notes chat sorts by when it was switched on
            snippetDate = System.currentTimeMillis()
        )
        val id = db.conversations().insert(c)
        return if (id > 0) c.copy(id = id) else db.conversations().byKey(KEY)!!
    }

    /** One attachment to keep with a note. [inPlace]: the file is already in
     *  the notes folder and is used as it is. */
    data class NotePart(
        val mime: String, val name: String, val file: java.io.File,
        val inPlace: Boolean = false, val width: Int = 0, val height: Int = 0
    )
    data class NoteIn(val msg: MessageEntity, val parts: List<NotePart>)

    fun notesDir(context: Context): java.io.File =
        java.io.File(context.filesDir, "parts/notes").apply { mkdirs() }

    /** A new file name in the notes folder that can't clash with MMS files
     *  (backups store parts by file name). */
    private fun newNoteFile(context: Context, name: String): java.io.File {
        val dir = notesDir(context)
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(60).ifBlank { "file" }
        var n = 0
        var f: java.io.File
        do {
            f = java.io.File(dir, "note-${System.currentTimeMillis()}-$n-$safe"); n++
        } while (f.exists())
        return f
    }

    /** Move [src] into the notes folder when it is a loose file of ours (a
     *  fresh pick / recording / restore temp file that nothing else uses),
     *  otherwise copy it. Returns null when it can't be read. */
    private suspend fun adopt(context: Context, db: AppDb, src: java.io.File, name: String, mayMove: Boolean): java.io.File? {
        if (!src.isFile || !src.canRead()) return null
        val dest = newNoteFile(context, name)
        if (mayMove && src.renameTo(dest)) return dest
        return try {
            src.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            dest
        } catch (_: Exception) { runCatching { dest.delete() }; null }
            catch (_: OutOfMemoryError) { runCatching { dest.delete() }; null }
    }

    private suspend fun ownLooseFile(context: Context, db: AppDb, f: java.io.File): Boolean {
        val parts = java.io.File(context.filesDir, "parts").canonicalPath
        val cache = context.cacheDir.canonicalPath
        val parent = f.canonicalFile.parentFile?.path ?: return false
        if (parent != parts && !parent.startsWith(cache)) return false
        return db.parts().countByPath(f.path) == 0 && db.parts().countByPath(f.absolutePath) == 0
    }

    /** Store one note (text and/or attachments) locally. Nothing is sent.
     *  Returns the message id, or null when there was nothing to keep. */
    suspend fun save(
        context: Context, db: AppDb, convoId: Long, text: String,
        attachments: List<Triple<String, String, String>> = emptyList(),
        date: Long = System.currentTimeMillis()
    ): Long? {
        val kept = ArrayList<NotePart>()
        for ((path, mime, name) in attachments) {
            val src = java.io.File(path)
            val f = adopt(context, db, src, name, ownLooseFile(context, db, src)) ?: continue
            kept.add(NotePart(mime, name, f, inPlace = true))
        }
        if (text.isBlank() && kept.isEmpty()) return null
        return insertNote(context, db, convoId, MessageEntity(
            convoId = convoId, address = "", body = text, date = date,
            isMine = true, status = MsgStatus.NOTE, read = true
        ), kept)
    }

    private suspend fun insertNote(
        context: Context, db: AppDb, convoId: Long, m: MessageEntity, parts: List<NotePart>
    ): Long? {
        val files = parts.mapNotNull { p ->
            val f = if (p.inPlace) p.file.takeIf { it.isFile }
                else adopt(context, db, p.file, p.name, mayMove = true)
            f?.let { p to it }
        }
        if (m.body.isBlank() && files.isEmpty()) return null
        val id = db.messages().insert(m.copy(
            id = 0, convoId = convoId, address = "", isMine = true,
            status = MsgStatus.NOTE, read = true, isMms = files.isNotEmpty(),
            telephonyId = null, telephonyIsMms = false, scheduledAt = null,
            recipientStatuses = "", deliveryDebug = "", blockedByKeyword = false,
            elementsExtracted = false
        ))
        for ((p, f) in files) {
            var w = p.width; var h = p.height
            if (w == 0 && p.mime.startsWith("image/")) try {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.path, o)
                w = o.outWidth.coerceAtLeast(0); h = o.outHeight.coerceAtLeast(0)
            } catch (_: Exception) {}
            db.parts().insert(PartEntity(messageId = id, mimeType = p.mime, filePath = f.absolutePath,
                fileName = p.name, size = f.length(), width = w, height = h))
        }
        return id
    }

    /** Chat list: drop the notes chat when the switch is off; when on, make
     *  sure it is there (also while it is still empty) unless he archived or
     *  hid it. It is NOT pinned — the list's own sort places it by its
     *  latest note like any other chat. */
    suspend fun applyToList(context: Context, db: AppDb, list: List<ConversationEntity>): List<ConversationEntity> {
        val without = list.filter { !isNotes(it) }
        if (!enabled(context)) return without
        val n = ensure(context, db)
        return if (n.archived || n.hidden) without else without + n
    }

    /** Lists other than the main one (archived, hidden, pickers): only drop it when off. */
    fun filterOff(context: Context, list: List<ConversationEntity>): List<ConversationEntity> =
        if (enabled(context)) list else list.filter { !isNotes(it) }

    /** Notes (with their attachment rows) for carrying across a wipe
     *  (re-import / restore). The files themselves stay in parts/notes/. */
    suspend fun snapshot(db: AppDb): Pair<ConversationEntity, List<NoteIn>>? {
        val c = db.conversations().byKey(KEY) ?: return null
        return c to db.messages().allInConvo(c.id).map { m ->
            NoteIn(m, db.parts().byMessage(m.id).map { p ->
                NotePart(p.mimeType, p.fileName, java.io.File(p.filePath), inPlace = true,
                    width = p.width, height = p.height)
            })
        }
    }

    /** Put notes back after a wipe; skips exact repeats (same time + text). */
    suspend fun putBack(
        context: Context, db: AppDb, convo: ConversationEntity?, notes: List<NoteIn>
    ) {
        if (convo == null && notes.isEmpty()) return
        val target = ensure(context, db)
        if (convo != null) {
            if (convo.customName.isNotBlank()) db.conversations().setCustomName(target.id, convo.customName)
            db.conversations().applyRestoredSettings(
                target.id, convo.pinned, convo.archived, false, false,
                convo.hidden, convo.draft, convo.customTone, convo.vibrateMode, convo.groupMode
            )
        }
        fun key(m: MessageEntity, names: List<String>) = "${m.date}|${m.body}|${names.sorted().joinToString(",")}"
        val have = HashSet<String>()
        for (m in db.messages().allInConvo(target.id)) {
            have.add(key(m, db.parts().byMessage(m.id).map { it.fileName }))
        }
        for (n in notes) {
            // an exact repeat (same time, text and attachment names) once
            if (!have.add(key(n.msg, n.parts.map { it.name }))) continue
            try { insertNote(context, db, target.id, n.msg, n.parts) } catch (_: Exception) {}
        }
    }
}
