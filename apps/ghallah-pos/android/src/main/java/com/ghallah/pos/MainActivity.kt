package com.ghallah.pos

import android.app.AlertDialog
import android.os.Bundle
import android.widget.TextView
import com.ghallah.core.Payment
import com.ghallah.core.PaymentActivity
import com.ghallah.core.Protocol
import com.ghallah.core.Record

class MainActivity : PaymentActivity() {
    override val titleAr = "نقطة بيع غله"
    override val titleEn = "Ghallah POS"
    private val reader by lazy { GhallahNfcReader(this, ledger, identity, ::readerUpdate) }
    private var active: String? = null
    private var connectionLabel: TextView? = null
    private var showingResult = false
    private var cancellationRequested = false
    private var draft: org.json.JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        home()
        active = savedInstanceState?.getString("active")
        cancellationRequested = savedInstanceState?.getBoolean("cancel") ?: false
    }

    override fun onResume() {
        super.onResume()
        active?.let { id -> ledger.find(id)?.let { start(it, cancellationRequested) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("active", active)
        outState.putBoolean("cancel", cancellationRequested)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() { reader.stop(); super.onPause() }
    override fun onDestroy() { reader.close(); super.onDestroy() }

    private fun home() {
        active = null
        reader.stop()
        screen(tr("فاتورة جديدة", "New invoice"))
        text(nfcStatus(), bold = true)
        val prefs = identity.prefs
        val sequence = prefs.getLong("sequence", 1)
        val invoice = field(tr("رقم الفاتورة", "Invoice number"),
            draft?.optString("invoice_number") ?: "INV-" + sequence.toString().padStart(6, '0'))
        fun amount(key: String, fallback: String) =
            draft?.let { Protocol.money(it.getLong(key)).substringBefore(' ') } ?: fallback
        val subtotal = field(tr("المبلغ قبل الضريبة", "Subtotal"), amount("subtotal_minor", ""), true)
        val tax = field(tr("الضريبة", "Tax"), amount("tax_minor", "0"), true)
        val discount = field(tr("الخصم", "Discount"), amount("discount_minor", "0"), true)
        val item = field(tr("اسم صنف اختياري", "Optional item name"),
            draft?.optJSONArray("items")?.optJSONObject(0)?.optString("item_name") ?: "")
        val description = field(tr("وصف اختياري", "Optional description"), draft?.optString("description") ?: "")
        text(prefs.getString("merchant", tr("متجر غله", "Ghallah Store"))!! + " • " +
            prefs.getString("terminal", "POS-001"))
        button(tr("إرسال طلب الدفع", "Send payment request")) {
            if (identity.peer().isEmpty()) {
                toast(tr("اربط الجهازين من الإعدادات أولًا", "Pair devices in settings first"))
                return@button
            }
            if (nfc?.isEnabled != true) { toast(nfcStatus()); return@button }
            runCatching {
                val request = Payment.newRequest(invoice.text.toString().trim(),
                    prefs.getString("merchant", tr("متجر غله", "Ghallah Store"))!!,
                    prefs.getString("merchant_id", "MERCHANT-001")!!, prefs.getString("terminal", "POS-001")!!,
                    Protocol.minor(subtotal.text.toString()), Protocol.minor(tax.text.toString()),
                    Protocol.minor(discount.text.toString()), description.text.toString(), prefs.getLong("expiry", 120))
                val name = item.text.toString().trim()
                if (name.isNotEmpty()) {
                    val price = request.getLong("subtotal_minor")
                    request.put("items", org.json.JSONArray().put(org.json.JSONObject()
                        .put("item_code", "ITEM-001").put("item_name", name).put("quantity", 1)
                        .put("unit_price_minor", price).put("total_minor", price)))
                }
                Payment.validate(request)
                val wire = identity.seal(request)
                require(wire.size <= Protocol.limit)
                ledger.save(request, wire, Payment.waiting, identity.peer()).also {
                    prefs.edit().putLong("sequence", sequence + 1).apply()
                }
            }.onSuccess { draft = null; start(it) }
                .onFailure { toast(tr("تحقق من بيانات الفاتورة والمبالغ", "Check the invoice fields and amounts")) }
        }
        button(tr("سجل العمليات", "Transactions"), true) { history(::home, ::open) }
        button(tr("الإعدادات", "Settings"), true) { settings(::home, ::merchantSettings) }
    }

    private fun merchantSettings() {
        val prefs = identity.prefs
        val merchant = field(tr("اسم المتجر", "Merchant name"), prefs.getString("merchant", "متجر غله")!!)
        val id = field(tr("رقم المتجر", "Merchant ID"), prefs.getString("merchant_id", "MERCHANT-001")!!)
        val terminal = field(tr("معرّف نقطة البيع", "Terminal ID"), prefs.getString("terminal", "POS-001")!!)
        val expiry = field(tr("مدة الصلاحية بالثواني", "Expiry in seconds"), prefs.getLong("expiry", 120).toString(), true)
        button(tr("حفظ إعدادات المتجر", "Save merchant settings")) {
            val seconds = expiry.text.toString().toLongOrNull()
            val values = listOf(merchant, id, terminal).map { it.text.toString().trim() }
            if (seconds == null || seconds !in 30..900 || values.any { it.length !in 1..120 }) {
                toast(tr("القيم غير صالحة", "Invalid settings"))
            } else {
                prefs.edit().putString("merchant", values[0]).putString("merchant_id", values[1])
                    .putString("terminal", values[2]).putLong("expiry", seconds).apply()
                toast(tr("تم الحفظ", "Saved"))
            }
        }
    }

    private fun open(row: Record) {
        if (row.result == null || row.delivery != "ACKNOWLEDGED") {
            start(row)
        } else {
            showResult(row)
        }
    }

    private fun start(row: Record, cancellation: Boolean = false) {
        reader.stop()
        active = row.id
        cancellationRequested = cancellation
        showingResult = false
        screen(tr("طلب الدفع", "Payment request"))
        text(Protocol.money(row.request.getLong("amount_minor")), 34, true)
        text(row.request.getString("invoice_number"))
        connectionLabel = text(tr("قرّب الجهاز من جوال غله باي", "Hold this device near Ghallah Pay"), 18, true)
        if (row.result == null && !cancellation) {
            button(tr("طلب الإلغاء عند الاتصال", "Request cancellation when connected"), true) {
                start(row, cancellation = true)
            }
        }
        if (cancellation) text(tr("قرّب الجهازين للتحقق من القرار أو الإلغاء", "Tap to reconcile or cancel"))
        button(tr("العودة", "Back"), true, ::home)
        runCatching { reader.start(row.id, cancellation) }.onFailure { connectionLabel?.text = nfcStatus() }
    }

    private fun readerUpdate(state: String) {
        val id = active ?: return
        connectionLabel?.text = when (state) {
            "CONNECTING" -> tr("جاري الاتصال", "Connecting")
            "SENDING" -> tr("جاري إرسال الفاتورة", "Sending invoice")
            "PENDING_APPROVAL" -> tr("بانتظار الموافقة • يمكنك التقريب مرة أخرى", "Awaiting approval • tap again if separated")
            "CONNECTION_LOST" -> tr("انقطع الاتصال • قرّب الجهازين للتحقق من النتيجة", "Connection lost • tap again to verify")
            else -> status(ledger.find(id)?.state ?: state)
        }
        if (state == "ACKNOWLEDGED") {
            reader.stop()
            active = null
        }
        if (state == "RESULT" && !showingResult) ledger.find(id)?.let { showResult(it) }
    }

    private fun showResult(row: Record) {
        showingResult = true
        val dialog = AlertDialog.Builder(this).setTitle(status(row.state))
            .setMessage(tr("عملية تجريبية\n\n", "Demo transaction\n\n") + resultText(row, false))
            .setPositiveButton(tr("إغلاق", "Close")) { _, _ -> }
        if (row.state == "REJECTED") dialog.setNeutralButton(tr("إعادة المحاولة", "Retry")) { _, _ ->
            draft = row.request
            home()
        }
        if (row.state == "ACCEPTED") dialog.setNeutralButton(tr("فاتورة جديدة", "New invoice")) { _, _ -> home() }
        dialog.show()
    }
}
