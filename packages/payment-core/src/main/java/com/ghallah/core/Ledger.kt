package com.ghallah.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

data class Record(val id: String, val request: JSONObject, val wire: ByteArray, val state: String,
                  val result: ByteArray?, val peer: String, val delivery: String)

class Ledger(context: Context) : SQLiteOpenHelper(context, "gnpp.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE transactions(
            id TEXT PRIMARY KEY, nonce TEXT NOT NULL UNIQUE, request_payload TEXT NOT NULL,
            wire BLOB NOT NULL, request_hash TEXT NOT NULL, status TEXT NOT NULL, result_payload BLOB,
            peer TEXT NOT NULL, delivery_status TEXT NOT NULL, updated_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE transaction_events(
            id INTEGER PRIMARY KEY, transaction_id TEXT NOT NULL, previous_status TEXT,
            new_status TEXT NOT NULL, created_at INTEGER NOT NULL)""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("No migration from schema $oldVersion to $newVersion")
    }

    private fun <T> transaction(action: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            action(db).also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    private fun event(db: SQLiteDatabase, id: String, old: String?, state: String) {
        db.execSQL("INSERT INTO transaction_events(transaction_id,previous_status,new_status,created_at) VALUES(?,?,?,?)",
            arrayOf(id, old, state, System.currentTimeMillis()))
    }

    fun save(request: JSONObject, wire: ByteArray, state: String, peer: String): Record = transaction { db ->
        val id = request.getString("transaction_id")
        val existing = find(id)
        if (existing != null) {
            require(existing.wire.contentEquals(wire) && existing.peer == peer)
            existing
        } else {
            require(!Payment.expired(request))
            db.insertOrThrow("transactions", null, ContentValues().apply {
                put("id", id)
                put("nonce", request.getString("nonce"))
                put("request_payload", request.toString())
                put("wire", wire)
                put("request_hash", Identity.encode(Protocol.hash(wire)))
                put("status", state)
                put("peer", peer)
                put("delivery_status", "NOT_AVAILABLE")
                put("updated_at", System.currentTimeMillis())
            })
            event(db, id, null, state)
            checkNotNull(find(id))
        }
    }

    fun find(id: String): Record? = read("id = ?", arrayOf(id)).firstOrNull()
    fun all(): List<Record> = read(null, null)

    private fun read(where: String?, args: Array<String>?): List<Record> =
        readableDatabase.query("transactions",
            arrayOf("id", "request_payload", "wire", "status", "result_payload", "peer", "delivery_status"),
            where, args, null, null, "updated_at DESC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(Record(cursor.getString(0), JSONObject(cursor.getString(1)),
                    cursor.getBlob(2), cursor.getString(3), cursor.getBlob(4), cursor.getString(5), cursor.getString(6)))
            }
        }

    fun received(id: String) = transaction { db ->
        val old = checkNotNull(find(id))
        if (old.state == Payment.waiting) change(db, old, Payment.pending, null, "NOT_AVAILABLE")
    }

    fun decide(id: String, state: String, identity: Identity): Record = transaction { db ->
        require(state in Payment.decisions)
        val old = checkNotNull(find(id))
        if (old.state !in Payment.decisions) {
            val timestamp = java.time.Instant.now()
            val expired = Payment.expired(old.request, timestamp)
            require(state != "EXPIRED" || expired)
            val actual = if (expired) "EXPIRED" else state
            change(db, old, actual, identity.seal(Payment.result(old.request, actual, timestamp)), "PENDING_DELIVERY")
        }
        checkNotNull(find(id))
    }

    fun recordResult(id: String, packet: ByteArray, identity: Identity): Record = transaction { db ->
        val old = checkNotNull(find(id))
        val result = identity.open(packet, old.peer)
        val state = Payment.validateResult(old.request, result)
        if (old.state in Payment.decisions) {
            require(old.result?.contentEquals(packet) == true)
        } else {
            change(db, old, state, packet, "DELIVERED")
        }
        checkNotNull(find(id))
    }

    private fun change(db: SQLiteDatabase, old: Record, state: String, result: ByteArray?, delivery: String) {
        db.execSQL("UPDATE transactions SET status=?, result_payload=?, delivery_status=?, updated_at=? WHERE id=?",
            arrayOf(state, result, delivery, System.currentTimeMillis(), old.id))
        event(db, old.id, old.state, state)
    }

    fun acknowledged(id: String) = transaction { db ->
        val old = checkNotNull(find(id))
        require(old.result != null)
        if (old.delivery != "ACKNOWLEDGED") {
            db.execSQL("UPDATE transactions SET delivery_status=? WHERE id=?", arrayOf("ACKNOWLEDGED", id))
            event(db, id, old.state, "ACKNOWLEDGED")
        }
    }
}
