package com.ghallah.pay

import android.app.AlertDialog
import android.content.ComponentName
import android.content.pm.PackageManager
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import com.ghallah.core.Payment
import com.ghallah.core.PaymentActivity
import com.ghallah.core.Protocol
import com.ghallah.core.Record

class MainActivity : PaymentActivity() {
    override val titleAr = "غله باي"
    override val titleEn = "Ghallah Pay"
    private val handler = Handler(Looper.getMainLooper())
    private var approval: AlertDialog? = null
    private var approvalId: String? = null
    private var lastOperation: TextView? = null
    private var foreground = false
    private var atHome = false
    private val supported get() = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
    private val receiving get() = identity.prefs.getBoolean("receiving", false)
    private val tick = object : Runnable {
        override fun run() {
            if (!foreground) return
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        home()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        routePreferred(receiving)
        handler.post(tick)
    }

    override fun onPause() {
        foreground = false
        handler.removeCallbacks(tick)
        routePreferred(false)
        approval?.dismiss()
        approval = null
        approvalId = null
        super.onPause()
    }

    override fun onDestroy() {
        ledger.close()
        super.onDestroy()
    }

    private fun routePreferred(enable: Boolean) {
        val adapter = nfc ?: return
        if (!adapter.isEnabled || !supported) return
        runCatching {
            val routing = CardEmulation.getInstance(adapter)
            if (enable) routing.setPreferredService(this, ComponentName(this, GhallahHostApduService::class.java))
            else routing.unsetPreferredService(this)
        }
    }

    private fun home() {
        atHome = true
        screen(tr("استقبال طلبات الدفع", "Receive payment requests"))
        text(if (supported) nfcStatus() else tr("الجهاز لا يدعم HCE", "HCE is unsupported"), bold = true)
        text(if (receiving) tr("جاهز لاستقبال الطلبات", "Ready to receive requests")
            else tr("الاستقبال متوقف", "Receiving is off"), 24, true)
        button(if (receiving) tr("إيقاف الاستقبال", "Turn off receiving") else tr("تفعيل استقبال الدفع", "Enable receiving")) {
            if (!receiving && (!supported || nfc?.isEnabled != true || identity.peer().isEmpty())) {
                toast(if (identity.peer().isEmpty()) tr("اربط الجهازين من الإعدادات أولًا", "Pair devices in settings first")
                    else if (!supported) tr("الجهاز لا يدعم HCE", "HCE is unsupported") else nfcStatus())
            } else {
                val enabled = !receiving
                identity.prefs.edit().putBoolean("receiving", enabled).apply()
                routePreferred(enabled)
                home()
            }
        }
        lastOperation = text("")
        button(tr("سجل العمليات", "Transactions"), true) {
            atHome = false
            history(::home, ::open)
        }
        button(tr("الإعدادات", "Settings"), true) {
            atHome = false
            settings(::home)
        }
        refresh()
    }

    private fun refresh() {
        if (!foreground) return
        var rows = ledger.all()
        rows.filter { it.state == Payment.pending && Payment.expired(it.request) }.forEach {
            ledger.decide(it.id, "EXPIRED", identity)
        }
        if (rows.any { it.state == Payment.pending && Payment.expired(it.request) }) rows = ledger.all()
        if (atHome) {
            val last = rows.firstOrNull()
            lastOperation?.text = if (last == null) tr("لا توجد عمليات بعد", "No transactions yet")
                else status(last.state) + "\n" + Protocol.money(last.request.getLong("amount_minor")) +
                    if (last.result != null && last.delivery != "ACKNOWLEDGED")
                        "\n" + tr("قرّب الجوال مرة أخرى لإرسال التأكيد", "Tap again to send confirmation") else ""
        }
        if (approvalId != null && rows.none { it.id == approvalId && it.state == Payment.pending }) {
            approval?.dismiss()
            approvalId = null
            approval = null
        }
        if (receiving && approval == null) rows.lastOrNull {
            it.state == Payment.pending && it.peer == identity.peer()
        }?.let { showApproval(it) }
    }

    private fun open(row: Record) {
        if (row.state == Payment.pending && row.peer == identity.peer()) {
            showApproval(row)
        } else {
            AlertDialog.Builder(this).setTitle(status(row.state)).setMessage(resultText(row, true))
                .setPositiveButton(tr("إغلاق", "Close")) { _, _ -> }.show()
        }
    }

    private fun showApproval(row: Record) {
        if (approval != null) return
        approvalId = row.id
        approval = AlertDialog.Builder(this).setTitle(tr("طلب دفع جديد", "New payment request"))
            .setView(approvalDetails(row))
            .setCancelable(false)
            .setPositiveButton(tr("قبول الدفع", "Accept payment")) { _, _ -> decide(row, "ACCEPTED") }
            .setNegativeButton(tr("رفض الدفع", "Reject payment")) { _, _ -> decide(row, "REJECTED") }
            .create().apply {
                setCanceledOnTouchOutside(false)
                show()
                getButton(android.content.DialogInterface.BUTTON_POSITIVE).setTextColor(0xff14734e.toInt())
                getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setTextColor(0xffb91c1c.toInt())
            }
    }

    private fun approvalDetails(row: Record): android.view.View {
        val block = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutDirection = if (english) android.view.View.LAYOUT_DIRECTION_LTR else android.view.View.LAYOUT_DIRECTION_RTL
            setPadding(dp(24), dp(12), dp(24), dp(12))
            addView(TextView(this@MainActivity).apply {
                text = Protocol.money(row.request.getLong("amount_minor"))
                textSize = 32f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xff183c35.toInt())
            })
            addView(TextView(this@MainActivity).apply {
                text = details(row.request)
                textSize = 17f
                setPadding(0, dp(10), 0, 0)
            })
        }
        return android.widget.ScrollView(this).apply { addView(block) }
    }

    private fun decide(row: Record, state: String) {
        approvalId = null
        approval = null
        runCatching { ledger.decide(row.id, state, identity) }.onSuccess {
            toast(status(it.state) + "\n" + tr("قرّب الجوال مرة أخرى لإرسال التأكيد", "Tap again to send confirmation"))
            refresh()
        }.onFailure { toast(tr("تعذّر حفظ القرار", "Could not save the decision")) }
    }
}
