package com.ghallah.bridge

import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.provider.Settings
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.ghallah.core.GhallahNfcReader
import com.ghallah.core.Identity
import com.ghallah.core.Ledger
import com.ghallah.core.Payment
import com.ghallah.core.Protocol
import com.ghallah.core.Record
import org.json.JSONArray
import org.json.JSONObject

@CapacitorPlugin(name = "Ghallah")
class GhallahPlugin : Plugin() {
    private val identity by lazy { Identity(context.applicationContext) }
    private val ledger by lazy { Ledger(context.applicationContext) }
    private val reader by lazy { GhallahNfcReader(activity, ledger, identity, ::readerUpdate) }
    private val adapter get() = NfcAdapter.getDefaultAdapter(context)
    private val pos get() = context.packageName == "com.ghallah.pos"
    private val hce get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
    private val prefs get() = identity.prefs
    private val receiving get() = prefs.getBoolean("receiving", false)
    private var active: String? = null
    private var cancelling = false
    private var connection = ""

    private fun reply(call: PluginCall, error: String, action: () -> JSObject?) {
        activity.runOnUiThread {
            runCatching(action).onSuccess { if (it == null) call.resolve() else call.resolve(it) }
                .onFailure { call.reject(it.message?.takeIf { msg -> msg in errorCodes } ?: error) }
        }
    }

    private fun text(call: PluginCall, key: String) = call.getString(key, "")!!.trim()

    private fun rowJson(row: Record) = JSObject().apply {
        put("id", row.id)
        put("request", row.request)
        put("state", row.state)
        put("delivery", row.delivery)
        put("trusted", row.peer == identity.peer())
        row.result?.let { packet ->
            runCatching { identity.open(packet, if (pos) row.peer else identity.publicCode()) }
                .getOrNull()?.let { put("result", it) }
        }
    }

    @PluginMethod
    fun snapshot(call: PluginCall) = reply(call, "ERROR") {
        val records = ledger.all().map {
            if (!pos && it.state == Payment.pending && Payment.expired(it.request))
                ledger.decide(it.id, "EXPIRED", identity)
            else it
        }
        JSObject().apply {
            put("records", JSArray(records.map(::rowJson)))
            put("connection", connection)
            put("active", active ?: JSONObject.NULL)
            put("receiving", receiving)
            put("paired", identity.peer().isNotEmpty())
            put("nfc", when {
                adapter == null || !pos && !hce -> "UNSUPPORTED"
                adapter?.isEnabled != true -> "DISABLED"
                else -> "ENABLED"
            })
        }
    }

    @PluginMethod
    fun settings(call: PluginCall) = reply(call, "ERROR") {
        JSObject().apply {
            put("code", identity.publicCode())
            put("peer", identity.peer())
            put("english", prefs.getBoolean("english", false))
            put("theme", prefs.getString("theme", "system"))
            put("merchant", prefs.getString("merchant", ""))
            put("merchantId", prefs.getString("merchant_id", "MERCHANT-001"))
            put("terminal", prefs.getString("terminal", "POS-001"))
            put("expiry", prefs.getLong("expiry", 120).toString())
            put("invoice", "INV-" + prefs.getLong("sequence", 1).toString().padStart(6, '0'))
        }
    }

    @PluginMethod
    fun pair(call: PluginCall) = reply(call, "INVALID_CODE") { identity.pair(text(call, "peer")); null }

