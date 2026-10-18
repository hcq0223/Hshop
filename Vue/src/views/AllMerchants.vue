<template>
  <div class="page am-page">
    <section class="container">
      <div class="am-header-row">
        <div class="slide-in-left">
          <p class="section-label">All Merchants</p>
          <h1 class="section-title">全部商家</h1>
        </div>
      </div>

      <div class="am-search-bar">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <input v-model="keyword" @keyup.enter="doSearch" placeholder="搜索店铺名称..." />
        <button class="btn btn-primary btn-sm" @click="doSearch">搜索</button>
      </div>

      <div v-if="merchants.length > 0" class="am-list">
        <div
          class="am-card"
          v-for="m in merchants"
          :key="m.id"
          @dblclick="goShop(m.id)"
        >
          <div class="am-avatar-wrap">
            <img v-if="m.logoUrl" :src="m.logoUrl" class="am-avatar" />
            <span v-else class="am-avatar-fb">{{ (m.shopName || '店')[0] }}</span>
          </div>
          <div class="am-body">
            <div class="am-head">
              <h3 class="am-name">{{ m.shopName || '未命名店铺' }}</h3>
              <span class="am-rating" v-if="m.rating !== null && m.rating !== undefined">
                {{ Number(m.rating).toFixed(1) }} 分
              </span>
            </div>
            <p class="am-desc" v-if="m.shopDescription">{{ truncateText(m.shopDescription, 80) }}</p>
            <p class="am-desc am-desc-empty" v-else>暂无简介</p>
            <div class="am-meta">
              <span class="am-verified">已实名认证</span>
              <span class="am-time" v-if="m.createdAt">入驻 {{ formatDate(m.createdAt) }}</span>
            </div>
          </div>
          <span class="am-arrow">&rsaquo;</span>
        </div>
      </div>

      <div v-else-if="!loading" class="empty">暂无商家</div>
      <div v-else class="empty">加载中...</div>

      <div class="pagination" v-if="totalPages > 1">
        <button :disabled="page <= 1" @click="page--; fetchMerchants()">上一页</button>
        <span>{{ page }} / {{ totalPages }}</span>
        <button :disabled="page >= totalPages" @click="page++; fetchMerchants()">下一页</button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import http from '../utils/http.js'

const router = useRouter()
const merchants = ref([])
const loading = ref(true)
const keyword = ref('')
const page = ref(1)
const size = ref(10)
const totalPages = ref(1)

const truncateText = (text, maxLen) => {
  if (!text) return ''
  return text.length > maxLen ? text.slice(0, maxLen) + '...' : text
}

const formatDate = (dateStr) => {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

const fetchMerchants = async () => {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    const { data } = await http.get('/search/merchant', { params })
    if (data.code === 200) {
      const res = data.data
      merchants.value = res.records || []
      totalPages.value = Math.ceil((res.total || 0) / size.value)
    }
  } catch (_) {}
  loading.value = false
}

const doSearch = () => {
  page.value = 1
  fetchMerchants()
}

const goShop = (id) => {
  router.push(`/shop/${id}`)
}

onMounted(fetchMerchants)
</script>

<style scoped>
.am-page { padding: 120px 0 80px; }

.am-header-row { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 28px; }
.slide-in-left { animation: slideLeft 0.6s ease both; }
@keyframes slideLeft {
  from { opacity: 0; transform: translateX(-40px); }
  to   { opacity: 1; transform: translateX(0); }
}

.am-search-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 28px; max-width: 520px; position: relative; }
.am-search-bar svg { position: absolute; left: 16px; top: 50%; transform: translateY(-50%); color: var(--color-text-light); }
.am-search-bar input { flex: 1; padding: 12px 16px 12px 42px; border: 1px solid var(--color-border); border-radius: 24px; font-size: 14px; outline: none; transition: border-color 0.25s; }
.am-search-bar input:focus { border-color: var(--color-accent); }
.am-search-bar .btn-sm { padding: 10px 24px; font-size: 13px; border-radius: 24px; }

.am-list { display: flex; flex-direction: column; gap: 14px; }
.am-card {
  display: flex; align-items: center; gap: 16px;
  background: #fff; border-radius: var(--radius); box-shadow: var(--shadow-card);
  padding: 20px 24px; cursor: pointer; transition: all 0.25s;
}
.am-card:hover { box-shadow: 0 8px 28px rgba(0,0,0,0.1); transform: translateY(-2px); }

.am-avatar-wrap { flex-shrink: 0; }
.am-avatar { width: 64px; height: 64px; border-radius: 12px; object-fit: cover; border: 2px solid var(--color-accent); }
.am-avatar-fb { width: 64px; height: 64px; border-radius: 12px; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #1a1a2e, #2d2d44); color: #fff; font-size: 24px; font-weight: 700; border: 2px solid var(--color-accent); }

.am-body { flex: 1; min-width: 0; }
.am-head { display: flex; align-items: center; gap: 12px; margin-bottom: 6px; }
.am-name { font-size: 16px; font-weight: 700; margin: 0; }
.am-rating { font-size: 13px; color: var(--color-accent); font-weight: 600; }
.am-desc { font-size: 13px; color: var(--color-text-light); line-height: 1.6; margin: 0 0 8px; }
.am-desc-empty { color: #bbb; font-style: italic; }
.am-meta { display: flex; align-items: center; gap: 12px; }
.am-verified { display: inline-block; font-size: 11px; padding: 2px 10px; border-radius: 20px; background: rgba(39,174,96,0.1); color: #27ae60; border: 1px solid rgba(39,174,96,0.3); }
.am-time { font-size: 12px; color: #aaa; }

.am-arrow { font-size: 24px; color: #ccc; flex-shrink: 0; transition: color 0.2s; }
.am-card:hover .am-arrow { color: var(--color-accent); }

.pagination { display: flex; justify-content: center; align-items: center; gap: 20px; padding: 40px 0; }
.pagination button { padding: 8px 20px; border: 1px solid var(--color-border); background: #fff; border-radius: var(--radius); cursor: pointer; }
.pagination button:disabled { opacity: 0.4; cursor: not-allowed; }
.empty { text-align: center; padding: 80px 0; color: #999; }

@media (max-width: 768px) {
  .am-card { padding: 16px; }
  .am-avatar, .am-avatar-fb { width: 48px; height: 48px; border-radius: 10px; }
  .am-name { font-size: 14px; }
}
</style>
