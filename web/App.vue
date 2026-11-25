<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watchEffect } from 'vue'
import { Capacitor, registerPlugin } from '@capacitor/core'

const native = Capacitor.isNativePlatform()
const bridge = registerPlugin('Ghallah')
const pos = import.meta.env.MODE === 'pos'
const page = ref('home')
const selected = ref('')
const filter = ref('')
const message = ref('')
const failed = ref(false)
const busy = ref(false)
const settings = reactive({ invoice: 'INV-000001', code: '', peer: '', english: false,
  theme: 'system', merchant: '', merchantId: 'MERCHANT-001', terminal: 'POS-001', expiry: '120' })
const initialDraft = () => ({ invoice: settings.invoice, subtotal: '', tax: '0', discount: '0', item: '', description: '' })
const invoice = reactive(initialDraft())
const data = reactive({ records: [], receiving: false, paired: false, nfc: 'UNSUPPORTED', active: null, connection: '' })
const t = (ar, en) => settings.english ? en : ar
const title = computed(() => pos ? t('نقطة بيع غله', 'Ghallah POS') : t('غله باي', 'Ghallah Pay'))
const status = state => ({
  WAITING_FOR_DEVICE: t('قرّب الجهاز من جوال غله باي', 'Hold this device near Ghallah Pay'),
  PENDING_APPROVAL: t('بانتظار الموافقة', 'Awaiting approval'),
  ACCEPTED: t('تم قبول الدفع التجريبي', 'Demo payment accepted'),
  REJECTED: t('تم رفض الدفع', 'Payment rejected'),
  CANCELLED: t('تم الإلغاء', 'Cancelled'),
  EXPIRED: t('انتهت الصلاحية', 'Expired'),
  CONNECTING: t('جارٍ الاتصال', 'Connecting'),
  SENDING: t('جارٍ إرسال الفاتورة', 'Sending invoice'),
  CONNECTION_LOST: t('انقطع الاتصال. قرّب الجهازين مجددًا', 'Connection lost. Tap again to verify'),
}[state] || state)
const errors = {
  PAIR_FIRST: ['اربط الجهازين من الإعدادات أولًا', 'Pair the devices in Settings first'],
  NFC_UNSUPPORTED: ['الجهاز لا يدعم وضع NFC المطلوب', 'This device does not support the required NFC mode'],
  NFC_DISABLED: ['فعّل NFC من الإعدادات', 'Enable NFC in Settings'],
  INVALID_CODE: ['رمز الجهاز غير صالح', 'Invalid device code'],
  INVALID_SETTINGS: ['تحقق من الإعدادات المدخلة', 'Check the settings you entered'],
  INVALID_INVOICE: ['تحقق من حقول الفاتورة والمبالغ', 'Check the invoice fields and amounts'],
  BROWSER: ['هذه معاينة. يتطلب NFC تطبيق Android', 'Preview only. NFC requires the Android app'],
  ERROR: ['تعذّر إكمال العملية', 'Could not complete the operation'],
}
const nfc = computed(() => !native ? t(...errors.BROWSER) : data.nfc === 'ENABLED'
  ? t('NFC مفعّل', 'NFC enabled') : t(...errors[`NFC_${data.nfc}`]))
const row = computed(() => data.records.find(entry => entry.id === selected.value))
const approval = computed(() => !pos && data.receiving
  ? data.records.filter(entry => entry.state === 'PENDING_APPROVAL' && entry.trusted).pop() : null)
const visible = computed(() => approval.value || (page.value === 'detail' ? row.value : null))
const history = computed(() => {
  const term = filter.value.trim().toLowerCase()
  return data.records.filter(entry => [entry.id, entry.request.invoice_number, entry.request.created_at,
    entry.state, status(entry.state)].join(' ').toLowerCase().includes(term))
})
const money = minor => new Intl.NumberFormat(settings.english ? 'en-SA' : 'ar-SA',
  { style: 'currency', currency: 'SAR' }).format(minor / 100)