    @PluginMethod
    fun copyCode(call: PluginCall) = reply(call, "ERROR") {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("GNPP", identity.publicCode()))
        null
    }

    @PluginMethod
    fun appearance(call: PluginCall) = reply(call, "INVALID_SETTINGS") {
        val theme = text(call, "theme")
        require(theme in setOf("system", "light", "dark"))
        prefs.edit().putString("theme", theme).putBoolean("english", call.getBoolean("english", false) == true).apply()
        null
    }

    @PluginMethod
    fun merchant(call: PluginCall) = reply(call, "INVALID_SETTINGS") {
        val values = listOf("merchant", "merchantId", "terminal").map { text(call, it) }
        val expiry = text(call, "expiry").toLong()
        require(pos && expiry in 30..900 && values.all { it.length in 1..120 })
        prefs.edit().putString("merchant", values[0]).putString("merchant_id", values[1])
            .putString("terminal", values[2]).putLong("expiry", expiry).apply()
        null
    }

    @PluginMethod
    fun nfcSettings(call: PluginCall) = reply(call, "NFC_DISABLED") {
        activity.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        null
    }

    private fun requireReady() {
        check(identity.peer().isNotEmpty()) { "PAIR_FIRST" }
        check(adapter != null && (pos || hce)) { "NFC_UNSUPPORTED" }
        check(adapter?.isEnabled == true) { "NFC_DISABLED" }
    }

    @PluginMethod
    fun create(call: PluginCall) = reply(call, "INVALID_INVOICE") {
        check(pos)
        requireReady()
        val request = Payment.newRequest(text(call, "invoice"),
            prefs.getString("merchant", null) ?: if (prefs.getBoolean("english", false)) "Ghallah Store" else "متجر غله",
            prefs.getString("merchant_id", "MERCHANT-001")!!, prefs.getString("terminal", "POS-001")!!,
            Protocol.minor(text(call, "subtotal")), Protocol.minor(text(call, "tax")),
            Protocol.minor(text(call, "discount")), text(call, "description"), prefs.getLong("expiry", 120))
        val item = text(call, "item")
        if (item.isNotEmpty()) {
            val price = request.getLong("subtotal_minor")
            request.put("items", JSONArray().put(JSONObject().put("item_code", "ITEM-001")
                .put("item_name", item).put("quantity", 1).put("unit_price_minor", price).put("total_minor", price)))
        }
        Payment.validate(request)
        val wire = identity.seal(request)
        require(wire.size <= Protocol.limit)
        val row = ledger.save(request, wire, Payment.waiting, identity.peer())
        prefs.edit().putLong("sequence", prefs.getLong("sequence", 1) + 1).apply()
        rowJson(row)
    }

    @PluginMethod
    fun start(call: PluginCall) = reply(call, "ERROR") {
        check(pos)
        requireReady()
        val row = checkNotNull(ledger.find(text(call, "id")))
        check(row.peer == identity.peer()) { "PAIR_FIRST" }
        if (row.delivery != "ACKNOWLEDGED") {
            reader.stop()
            cancelling = call.getBoolean("cancel", false) == true
            reader.start(row.id, cancelling)
            active = row.id
            connection = "WAITING_FOR_DEVICE"
        }
        null
    }

    @PluginMethod
    fun stop(call: PluginCall) = reply(call, "ERROR") {
        if (pos) reader.stop()
        active = null
        connection = ""
        null
    }

    @PluginMethod
    fun receive(call: PluginCall) = reply(call, "ERROR") {
        check(!pos)
        val enabled = call.getBoolean("enabled", false) == true
        if (enabled) requireReady()
        prefs.edit().putBoolean("receiving", enabled).apply()
        routePreferred(enabled)
        null
    }

    @PluginMethod
    fun decide(call: PluginCall) = reply(call, "ERROR") {
        check(!pos)
        val row = checkNotNull(ledger.find(text(call, "id")))
        val decision = text(call, "decision")
        require(row.peer == identity.peer() && decision in setOf("ACCEPTED", "REJECTED"))
        rowJson(ledger.decide(row.id, decision, identity))
    }

    private fun readerUpdate(state: String) {
        connection = state
        if (state == "ACKNOWLEDGED") {
            reader.stop()
            active = null
        }
    }

    private fun routePreferred(enable: Boolean) {
        val nfc = adapter ?: return
        if (!hce || !nfc.isEnabled) return
        runCatching {
            val routing = CardEmulation.getInstance(nfc)
            if (enable) routing.setPreferredService(activity,
                ComponentName(context.packageName, "com.ghallah.pay.GhallahHostApduService"))
            else routing.unsetPreferredService(activity)
        }
    }

    override fun handleOnResume() {
        if (pos) active?.let { runCatching { reader.start(it, cancelling) } }
        else routePreferred(receiving)
    }

    override fun handleOnPause() {
        if (pos) { if (active != null) reader.stop() }
        else routePreferred(false)
    }

    override fun handleOnDestroy() {
        if (pos) reader.close() else ledger.close()
    }

    companion object {
        private val errorCodes = setOf("PAIR_FIRST", "NFC_UNSUPPORTED", "NFC_DISABLED")
    }
}
