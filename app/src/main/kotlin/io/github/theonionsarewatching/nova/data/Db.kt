package io.github.theonionsarewatching.nova.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update

// ============================== Message status ==============================
object MsgStatus {
    const val RECEIVED = 0
    const val SENDING = 1
    const val SENT = 2
    const val DELIVERED = 3
    const val FAILED = 4
    const val CANCELED = 5
    const val SCHEDULED = 6
    /** the recipient's phone reported the MMS as read (read-orig-ind) */
    const val READ_BY_RECIPIENT = 7
}

object GroupMode {
    const val BROADCAST = 0
    const val GROUP_MMS = 1
}

object ElementType {
    const val PHONE = 0
    const val EMAIL = 1
    const val URL = 2
    const val ADDRESS = 3
}

// ============================== Entities ==============================

@Entity(tableName = "conversations", indices = [Index(value = ["convoKey"], unique = true)])
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // stable identity: sorted normalized addresses joined with ","
    val convoKey: String,
    // display addresses joined with "|"
    val addresses: String,
    // cached contact display names joined with "|" ("" where unresolved)
    val cachedNames: String = "",
    // cached contact photo URI for 1:1 conversations ("" when none)
    val cachedPhotoUri: String = "",
    val isGroup: Boolean = false,
    val groupMode: Int = GroupMode.BROADCAST,
    val customName: String = "",
    val snippet: String = "",
    val snippetDate: Long = 0,
    val snippetIsMine: Boolean = false,
    val unreadCount: Int = 0,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,          // notification still shown, but silent
    val notifBlocked: Boolean = false,   // no notification at all
    val hidden: Boolean = false,         // not in the list; settings > hidden conversations
    val draft: String = "",
    // per-conversation notification sound URI ("" = app default, "silent" = no sound)
    val customTone: String = "",
    // 0 = follow app setting, 1 = vibrate on, 2 = vibrate off
    val vibrateMode: Int = 0
) {
    fun addressList(): List<String> = addresses.split("|").filter { it.isNotBlank() }
    fun nameList(): List<String> = cachedNames.split("|")
    fun displayTitle(): String {
        if (customName.isNotBlank()) return customName
        val addrs = addressList()
        val names = nameList()
        val parts = addrs.mapIndexed { i, a -> names.getOrNull(i)?.takeIf { it.isNotBlank() } ?: a }
        return parts.joinToString(", ")
    }
}

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["convoId", "date"]),
        Index(value = ["deletedAt"]),
        Index(value = ["status"]),
        Index(value = ["telephonyId", "telephonyIsMms"])
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val convoId: Long,
    // sender address for received; joined recipients for sent group messages
    val address: String,
    val body: String,
    val date: Long,
    val isMine: Boolean,
    val status: Int,
    val read: Boolean = true,
    val locked: Boolean = false,
    val deletedAt: Long? = null,
    val isMms: Boolean = false,
    val subId: Int = -1,
    val scheduledAt: Long? = null,
    val blockedByKeyword: Boolean = false,
    // JSON-ish "addr=status,addr=status" per-recipient status for broadcast sends
    val recipientStatuses: String = "",
    /** raw trail of delivery reports received for this message (diagnostics) */
    val deliveryDebug: String = "",
    val elementsExtracted: Boolean = false,
    // reconciliation link to the system telephony provider
    val telephonyId: Long? = null,
    val telephonyIsMms: Boolean = false
)

@Entity(tableName = "parts", indices = [Index(value = ["messageId"])])
data class PartEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val mimeType: String,
    val filePath: String,
    val fileName: String,
    val size: Long = 0
) {
    fun isImage() = mimeType.startsWith("image/")
    fun isVideo() = mimeType.startsWith("video/")
    fun isAudio() = mimeType.startsWith("audio/")
    fun isVCard() = mimeType.contains("vcard") || fileName.endsWith(".vcf", true)
}

@Entity(tableName = "elements", indices = [Index(value = ["messageId"])])
data class ElementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val type: Int,
    val value: String
)

@Entity(tableName = "keywords")
data class KeywordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    /** 0 = block from everyone (default); 1 = block only senders NOT in
     *  contacts; 2 = block everyone EXCEPT the listed numbers; 3 = block ONLY
     *  the listed numbers. */
    val mode: Int = 0,
    /** comma-separated numbers/emails for modes 2 and 3 */
    val numbers: String = "",
    val caseSensitive: Boolean = false
)

@Entity(tableName = "contact_names")
data class ContactNameEntity(
    @PrimaryKey val normalizedKey: String,
    val name: String,
    val updatedAt: Long
)

