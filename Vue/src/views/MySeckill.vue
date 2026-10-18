<template>
  <div class="my-page">
    <div class="container my-main">
      <div class="my-header">
        <p class="section-label">My Flash Sale</p>
        <h1 class="section-title">我的秒杀</h1>
        <p class="my-sub">抢购记录与成单结果。抢到后订单生成需要几秒，若显示「生成中」请稍后刷新</p>
      </div>

      <div v-if="loading" class="my-empty">加载中…</div>
      <div v-else-if="records.length === 0" class="my-empty">
        <p>还没有参与过秒杀</p>
        <router-link to="/seckill" class="btn btn-outline" style="margin-top:20px">去看看有什么</router-link>
      </div>

      <div v-else class="my-list">
        <div v-for="r in records" :key="r.id" class="my-card">
          <div class="my-card-head">
            <span class="my-status" :class="r.status">{{ recordStatusText(r.status) }}</span>
            <span class="my-time">{{ fmt(r.createdAt) }}</span>
          </div>
          <div class="my-card-body">
            <div class="my-row">
              <span class="my-label">秒杀价</span>
              <span class="my-price">¥{{ r.seckillPrice }}</span>
            </div>
            <div class="my-row">
              <span class="my-label">数量</span>
              <span>{{ r.quantity }}</span>
            </div>
            <div class="my-row">
              <span class="my-label">合计</span>
              <span class="my-price">¥{{ totalOf(r) }}</span>
            </div>
            <div class="my-row" v-if="r.orderNo">
              <span class="my-label">订单号</span>
              <span class="my-mono">{{ r.orderNo }}</span>
            </div>
            <div class="my-row" v-if="r.failReason">
              <span class="my-label">失败原因</span>
              <span class="my-fail">{{ r.failReason }}</span>
            </div>
          </div>
          <div class="my-card-foot">
            <router-link :to="`/seckill/${r.activityId}`" class="b b-outline b-xs">查看活动</router-link>
            <!-- 只有已成单且拿到订单ID才能跳订单详情 -->
            <router-link v-if="r.orderId" :to="`/order/${r.orderId}`" class="b b-primary b-xs">
              查看订单
            </router-link>
            <span v-else-if="r.status === 'pending'" class="my-hint">订单生成中…</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import http from '../utils/http.js'

const records = ref([])
const loading = ref(true)

const recordStatusText = (s) => ({
  pending: '订单生成中',
  success: '已下单',
  failed: '未成功',
  cancelled: '已取消'
}[s] || s)

const fmt = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '')
const totalOf = (r) => (Number(r.seckillPrice || 0) * Number(r.quantity || 0)).toFixed(2)

const fetchRecords = async () => {
  try {
    const { data } = await http.get('/seckill/my')
    if (data.code === 200) records.value = data.data || []
  } catch (_) {
    records.value = []
  } finally {
    loading.value = false
  }
}

onMounted(fetchRecords)
</script>

<style scoped>
.my-page { background: var(--color-bg); min-height: 100vh; padding: 110px 0 80px; }
.my-main { max-width: 1000px; }
.my-header { margin-bottom: 28px; }
.my-sub { font-size: 13px; color: var(--color-text-light); margin-top: 4px; }
.my-empty { text-align: center; padding: 80px 0; color: var(--color-text-light); }

.my-list { display: flex; flex-direction: column; gap: 14px; }
.my-card {
  background: #fff; border: 1px solid var(--color-border);
  border-radius: var(--radius-lg); box-shadow: var(--shadow-card); overflow: hidden;
}
.my-card-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 20px; background: #fcfbf9; border-bottom: 1px solid var(--color-border);
}
.my-status { font-size: 12px; padding: 3px 12px; border-radius: 10px; background: #f0f0f0; color: #888; }
.my-status.success { background: #2e7d5b; color: #fff; }
.my-status.pending { background: var(--color-accent); color: #fff; }
.my-status.failed { background: #c0392b; color: #fff; }
.my-status.cancelled { background: #999; color: #fff; }
.my-time { font-size: 12px; color: var(--color-text-light); }

.my-card-body { padding: 16px 20px; }
.my-row { display: flex; gap: 12px; font-size: 13px; padding: 3px 0; }
.my-label { width: 64px; flex-shrink: 0; color: var(--color-text-light); font-size: 12px; }
.my-price { font-family: var(--font-en); font-weight: 700; color: #c0392b; }
.my-mono { font-family: var(--font-en); font-size: 12px; color: #666; }
.my-fail { color: #c0392b; font-size: 12px; }

.my-card-foot {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 20px; border-top: 1px solid var(--color-border); background: #fcfbf9;
}
.my-hint { font-size: 12px; color: var(--color-accent); }
.b { padding: 5px 14px; border-radius: 4px; font-size: 12px; cursor: pointer; transition: all .2s; }
.b-outline { border: 1px solid var(--color-border); color: var(--color-text); background: #fff; }
.b-outline:hover { border-color: var(--color-accent); color: var(--color-accent); }
.b-primary { background: var(--color-primary); color: #fff; border: 1px solid var(--color-primary); }
.b-primary:hover { background: #2a2a42; }
</style>
