<template>
  <div class="history-page">
    <section class="container">
      <div class="history-header-row">
        <div class="slide-in-left">
          <p class="section-label">Order History</p>
          <h1 class="section-title">订单历史</h1>
        </div>
        <button class="btn btn-outline" @click="goBack">返回</button>
      </div>

      <div class="history-tip">
        <p>已取消和已完成的订单会从主订单列表中移到此页面长期保存。</p>
      </div>

      <div v-if="orders.length > 0">
        <div class="order-card" v-for="order in orders" :key="order.id">
          <div class="order-header">
            <span class="order-no">订单号：{{ order.orderNo }}</span>
            <span class="order-status" :class="'status-' + order.status">{{ statusMap[order.status] || order.status }}</span>
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
            <span class="order-total">共 {{ (order.items || order.orderItems || []).reduce((s, i) => s + (i.quantity || 0), 0) }} 件，合计：<em>¥{{ order.payAmount || order.totalAmount }}</em></span>
            <button class="btn btn-outline btn-sm" @click="$router.push(`/order/${order.id}`)">查看详情</button>
          </div>
        </div>
      </div>

      <div v-else-if="!loading" class="empty">暂无历史订单</div>
      <div v-else class="empty">加载中...</div>
    </section>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import http from '../utils/http.js'

const router = useRouter()
const orders = ref([])
const loading = ref(true)

const statusMap = {
  pending_pay: '待付款', paid: '待发货', shipped: '待收货',
  received: '已收货', completed: '已完成', cancelled: '已取消', refunding: '退款中'
}

const goBack = () => {
  // 根据角色返回不同页面
  const role = historyRole()
  if (role === 'admin') router.push('/admin')
  else if (role === 'merchant') router.push('/merchant')
  else router.push('/orders')
}

const historyRole = () => {
  // 通过 URL 路径判断角色
  const path = router.currentRoute.value?.path || ''
  if (path.startsWith('/admin')) return 'admin'
  if (path.startsWith('/merchant')) return 'merchant'
  return 'customer'
}

const fetchHistoryOrders = async () => {
  try {
    const role = historyRole()
    let endpoint, params
    if (role === 'admin') {
      endpoint = '/order/admin/history'
    } else if (role === 'merchant') {
      endpoint = '/order/merchant/history'
    } else {
      endpoint = '/order/customer/history'
    }
    const { data } = await http.get(endpoint, { params })
    if (data.code === 200) {
      orders.value = Array.isArray(data.data) ? data.data : (data.data?.records || [])
    }
  } catch (_) {}
  loading.value = false
}

onMounted(fetchHistoryOrders)
</script>

<style scoped>
.history-page { padding: 120px 0 80px; }
.history-header-row { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 8px; }
.slide-in-left { animation: slideLeft 0.6s ease both; }
@keyframes slideLeft { from { opacity: 0; transform: translateX(-40px); } to { opacity: 1; transform: translateX(0); } }

.history-tip { margin-bottom: 24px; padding: 12px 16px; background: #f8f9fa; border-radius: 8px; border-left: 3px solid var(--color-primary); }
.history-tip p { font-size: 13px; color: var(--color-text-light); margin: 0; }

.order-card { background: #fff; border-radius: var(--radius); box-shadow: var(--shadow-card); margin-bottom: 20px; overflow: hidden; }
.order-header { display: flex; justify-content: space-between; align-items: center; padding: 14px 20px; background: var(--color-bg); font-size: 13px; }
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

.empty { text-align: center; padding: 80px 0; color: #999; }
</style>
