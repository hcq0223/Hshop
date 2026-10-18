<template>
  <div class="page order-detail-page">
    <section class="container" v-if="order">
      <p class="section-label">Order Detail</p>
      <h1 class="section-title">订单详情</h1>

      <div class="card order-info-card">
        <div class="info-row">
          <span class="info-label">订单号</span><span>{{ order.orderNo }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">状态</span>
          <span class="order-status" :class="'status-' + order.status">{{ statusMap[order.status] || order.status }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">创建时间</span><span>{{ order.createTime }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">快递公司</span><span>{{ order.trackingCompany || '暂无' }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">快递单号</span><span>{{ order.trackingNumber || '暂无' }}</span>
        </div>
        <div class="info-row" v-if="order.receiverSnapshot">
          <span class="info-label">收货地址</span><span>{{ order.receiverSnapshot.province + order.receiverSnapshot.city + order.receiverSnapshot.district + order.receiverSnapshot.detailAddress }}</span>
        </div>
        <div class="info-row" v-if="order.receiverSnapshot">
          <span class="info-label">收货人</span><span>{{ order.receiverSnapshot.receiverName }}</span>
        </div>
        <div class="info-row" v-if="order.receiverSnapshot">
          <span class="info-label">联系电话</span><span>{{ order.receiverSnapshot.receiverPhone }}</span>
        </div>
        <div class="info-row" v-if="order.refundReason">
          <span class="info-label">退款原因</span><span>{{ order.refundReason }}</span>
        </div>
        <div class="info-row" v-if="order.refundAmount">
          <span class="info-label">退款金额</span><span>¥{{ order.refundAmount }}</span>
        </div>
      </div>

      <div class="card items-card">
        <h3>商品信息</h3>
        <div class="order-item" v-for="item in (order.items || order.orderItems || [])" :key="item.id">
          <img :src="item.imageUrl || '/images/products/OIP-C.webp'" :alt="item.productName" />
          <div class="item-info">
            <p>{{ item.productName }}</p>
            <p class="sku" v-if="item.skuAttrs">{{ item.skuAttrs }}</p>
            <p class="sales" v-if="item.salesCount !== null && item.salesCount !== undefined">已售 {{ item.salesCount }} 件</p>
          </div>
          <div class="item-right">
            <div class="item-merchant" v-if="item.merchant">
              <img v-if="item.merchant.logoUrl" :src="item.merchant.logoUrl" class="merchant-logo-sm" />
              <span v-else class="merchant-logo-fb-sm">{{ (item.merchant.shopName || '店')[0] }}</span>
              <span class="merchant-name-sm">{{ item.merchant.shopName }}</span>
            </div>
            <span class="item-price-qty">¥{{ item.price }} × {{ item.quantity }}</span>
            <strong>¥{{ (item.price * item.quantity).toFixed(2) }}</strong>
          </div>
        </div>
        <div class="items-total">
          合计：<em>¥{{ order.totalAmount }}</em>
        </div>
      </div>

      <div class="detail-actions">
        <!-- 顾客操作按钮：支付/退款/收货/确认完成 -->
        <template v-if="currentRole === 'customer'">
          <!-- disabled 是连点的第一道防线（paying 由 toPay 控制），
               第二道是后端 payOrder 的 CAS 幂等，两层都要 -->
          <button v-if="order.status === 'pending_pay'" class="btn btn-primary"
                  :disabled="paying" @click="toPay">
            {{ paying ? '处理中…' : '去付款' }}
          </button>
          <button v-if="order.status === 'pending_pay'" class="btn btn-outline"
                  :disabled="paying" @click="cancelOrder">取消订单</button>

          <!-- 已付款：申请退款 -->
          <button v-if="order.status === 'paid'" class="btn btn-outline" @click="openRefundDialog">申请退款</button>

          <!-- 已发货：确认收货 + 申请退货退款 -->
          <button v-if="order.status === 'shipped'" class="btn btn-primary" :disabled="acting" @click="confirmReceive">{{ acting ? '处理中…' : '确认收货' }}</button>
          <button v-if="order.status === 'shipped'" class="btn btn-outline" :disabled="acting" @click="openRefundDialog">申请退货退款</button>

          <!-- 已收货：申请售后 + 确认完成 -->
          <button v-if="order.status === 'received'" class="btn btn-outline" :disabled="acting" @click="openRefundDialog">申请售后</button>
          <button v-if="order.status === 'received' && currentRole !== 'merchant'" class="btn btn-primary" :disabled="acting" @click="confirmComplete">{{ acting ? '处理中…' : '确认完成' }}</button>
        </template>

        <!-- 退款中：仅展示 -->
        <span v-if="order.status === 'refunding'" class="refund-hint">退款处理中，请耐心等待</span>

        <router-link v-if="currentRole === 'merchant'" to="/merchant" class="btn btn-outline">返回订单管理</router-link>
        <router-link v-else-if="currentRole === 'admin'" to="/admin" class="btn btn-outline">返回管理后台</router-link>
        <router-link v-else to="/orders" class="btn btn-outline">返回订单列表</router-link>
      </div>
    </section>

    <div class="container" v-else-if="loading">加载中...</div>
    <div class="container" v-else>订单不存在</div>

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
          <input type="number" v-model="refundForm.refundAmount" :placeholder="'最多 ¥' + (order ? order.payAmount : '')" />
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
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import http from '../utils/http.js'
import { currentRole } from '../stores/auth.js'

const route = useRoute()
const order = ref(null)
const loading = ref(true)
const refundVisible = ref(false)
const refundForm = reactive({ refundType: 'refund', refundAmount: null, refundReason: '' })

const statusMap = {
  pending_pay: '待付款', paid: '待发货', shipped: '待收货',
  received: '已收货', completed: '已完成', cancelled: '已取消', refunding: '退款中'
}

const fetchOrder = async () => {
  try {
    const { data } = await http.get(`/order/detail/${route.params.id}`)
    if (data.code === 200) order.value = data.data
  } catch (_) {}
  loading.value = false
}

/**
 * 是否正在处理付款请求。
 *
 * 用途：① 连点保护 ② 按钮显示 loading。
 * 只靠后端幂等也能保证「同一订单只结算一次」，但每次点击仍会打到后端、
 * 各开一个事务 —— 连点 10 次就是 10 个事务。前端这道闸门把无效请求挡在浏览器里，
 * 后端那道（CAS）负责真正的正确性，两层都要有。
 */
const paying = ref(false)
// 确认收货 / 确认完成 的连点保护：请求未返回前忽略后续点击，
// 避免同一笔订单被连点两次（第二次会拿到「状态不可操作」的噪声错误）。
const acting = ref(false)

const toPay = async () => {
  // 连点保护：请求未返回前直接忽略后续点击
  if (paying.value) return
  paying.value = true
  try {
    const { data } = await http.put(`/order/pay/${order.value.id}`)
    if (data.code === 200) {
      await fetchOrder()
    } else {
      // 后端返回了业务失败（如商家资金变更失败）。
      // 必须明确提示：原实现在这里什么都不做，用户点完毫无反馈，
      // 只能靠反复点击试探 —— 这正是「连点」的来源之一。
      alert(data.message || '支付失败，请稍后重试')
    }
  } catch (e) {
    // 网络异常 / 401 等。
    // 注意：这里【不再】回退调 PUT /order/status/{id}。
    // 那个接口是已废弃的「直接改状态」入口，它只改订单状态、
    // 不结算商家资金 —— 会让订单显示「已支付」但钱没到商户账上，
    // 是比报错严重得多的静默数据不一致。
    alert(e.response?.data?.message || e.message || '网络异常，请稍后重试')
  } finally {
    // finally 保证任何分支都能解锁，否则一次失败后按钮会永久卡住
    paying.value = false
  }
}

const cancelOrder = async () => {
  try {
    const { data } = await http.post(`/order/cancel/${order.value.id}`)
    if (data.code === 200) fetchOrder()
  } catch (_) {}
}

const confirmReceive = async () => {
  if (acting.value) return
  acting.value = true
  try {
    const { data } = await http.put(`/order/receive/${order.value.id}`)
    if (data.code === 200) { await fetchOrder() } else { alert(data.message || '确认收货失败') }
  } catch (e) {
    // 与 toPay 同理：不再回退调 PUT /order/status/{id}。
    // 传输层抖动时重复发一次写请求，会在已经成功的订单上再写一遍，
    // 拿到「当前订单状态不可确认收货」的噪声错误，掩盖真实结果。
    alert(e.response?.data?.message || e.message || '网络异常，请稍后重试')
  } finally { acting.value = false }
}

const confirmComplete = async () => {
  if (acting.value) return
  acting.value = true
  try {
    const { data } = await http.put(`/order/complete/${order.value.id}`)
    if (data.code === 200) { await fetchOrder() } else { alert(data.message || '确认完成失败') }
  } catch (e) {
    alert(e.response?.data?.message || e.message || '网络异常，请稍后重试')
  } finally { acting.value = false }
}

const openRefundDialog = () => {
  refundForm.refundType = order.value.status === 'paid' ? 'refund' : 'return_refund'
  refundForm.refundAmount = order.value.payAmount
  refundForm.refundReason = ''
  refundVisible.value = true
}

const submitRefund = async () => {
  try {
    const { data } = await http.post(`/order/refund/request/${order.value.id}`, refundForm)
    if (data.code === 200) { refundVisible.value = false; fetchOrder() }
  } catch (_) {}
}

onMounted(fetchOrder)
</script>

<style scoped>
.order-detail-page { padding: 120px 0 80px; }

.card { background: #fff; border-radius: var(--radius); box-shadow: var(--shadow-card); padding: 24px 28px; margin-bottom: 20px; }

.info-row { display: flex; padding: 10px 0; font-size: 14px; border-bottom: 1px solid var(--color-border); }
.info-row:last-child { border-bottom: none; }
.info-label { width: 100px; flex-shrink: 0; color: var(--color-text-light); }
.order-status { font-weight: 600; }
.status-pending_pay { color: var(--color-accent); }
.status-paid { color: #2980b9; }
.status-shipped { color: #8e44ad; }
.status-received { color: #27ae60; }
.status-completed { color: #27ae60; }
.status-cancelled { color: #999; }
.status-refunding { color: #e67e22; }

.items-card h3 { font-size: 16px; margin-bottom: 16px; }
.order-item { display: flex; align-items: center; padding: 12px 0; border-bottom: 1px solid var(--color-border); gap: 12px; }
.order-item > img { width: 72px !important; height: 72px !important; object-fit: cover; border-radius: 4px; }
.item-info { flex: 1; font-size: 14px; }
.item-info p { margin-bottom: 4px; }
.sku { font-size: 12px; color: #999; }
.sales { font-size: 12px; color: var(--color-accent); }
.item-right { text-align: right; display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }
.item-merchant { display: flex; align-items: center; gap: 6px; margin-bottom: 2px; }
.merchant-logo-sm { width: 20px !important; height: 20px !important; min-width: 20px !important; min-height: 20px !important; max-width: 20px !important; max-height: 20px !important; border-radius: 4px; object-fit: cover; border: 1px solid var(--color-accent); }
.merchant-logo-fb-sm { width: 20px; height: 20px; border-radius: 4px; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #1a1a2e, #2d2d44); color: #fff; font-size: 10px; font-weight: 700; border: 1px solid var(--color-accent); }
.merchant-name-sm { font-size: 11px; color: var(--color-text-light); }
.item-price-qty { font-size: 13px; color: var(--color-text-light); }
.order-item strong { font-family: var(--font-en); font-size: 14px; }

.items-total { text-align: right; padding-top: 16px; font-size: 14px; }
.items-total em { font-family: var(--font-en); font-size: 20px; font-weight: 700; color: #c0392b; font-style: normal; margin-left: 4px; }

.detail-actions { display: flex; gap: 12px; margin-top: 24px; align-items: center; flex-wrap: wrap; }
.refund-hint { font-size: 14px; color: #e67e22; font-weight: 600; }

/* 退款弹窗 */
.modal-mask { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.45); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal-card { background: #fff; border-radius: var(--radius); padding: 28px; width: 420px; max-width: 90vw; box-shadow: 0 12px 40px rgba(0,0,0,0.15); }
.modal-card h3 { margin-bottom: 20px; font-size: 18px; }
.form-group { margin-bottom: 14px; }
.form-group label { display: block; font-size: 13px; color: var(--color-text-light); margin-bottom: 4px; }
.form-group select, .form-group input, .form-group textarea { width: 100%; padding: 8px 12px; border: 1px solid var(--color-border); border-radius: 6px; font-size: 14px; font-family: var(--font-base); }
.modal-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 8px; }
</style>
