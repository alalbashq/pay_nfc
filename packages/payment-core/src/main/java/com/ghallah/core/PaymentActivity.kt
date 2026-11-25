package com.ghallah.core

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

abstract class PaymentActivity : Activity() {
    protected val ledger by lazy { Ledger(applicationContext) }
    protected val identity by lazy { Identity(applicationContext) }
    protected val nfc by lazy { NfcAdapter.getDefaultAdapter(this) }
    protected lateinit var content: LinearLayout
    protected abstract val titleAr: String
    protected abstract val titleEn: String
    protected val english get() = identity.prefs.getBoolean("english", false)
    private val night get() = when (identity.prefs.getString("theme", "system")) {
        "dark" -> true
        "light" -> false
        else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    private val ink get() = if (night) 0xffecf3f0.toInt() else 0xff183c35.toInt()
    private val surface get() = if (night) 0xff202e29.toInt() else 0xffffffff.toInt()
    protected fun tr(ar: String, en: String) = if (english) en else ar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xff183c35.toInt()
        window.navigationBarColor = 0xff183c35.toInt()
    }

    protected fun screen(subtitle: String) {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = if (english) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
            setPadding(dp(22), dp(26), dp(22), dp(32))
            setBackgroundColor(if (night) 0xff101c18.toInt() else 0xfff5f8f6.toInt())
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        setContentView(scroll)
        val heading = text(tr(titleAr, titleEn), 32, true)
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.isAccessibilityHeading = true
        text(subtitle, 17)
        text(tr("نسخة تجريبية • لا يتم خصم أموال", "Demo • No money is moved"), 15, true)
    }

