<template>
  <div class="sk-page">
    <!-- 头部 -->
    <div class="sk-hero">
      <div class="container">
        <p class="section-label">Flash Sale</p>
        <h1 class="sk-hero-title">限时秒杀</h1>
        <p class="sk-hero-desc">每人限购，数量有限，抢完即止</p>
      </div>
    </div>

    <div class="container sk-main">
      <!-- 加载中 -->
      <div v-if="loading" class="sk-empty">加载中…</div>

      <!-- 空态 -->
      <div v-else-if="activities.length === 0" class="sk-empty">
        <p class="sk-empty-title">暂无进行中的秒杀活动</p>
        <p class="sk-empty-sub">新活动上线后会第一时间在这里出现</p>
        <router-link to="/products" class="btn btn-outline">去逛逛商品</router-link>
      </div>

      <!-- 活动列表 -->
      <div v-else class="sk-list">
        <div v-for="act in activities" :key="act.id" class="sk-card">
          <!-- 活动头 -->
          <div class="sk-card-head">
            <div class="sk-card-head-left">
              <h3 class="sk-card-title">{{ act.title }}</h3>
              <p class="sk-card-time">{{ fmtTime(act.startTime) }} — {{ fmtTime(act.endTime) }}</p>
            </div>
            <div class="sk-card-head-right">
              <!-- 进行中：倒计时到结束；未开始：倒计时到开始 -->
              <div class="sk-countdown" :class="phase(act)">
                <span class="sk-cd-label">{{ phaseLabel(act) }}</span>
                <span class="sk-cd-value">{{ countdownText(act) }}</span>
              </div>
              <span class="sk-status-tag" :class="phase(act)">{{ statusText(act.status) }}</span>
            </div>
          </div>

          <!-- 活动说明 -->
          <p v-if="act.description" class="sk-card-desc">{{ act.description }}</p>

          <!-- 商品网格 -->
          <div class="sk-grid">
            <div
              v-for="item in act.items"
              :key="item.stockId"
              class="sk-item"
              @click="goDetail(act.id)"
            >
              <div class="sk-item-img">
                <img :src="item.skuImage || item.productImage || '/images/products/OIP-C.webp'" alt="" loading="lazy" />
                <!-- 售罄蒙层：以后端返回的 soldOut 为准，前端不自己比数字 -->
                <div v-if="item.soldOut" class="sk-item-mask">
                  <span>已抢完</span>
                </div>
                <span v-else-if="remainPercent(item) <= 20" class="sk-item-hot">仅剩 {{ item.availableStock }} 件</span>
              </div>
              <div class="sk-item-body">
                <!-- infoMissing：商品服务拉取失败，明确提示而不是显示空白商品名 -->
                <p v-if="item.infoMissing" class="sk-item-name sk-item-name--missing">商品信息加载失败</p>
                <p v-else class="sk-item-name">{{ item.productName }}</p>
                <p v-if="item.skuAttrs" class="sk-item-attrs">{{ item.skuAttrs }}</p>
                <div class="sk-item-price">
                  <span class="sk-price-now">¥{{ item.seckillPrice }}</span>
                  <span v-if="item.originPrice" class="sk-price-old">¥{{ item.originPrice }}</span>
                </div>
                <!-- 进度条：已抢占比 -->
                <div class="sk-progress">
                  <div class="sk-progress-bar" :style="{ width: soldPercent(item) + '%' }"></div>
                  <span class="sk-progress-text">已抢 {{ soldPercent(item) }}%</span>
                </div>
              </div>
            </div>
          </div>

          <!-- 活动底栏 -->
          <div class="sk-card-foot">
            <span class="sk-foot-info">
              共 {{ act.items.length }} 件商品 · 总库存 {{ act.totalStock || 0 }}
              <template v-if="act.perUserLimit > 0"> · 每人限购 {{ act.perUserLimit }} 件</template>
            </span>
            <button class="btn btn-primary sk-foot-btn" @click="goDetail(act.id)">
              {{ phase(act) === 'running' ? '立即抢购' : '查看详情' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import http from '../utils/http.js'

const router = useRouter()
const activities = ref([])
const loading = ref(true)

/**
 * 服务端时间偏移量（毫秒）。
 *
 * 为什么需要它：秒杀是强时间敏感场景。如果直接用浏览器本地时钟算倒计时，
 * 用户系统时间不准（差几分钟很常见）就会看到错误的倒计时 ——
 * 甚至还没开抢就显示"已开始"。
 * 后端在活动详情的 serverTime 字段返回服务端时间，这里算出偏移量，
 * 之后所有倒计时都基于「本地时间 + 偏移」计算。
 */
const serverOffset = ref(0)

/** 每秒 tick 一次驱动倒计时刷新 */
const now = ref(Date.now())
let timer = null

const syncedNow = () => now.value + serverOffset.value

const fmtTime = (s) => {
  if (!s) return ''
  // 后端返回 "yyyy-MM-dd HH:mm:ss"，Safari 不认带空格的格式，替换成 T
  return String(s).replace('T', ' ').slice(5, 16)
}

/**
 * 解析后端时间字符串为时间戳。
 * 兼容 "yyyy-MM-dd HH:mm:ss" 与 ISO 格式。
 */
const parseTime = (s) => {
  if (!s) return 0
  const normalized = String(s).replace(' ', 'T')
  const t = Date.parse(normalized)
  return Number.isNaN(t) ? 0 : t
}

/**
 * 活动阶段。
 *
 * 用服务端时间判断，且「已下线/已结束」优先 —— 光看时间窗不够：
 * 活动可能被商家下线，此时即使还在时间窗内也不能抢。
 */
const phase = (act) => {
  if (act.status === 'ended' || act.status === 'offline') return 'ended'
  const t = syncedNow()
  const start = parseTime(act.startTime)
  const end = parseTime(act.endTime)
  if (t >= end) return 'ended'
  if (t < start) return 'pending'
  return 'running'
}

const phaseLabel = (act) => {
  const p = phase(act)
  if (p === 'running') return '距结束'
  if (p === 'pending') return '距开始'
  return '已结束'
}

/** 倒计时文本 HH:MM:SS；已结束显示横线 */
const countdownText = (act) => {
  const p = phase(act)
  if (p === 'ended') return '——'
  const target = p === 'running' ? parseTime(act.endTime) : parseTime(act.startTime)
  let diff = Math.max(0, target - syncedNow())
  const totalSec = Math.floor(diff / 1000)
  const h = Math.floor(totalSec / 3600)
  const m = Math.floor((totalSec % 3600) / 60)
  const s = totalSec % 60
  return `${pad(h)}:${pad(m)}:${pad(s)}`
}

const pad = (n) => String(n).padStart(2, '0')

const statusText = (status) => {
  const map = {
    pending: '即将开始',
    running: '进行中',
    ended: '已结束',
    offline: '已下线',
    draft: '草稿'
  }
  return map[status] || status
}

/** 剩余百分比（用于"仅剩 N 件"的紧急标记） */
const remainPercent = (item) => {
  const total = item.totalStock || 0
  if (total <= 0) return 0
  return Math.round(((item.availableStock || 0) / total) * 100)
}

/** 已抢百分比（进度条宽度） */
const soldPercent = (item) => {
  const total = item.totalStock || 0
  if (total <= 0) return 0
  const sold = total - (item.availableStock || 0)
  return Math.max(0, Math.min(100, Math.round((sold / total) * 100)))
}

const goDetail = (id) => router.push(`/seckill/${id}`)

const fetchList = async () => {
  loading.value = true
  try {
    const { data } = await http.get('/seckill/list')
    if (data.code === 200) {
      activities.value = data.data || []
      // /list 接口不返回 serverTime（那是详情接口的字段），
      // 用响应时间粗对齐一次，避免倒计时基于严重偏差的本地时钟
      serverOffset.value = 0
      now.value = Date.now()
    }
  } catch (e) {
    activities.value = []
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchList()
  timer = setInterval(() => { now.value = Date.now() }, 1000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.sk-page { background: var(--color-bg); min-height: 100vh; padding-bottom: 80px; }

/* 头部 */
.sk-hero {
  background: linear-gradient(135deg, #1a1a2e 0%, #2d2d4a 100%);
  padding: 120px 0 48px;
  margin-bottom: 32px;
}
.sk-hero .section-label { color: var(--color-accent); }
.sk-hero-title {
  font-size: 40px; font-weight: 600; color: #fff;
  letter-spacing: 2px; margin-bottom: 8px;
}
.sk-hero-desc { font-size: 14px; color: rgba(255, 255, 255, .65); letter-spacing: 1px; }

.sk-main { max-width: 1280px; }

/* 空态 */
.sk-empty { text-align: center; padding: 80px 0; color: var(--color-text-light); }
.sk-empty-title { font-size: 18px; color: var(--color-text); margin-bottom: 8px; }
.sk-empty-sub { font-size: 13px; margin-bottom: 24px; }

/* 活动卡片 */
.sk-list { display: flex; flex-direction: column; gap: 28px; }
.sk-card {
  background: #fff; border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card); border: 1px solid var(--color-border);
  overflow: hidden;
}
.sk-card-head {
  display: flex; align-items: center; justify-content: space-between;
  gap: 16px; padding: 20px 24px;
  background: linear-gradient(90deg, #fff8ef 0%, #fff 60%);
  border-bottom: 1px solid var(--color-border);
  flex-wrap: wrap;
}
.sk-card-title { font-size: 20px; font-weight: 600; color: var(--color-primary); letter-spacing: .5px; }
.sk-card-time { font-size: 12px; color: var(--color-text-light); margin-top: 2px; }
.sk-card-head-right { display: flex; align-items: center; gap: 12px; }

/* 倒计时 */
.sk-countdown {
  display: flex; flex-direction: column; align-items: center;
  padding: 6px 14px; border-radius: var(--radius);
  background: var(--color-bg-dark);
}
.sk-countdown.pending { background: #4a4a5e; }
.sk-countdown.ended { background: #d8d5cd; }
.sk-cd-label { font-size: 10px; color: rgba(255, 255, 255, .6); letter-spacing: 1px; }
.sk-cd-value {
  font-family: var(--font-en); font-size: 18px; font-weight: 700;
  color: var(--color-accent); letter-spacing: 1px;
}
.sk-countdown.ended .sk-cd-label,
.sk-countdown.ended .sk-cd-value { color: #fff; }

.sk-status-tag {
  font-size: 11px; padding: 3px 10px; border-radius: 10px;
  background: #f0f0f0; color: #888;
}
.sk-status-tag.running { background: #c0392b; color: #fff; }
.sk-status-tag.pending { background: var(--color-accent); color: #fff; }

.sk-card-desc {
  padding: 12px 24px 0; font-size: 13px; color: var(--color-text-light);
}

/* 商品网格 */
.sk-grid {
  display: grid; grid-template-columns: repeat(4, 1fr);
  gap: 16px; padding: 20px 24px;
}
.sk-item {
  border: 1px solid var(--color-border); border-radius: var(--radius);
  overflow: hidden; cursor: pointer; transition: all var(--transition);
  background: #fff;
}
.sk-item:hover { border-color: var(--color-accent); box-shadow: var(--shadow-soft); }
.sk-item-img { position: relative; aspect-ratio: 1/1; background: #f5f5f5; overflow: hidden; }
.sk-item-img img { width: 100%; height: 100%; object-fit: cover; transition: transform .4s; }
.sk-item:hover .sk-item-img img { transform: scale(1.04); }
.sk-item-mask {
  position: absolute; inset: 0; background: rgba(0, 0, 0, .55);
  display: flex; align-items: center; justify-content: center;
}
.sk-item-mask span {
  color: #fff; font-size: 14px; letter-spacing: 2px;
  border: 1px solid rgba(255, 255, 255, .6); padding: 4px 14px; border-radius: 20px;
}
.sk-item-hot {
  position: absolute; top: 8px; left: 8px; background: #c0392b; color: #fff;
  font-size: 11px; padding: 2px 8px; border-radius: 10px;
}
.sk-item-body { padding: 10px 12px 12px; }
.sk-item-name {
  font-size: 13px; font-weight: 500; color: var(--color-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.sk-item-name--missing { color: #c0392b; font-style: italic; }
.sk-item-attrs { font-size: 11px; color: var(--color-text-light); margin-top: 2px; }
.sk-item-price { display: flex; align-items: baseline; gap: 6px; margin: 6px 0 8px; }
.sk-price-now {
  font-family: var(--font-en); font-size: 18px; font-weight: 700; color: #c0392b;
}
.sk-price-old { font-size: 12px; color: #bbb; text-decoration: line-through; }

.sk-progress {
  position: relative; height: 14px; background: #f5f2ec;
  border-radius: 7px; overflow: hidden;
}
.sk-progress-bar {
  height: 100%; background: linear-gradient(90deg, #e8a87c, #c0392b);
  transition: width .4s;
}
.sk-progress-text {
  position: absolute; inset: 0; display: flex; align-items: center;
  justify-content: center; font-size: 10px; color: #6b5b4a; letter-spacing: .5px;
}

/* 底栏 */
.sk-card-foot {
  display: flex; align-items: center; justify-content: space-between;
  gap: 12px; padding: 14px 24px;
  border-top: 1px solid var(--color-border); background: #fcfbf9;
  flex-wrap: wrap;
}
.sk-foot-info { font-size: 12px; color: var(--color-text-light); }
.sk-foot-btn { padding: 8px 24px; font-size: 13px; }

@media (max-width: 1024px) { .sk-grid { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 768px) {
  .sk-grid { grid-template-columns: repeat(2, 1fr); }
  .sk-hero { padding: 100px 0 32px; }
  .sk-hero-title { font-size: 28px; }
  .sk-card-head { flex-direction: column; align-items: flex-start; }
}
</style>
