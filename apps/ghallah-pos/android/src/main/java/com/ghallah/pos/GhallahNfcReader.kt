package com.ghallah.pos

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import com.ghallah.core.Identity
import com.ghallah.core.Ledger
import com.ghallah.core.Protocol
import com.ghallah.core.Record
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import android.util.Log

private class ApduFailure(val status: Int) : IOException("APDU %04X".format(status))

class GhallahNfcReader(
    private val activity: Activity, private val ledger: Ledger, private val identity: Identity,
    private val update: (String) -> Unit
) {
    private val adapter = NfcAdapter.getDefaultAdapter(activity)
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0
    @Volatile private var active: IsoDep? = null
    @Volatile private var cancel = false
    private val occupied = java.util.concurrent.atomic.AtomicBoolean()

    fun start(id: String, cancelRequested: Boolean = false) {
        val hardware = checkNotNull(adapter)
        check(hardware.isEnabled)
        cancel = cancelRequested
        val epoch = ++generation
        hardware.enableReaderMode(activity, { tag ->
            if (generation == epoch && occupied.compareAndSet(false, true)) {
                io.execute {
                    try {
                        exchange(tag, id, epoch)
                    } catch (error: Exception) {
                        Log.w("GhallahNfcReader", "NFC exchange interrupted", error)
                        if (generation == epoch) post("CONNECTION_LOST", epoch)
                    } finally {
                        runCatching { active?.close() }
                        active = null
                        occupied.set(false)
                    }
                }
            }
        }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
    }

    fun stop() {
        generation++
        adapter?.disableReaderMode(activity)
        val connection = active
        if (connection != null) Thread { runCatching { connection.close() } }.start()
    }

    fun close() {
        stop()
        io.execute { ledger.close() }
        io.shutdown()
    }

    private fun post(state: String, epoch: Int) =
        activity.runOnUiThread { if (generation == epoch) update(state) }

    private fun exchange(tag: Tag, id: String, epoch: Int) {
        val nfc = IsoDep.get(tag) ?: return
        active = nfc
        if (generation != epoch) return
        post("CONNECTING", epoch)
        nfc.connect()
        nfc.timeout = 2500

        fun send(ins: Int, data: ByteArray = byteArrayOf(), index: Int = 0, select: Boolean = false): ByteArray {
            if (generation != epoch) throw IOException("Reader stopped")
            val command = Protocol.command(ins, data, index, select)
            check(command.size <= nfc.maxTransceiveLength)
            val response = nfc.transceive(command)
            if (response.size < 2) throw IOException("Truncated APDU")
            val status = ((response[response.size - 2].toInt() and 255) shl 8) or
                (response.last().toInt() and 255)
            if (status != 0x9000) throw ApduFailure(status)
            return response.copyOf(response.size - 2)
        }

        val row = checkNotNull(ledger.find(id))
        val uuid = Protocol.uuidBytes(id)
        fun control(ins: Int) = uuid + identity.sign(Identity.control(ins, uuid))
        send(0xA4, Protocol.aid, select = true)
        val info = send(Protocol.info)
        check(info.size == 2 && info[0].toInt() == Protocol.version)
        val size = minOf(info[1].toInt() and 255, Protocol.chunk, nfc.maxTransceiveLength - 5)
        check(size > 0)
        val query = control(Protocol.status)
        var state = try {
            send(Protocol.status, query)
        } catch (error: ApduFailure) {
            if (error.status != 0x6A82 || row.result != null || row.state != "WAITING_FOR_DEVICE") throw error
            post("SENDING", epoch)
            send(Protocol.begin, ByteBuffer.allocate(36).putInt(row.wire.size).put(Protocol.hash(row.wire)).array())
            var offset = 0
            var index = 0
            while (offset < row.wire.size) {
                val end = minOf(offset + size, row.wire.size)
                send(Protocol.part, row.wire.copyOfRange(offset, end), index++)
                offset = end
            }
            check(send(Protocol.commit).contentEquals(uuid))
            ledger.received(id)
            send(Protocol.status, query)
        }
        ledger.received(id)
        post("PENDING_APPROVAL", epoch)
        if (cancel && state.contentEquals(byteArrayOf(0))) {
            send(Protocol.cancel, control(Protocol.cancel))
            state = send(Protocol.status, query)
        }
        while (state.contentEquals(byteArrayOf(0))) {
            Thread.sleep(500)
            state = send(Protocol.status, query)
        }
        check(state.contentEquals(byteArrayOf(1)))
        persistResult(row, control(Protocol.result), identity) { auth, offset ->
            send(Protocol.result, auth, offset)
        }
        post("RESULT", epoch)
        send(Protocol.ack, control(Protocol.ack))
        ledger.acknowledged(id)
        post("ACKNOWLEDGED", epoch)
    }

    private fun persistResult(row: Record, control: ByteArray, identity: Identity,
                              fetch: (ByteArray, Int) -> ByteArray) {
        val buffer = ByteArrayOutputStream()
        var expected = 0
        do {
            val response = fetch(control, buffer.size())
            require(response.size in 3..98)
            val size = ByteBuffer.wrap(response).short.toInt() and 65535
            if (expected == 0) expected = size
            require(size == expected && size in 1..Protocol.limit && buffer.size() + response.size - 2 <= size)
            buffer.write(response, 2, response.size - 2)
        } while (buffer.size() < expected)
        ledger.recordResult(row.id, buffer.toByteArray(), identity)
    }
}
