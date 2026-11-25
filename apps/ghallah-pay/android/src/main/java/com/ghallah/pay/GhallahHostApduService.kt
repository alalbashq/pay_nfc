package com.ghallah.pay

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.ghallah.core.Identity
import com.ghallah.core.Incoming
import com.ghallah.core.Ledger
import com.ghallah.core.Payment
import com.ghallah.core.Protocol
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class GhallahHostApduService : HostApduService() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val ledger by lazy { Ledger(applicationContext) }
    private val identity by lazy { Identity(applicationContext) }
    private var generation = 0
    private var selected = false
    private var incoming: Incoming? = null

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray? {
        val epoch = generation
        worker.execute {
            val response = try {
                handle(commandApdu)
            } catch (_: SecurityException) {
                Protocol.reply(0x6982)
            } catch (_: IllegalStateException) {
                Protocol.reply(0x6985)
            } catch (_: IllegalArgumentException) {
                Protocol.reply(0x6A80)
            } catch (_: org.json.JSONException) {
                Protocol.reply(0x6A80)
            } catch (_: Exception) {
                Protocol.reply(0x6F00)
            }
            main.post { if (generation == epoch) sendResponseApdu(response) }
        }
        return null
    }

    override fun onDeactivated(reason: Int) {
        generation++
        worker.execute { selected = false; incoming = null }
    }

    override fun onDestroy() {
        generation++
        worker.execute { ledger.close() }
        worker.shutdown()
        super.onDestroy()
    }

    private fun handle(apdu: ByteArray): ByteArray {
        if (apdu.size < 4) return Protocol.reply(0x6700)
        val cla = apdu[0].toInt() and 255
        val ins = apdu[1].toInt() and 255
        val index = ((apdu[2].toInt() and 255) shl 8) or (apdu[3].toInt() and 255)
        val data = if (apdu.size == 4) byteArrayOf() else {
            val size = apdu[4].toInt() and 255
            if (size == 0 || size + 5 != apdu.size) return Protocol.reply(0x6700)
            apdu.copyOfRange(5, apdu.size)
        }
        if (cla == 0 && ins == 0xA4) {
            incoming = null
            selected = index == 0x400 && data.contentEquals(Protocol.aid)
            return Protocol.reply(if (selected) 0x9000 else 0x6A82)
        }
        if (cla != 0x80) return Protocol.reply(0x6E00)
        if (!selected || !identity.prefs.getBoolean("receiving", false)) return Protocol.reply(0x6985)
        if (index != 0 && ins != Protocol.part && ins != Protocol.result) return Protocol.reply(0x6A86)
        return when (ins) {
            Protocol.info -> {
                if (data.isNotEmpty()) return Protocol.reply(0x6700)
                Protocol.reply(0x9000, byteArrayOf(Protocol.version.toByte(), Protocol.chunk.toByte()))
            }
            Protocol.begin -> {
                if (data.size != 36) return Protocol.reply(0x6700)
                incoming = Incoming(ByteBuffer.wrap(data).int, data.copyOfRange(4, 36))
                Protocol.reply(0x9000)
            }
            Protocol.part -> {
                checkNotNull(incoming).put(index, data)
                Protocol.reply(0x9000)
            }
            Protocol.commit -> {
                if (data.isNotEmpty()) return Protocol.reply(0x6700)
                val packet = checkNotNull(incoming).finish()
                incoming = null
                val peer = identity.peer()
                if (peer.isEmpty()) throw SecurityException()
                val request = identity.open(packet, peer)
                Payment.validate(request)
                val row = ledger.save(request, packet, Payment.pending, peer)
                Protocol.reply(0x9000, Protocol.uuidBytes(row.id))
            }
            Protocol.status, Protocol.result, Protocol.ack, Protocol.cancel -> control(ins, index, data)
            else -> Protocol.reply(0x6D00)
        }
    }

    private fun control(ins: Int, offset: Int, data: ByteArray): ByteArray {
        if (data.size !in 17..96) return Protocol.reply(0x6700)
        val id = data.copyOfRange(0, 16)
        val row = ledger.find(Protocol.uuid(id)) ?: return Protocol.reply(0x6A82)
        if (row.peer != identity.peer() ||
            !identity.verify(Identity.control(ins, id), data.copyOfRange(16, data.size), row.peer))
            return Protocol.reply(0x6982)
        val current = if (row.result == null && Payment.expired(row.request))
            ledger.decide(row.id, "EXPIRED", identity) else row
        return when (ins) {
            Protocol.status -> Protocol.reply(0x9000, byteArrayOf(if (current.result == null) 0 else 1))
            Protocol.ack -> {
                ledger.acknowledged(row.id)
                Protocol.reply(0x9000)
            }
            Protocol.cancel -> {
                ledger.decide(row.id, "CANCELLED", identity)
                Protocol.reply(0x9000)
            }
            else -> {
                val packet = current.result ?: return Protocol.reply(0x6985)
                if (offset >= packet.size) return Protocol.reply(0x6A86)
                val part = packet.copyOfRange(offset, minOf(offset + 96, packet.size))
                Protocol.reply(0x9000, ByteBuffer.allocate(2).putShort(packet.size.toShort()).array() + part)
            }
        }
    }
}
