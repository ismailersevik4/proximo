package fr.proximo.app.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Un contact (téléphone ajouté). status : 1 = accepté, 2 = demande reçue en attente, 0 = demande envoyée. */
data class Contact(val id: String, val name: String, val dhPub: ByteArray, val sigPub: ByteArray, val status: Int, val added: Long, val lastSeen: Long) {
    val accepted get() = status == 1
}

data class Group(val id: String, val name: String, val key: ByteArray, val owner: String, val members: List<String>, val created: Long)

/** Un message affiché dans une discussion. */
data class Message(
    val mid: String, val chat: String, val from: String, val mine: Boolean, val kind: String,
    val body: String, val file: String?, val time: Long, val status: Int, val read: Boolean, val route: String,
    /** Pseudo de l'expéditeur tel qu'il s'est présenté (utile pour « Tout le monde » et les non-contacts). */
    val senderName: String = "",
) {
    companion object {
        const val PENDING = 0; const val RELAYED = 1; const val DELIVERED = 2; const val FAILED = 3
        const val TEXT = "text"; const val PHOTO = "photo"; const val VOICE = "voice"; const val LOC = "loc"; const val SOS = "sos"; const val SYS = "sys"
    }
}

data class ChatSummary(val chat: String, val name: String, val last: Message?, val unread: Int, val isGroup: Boolean)

