package com.ghallah.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolTest {
    @Test fun arabicAmountIsParsedAsInteger() {
        assertEquals(28750L, Protocol.minor("٢٨٧٫٥٠"))
        assertEquals(10L, Protocol.minor("0.1"))
        assertThrows(IllegalArgumentException::class.java) { Protocol.minor("12.001") }
        assertThrows(IllegalArgumentException::class.java) { Protocol.minor("-1") }
    }

    @Test fun selectUsesStandardShortHeader() {
        assertArrayEquals(byteArrayOf(0, 0xA4.toByte(), 4, 0, 9) + Protocol.aid,
            Protocol.command(0xA4, Protocol.aid, select = true))
    }

    @Test fun assemblesArabicWithoutCorruptingSplitCharacters() {
        val bytes = "فاتورة متجر غله".toByteArray(Charsets.UTF_8)
        val transfer = Incoming(bytes.size, Protocol.hash(bytes))
        transfer.put(0, bytes.copyOfRange(0, 1))
        transfer.put(1, bytes.copyOfRange(1, bytes.size))
        assertArrayEquals(bytes, transfer.finish())
    }

    @Test fun rejectsWrongHashAndSequence() {
        val bytes = byteArrayOf(1, 2)
        val transfer = Incoming(2, Protocol.hash(byteArrayOf(0, 0)))
        assertThrows(IllegalArgumentException::class.java) { transfer.put(1, bytes) }
        transfer.put(0, bytes)
        assertThrows(IllegalArgumentException::class.java) { transfer.finish() }
        assertThrows(IllegalArgumentException::class.java) {
            Incoming(Protocol.limit + 1, Protocol.hash(bytes))
        }
    }

    @Test fun transactionIdRoundTrips() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        assertEquals(uuid, Protocol.uuid(Protocol.uuidBytes(uuid)))
    }
}