@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "messages_fts")
data class MessageFts(
    @ColumnInfo(name = "body") val body: String
)

// ============================== DAOs ==============================

data class BinRow(
    val id: Long, val convoId: Long, val body: String, val date: Long,
    val deletedAt: Long, val isMine: Boolean, val isMms: Boolean, val address: String
)

data class SearchRow(
    val id: Long, val convoId: Long, val body: String, val date: Long, val isMine: Boolean
)

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(c: ConversationEntity): Long

    @Update
    suspend fun update(c: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun byId(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE convoKey = :key")
    suspend fun byKey(key: String): ConversationEntity?

    // Visible = not hidden/archived and has at least one live message or a draft
    @Query(
        """SELECT c.* FROM conversations c WHERE c.hidden = 0 AND c.archived = 0 AND
           (c.draft != '' OR EXISTS(SELECT 1 FROM messages m WHERE m.convoId = c.id AND m.deletedAt IS NULL AND m.blockedByKeyword = 0))"""
    )
    suspend fun visible(): List<ConversationEntity>

    @Query(
        """SELECT c.* FROM conversations c WHERE c.hidden = 0 AND c.archived = 1 AND
           EXISTS(SELECT 1 FROM messages m WHERE m.convoId = c.id AND m.deletedAt IS NULL AND m.blockedByKeyword = 0)"""
    )
    suspend fun archivedList(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE hidden = 1")
    suspend fun hiddenList(): List<ConversationEntity>

    @Query("SELECT * FROM conversations")
    suspend fun all(): List<ConversationEntity>

    @Query("UPDATE conversations SET snippet = :snippet, snippetDate = :date, snippetIsMine = :mine, unreadCount = :unread WHERE id = :id")
    suspend fun updateSummary(id: Long, snippet: String, date: Long, mine: Boolean, unread: Int)

    @Query("UPDATE conversations SET draft = :draft WHERE id = :id")
    suspend fun setDraft(id: Long, draft: String)

    @Query("UPDATE conversations SET pinned = :v WHERE id = :id")
    suspend fun setPinned(id: Long, v: Boolean)

    @Query("UPDATE conversations SET archived = :v WHERE id = :id")
    suspend fun setArchived(id: Long, v: Boolean)

    @Query("UPDATE conversations SET customName = :name WHERE id = :id")
    suspend fun setCustomName(id: Long, name: String)

    @Query("UPDATE conversations SET muted = :v WHERE id = :id")
    suspend fun setMuted(id: Long, v: Boolean)

    @Query("UPDATE conversations SET notifBlocked = :v WHERE id = :id")
    suspend fun setNotifBlocked(id: Long, v: Boolean)

    @Query("UPDATE conversations SET hidden = :v WHERE id = :id")
    suspend fun setHidden(id: Long, v: Boolean)

    @Query("UPDATE conversations SET groupMode = :mode WHERE id = :id")
    suspend fun setGroupMode(id: Long, mode: Int)

    @Query("UPDATE conversations SET cachedNames = :names, cachedPhotoUri = :photo WHERE id = :id")
    suspend fun setCachedNames(id: Long, names: String, photo: String)

    @Query("UPDATE conversations SET customTone = :tone WHERE id = :id")
    suspend fun setCustomTone(id: Long, tone: String)

    @Query(
        """UPDATE conversations SET pinned = :pinned, archived = :archived, muted = :muted,
           notifBlocked = :notifBlocked, hidden = :hidden, draft = :draft,
           customTone = :customTone, vibrateMode = :vibrateMode, groupMode = :groupMode
           WHERE id = :id"""
    )
    suspend fun applyRestoredSettings(
        id: Long, pinned: Boolean, archived: Boolean, muted: Boolean, notifBlocked: Boolean,
        hidden: Boolean, draft: String, customTone: String, vibrateMode: Int, groupMode: Int
    )

    @Query("UPDATE conversations SET vibrateMode = :mode WHERE id = :id")
    suspend fun setVibrateMode(id: Long, mode: Int)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(m: MessageEntity): Long

    @Update
    suspend fun update(m: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: Long): MessageEntity?

    // Newest page: DESC then reversed by caller
    @Query(
        """SELECT * FROM messages WHERE convoId = :convoId AND deletedAt IS NULL AND blockedByKeyword = 0
           ORDER BY date DESC, id DESC LIMIT :limit"""
    )
    suspend fun latest(convoId: Long, limit: Int): List<MessageEntity>

    @Query(
        """SELECT * FROM messages WHERE convoId = :convoId AND deletedAt IS NULL AND blockedByKeyword = 0
           AND (date < :beforeDate OR (date = :beforeDate AND id < :beforeId))
           ORDER BY date DESC, id DESC LIMIT :limit"""
    )
    suspend fun olderThan(convoId: Long, beforeDate: Long, beforeId: Long, limit: Int): List<MessageEntity>

    @Query(
        """SELECT * FROM messages WHERE convoId = :convoId AND deletedAt IS NULL AND blockedByKeyword = 0
           AND (date > :afterDate OR (date = :afterDate AND id > :afterId))
           ORDER BY date ASC, id ASC LIMIT :limit"""
    )
    suspend fun newerThan(convoId: Long, afterDate: Long, afterId: Long, limit: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE convoId = :convoId AND deletedAt IS NULL AND blockedByKeyword = 0 AND read = 0")
    suspend fun unreadCount(convoId: Long): Int

    @Query("SELECT COUNT(*) FROM messages WHERE deletedAt IS NULL AND blockedByKeyword = 0 AND read = 0")
    suspend fun totalUnread(): Int

    @Query("SELECT COUNT(DISTINCT convoId) FROM messages WHERE deletedAt IS NULL AND blockedByKeyword = 0 AND read = 0")
    suspend fun unreadConvoCount(): Int

    @Query("UPDATE messages SET read = 1 WHERE read = 0")
    suspend fun markAllRead()

    @Query("SELECT * FROM messages WHERE isMms = 1 AND body = :placeholder AND deletedAt IS NULL")
    suspend fun placeholderMms(placeholder: String): List<MessageEntity>

    @Query(
        """UPDATE messages SET read = 1 WHERE convoId = :convoId AND read = 0
           AND (date < :date OR (date = :date AND id <= :id))"""
    )
    suspend fun markReadUpTo(convoId: Long, date: Long, id: Long): Int

    @Query(
        """SELECT id FROM messages WHERE convoId = :convoId AND read = 0
           AND deletedAt IS NULL AND blockedByKeyword = 0 ORDER BY date, id LIMIT 1"""
    )
    suspend fun firstUnreadId(convoId: Long): Long?

    @Query(
        """SELECT * FROM messages WHERE convoId = :convoId AND deletedAt IS NULL AND blockedByKeyword = 0
           ORDER BY date DESC, id DESC LIMIT 1"""
    )
    suspend fun newest(convoId: Long): MessageEntity?

    @Query("UPDATE messages SET read = 1 WHERE convoId = :convoId AND read = 0")
    suspend fun markThreadRead(convoId: Long)

    @Query("UPDATE messages SET read = :read WHERE id = :id")
    suspend fun setRead(id: Long, read: Boolean)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: Int)

    @Query("UPDATE messages SET locked = :locked WHERE id = :id")
    suspend fun setLocked(id: Long, locked: Boolean)

    @Query(
        """SELECT * FROM messages WHERE isMine = 1 AND isMms = 0 AND deletedAt IS NULL
           AND date > :since ORDER BY date DESC LIMIT 20"""
    )
    suspend fun recentMineSms(since: Long): List<MessageEntity>

    @Query("UPDATE messages SET deliveryDebug = deliveryDebug || :line WHERE id = :id")
    suspend fun appendDeliveryDebug(id: Long, line: String)

    @Query("UPDATE messages SET recipientStatuses = :v WHERE id = :id")
    suspend fun setRecipientStatuses(id: Long, v: String)

    // Delete = move to recycle bin. Lock status is cleared on delete (per app policy).
    @Query("UPDATE messages SET deletedAt = :now, locked = 0 WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long)

    @Query("UPDATE messages SET deletedAt = :now, locked = 0 WHERE convoId = :convoId AND deletedAt IS NULL AND (:includeLocked = 1 OR locked = 0)")
    suspend fun softDeleteThread(convoId: Long, now: Long, includeLocked: Int)

    // the rows softDeleteThread will hit — read first so their telephony
    // backing rows can be purged too (stops re-import resurrection)
    @Query("SELECT * FROM messages WHERE convoId = :convoId AND deletedAt IS NULL AND (:includeLocked = 1 OR locked = 0)")
    suspend fun threadMessagesForDelete(convoId: Long, includeLocked: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE convoId = :convoId AND deletedAt IS NULL AND blockedByKeyword = 0")
    suspend fun countInConvo(convoId: Long): Int

    // oldest unlocked live messages in a conversation beyond the newest `keep`,
    // for auto-delete. Locked messages are never returned.
    @Query("""SELECT * FROM messages WHERE convoId = :convoId AND deletedAt IS NULL
              AND locked = 0
              ORDER BY date DESC LIMIT -1 OFFSET :keep""")
    suspend fun messagesBeyondKeep(convoId: Long, keep: Int): List<MessageEntity>

    @Query(
        """SELECT COUNT(*) FROM messages WHERE convoId = :convoId AND deletedAt IS NULL
           AND blockedByKeyword = 0 AND (date < :beforeDate OR (date = :beforeDate AND id < :beforeId))"""
    )
    suspend fun countOlderThan(convoId: Long, beforeDate: Long, beforeId: Long): Int

    @Query(
        """SELECT COUNT(*) FROM messages WHERE convoId = :convoId AND deletedAt IS NULL
           AND blockedByKeyword = 0 AND (date > :afterDate OR (date = :afterDate AND id > :afterId))"""
    )
    suspend fun countNewerThan(convoId: Long, afterDate: Long, afterId: Long): Int

    @Query("SELECT DISTINCT convoId FROM messages WHERE status = 6 AND deletedAt IS NULL")
    suspend fun convoIdsWithScheduled(): List<Long>

    @Query("SELECT COUNT(*) FROM messages WHERE convoId = :convoId AND deletedAt IS NULL AND locked = 1")
    suspend fun lockedCount(convoId: Long): Int

    @Query("UPDATE messages SET deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun hardDelete(id: Long)

    @Query("SELECT id FROM messages WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun expiredBinIds(cutoff: Long): List<Long>

    @Query(
        """SELECT m.id AS id, m.convoId AS convoId, m.body AS body, m.date AS date, m.deletedAt AS deletedAt,
                  m.isMine AS isMine, m.isMms AS isMms, m.address AS address
           FROM messages m WHERE m.deletedAt IS NOT NULL ORDER BY m.deletedAt DESC"""
    )
    suspend fun binList(): List<BinRow>

    @Query(
        """SELECT m.id AS id, m.convoId AS convoId, m.body AS body, m.date AS date, m.deletedAt AS deletedAt,
                  m.isMine AS isMine, m.isMms AS isMms, m.address AS address
           FROM messages m WHERE m.blockedByKeyword = 1 AND m.deletedAt IS NULL ORDER BY m.date DESC"""
    )
    suspend fun blockedList(): List<BinRow>

    @Query("UPDATE messages SET blockedByKeyword = 0 WHERE id = :id")
    suspend fun unblock(id: Long)

    @Query("SELECT * FROM messages WHERE status = 6 AND deletedAt IS NULL")
    suspend fun scheduled(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE elementsExtracted = 0 AND deletedAt IS NULL LIMIT :limit")
    suspend fun needingExtraction(limit: Int): List<MessageEntity>

    @Query("UPDATE messages SET elementsExtracted = 1 WHERE id = :id")
    suspend fun markExtracted(id: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE elementsExtracted = 0 AND deletedAt IS NULL")
    suspend fun extractionBacklog(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE telephonyId = :tId AND telephonyIsMms = :isMms)")
    suspend fun existsByTelephonyId(tId: Long, isMms: Boolean): Boolean

    @Query("SELECT * FROM messages WHERE telephonyId = :tId AND telephonyIsMms = 1 LIMIT 1")
    suspend fun byTelephonyMms(tId: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE telephonyId = :tId AND telephonyIsMms = :isMms LIMIT 1")
    suspend fun byTelephonyId(tId: Long, isMms: Boolean): MessageEntity?

    // download stubs carry their transaction id inside the encoded body; a
    // downloaded copy matches its stub on that substring, whatever telephony
    // row each is linked to
    @Query("SELECT * FROM messages WHERE deletedAt IS NULL AND body LIKE :pattern")
    suspend fun messagesWithBodyLike(pattern: String): List<MessageEntity>

    @Query(
        """SELECT * FROM messages WHERE convoId = :convoId AND isMine = 1 AND isMms = :isMms
           AND telephonyId IS NULL AND deletedAt IS NULL AND date BETWEEN :lo AND :hi"""
    )
    suspend fun unlinkedOutgoing(convoId: Long, isMms: Boolean, lo: Long, hi: Long): List<MessageEntity>

    @Query("UPDATE messages SET telephonyId = :tId, telephonyIsMms = :isMms WHERE id = :id")
    suspend fun setTelephonyId(id: Long, tId: Long, isMms: Boolean)

    @Query("UPDATE messages SET telephonyId = NULL WHERE id = :id")
    suspend fun clearTelephonyId(id: Long)

    @Query("DELETE FROM messages WHERE telephonyId = :tId AND telephonyIsMms = :isMms AND id != :keepId")
    suspend fun deleteOthersByTelephony(tId: Long, isMms: Boolean, keepId: Long)

    @Query(
        """DELETE FROM messages WHERE convoId = :convoId AND isMine = :isMine
           AND body = :body AND date BETWEEN :lo AND :hi AND id != :keepId
           AND deletedAt IS NULL"""
    )
    suspend fun deleteTwins(
        convoId: Long, isMine: Boolean, body: String, lo: Long, hi: Long, keepId: Long
    )

    @Query("UPDATE messages SET date = :date WHERE id = :id")
    suspend fun setDate(id: Long, date: Long)

    @Query("UPDATE messages SET body = :body WHERE id = :id")
    suspend fun updateBody(id: Long, body: String)

    @Query(
        """SELECT EXISTS(SELECT 1 FROM messages WHERE convoId = :convoId AND isMine = :mine
           AND body = :body AND date BETWEEN :from AND :to)"""
    )
    suspend fun existsSimilar(convoId: Long, mine: Boolean, body: String, from: Long, to: Long): Boolean

    @Query(
        """SELECT m.id AS id, m.convoId AS convoId, m.body AS body, m.date AS date, m.isMine AS isMine
           FROM messages m JOIN messages_fts f ON m.id = f.rowid
           WHERE messages_fts MATCH :query AND m.deletedAt IS NULL AND m.blockedByKeyword = 0
           ORDER BY m.date DESC LIMIT 100"""
    )
    suspend fun search(query: String): List<SearchRow>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    @Query("SELECT * FROM messages")
    suspend fun allMessages(): List<MessageEntity>

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Dao
interface PartDao {
    @Insert
    suspend fun insert(p: PartEntity): Long

    @Query("SELECT * FROM parts WHERE messageId = :messageId")
    suspend fun byMessage(messageId: Long): List<PartEntity>

    @Query("SELECT * FROM parts WHERE messageId IN (:ids)")
    suspend fun byMessages(ids: List<Long>): List<PartEntity>

    // visual media only, newest first (viewer: right = older, left = newer)
    @Query(
        """SELECT p.* FROM parts p JOIN messages m ON p.messageId = m.id
           WHERE m.convoId = :convoId AND m.deletedAt IS NULL
           AND (p.mimeType LIKE 'image/%' OR p.mimeType LIKE 'video/%')
           ORDER BY m.date DESC, p.id DESC"""
    )
    suspend fun mediaForConvo(convoId: Long): List<PartEntity>

    @Query("DELETE FROM parts WHERE messageId = :messageId")
    suspend fun deleteByMessage(messageId: Long)

    @Query("SELECT * FROM parts")
    suspend fun allParts(): List<PartEntity>

    @Query("DELETE FROM parts")
    suspend fun deleteAll()
}

@Dao
interface ElementDao {
    @Insert
    suspend fun insertAll(items: List<ElementEntity>)

    @Query("SELECT * FROM elements WHERE messageId = :messageId")
    suspend fun byMessage(messageId: Long): List<ElementEntity>

    @Query("SELECT * FROM elements WHERE messageId IN (:ids)")
    suspend fun byMessages(ids: List<Long>): List<ElementEntity>

    @Query("DELETE FROM elements WHERE messageId = :messageId")
    suspend fun deleteByMessage(messageId: Long)

    @Query("SELECT * FROM elements")
    suspend fun allElements(): List<ElementEntity>

    @Query("DELETE FROM elements")
    suspend fun deleteAll()
}

@Dao
interface KeywordDao {
    @Insert
    suspend fun insert(k: KeywordEntity)

    @androidx.room.Update
    suspend fun update(k: KeywordEntity)

    @Query("SELECT * FROM keywords ORDER BY keyword")
    suspend fun all(): List<KeywordEntity>

    @Query("DELETE FROM keywords WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM keywords")
    suspend fun deleteAll()
}

@Dao
interface ContactNameDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: ContactNameEntity)

    @Query("SELECT * FROM contact_names WHERE normalizedKey = :key")
    suspend fun byKey(key: String): ContactNameEntity?

    @Query("DELETE FROM contact_names")
    suspend fun clear()
}

// ============================== Database ==============================

val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN customName TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE keywords ADD COLUMN mode INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE keywords ADD COLUMN numbers TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE keywords ADD COLUMN caseSensitive INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN deliveryDebug TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN customTone TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE conversations ADD COLUMN vibrateMode INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [
        ConversationEntity::class, MessageEntity::class, PartEntity::class,
        ElementEntity::class, KeywordEntity::class, ContactNameEntity::class, MessageFts::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun parts(): PartDao
    abstract fun elements(): ElementDao
    abstract fun keywords(): KeywordDao
    abstract fun contactNames(): ContactNameDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "dsms.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
