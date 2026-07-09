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
    val snippet: String = "",
    val snippetDate: Long = 0,
    val snippetIsMine: Boolean = false,
    val unreadCount: Int = 0,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,          // notification still shown, but silent
    val notifBlocked: Boolean = false,   // no notification at all
    val hidden: Boolean = false,         // not in the list; settings > hidden conversations
    val draft: String = ""
) {
    fun addressList(): List<String> = addresses.split("|").filter { it.isNotBlank() }
    fun nameList(): List<String> = cachedNames.split("|")
    fun displayTitle(): String {
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
    val keyword: String
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

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)
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

    @Query("UPDATE messages SET recipientStatuses = :v WHERE id = :id")
    suspend fun setRecipientStatuses(id: Long, v: String)

    // Delete = move to recycle bin. Lock status is cleared on delete (per app policy).
    @Query("UPDATE messages SET deletedAt = :now, locked = 0 WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long)

    @Query("UPDATE messages SET deletedAt = :now, locked = 0 WHERE convoId = :convoId AND deletedAt IS NULL AND (:includeLocked = 1 OR locked = 0)")
    suspend fun softDeleteThread(convoId: Long, now: Long, includeLocked: Int)

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
}

@Dao
interface KeywordDao {
    @Insert
    suspend fun insert(k: KeywordEntity)

    @Query("SELECT * FROM keywords ORDER BY keyword")
    suspend fun all(): List<KeywordEntity>

    @Query("DELETE FROM keywords WHERE id = :id")
    suspend fun delete(id: Long)
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

@Database(
    entities = [
        ConversationEntity::class, MessageEntity::class, PartEntity::class,
        ElementEntity::class, KeywordEntity::class, ContactNameEntity::class, MessageFts::class
    ],
    version = 2,
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
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