/** Trame rangée en attente (pas encore remise ou à relayer plus tard). */
data class Outbox(val fid: String, val data: ByteArray, val dest: String, val created: Long, val sentTo: MutableSet<String>, val mine: Boolean)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "proximo.db", null, 2) {
    /** Compteur qui change à chaque écriture : l'interface se rafraîchit en l'observant. */
    val changes = MutableStateFlow(0L)
    private fun bump() { changes.value = changes.value + 1 }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE contacts(id TEXT PRIMARY KEY, name TEXT, dh BLOB, sg BLOB, status INTEGER, added INTEGER, last_seen INTEGER)")
        db.execSQL("CREATE TABLE pending_codes(code TEXT PRIMARY KEY, added INTEGER)")
        db.execSQL("CREATE TABLE groups(id TEXT PRIMARY KEY, name TEXT, key BLOB, owner TEXT, members TEXT, created INTEGER)")
        db.execSQL("CREATE TABLE messages(mid TEXT PRIMARY KEY, chat TEXT, from_id TEXT, mine INTEGER, kind TEXT, body TEXT, file TEXT, time INTEGER, status INTEGER, read INTEGER, route TEXT, sender_name TEXT DEFAULT '')")
        db.execSQL("CREATE INDEX idx_msg_chat ON messages(chat, time)")
        db.execSQL("CREATE TABLE seen(fid TEXT PRIMARY KEY, t INTEGER)")
        db.execSQL("CREATE TABLE outbox(fid TEXT PRIMARY KEY, data BLOB, dest TEXT, created INTEGER, sent_to TEXT, mine INTEGER)")
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) db.execSQL("ALTER TABLE messages ADD COLUMN sender_name TEXT DEFAULT ''")
    }

    // ---------- contacts ----------
    fun contacts(): List<Contact> = q("SELECT * FROM contacts ORDER BY name COLLATE NOCASE") { readContact(it) }
    fun contact(id: String): Contact? = q("SELECT * FROM contacts WHERE id=?", id) { readContact(it) }.firstOrNull()
    fun acceptedContacts(): List<Contact> = contacts().filter { it.accepted }
    private fun readContact(c: Cursor) = Contact(c.getString(0), c.getString(1), c.getBlob(2), c.getBlob(3), c.getInt(4), c.getLong(5), c.getLong(6))

    fun saveContact(card: Card, status: Int) {
        val cv = ContentValues().apply {
            put("id", card.id); put("name", card.name); put("dh", card.dhPub); put("sg", card.sigPub); put("status", status)
            put("added", System.currentTimeMillis()); put("last_seen", 0L)
        }
        val existing = contact(card.id)
        if (existing != null) {
            // On ne rétrograde jamais un contact accepté ; on met à jour pseudo et clés si la carte est plus récente.
            cv.put("status", if (existing.accepted) 1 else status); cv.put("added", existing.added); cv.put("last_seen", existing.lastSeen)
            writableDatabase.update("contacts", cv, "id=?", arrayOf(card.id))
        } else writableDatabase.insert("contacts", null, cv)
        bump()
    }
    fun setContactStatus(id: String, status: Int) { writableDatabase.execSQL("UPDATE contacts SET status=? WHERE id=?", arrayOf(status, id)); bump() }
    fun touchContact(id: String) { writableDatabase.execSQL("UPDATE contacts SET last_seen=? WHERE id=?", arrayOf(System.currentTimeMillis(), id)) }
    fun renameContact(id: String, name: String) { writableDatabase.execSQL("UPDATE contacts SET name=? WHERE id=?", arrayOf(name, id)); bump() }
    fun deleteContact(id: String) {
        writableDatabase.delete("contacts", "id=?", arrayOf(id)); writableDatabase.delete("messages", "chat=?", arrayOf(id)); bump()
    }

    fun addPendingCode(code: String) { writableDatabase.execSQL("INSERT OR REPLACE INTO pending_codes VALUES(?,?)", arrayOf(code, System.currentTimeMillis())); bump() }
    fun pendingCodes(): List<String> = q("SELECT code FROM pending_codes") { it.getString(0) }
    fun removePendingCode(code: String) { writableDatabase.delete("pending_codes", "code=?", arrayOf(code)); bump() }

    // ---------- groupes ----------
    fun groups(): List<Group> = q("SELECT * FROM groups ORDER BY name COLLATE NOCASE") { readGroup(it) }
    fun group(id: String): Group? = q("SELECT * FROM groups WHERE id=?", id) { readGroup(it) }.firstOrNull()
    private fun readGroup(c: Cursor): Group {
        val arr = JSONArray(c.getString(4)); val m = ArrayList<String>(); for (i in 0 until arr.length()) m.add(arr.getString(i))
        return Group(c.getString(0), c.getString(1), c.getBlob(2), c.getString(3), m, c.getLong(5))
    }
    fun saveGroup(g: Group) {
        val cv = ContentValues().apply {
            put("id", g.id); put("name", g.name); put("key", g.key); put("owner", g.owner); put("members", JSONArray(g.members).toString()); put("created", g.created)
        }
        writableDatabase.insertWithOnConflict("groups", null, cv, SQLiteDatabase.CONFLICT_REPLACE); bump()
    }
    fun deleteGroup(id: String) { writableDatabase.delete("groups", "id=?", arrayOf(id)); writableDatabase.delete("messages", "chat=?", arrayOf(id)); bump() }

    // ---------- messages ----------
    fun messages(chat: String, limit: Int = 500): List<Message> =
        q("SELECT * FROM messages WHERE chat=? ORDER BY time DESC LIMIT $limit", chat) { readMsg(it) }.asReversed()
    fun message(mid: String): Message? = q("SELECT * FROM messages WHERE mid=?", mid) { readMsg(it) }.firstOrNull()
    private fun readMsg(c: Cursor) = Message(c.getString(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getString(4), c.getString(5), c.getString(6), c.getLong(7), c.getInt(8), c.getInt(9) == 1, c.getString(10) ?: "", c.getString(11) ?: "")

    fun saveMessage(m: Message) {
        val cv = ContentValues().apply {
            put("mid", m.mid); put("chat", m.chat); put("from_id", m.from); put("mine", if (m.mine) 1 else 0); put("kind", m.kind)
            put("body", m.body); put("file", m.file); put("time", m.time); put("status", m.status); put("read", if (m.read) 1 else 0); put("route", m.route); put("sender_name", m.senderName)
        }
        writableDatabase.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE); bump()
    }
    fun setStatus(mid: String, status: Int, route: String? = null) {
        if (route != null) writableDatabase.execSQL("UPDATE messages SET status=MAX(status,?), route=? WHERE mid=?", arrayOf(status, route, mid))
        else writableDatabase.execSQL("UPDATE messages SET status=MAX(status,?) WHERE mid=?", arrayOf(status, mid))
        bump()
    }
    fun markRead(chat: String) { writableDatabase.execSQL("UPDATE messages SET read=1 WHERE chat=? AND read=0", arrayOf(chat)); bump() }
    fun unreadTotal(): Int = q("SELECT COUNT(*) FROM messages WHERE read=0 AND mine=0") { it.getInt(0) }.firstOrNull() ?: 0
    fun deleteMessage(mid: String) { writableDatabase.delete("messages", "mid=?", arrayOf(mid)); bump() }
    fun clearChat(chat: String) { writableDatabase.delete("messages", "chat=?", arrayOf(chat)); bump() }

    fun chatSummaries(): List<ChatSummary> {
        val out = ArrayList<ChatSummary>()
        fun add(chat: String, name: String, isGroup: Boolean) {
            val last = q("SELECT * FROM messages WHERE chat=? ORDER BY time DESC LIMIT 1", chat) { readMsg(it) }.firstOrNull()
            val unread = q("SELECT COUNT(*) FROM messages WHERE chat=? AND read=0 AND mine=0", chat) { it.getInt(0) }.firstOrNull() ?: 0
            out.add(ChatSummary(chat, name, last, unread, isGroup))
        }
        add(ALL, "Tout le monde", true)
        groups().forEach { add(it.id, it.name, true) }
        contacts().filter { it.status != 0 }.forEach { add(it.id, it.name, false) }
        return out.sortedWith(compareByDescending<ChatSummary> { it.chat == ALL }.thenByDescending { it.last?.time ?: 0L })
    }

    // ---------- dédoublonnage ----------
    fun seen(fid: String): Boolean = q("SELECT 1 FROM seen WHERE fid=?", fid) { 1 }.isNotEmpty()
    fun markSeen(fid: String) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO seen VALUES(?,?)", arrayOf(fid, System.currentTimeMillis()))
    }
    fun purgeOld() {
        val d = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
        writableDatabase.delete("seen", "t<?", arrayOf(d.toString()))
        writableDatabase.delete("outbox", "created<?", arrayOf(d.toString()))
    }

    // ---------- file d'attente ----------
    fun outbox(): List<Outbox> = q("SELECT * FROM outbox ORDER BY created") {
        val set = HashSet<String>(); val arr = JSONArray(it.getString(4)); for (i in 0 until arr.length()) set.add(arr.getString(i))
        Outbox(it.getString(0), it.getBlob(1), it.getString(2), it.getLong(3), set, it.getInt(5) == 1)
    }
    fun outboxCount(): Int = q("SELECT COUNT(*) FROM outbox") { it.getInt(0) }.firstOrNull() ?: 0
    fun saveOutbox(o: Outbox) {
        val cv = ContentValues().apply {
            put("fid", o.fid); put("data", o.data); put("dest", o.dest); put("created", o.created); put("sent_to", JSONArray(o.sentTo.toList()).toString()); put("mine", if (o.mine) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("outbox", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun outboxSentTo(fid: String, sentTo: Set<String>) { writableDatabase.execSQL("UPDATE outbox SET sent_to=? WHERE fid=?", arrayOf(JSONArray(sentTo.toList()).toString(), fid)) }
    fun removeOutbox(fid: String) { writableDatabase.delete("outbox", "fid=?", arrayOf(fid)) }

    // ---------- réglages divers ----------
    fun kv(k: String): String? = q("SELECT v FROM kv WHERE k=?", k) { it.getString(0) }.firstOrNull()
    fun kv(k: String, v: String) { writableDatabase.execSQL("INSERT OR REPLACE INTO kv VALUES(?,?)", arrayOf(k, v)); bump() }

    private fun <T> q(sql: String, vararg args: String, read: (Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(read(c)) }
        return out
    }

    companion object {
        const val ALL = "all"
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) { inst ?: Db(ctx.applicationContext).also { inst = it } }
        fun jsonObj(s: String): JSONObject = try { JSONObject(s) } catch (e: Exception) { JSONObject() }
    }
}