let timer
let refreshing = false
onMounted(async () => {
  if (!native) return
  await act(async () => {
    Object.assign(settings, await bridge.settings())
    Object.assign(invoice, initialDraft())
  })
  timer = setInterval(() => { if (!document.hidden) refresh().catch(() => {}) }, 500)
})
onUnmounted(() => clearInterval(timer))
watchEffect(() => {
  document.documentElement.lang = settings.english ? 'en' : 'ar'
  document.documentElement.dir = settings.english ? 'ltr' : 'rtl'
  document.documentElement.dataset.theme = settings.theme
  document.title = title.value
})

async function refresh() {
  if (!native || refreshing) return
  refreshing = true
  try { Object.assign(data, await bridge.snapshot()) } finally { refreshing = false }
}
async function act(action, confirmation = '') {
  if (busy.value) return
  busy.value = true
  message.value = ''
  failed.value = false
  try {
    if (!native) throw new Error('BROWSER')
    await action()
    await refresh()
    message.value = confirmation
  } catch (error) {
    failed.value = true
    message.value = t(...(errors[error.message] || errors.ERROR))
  } finally { busy.value = false }
}
async function go(next) {
  if (native && pos && data.active) await act(() => bridge.stop())
  page.value = next
  selected.value = ''
}
function open(entry) {
  selected.value = entry.id
  page.value = 'detail'
  if (pos && entry.delivery !== 'ACKNOWLEDGED') act(() => bridge.start({ id: entry.id }))
}
function submit() {
  act(async () => {
    const entry = await bridge.create({ ...invoice })
    data.records = [entry, ...data.records]
    selected.value = entry.id
    page.value = 'detail'
    Object.assign(settings, await bridge.settings())
    Object.assign(invoice, initialDraft())
    await bridge.start({ id: entry.id })
  })
}
function retry(entry) {
  const req = entry.request
  Object.assign(invoice, { invoice: req.invoice_number, subtotal: (req.subtotal_minor / 100).toFixed(2),
    tax: (req.tax_minor / 100).toFixed(2), discount: (req.discount_minor / 100).toFixed(2),
    item: req.items?.[0]?.item_name || '', description: req.description || '' })
  go('home')
}
function selectAppearance() {
  if (native) act(() => bridge.appearance({ english: settings.english, theme: settings.theme }))
}
function copyCode() {
  act(() => bridge.copyCode(), t('تم نسخ الرمز', 'Code copied'))
}
</script>

