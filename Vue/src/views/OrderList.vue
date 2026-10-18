<template>
  <div class="page order-page">
    <section class="container">
      <div class="order-header-row">
        <div class="slide-in-left">
          <p class="section-label">My Orders</p>
          <h1 class="section-title">我的订单</h1>
        </div>
      </div>

      <!-- 状态筛选 -->
      <div class="status-tabs">
        <button :class="{ active: activeStatus === '' }" @click="activeStatus = ''; fetchOrders()">全部</button>
        <button :class="{ active: activeStatus === 'pending_pay' }" @click="activeStatus = 'pending_pay'; fetchOrders()">待付款</button>
        <button :class="{ active: activeStatus === 'paid' }" @click="activeStatus = 'paid'; fetchOrders()">待发货</button>
        <button :class="{ active: activeStatus === 'shipped' }" @click="activeStatus = 'shipped'; fetchOrders()">待收货</button>
        <button :class="{ active: activeStatus === 'received' }" @click="activeStatus = 'received'; fetchOrders()">已收货</button>
        <button :class="{ active: activeStatus === 'refunding' }" @click="activeStatus = 'refunding'; fetchOrders()">退款中</button>
        <button class="btn-history-link" @click="$router.push('/order-history')">历史订单 →</button>
      </div>

      <div v-if="orders.length > 0">
        <div class="order-card" v-for="order in orders" :key="order.id">
          <div class="order-header">
            <span class="order-no">订单号：{{ order.orderNo }}</span>
            <div class="order-header-right">
              <span v-if="order.status === 'pending_pay' && countdowns[order.id] != null && countdowns[order.id] >= 0" class="countdown" :class="{ 'countdown-expiring': countdowns[order.id] <= 60 }">
                {{ formatCountdown(countdowns[order.id]) }}
              </span>
              <span class="order-status" :class="'status-' + order.status">{{ statusMap[order.status] || order.status }}</span>
            </div>
          </div>
          <div class="order-items">
            <div class="order-item" v-for="item in (order.items || order.orderItems || [])" :key="item.id">
              <img :src="item.imageUrl || '/images/products/OIP-C.webp'" :alt="item.productName" />
              <div class="item-detail">
                <p class="item-name">{{ item.productName }}</p>
                <p class="item-sku" v-if="item.skuAttrs">{{ item.skuAttrs }}</p>
              </div>
              <div class="item-right">
                <div class="item-merchant" v-if="item.merchant">
                  <img v-if="item.merchant.logoUrl" :src="item.merchant.logoUrl" class="merchant-logo-xs" />
                  <span v-else class="merchant-logo-fb-xs">{{ (item.merchant.shopName || '店')[0] }}</span>
                  <span class="merchant-name-xs">{{ item.merchant.shopName }}</span>
                </div>
                <span class="item-price">¥{{ item.price }} × {{ item.quantity }}</span>
              </div>
            </div>
          </div>
          <div class="order-footer">
            <span class="order-total">共 {{ (order.items || order.orderItems || []).reduce((s, i) => s + (i.quantity || 0), 0) }} 件，合计：<em>¥{{ order.totalAmount }}</em></span>
            <div class="order-actions">
              <button v-if="order.status === 'pending_pay'" class="btn btn-primary btn-sm" :disabled="isActing(order.id)" @click="toPay(order)">{{ isActing(order.id) ? '处理中…' : '去付款' }}</button>
              <button v-if="order.status === 'pending_pay'" class="btn btn-outline btn-sm" :disabled="isActing(order.id)" @click="cancelOrder(order)">取消订单</button>
              <button v-if="order.status === 'paid'" class="btn btn-outline btn-sm" :disabled="isActing(order.id)" @click="openRefund(order)">申请退款</button>
              <button v-if="order.status === 'shipped'" class="btn btn-primary btn-sm" :disabled="isActing(order.id)" @click="confirmReceive(order)">{{ isActing(order.id) ? '处理中…' : '确认收货' }}</button>
              <button v-if="order.status === 'shipped'" class="btn btn-outline btn-sm" :disabled="isActing(order.id)" @click="openRefund(order)">申请退货退款</button>
              <button v-if="order.status === 'received'" class="btn btn-outline btn-sm" :disabled="isActing(order.id)" @click="openRefund(order)">申请售后</button>
              <button v-if="order.status === 'received' && currentRole !== 'merchant'" class="btn btn-primary btn-sm" :disabled="isActing(order.id)" @click="confirmComplete(order)">{{ isActing(order.id) ? '处理中…' : '确认完成' }}</button>
              <button v-if="order.status === 'received' && order.isReviewed !== 1" class="btn btn-accent btn-sm" @click="openReview(order)">去评价</button>
              <span v-else-if="order.status === 'received' && order.isReviewed === 1" class="reviewed-tag">已评价</span>
              <button v-if="order.status === 'refunding'" class="btn btn-outline btn-sm disabled" disabled>退款处理中</button>
              <button class="btn btn-outline btn-sm" @click="$router.push(`/order/${order.id}`)">查看详情</button>
            </div>
          </div>
        </div>

        <!-- 翻页 -->
        <div class="pagination" v-if="totalPages > 1">
          <button :disabled="page <= 1" @click="page--; fetchOrders()">上一页</button>
          <span>{{ page }} / {{ totalPages }}</span>
          <button :disabled="page >= totalPages" @click="page++; fetchOrders()">下一页</button>
        </div>
      </div>

      <div v-else-if="!loading" class="empty">暂无订单</div>
      <div v-else class="empty">加载中...</div>
    </section>

    <!-- 退款弹窗 -->
    <div class="modal-mask" v-if="refundVisible" @click.self="refundVisible = false">
      <div class="modal-card">
        <h3>申请退款</h3>
        <div class="form-group">
          <label>退款类型</label>
          <select v-model="refundForm.refundType">
            <option value="refund">仅退款</option>
            <option value="return_refund">退货退款</option>
          </select>
        </div>
        <div class="form-group">
          <label>退款金额</label>
          <input type="number" v-model="refundForm.refundAmount" :placeholder="'最多 ¥' + (refundOrder ? refundOrder.payAmount : '')" />
        </div>
        <div class="form-group">
          <label>退款原因</label>
          <textarea v-model="refundForm.refundReason" placeholder="请描述退款原因" rows="3"></textarea>
        </div>
        <div class="modal-actions">
          <button class="btn btn-outline btn-sm" @click="refundVisible = false">取消</button>
          <button class="btn btn-primary btn-sm" @click="submitRefund">提交申请</button>
        </div>
      </div>
    </div>
    <!-- 评价弹窗 -->
    <div class="modal-mask" v-if="reviewVisible" @click.self="reviewVisible = false">
      <div class="modal-card">
        <h3>评价订单</h3>
        <div class="form-group">
          <label>评分</label>
          <div class="star-picker">
            <span v-for="s in 5" :key="s" class="star-item" :class="{ active: reviewForm.rating >= s }" @click="reviewForm.rating = s">
              {{ reviewForm.rating >= s ? '★' : '☆' }}
            </span>
          </div>
        </div>
        <div class="form-group">
          <label>评价内容</label>
          <textarea v-model="reviewForm.content" placeholder="说说你的购物体验..." rows="3"></textarea>
        </div>
        <p v-if="reviewMsg" class="msg" :class="reviewOk ? 'msg-success' : 'msg-error'">{{ reviewMsg }}</p>
        <div class="modal-actions">
          <button class="btn btn-outline btn-sm" @click="reviewVisible = false">取消</button>
          <button class="btn btn-primary btn-sm" @click="submitReview" :disabled="reviewLoading">{{ reviewLoading ? '提交中...' : '提交评价' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import http from '../utils/http.js'
import { currentRole } from '../stores/auth.js'

const orders = ref([])
const loading = ref(true)
const activeStatus = ref('')
const page = ref(1)
const size = ref(10)
const totalPages = ref(1)
const countdowns = reactive({})
let countdownTimer = null

const refundVisible = ref(false)
const refundOrder = ref(null)
const refundForm = reactive({ refundType: 'refund', refundAmount: null, refundReason: '' })

// 评价
const reviewVisible = ref(false)
const reviewOrder = ref(null)
const reviewLoading = ref(false)
const reviewMsg = ref('')
const reviewOk = ref(false)
const reviewForm = reactive({ rating: 5, content: '' })

const statusMap = {
  pending_pay: '待付款', paid: '待发货', shipped: '待收货',
  received: '已收货', completed: '已完成', cancelled: '已取消', refunding: '退款中'
}

/** 拉取订单列表（不改动界面），失败返回 null */
const loadOrders = async () => {
  try {
    const params = { page: page.value, size: size.value }
    if (activeStatus.value) params.status = activeStatus.value
    const { data } = await http.get('/order/customer/orders', { params })
    if (data.code === 200) {
      return {
        list: Array.isArray(data.data) ? data.data : (data.data?.records || []),
        total: data.data?.total,
      }
    }
  } catch (_) {}
  return null
}

/** 应用订单列表并重算倒计时 */
const applyOrders = ({ list, total }) => {
  orders.value = list
  totalPages.value = Math.ceil((total || list.length || 0) / size.value)
  initCountdowns()
}

const fetchOrders = async () => {
  const res = await loadOrders()
  if (res) applyOrders(res)
  loading.value = false
}

/* ──────────────────────────────────────────────────────────────────────────
 * 写操作后的列表同步
 *
 * 订单列表读的是 ES 投影（order 服务写库 → 发 order.changed → shopai-search 消费后
 * 重建索引），而不是直接读库。实测写接口返回时 ES 还没同步完（约 100~300ms），
 * 此时立刻回读会拿到【旧状态】：
 *   1. 卡片上的按钮不消失，用户以为没点成功 → 重复点击；
 *   2. 等 ES 同步落地时那笔订单才变，而用户此时已经在点【下一张卡片】，
 *      看起来就像"点了下一单，上一单却变了"。
 *
 * 因此写成功后：先就地更新本地状态（乐观更新）让界面立刻响应，再带重试静默回读，
 * 等 ES 追平后再以索引数据为准。配合 isActing 防连点，彻底去掉这个错觉。
 * ────────────────────────────────────────────────────────────────────────── */
const actingIds = ref(new Set())
const isActing = (id) => actingIds.value.has(id)

/** 从业务错误对象或 axios 错误里提取可展示的文案 */
const errText = (e, fallback) => {
  if (!e) return fallback
  // 后端 Result（code != 200）不是 Error 实例，只有 message
  if (!e.isAxiosError && e.message) return e.message
  return e.response?.data?.message || e.message || fallback
}

/** 状态变更后就地更新当前页，避免依赖滞后的 ES 回读 */
const applyLocalStatus = (order, status) => {
  const idx = orders.value.findIndex(o => o.id === order.id)
  if (idx < 0) return
  // completed / cancelled 是终态，"我的订单"默认不展示；按状态筛选时也必须仍满足筛选条件
  const isFinal = status === 'completed' || status === 'cancelled'
  const matchesFilter = !activeStatus.value || activeStatus.value === status
  if ((isFinal && !activeStatus.value) || !matchesFilter) {
    orders.value.splice(idx, 1)
    return
  }
  orders.value[idx].status = status
}

/**
 * 等 ES 投影追上这次写入，再以索引数据为准。
 * 只有「确实追上」才覆盖界面：否则刚被 applyLocalStatus 移除/改状态的卡片
 * 会在第一次回读时闪回来，等于把问题换了个形式。
 * 多轮仍未追上则兜底刷新一次（不会一直错下去）。
 */
const refetchUntil = async (orderId, expectStatus, tries = 6, delay = 300) => {
  for (let i = 0; i < tries; i += 1) {
    await new Promise(resolve => setTimeout(resolve, delay))
    const res = await loadOrders()
    if (!res) continue
    const found = res.list.find(o => o.id === orderId)
    if (!found || found.status === expectStatus) {
      applyOrders(res)
      return
    }
  }
  await fetchOrders()
}

/**
 * 统一的写操作包装：防连点 → 乐观更新 → 等待 ES 收敛
 *
 * @param order        当前操作的订单
 * @param expectStatus 成功后的目标状态
 * @param request      发起写请求的函数（返回 axios 响应）
 * @param failText     失败时的兜底提示文案
 * @returns {Promise<boolean>} 是否成功
 */
const runAction = async (order, expectStatus, request, failText) => {
  if (actingIds.value.has(order.id)) return false
  actingIds.value.add(order.id)
  try {
    const { data } = await request()
    if (data.code !== 200) { alert(errText(data, failText)); return false }
    applyLocalStatus(order, expectStatus)
    await refetchUntil(order.id, expectStatus)
    return true
  } catch (e) {
    alert(errText(e, failText))
    return false
  } finally {
    actingIds.value.delete(order.id)
  }
}

// 去支付：注意【不再】回退调 PUT /order/status/{id}。
// 那个废弃入口只改订单状态、不结算商家资金，会让订单显示"已支付"但钱没到商户账上。
const toPay = (order) => runAction(order, 'paid',
  () => http.put(`/order/pay/${order.id}`), '支付失败，请稍后重试')

const cancelOrder = async (order) => {
  const ok = await runAction(order, 'cancelled',
    () => http.post(`/order/cancel/${order.id}`), '取消失败，请稍后重试')
  if (ok) delete countdowns[order.id]
}

const confirmReceive = (order) => runAction(order, 'received',
  () => http.put(`/order/receive/${order.id}`), '确认收货失败，请稍后重试')

const confirmComplete = (order) => runAction(order, 'completed',
  () => http.put(`/order/complete/${order.id}`), '确认完成失败，请稍后重试')

const openRefund = (order) => {
  refundOrder.value = order
  refundForm.refundType = order.status === 'paid' ? 'refund' : 'return_refund'
  refundForm.refundAmount = order.payAmount
  refundForm.refundReason = ''
  refundVisible.value = true
}

const submitRefund = async () => {
  const target = refundOrder.value
  if (!target) return
  const ok = await runAction(target, 'refunding',
    () => http.post(`/order/refund/request/${target.id}`, refundForm), '退款申请失败，请稍后重试')
  if (ok) refundVisible.value = false
}

// 评价
const openReview = (order) => {
  reviewOrder.value = order
  reviewForm.rating = 5
  reviewForm.content = ''
  reviewMsg.value = ''
  reviewVisible.value = true
}

const submitReview = async () => {
  if (!reviewForm.rating) { reviewMsg.value = '请选择评分'; reviewOk.value = false; return }
  reviewLoading.value = true
  try {
    // 列表数据来自 ES 索引，索引里只保留「件数/商品名摘要/主图」，没有 productId；
    // 而评价必须落到真实商品上（否则 productId 为空，评价不会出现在商品页、也不会计入店铺评分）。
    // 因此这里按需回查一次订单详情 —— 详情走库并已是权威数据，字段完整。
    let firstItem = reviewOrder.value?.items?.[0] || reviewOrder.value?.orderItems?.[0] || null
    if (!firstItem?.productId) {
      try {
        const { data: detail } = await http.get(`/order/detail/${reviewOrder.value.id}`)
        if (detail.code === 200 && detail.data?.orderItems?.length) {
          firstItem = detail.data.orderItems[0]
        }
      } catch (_) { /* 回查失败则在下面统一报错 */ }
    }
    const productId = firstItem?.productId ?? null
    if (!productId) {
      reviewOk.value = false
      reviewMsg.value = '未取到商品信息，请进入订单详情页再评价'
      return
    }
    const { data } = await http.post('/review/add', {
      orderId: reviewOrder.value.id,
      customerId: null,
      productId,
      // 列表里的订单项不带 merchant，订单本身带（索引已冗余 merchantId）
      merchantId: firstItem?.merchant?.id || reviewOrder.value.merchantId || reviewOrder.value.merchant?.id || null,
      rating: reviewForm.rating,
      content: reviewForm.content,
    })
    if (data.code === 200) {
      reviewOk.value = true
      reviewMsg.value = '评价提交成功'
      reviewOrder.value.isReviewed = 1
      setTimeout(() => { reviewVisible.value = false }, 1200)
    } else {
      reviewOk.value = false
      reviewMsg.value = data.message || '提交失败'
    }
  } catch (e) {
    reviewOk.value = false
    reviewMsg.value = e.response?.data?.message || e.message || '网络异常'
  } finally {
    reviewLoading.value = false
  }
}


/* 倒计时 */
const PAY_DEADLINE_MS = 15 * 60 * 1000
const LS_KEY = 'order_pay_deadlines'

const getStoredDeadlines = () => {
  try { return JSON.parse(localStorage.getItem(LS_KEY)) || {} } catch (_) { return {} }
}
const saveStoredDeadlines = (map) => { localStorage.setItem(LS_KEY, JSON.stringify(map)) }

const initCountdowns = () => {
  if (countdownTimer) clearInterval(countdownTimer)
  const stored = getStoredDeadlines()
  for (const order of orders.value) {
    if (order.status !== 'pending_pay') continue
    if (!stored[order.id]) stored[order.id] = Date.now() + PAY_DEADLINE_MS
  }
  saveStoredDeadlines(stored)

  const tick = async () => {
    const stored = getStoredDeadlines()
    let changed = false
    for (const order of orders.value) {
      if (order.status !== 'pending_pay') continue
      const deadline = stored[order.id]
      if (!deadline) continue
      const left = Math.max(0, Math.floor((deadline - Date.now()) / 1000))
      countdowns[order.id] = left
      if (left <= 0) {
        try { await http.post(`/order/cancel/${order.id}`) } catch (_) {}
        delete stored[order.id]
        changed = true
      }
    }
    if (changed) { saveStoredDeadlines(stored); fetchOrders() }
  }

  tick()
  countdownTimer = setInterval(tick, 1000)
}

const formatCountdown = (seconds) => {
  if (seconds === undefined || seconds === null || seconds <= 0) return '00:00'
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return m + ':' + String(s).padStart(2, '0')
}

onMounted(fetchOrders)
onUnmounted(() => { if (countdownTimer) clearInterval(countdownTimer) })
</script>

<style scoped>
.order-page { padding: 120px 0 80px; }
.order-header-row { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 32px; }
.slide-in-left { animation: slideLeft 0.6s ease both; }
@keyframes slideLeft { from { opacity: 0; transform: translateX(-40px); } to { opacity: 1; transform: translateX(0); } }

.status-tabs { display: flex; gap: 8px; margin-bottom: 32px; flex-wrap: wrap; }
.status-tabs button { padding: 8px 20px; border: 1px solid var(--color-border); background: #fff; border-radius: 20px; font-size: 13px; cursor: pointer; color: var(--color-text); transition: all var(--transition); }
.status-tabs button.active { background: var(--color-primary); color: #fff; border-color: var(--color-primary); }

.order-card { background: #fff; border-radius: var(--radius); box-shadow: var(--shadow-card); margin-bottom: 20px; overflow: hidden; }
.order-header { display: flex; justify-content: space-between; align-items: center; padding: 14px 20px; background: var(--color-bg); font-size: 13px; }
.order-header-right { display: flex; align-items: center; gap: 16px; }
.order-no { color: var(--color-text-light); }
.order-status { font-weight: 600; }
.status-pending_pay { color: var(--color-accent); }
.status-paid { color: #2980b9; }
.status-shipped { color: #8e44ad; }
.status-received { color: #27ae60; }
.status-completed { color: #27ae60; }
.status-cancelled { color: #999; }
.status-refunding { color: #e67e22; }

.order-item { display: flex; align-items: center; padding: 12px 20px; border-bottom: 1px solid var(--color-border); gap: 12px; }
.order-item > img { width: 72px !important; height: 72px !important; object-fit: cover; border-radius: 4px; }
.item-detail { flex: 1; }
.item-name { font-size: 14px; margin-bottom: 4px; }
.item-sku { font-size: 12px; color: #999; }
.item-right { text-align: right; display: flex; flex-direction: column; align-items: flex-end; gap: 3px; }
.item-merchant { display: flex; align-items: center; gap: 4px; }
.merchant-logo-xs { width: 20px !important; height: 20px !important; min-width: 20px !important; min-height: 20px !important; max-width: 20px !important; max-height: 20px !important; border-radius: 4px; object-fit: cover; border: 1px solid var(--color-accent); }
.merchant-logo-fb-xs { width: 20px; height: 20px; border-radius: 4px; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #1a1a2e, #2d2d44); color: #fff; font-size: 10px; font-weight: 700; border: 1px solid var(--color-accent); }
.merchant-name-xs { font-size: 11px; color: var(--color-text-light); }
.item-price { font-size: 13px; color: var(--color-text-light); }

.order-footer { display: flex; justify-content: space-between; align-items: center; padding: 16px 20px; }
.order-total { font-size: 14px; }
.order-total em { font-family: var(--font-en); font-size: 18px; font-weight: 700; color: #c0392b; font-style: normal; margin-left: 4px; }
.order-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.countdown { font-family: var(--font-en); font-size: 13px; font-weight: 600; color: var(--color-accent); white-space: nowrap; }
.countdown-expiring { color: #e74c3c; animation: pulse-red 1s infinite; }
@keyframes pulse-red { 0%, 100% { opacity: 1; } 50% { opacity: 0.5; } }

.btn-outline.btn-sm.disabled { opacity: 0.5; cursor: not-allowed; }

.btn-accent { background: var(--color-accent); color: #fff; border-color: var(--color-accent); }
.btn-accent:hover { background: #d4b87a; }
.reviewed-tag { font-size: 12px; color: #999; padding: 6px 12px; }

/* 评价弹窗 */
.star-picker { display: flex; gap: 4px; }
.star-item { font-size: 28px; color: #ccc; cursor: pointer; transition: color 0.2s; }
.star-item.active { color: var(--color-accent); }
.msg { font-size: 13px; margin-bottom: 8px; }
.msg-error { color: #d44; }
.msg-success { color: #27ae60; }

/* 退款弹窗 */
.modal-mask { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.45); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal-card { background: #fff; border-radius: var(--radius); padding: 28px; width: 420px; max-width: 90vw; box-shadow: 0 12px 40px rgba(0,0,0,0.15); }
.modal-card h3 { margin-bottom: 20px; font-size: 18px; }
.form-group { margin-bottom: 14px; }
.form-group label { display: block; font-size: 13px; color: var(--color-text-light); margin-bottom: 4px; }
.form-group select, .form-group input, .form-group textarea { width: 100%; padding: 8px 12px; border: 1px solid var(--color-border); border-radius: 6px; font-size: 14px; font-family: var(--font-base); }
.modal-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 8px; }

.pagination { display: flex; justify-content: center; align-items: center; gap: 20px; padding: 40px 0; }
.pagination button { padding: 8px 20px; border: 1px solid var(--color-border); background: #fff; border-radius: var(--radius); cursor: pointer; }
.pagination button:disabled { opacity: 0.4; cursor: not-allowed; }
.empty { text-align: center; padding: 80px 0; color: #999; }
.btn-history-link { padding: 8px 20px; border: 1px dashed var(--color-primary); background: transparent; border-radius: 20px; font-size: 13px; cursor: pointer; color: var(--color-primary); transition: all var(--transition); margin-left: auto; }
.btn-history-link:hover { background: rgba(26, 26, 46, 0.05); }
</style>