    protected fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    protected fun text(value: String, size: Int = 16, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = value
            textSize = size.toFloat()
            setTextColor(ink)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(7), 0, dp(7))
            content.addView(this)
        }

    protected fun field(label: String, value: String = "", amount: Boolean = false): EditText {
        val caption = text(label, 15, true)
        return EditText(this).apply {
            id = View.generateViewId()
            caption.labelFor = id
            setSingleLine()
            inputType = if (amount) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else InputType.TYPE_CLASS_TEXT
            setTextColor(ink)
            setText(value)
            minHeight = dp(50)
            setPadding(dp(13), dp(8), dp(13), dp(8))
            background = GradientDrawable().apply { setColor(surface); cornerRadius = dp(13).toFloat() }
            content.addView(this)
        }
    }

    protected fun button(label: String, secondary: Boolean = false, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            minHeight = dp(54)
            setTextColor(if (secondary) ink else 0xffffffff.toInt())
            backgroundTintList = ColorStateList.valueOf(if (secondary) surface else 0xff287a65.toInt())
            content.addView(this)
            setOnClickListener { action() }
        }

    protected fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    protected fun nfcStatus() = when {
        nfc == null -> tr("الجهاز لا يدعم NFC", "NFC is unsupported")
        nfc?.isEnabled != true -> tr("يرجى تفعيل NFC", "Enable NFC")
        else -> tr("NFC مفعّل", "NFC enabled")
    }

    protected fun status(state: String): String = when (state) {
        "ACCEPTED" -> tr("تم قبول الدفع التجريبي", "Demo payment accepted")
        "REJECTED" -> tr("تم رفض الدفع", "Payment rejected")
        "CANCELLED" -> tr("تم الإلغاء", "Cancelled")
        "EXPIRED" -> tr("انتهت الصلاحية", "Expired")
        "PENDING_APPROVAL" -> tr("بانتظار الموافقة", "Awaiting approval")
        "WAITING_FOR_DEVICE" -> tr("انتظار الجهاز", "Waiting for device")
        else -> state
    }

    protected fun details(request: JSONObject): String = buildString {
        appendLine(request.getString("merchant_name"))
        appendLine(tr("نقطة البيع", "Terminal") + " • " + request.getString("terminal_id"))
        appendLine(tr("الفاتورة", "Invoice") + " • " + request.getString("invoice_number"))
        val items = request.optJSONArray("items")
        if (items != null) for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            appendLine(item.getString("item_name") + " × " + item.getInt("quantity") + " • " +
                Protocol.money(item.getLong("total_minor")))
        }
        appendLine(tr("قبل الضريبة", "Subtotal") + " • " + Protocol.money(request.getLong("subtotal_minor")))
        appendLine(tr("الخصم", "Discount") + " • " + Protocol.money(request.getLong("discount_minor")))
        appendLine(tr("الضريبة", "Tax") + " • " + Protocol.money(request.getLong("tax_minor")))
        append(Protocol.money(request.getLong("amount_minor")))
        request.optString("description").takeIf { it.isNotBlank() }?.let { append("\n\n" + it) }
    }

    protected fun resultText(row: Record, localResult: Boolean): String {
        val result = row.result?.let {
            runCatching { identity.open(it, if (localResult) identity.publicCode() else row.peer) }.getOrNull()
        }
        return buildString {
            append(details(row.request))
            if (result != null) {
                append("\n\n" + result.optString("decided_at"))
                for (field in listOf("reference", "reason_code")) {
                    if (result.has(field)) append("\n" + result.getString(field))
                }
            }
            append("\n" + row.id)
        }
    }

    protected fun history(home: () -> Unit, open: (Record) -> Unit) {
        screen(tr("سجل العمليات", "Transactions"))
        button(tr("الرئيسية", "Home"), true, home)
        val filter = field(tr("بحث برقم الفاتورة أو الحالة أو التاريخ", "Search invoice, status or date"))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        val page = content
        fun populate() {
            list.removeAllViews()
            content = list
            val query = filter.text.toString().trim()
            val matches = ledger.all().filter {
                query.isEmpty() || (it.id + " " + it.request.optString("invoice_number") + " " +
                    it.request.optString("created_at") + " " + it.state + " " + status(it.state)).contains(query, true)
            }
            if (matches.isEmpty()) text(tr("لا توجد عمليات مطابقة", "No matching transactions"))
            for (row in matches) button(row.request.getString("invoice_number") + " • " +
                Protocol.money(row.request.getLong("amount_minor")) + "\n" + status(row.state), true) { open(row) }
            content = page
        }
        filter.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = populate()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        populate()
    }

    protected fun settings(home: () -> Unit, extra: () -> Unit = {}) {
        screen(tr("الإعدادات", "Settings"))
        button(tr("الرئيسية", "Home"), true, home)
        text(nfcStatus())
        button(tr("إعدادات NFC", "NFC settings"), true) {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        }
        button(tr("English", "العربية"), true) {
            identity.prefs.edit().putBoolean("english", !english).apply()
            settings(home, extra)
        }
        button(tr("المظهر", "Appearance"), true) {
            AlertDialog.Builder(this).setItems(arrayOf(tr("النظام", "System"), tr("فاتح", "Light"), tr("داكن", "Dark"))) { _, choice ->
                identity.prefs.edit().putString("theme", arrayOf("system", "light", "dark")[choice]).apply()
                settings(home, extra)
            }.show()
        }
        button(tr("نسخ رمز هذا الجهاز", "Copy this device’s code"), true) {
            runCatching {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("GNPP", identity.publicCode()))
            }.onSuccess { toast(tr("تم نسخ الرمز", "Code copied")) }
                .onFailure { toast(tr("تعذّر إنشاء مفتاح الجهاز", "Could not create device key")) }
        }
        val peer = field(tr("رمز الجهاز المقابل الموثوق", "Trusted peer device code"), identity.peer())
        button(tr("حفظ الربط", "Save pairing")) {
            runCatching { identity.pair(peer.text.toString().trim()) }
                .onSuccess { toast(tr("تم حفظ الربط", "Pairing saved")) }
                .onFailure { peer.error = tr("رمز غير صالح", "Invalid code") }
        }
        extra()
        text("NFC • SAR • v1.0.0")
    }
}
