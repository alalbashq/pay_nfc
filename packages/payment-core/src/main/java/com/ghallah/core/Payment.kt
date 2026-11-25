package com.ghallah.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.UUID

class Identity(context: Context) {
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias = "gnpp-v1"
    private val entry: KeyStore.PrivateKeyEntry
        get() = synchronized(lock) {
            if (!store.containsAlias(alias)) {
                KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
                    initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256).build())
                }.generateKeyPair()
                store.load(null)
            }
            store.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        }

    fun publicCode(): String = encode(entry.certificate.publicKey.encoded)
    fun peer(): String = prefs.getString("peer", "") ?: ""
    fun pair(code: String) {
        require(code != publicCode())
        decodeKey(code)
        check(prefs.edit().putString("peer", code).commit())
    }
    fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(entry.privateKey)
        update(data)
        sign()
    }
    fun verify(data: ByteArray, signature: ByteArray, peerCode: String): Boolean =
        runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(decodeKey(peerCode))
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)

    fun seal(message: JSONObject): ByteArray {
        val body = message.toString().toByteArray(Charsets.UTF_8)
        return JSONObject().put("body", encode(body)).put("signature", encode(sign(body)))
            .toString().toByteArray(Charsets.UTF_8)
    }

    fun open(packet: ByteArray, peerCode: String): JSONObject {
        require(packet.size in 1..Protocol.limit)
        val envelope = JSONObject(packet.toString(Charsets.UTF_8))
        val body = decode(envelope.getString("body"))
        if (!verify(body, decode(envelope.getString("signature")), peerCode)) throw SecurityException()
        return JSONObject(body.toString(Charsets.UTF_8))
    }

    companion object {
        private val lock = Any()
        fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        fun decode(text: String): ByteArray = Base64.decode(text, Base64.DEFAULT)
        private fun decodeKey(code: String): PublicKey {
            require(code.length in 100..180)
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode(code)))
            require((key as java.security.interfaces.ECPublicKey).params.curve.field.fieldSize == 256)
            return key
        }
        fun control(ins: Int, id: ByteArray) = "GNPP-1-control".toByteArray() + byteArrayOf(ins.toByte()) + id
    }
}

object Payment {
    const val pending = "PENDING_APPROVAL"
    const val waiting = "WAITING_FOR_DEVICE"
    val decisions = setOf("ACCEPTED", "REJECTED", "CANCELLED", "EXPIRED")

    fun newRequest(invoice: String, merchant: String, merchantId: String, terminal: String,
                   subtotal: Long, tax: Long, discount: Long, description: String, seconds: Long): JSONObject {
        val now = Instant.now()
        return JSONObject().put("protocol_version", 1).put("message_type", "PAYMENT_REQUEST")
            .put("transaction_id", UUID.randomUUID().toString()).put("invoice_number", invoice)
            .put("merchant_name", merchant).put("merchant_id", merchantId).put("terminal_id", terminal)
            .put("subtotal_minor", subtotal).put("tax_minor", tax).put("discount_minor", discount)
            .put("amount_minor", subtotal + tax - discount).put("currency", "SAR")
            .put("description", description).put("created_at", now.toString())
            .put("expires_at", now.plusSeconds(seconds).toString())
            .put("nonce", UUID.randomUUID().toString()).put("mode", "DEMO")
            .also { validate(it) }
    }

    private fun string(json: JSONObject, field: String, max: Int): String {
        val value = json.get(field)
        require(value is String && value.isNotBlank() && value.length <= max)
        return value
    }

    private fun integer(json: JSONObject, field: String): Long {
        val value = json.get(field)
        require(value is Int || value is Long)
        return (value as Number).toLong().also { require(it in 0..99_999_999_999L) }
    }

    fun validate(r: JSONObject) {
        require(r.get("protocol_version") == 1 && r.get("message_type") == "PAYMENT_REQUEST")
        for (field in listOf("transaction_id", "nonce")) {
            val id = string(r, field, 36)
            val uuid = UUID.fromString(id)
            require(uuid.toString() == id && uuid.version() == 4 && uuid.variant() == 2)
        }
        for (field in listOf("invoice_number", "merchant_name", "merchant_id", "terminal_id"))
            string(r, field, 120)
        if (r.has("description")) require((r.get("description") as String).length <= 500)
        val subtotal = integer(r, "subtotal_minor")
        val tax = integer(r, "tax_minor")
        val discount = integer(r, "discount_minor")
        val amount = integer(r, "amount_minor")
        require(discount <= subtotal && amount > 0 && subtotal + tax - discount == amount)
        if (r.has("items")) {
            val items = r.getJSONArray("items")
            require(items.length() in 1..100)
            var sum = 0L
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                string(item, "item_code", 120)
                string(item, "item_name", 120)
                val quantity = integer(item, "quantity")
                require(quantity in 1..100000)
                val total = integer(item, "total_minor")
                require(total == quantity * integer(item, "unit_price_minor"))
                sum += total
            }
            require(sum == subtotal)
        }
        require(r.get("mode") == "DEMO" && r.get("currency") == "SAR")
        val created = Instant.parse(string(r, "created_at", 40))
        val expires = Instant.parse(string(r, "expires_at", 40))
        require(expires.isAfter(created) && !expires.isAfter(created.plusSeconds(900)))
        require(!created.isAfter(Instant.now().plusSeconds(30)))
    }

    fun expired(r: JSONObject, at: Instant = Instant.now()) =
        !at.isBefore(Instant.parse(r.getString("expires_at")))

    fun result(r: JSONObject, state: String, at: Instant): JSONObject = JSONObject()
        .put("protocol_version", 1).put("message_type", "PAYMENT_RESULT")
        .put("transaction_id", r.getString("transaction_id"))
        .put("invoice_number", r.getString("invoice_number"))
        .put("amount_minor", r.getLong("amount_minor")).put("currency", "SAR")
        .put("status", state).put("decided_at", at.toString())
        .put("mode", "DEMO").put("settled", false).apply {
            if (state == "ACCEPTED") put("reference", "GHP-" + UUID.randomUUID())
            if (state == "REJECTED") put("reason_code", "USER_REJECTED")
        }

    fun validateResult(r: JSONObject, result: JSONObject): String {
        require(result.get("protocol_version") == 1 && result.get("message_type") == "PAYMENT_RESULT")
        require(result.get("mode") == "DEMO" && result.get("settled") == false)
        for (field in listOf("transaction_id", "invoice_number", "currency"))
            require(result.get(field) == r.get(field))
        require(integer(result, "amount_minor") == r.getLong("amount_minor"))
        val state = result.getString("status")
        require(state in decisions)
        val decided = Instant.parse(string(result, "decided_at", 40))
        require(!decided.isAfter(Instant.now().plusSeconds(30)))
        if (state == "ACCEPTED") {
            string(result, "reference", 100)
            require(decided.isBefore(Instant.parse(r.getString("expires_at"))))
        }
        return state
    }
}