<template>
  <main>
    <header>
      <p class="badge">{{ t('نسخة تجريبية · لا يتم خصم أموال', 'Demo · No money is moved') }}</p>
      <h1>{{ title }}</h1>
      <nav :aria-label="t('التنقل', 'Navigation')">
        <button v-for="[key, label] in [['home', t('الرئيسية', 'Home')], ['history', t('سجل العمليات', 'Transactions')], ['settings', t('الإعدادات', 'Settings')]]"
          :key="key" type="button" :aria-current="page === key ? 'page' : undefined" @click="go(key)">{{ label }}</button>
      </nav>
    </header>
    <p v-if="message" class="notice" :class="{ error: failed }" role="status">{{ message }}</p>

    <section v-if="visible" class="card" aria-labelledby="transaction">
      <h2 id="transaction">{{ approval ? t('طلب دفع جديد', 'New payment request') : status(visible.state) }}</h2>
      <strong class="amount">{{ money(visible.request.amount_minor) }}</strong>
      <p>{{ visible.request.merchant_name }} · <bdi>{{ visible.request.terminal_id }}</bdi></p>
      <dl>
        <dt>{{ t('الفاتورة', 'Invoice') }}</dt><dd><bdi>{{ visible.request.invoice_number }}</bdi></dd>
        <dt>{{ t('قبل الضريبة', 'Subtotal') }}</dt><dd>{{ money(visible.request.subtotal_minor) }}</dd>
        <dt>{{ t('الضريبة', 'Tax') }}</dt><dd>{{ money(visible.request.tax_minor) }}</dd>
        <dt>{{ t('الخصم', 'Discount') }}</dt><dd>{{ money(visible.request.discount_minor) }}</dd>
      </dl>
      <ul v-if="visible.request.items?.length">
        <li v-for="item in visible.request.items" :key="item.item_code">
          {{ item.item_name }} × {{ item.quantity }} · {{ money(item.total_minor) }}
        </li>
      </ul>
      <p v-if="visible.request.description">{{ visible.request.description }}</p>
      <p v-if="visible.result" class="meta">
        <bdi>{{ visible.result.decided_at }}</bdi><br>
        <bdi>{{ visible.result.reference || visible.result.reason_code }}</bdi>
      </p>
      <p class="meta"><bdi>{{ visible.id }}</bdi></p>
      <template v-if="pos">
        <p v-if="data.active === visible.id && data.connection && !['RESULT', 'ACKNOWLEDGED'].includes(data.connection)" role="status">{{ status(data.connection) }}</p>
        <button v-if="!visible.result" :disabled="busy" @click="act(() => bridge.start({ id: visible.id, cancel: true }), t('قرّب الجهازين للإلغاء أو لاسترجاع القرار', 'Tap to cancel or retrieve the decision'))">
          {{ t('طلب الإلغاء عند الاتصال', 'Request cancellation when connected') }}
        </button>
        <button v-if="visible.state === 'REJECTED'" :disabled="busy" @click="retry(visible)">{{ t('إعادة المحاولة', 'Retry') }}</button>
      </template>
      <div v-else-if="visible.state === 'PENDING_APPROVAL' && visible.trusted" class="actions">
        <button class="primary" :disabled="busy" @click="act(() => bridge.decide({ id: visible.id, decision: 'ACCEPTED' }))">{{ t('قبول الدفع', 'Accept payment') }}</button>
        <button class="danger" :disabled="busy" @click="act(() => bridge.decide({ id: visible.id, decision: 'REJECTED' }))">{{ t('رفض الدفع', 'Reject payment') }}</button>
      </div>
      <p v-if="visible.result && visible.delivery !== 'ACKNOWLEDGED'">{{ t('قرّب الجهازين مرة أخرى لإتمام التأكيد', 'Tap again to complete confirmation') }}</p>
    </section>

    <section v-else-if="page === 'home' && pos" class="card">
      <h2>{{ t('فاتورة جديدة', 'New invoice') }}</h2>
      <p>{{ nfc }}</p>
      <form @submit.prevent="submit">
        <label>{{ t('رقم الفاتورة', 'Invoice number') }}<input v-model="invoice.invoice" maxlength="120" required></label>
        <label>{{ t('المبلغ قبل الضريبة', 'Subtotal') }}<input v-model="invoice.subtotal" inputmode="decimal" required></label>
        <div class="actions">
          <label>{{ t('الضريبة', 'Tax') }}<input v-model="invoice.tax" inputmode="decimal" required></label>
          <label>{{ t('الخصم', 'Discount') }}<input v-model="invoice.discount" inputmode="decimal" required></label>
        </div>
        <label>{{ t('اسم صنف اختياري', 'Optional item name') }}<input v-model="invoice.item" maxlength="120"></label>
        <label>{{ t('وصف اختياري', 'Optional description') }}<textarea v-model="invoice.description" maxlength="500" rows="2"></textarea></label>
        <button class="primary" :disabled="busy">{{ t('إرسال طلب الدفع', 'Send payment request') }}</button>
      </form>
    </section>

    <section v-else-if="page === 'home'" class="card">
      <h2>{{ t('استقبال طلبات الدفع', 'Receive payment requests') }}</h2>
      <p>{{ nfc }}</p>
      <p>{{ data.receiving ? t('جاهز لاستقبال الطلبات', 'Ready to receive requests') : t('الاستقبال متوقف', 'Receiving is off') }}</p>
      <button class="primary" :disabled="busy" @click="act(() => bridge.receive({ enabled: !data.receiving }))">
        {{ data.receiving ? t('إيقاف الاستقبال', 'Turn off receiving') : t('تفعيل استقبال الدفع', 'Enable receiving') }}
      </button>
      <template v-if="data.records[0]">
        <p>{{ status(data.records[0].state) }} · {{ money(data.records[0].request.amount_minor) }}</p>
        <p v-if="data.records[0].result && data.records[0].delivery !== 'ACKNOWLEDGED'">{{ t('قرّب الجهازين مرة أخرى لإتمام التأكيد', 'Tap again to complete confirmation') }}</p>
      </template>
      <p v-else>{{ t('لا توجد عمليات بعد', 'No transactions yet') }}</p>
    </section>

    <section v-else-if="page === 'history'" class="card">
      <h2>{{ t('سجل العمليات', 'Transactions') }}</h2>
      <label>{{ t('بحث برقم الفاتورة أو الحالة أو التاريخ', 'Search invoice, status or date') }}<input v-model="filter" type="search"></label>
      <p v-if="!history.length">{{ t('لا توجد عمليات مطابقة', 'No matching transactions') }}</p>
      <button v-for="entry in history" :key="entry.id" class="record" @click="open(entry)">
        <bdi>{{ entry.request.invoice_number }}</bdi> · {{ money(entry.request.amount_minor) }}<br>{{ status(entry.state) }}
      </button>
    </section>

    <template v-else-if="page === 'settings'">
      <section class="card">
        <h2>{{ t('إعدادات الجهاز', 'Device settings') }}</h2>
        <p>{{ nfc }}</p>
        <button :disabled="busy" @click="act(() => bridge.nfcSettings())">{{ t('إعدادات NFC', 'NFC settings') }}</button>
        <label>{{ t('اللغة', 'Language') }}<select v-model="settings.english" @change="selectAppearance"><option :value="false">العربية</option><option :value="true">English</option></select></label>
        <label>{{ t('المظهر', 'Appearance') }}<select v-model="settings.theme" @change="selectAppearance">
          <option value="system">{{ t('النظام', 'System') }}</option><option value="light">{{ t('فاتح', 'Light') }}</option><option value="dark">{{ t('داكن', 'Dark') }}</option>
        </select></label>
        <label>{{ t('رمز هذا الجهاز', 'This device’s code') }}<textarea :value="settings.code" readonly dir="ltr" rows="3"></textarea></label>
        <button :disabled="busy" @click="copyCode">{{ t('نسخ رمز هذا الجهاز', 'Copy device code') }}</button>
        <form @submit.prevent="act(() => bridge.pair({ peer: settings.peer }), t('تم حفظ الربط', 'Pairing saved'))">
          <label>{{ t('رمز الجهاز المقابل الموثوق', 'Trusted peer device code') }}<textarea v-model.trim="settings.peer" dir="ltr" rows="3" required></textarea></label>
          <button class="primary" :disabled="busy">{{ t('حفظ الربط', 'Save pairing') }}</button>
        </form>
      </section>
      <section v-if="pos" class="card">
        <h2>{{ t('إعدادات المتجر', 'Merchant settings') }}</h2>
        <form @submit.prevent="act(() => bridge.merchant({ ...settings }), t('تم حفظ الإعدادات', 'Settings saved'))">
          <label>{{ t('اسم المتجر', 'Merchant name') }}<input v-model="settings.merchant" :placeholder="t('متجر غله', 'Ghallah Store')" maxlength="120" required></label>
          <label>{{ t('رقم المتجر', 'Merchant ID') }}<input v-model="settings.merchantId" maxlength="120" required></label>
          <label>{{ t('معرّف نقطة البيع', 'Terminal ID') }}<input v-model="settings.terminal" maxlength="120" required></label>
          <label>{{ t('مدة الصلاحية بالثواني', 'Expiry in seconds') }}<input v-model="settings.expiry" inputmode="numeric" required></label>
          <button class="primary" :disabled="busy">{{ t('حفظ إعدادات المتجر', 'Save merchant settings') }}</button>
        </form>
      </section>
    </template>
  </main>
</template>
