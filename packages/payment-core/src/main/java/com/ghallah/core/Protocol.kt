package com.ghallah.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

object Protocol {
    val aid = byteArrayOf(0xf0.toByte(), 0x47, 0x48, 0x41, 0x4c, 0x4c, 0x41, 0x48, 0x01)
    const val version = 1
    const val limit = 16 * 1024
    const val chunk = 200
    const val info = 0x10
    const val begin = 0x20
    const val part = 0x21
    const val commit = 0x22
    const val status = 0x30
    const val result = 0x31
    const val ack = 0x32
    const val cancel = 0x40

    fun command(ins: Int, data: ByteArray = byteArrayOf(), index: Int = 0, select: Boolean = false): ByteArray {
        require(data.size <= 255 && index in 0..65535)
        val header = byteArrayOf(if (select) 0 else 0x80.toByte(), ins.toByte(),
            if (select) 4 else (index shr 8).toByte(), index.toByte())
        return if (data.isEmpty()) header else header + byteArrayOf(data.size.toByte()) + data
    }

    fun reply(status: Int, data: ByteArray = byteArrayOf()) =
        data + byteArrayOf((status shr 8).toByte(), status.toByte())

    fun hash(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    fun uuid(bytes: ByteArray): String {
        require(bytes.size == 16)
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }
    fun uuidBytes(id: String): ByteArray {
        val uuid = UUID.fromString(id)
        return ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
    }

    fun minor(input: String): Long {
        val normalized = input.trim().map { char ->
            val digit = char.digitToIntOrNull()
            when {
                digit != null -> ('0'.code + digit).toChar()
                char == '٫' -> '.'
                else -> char
            }
        }.joinToString("")
        require(normalized.matches(Regex("[0-9]{1,9}(\\.[0-9]{1,2})?")))
        return normalized.substringBefore('.').toLong() * 100 +
            normalized.substringAfter('.', "").padEnd(2, '0').toLong()
    }

    fun money(minor: Long) = "${minor / 100}.${(minor % 100).toString().padStart(2, '0')} SAR"
}

class Incoming(val size: Int, val digest: ByteArray) {
    init { require(size in 1..Protocol.limit && digest.size == 32) }
    private val buffer = java.io.ByteArrayOutputStream(size)
    private var next = 0

    fun put(index: Int, data: ByteArray) {
        require(index == next && data.size in 1..Protocol.chunk && buffer.size() + data.size <= size)
        buffer.write(data)
        next++
    }

    fun finish(): ByteArray {
        val data = buffer.toByteArray()
        require(data.size == size && MessageDigest.isEqual(digest, Protocol.hash(data)))
        return data
    }
}
