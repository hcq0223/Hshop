<template>
  <div class="ms-page">
    <!-- 商户信息卡片 -->
    <div class="ms-hero">
      <div class="ms-hero-inner container">
        <div class="ms-hero-left slide-in-left">
          <div class="ms-merchant-info">
            <div class="ms-avatar-wrap">
              <img v-if="merchant.logoUrl" :src="merchant.logoUrl" class="ms-avatar" />
              <span v-else class="ms-avatar-fb">{{ (merchant.shopName || 'S')[0] }}</span>
            </div>
            <div class="ms-info">
              <h1 class="ms-shop-name">{{ merchant.shopName || '未命名店铺' }}</h1>
              <div class="ms-meta">
                <span class="ms-rating" v-if="merchant.rating !== null && merchant.rating !== undefined">
                  店铺评分 {{ Number(merchant.rating).toFixed(1) }}
                </span>
                <span class="ms-verify" :class="merchant.isVerified === 1 ? 'verified' : 'unverified'">
                  {{ merchant.isVerified === 1 ? '已实名认证' : '未实名认证' }}
                </span>
              </div>
            </div>
          </div>
          <p class="ms-desc" v-if="merchant.shopDescription">{{ merchant.shopDescription }}</p>
          <p class="ms-desc ms-desc-empty" v-else>暂无店铺简介</p>
        </div>

        <div class="ms-contact slide-in-right">
          <div class="ms-contact-card">
            <div class="ms-contact-row">
              <span class="ms-contact-label">联系人</span>
              <span class="ms-contact-val">{{ merchant.contactName || '-' }}</span>
            </div>
            <div class="ms-contact-row">
              <span class="ms-contact-label">联系电话</span>
              <span class="ms-contact-val">{{ merchant.contactPhone || '-' }}</span>
            </div>
            <div class="ms-contact-row">
              <span class="ms-contact-label">营业执照</span>
              <span class="ms-contact-val">
                <a v-if="merchant.businessLicense" :href="merchant.businessLicense" target="_blank" class="ms-license-link">查看执照</a>
                <span v-else class="ms-na">未上传</span>
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 轮播图 + 分类面板（有轮播时） -->
    <div class="ms-banner-row container" v-if="bannerUrls.length > 0">
      <div class="ms-banner" @mouseenter="pauseAuto" @mouseleave="resumeAuto">
        <div class="ms-banner-track" :class="{ notrans: !transitioning }" :style="{ width: (loopSlides.length * 100) + '%', transform: `translateX(-${displayIdx * (100 / loopSlides.length)}%)` }" @transitionend="onTransitionEnd">
          <div class="ms-banner-slide" v-for="(url, i) in loopSlides" :key="i" :style="{ width: (100 / loopSlides.length) + '%' }">
            <img :src="url" />
          </div>
        </div>
        <button class="ms-banner-prev" @click.stop="prevBanner" v-if="bannerUrls.length > 1">&lsaquo;</button>
        <button class="ms-banner-next" @click.stop="nextBanner" v-if="bannerUrls.length > 1">&rsaquo;</button>
        <div class="ms-banner-dots" v-if="bannerUrls.length > 1">
          <span v-for="(_, i) in bannerUrls" :key="i" :class="{ active: i === bannerDotIdx }" @click="goBanner(i)"></span>
        </div>
      </div>

      <div class="ms-cat-panel" :class="{ collapsed: !catExpanded }">
        <div class="ms-cat-header" @click="catExpanded = !catExpanded">
          <h3 class="ms-cat-title">全部分类</h3>
          <span class="ms-cat-toggle">{{ catExpanded ? '收起' : '展开' }}</span>
        </div>
        <div class="ms-cat-grid" v-show="catExpanded">
          <div class="ms-cat-item" :class="{ active: selectedCategory === null }" @click="selectedCategory = null; page = 1; fetchProducts()">全部</div>
          <div v-for="c in categories" :key="c.id" class="ms-cat-item" :class="{ active: selectedCategory === c.id }" @click="selectedCategory = c.id; page = 1; fetchProducts()">{{ c.name }}</div>
        </div>
      </div>
    </div>

    <!-- 无轮播图：搜索栏 + 分类面板 同行 -->
    <div class="ms-search-cat-row container" v-else>
      <div class="ms-search">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <input v-model="keyword" @keyup.enter="doSearch" placeholder="搜索店铺商品..." />
      </div>
      <div class="ms-cat-panel" :class="{ collapsed: !catExpanded }">
        <div class="ms-cat-header" @click="catExpanded = !catExpanded">
          <h3 class="ms-cat-title">全部分类</h3>
          <span class="ms-cat-toggle">{{ catExpanded ? '收起' : '展开' }}</span>
        </div>
        <div class="ms-cat-grid" v-show="catExpanded">
          <div class="ms-cat-item" :class="{ active: selectedCategory === null }" @click="selectedCategory = null; page = 1; fetchProducts()">全部</div>
          <div v-for="c in categories" :key="c.id" class="ms-cat-item" :class="{ active: selectedCategory === c.id }" @click="selectedCategory = c.id; page = 1; fetchProducts()">{{ c.name }}</div>
        </div>
      </div>
    </div>

    <!-- 搜索栏（有轮播时，单独一行） -->
    <div class="ms-search-bar container" v-if="bannerUrls.length > 0">
      <div class="ms-search">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <input v-model="keyword" @keyup.enter="doSearch" placeholder="搜索店铺商品..." />
      </div>
    </div>

    <!-- 商品区域 -->
    <div class="ms-products-section container">
      <div class="ms-products-header">
        <h2 class="ms-section-title">全部商品</h2>
      </div>

      <!-- 商品网格 -->
      <div class="ms-grid" v-if="products.length > 0">
        <div v-for="item in products" :key="item.id" class="ms-card" @click="$router.push(`/product/${item.id}`)">
          <div class="ms-card-img">
            <img :src="item.mainImage || '/images/products/OIP-C.webp'" :alt="item.name" loading="lazy" />
            <span class="ms-tag" v-if="item.status === 'on_sale'">在售</span>
          </div>
          <div class="ms-card-body">
            <h4>{{ item.name }}</h4>
            <div class="ms-card-bottom">
              <span class="ms-cat" v-if="item.categoryName">{{ item.categoryName }}</span>
              <span class="ms-price">¥{{ item.priceMin }}</span>
            </div>
            <p class="ms-sales" v-if="item.salesCount != null">已售 {{ item.salesCount }} 件</p>
          </div>
        </div>
      </div>
      <div v-else class="ms-empty">暂无商品</div>

      <!-- 翻页 -->
      <div class="ms-pager" v-if="totalPages > 1">
        <button :disabled="page <= 1" @click="page--; fetchProducts()">上一页</button>
        <span>{{ page }} / {{ totalPages }}</span>
        <button :disabled="page >= totalPages" @click="page++; fetchProducts()">下一页</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import http from '../utils/http.js'

const route = useRoute()
const isPublic = computed(() => !!route.params.merchantId)

const keyword = ref('')
const selectedCategory = ref(null)
const products = ref([])
const categories = ref([])
const merchant = ref({})
const page = ref(1)
const size = ref(12)
const totalPages = ref(1)
const catExpanded = ref(false)

const merchantId = ref(null)
const loading = ref(true)

let autoTimer = null
const AUTO_INTERVAL = 4000

const parseBanners = (raw) => {
  if (!raw) return []
  try { return JSON.parse(raw) } catch (_) { return [] }
}
const bannerUrls = computed(() => parseBanners(merchant.value.banners))

// 无缝循环：首尾各克隆一份
const loopSlides = computed(() => {
  const urls = bannerUrls.value
  if (urls.length <= 1) return urls
  return [urls[urls.length - 1], ...urls, urls[0]]
})

const transitioning = ref(true)
// displayIdx: 0 是克隆的末张，1~N 是真实，N+1 是克隆的首张
const displayIdx = ref(1)
const manualLock = ref(false)   // 手动点击后锁定，等过渡结束才解锁

const goBanner = (i) => {
  if (manualLock.value) return
  manualLock.value = true
  displayIdx.value = i + 1
}

const nextBanner = () => {
  if (bannerUrls.value.length <= 1 || manualLock.value) return
  manualLock.value = true
  displayIdx.value++
}

const prevBanner = () => {
  if (bannerUrls.value.length <= 1 || manualLock.value) return
  manualLock.value = true
  displayIdx.value--
}

// 监听过渡结束，到克隆位时静默跳回原位
const onTransitionEnd = () => {
  const n = bannerUrls.value.length
  if (n <= 1) { manualLock.value = false; return }

  const doJump = (target) => {
    transitioning.value = false
    setTimeout(() => {
      displayIdx.value = target
      nextTick(() => {
        setTimeout(() => {
          transitioning.value = true
          manualLock.value = false
        }, 30)
      })
    }, 30)
  }

  if (displayIdx.value <= 0) {
    doJump(n)
  } else if (displayIdx.value >= n + 1) {
    doJump(1)
  } else {
    manualLock.value = false
  }
}

// 兼容旧变量名，用于 dots
const bannerDotIdx = computed(() => {
  const n = bannerUrls.value.length
  if (n <= 1) return 0
  if (displayIdx.value === 0) return n - 1
  if (displayIdx.value === n + 1) return 0
  return displayIdx.value - 1
})

const startAuto = () => {
  stopAuto()
  if (bannerUrls.value.length > 1) {
    autoTimer = setInterval(nextBanner, AUTO_INTERVAL)
  }
}
const stopAuto = () => {
  if (autoTimer) { clearInterval(autoTimer); autoTimer = null }
}
const pauseAuto = () => { stopAuto() }
const resumeAuto = () => { startAuto() }

const fetchMerchant = async () => {
  try {
    if (isPublic.value) {
      // 公开访问：通过路由参数 merchantId 查询
      const { data } = await http.get(`/merchant/${route.params.merchantId}`)
      if (data.code === 200) {
        merchant.value = data.data
        merchantId.value = data.data.id
        startAuto()
      }
    } else {
      // 商户自有店铺：从 token 获取
      const { data } = await http.get('/merchant/me')
      if (data.code === 200) {
        merchant.value = data.data
        merchantId.value = data.data.id
        startAuto()
      }
    }
  } catch (_) {}
}

const fetchCategories = async () => {
  try {
    const { data } = await http.get('/category/all')
    if (data.code === 200) {
      categories.value = (data.data || []).filter(c => c.parentId !== 0)
    }
  } catch (_) {}
}

const fetchProducts = async () => {
  if (!merchantId.value) return
  try {
    const params = { page: page.value, size: size.value }
    if (keyword.value) params.keyword = keyword.value
    if (selectedCategory.value) params.categoryId = selectedCategory.value

    const { data } = await http.get(`/search/product/merchant/${merchantId.value}`, { params })
    if (data.code === 200) {
      const res = data.data
      products.value = res.records || []
      totalPages.value = Math.ceil((res.total || 0) / size.value)
    }
  } catch (_) {}
}

const doSearch = () => {
  page.value = 1
  fetchProducts()
}

onMounted(async () => {
  await Promise.all([fetchMerchant(), fetchCategories()])
  loading.value = false
  fetchProducts()
})

// 同一组件内路由切换（/shop/123 → /shop）时重新加载数据
watch(() => route.fullPath, async () => {
  stopAuto()
  await Promise.all([fetchMerchant(), fetchCategories()])
  page.value = 1
  keyword.value = ''
  selectedCategory.value = null
  fetchProducts()
})

onUnmounted(() => { stopAuto() })
</script>

<style scoped>
.ms-page { min-height: 100vh; background: var(--color-bg); }

/* Hero 区块 */
.ms-hero { background: linear-gradient(135deg, #1a1a2e 0%, #2d2d44 100%); padding: 130px 0 44px; color: #fff; }
.ms-hero-inner { display: flex; justify-content: space-between; align-items: flex-start; gap: 40px; }

.ms-hero-left { flex: 1; min-width: 0; }
.ms-merchant-info { display: flex; align-items: center; gap: 20px; }
.ms-avatar-wrap { width: 80px; height: 80px; border-radius: 16px; flex-shrink: 0; }
.ms-avatar { display: block; width: 80px; height: 80px; border-radius: 16px; object-fit: cover; box-shadow: 0 0 0 3px #1a1a2e, 0 0 0 5px var(--color-accent); }
.ms-avatar-fb { display: flex; align-items: center; justify-content: center; width: 80px; height: 80px; border-radius: 16px; background: linear-gradient(135deg, var(--color-accent), #c4a35a); color: #fff; font-family: var(--font-en); font-size: 32px; font-weight: 700; box-shadow: 0 0 0 3px #1a1a2e, 0 0 0 5px var(--color-accent); }

.ms-info { display: flex; flex-direction: column; gap: 8px; }
.ms-shop-name { font-size: 26px; font-weight: 700; margin: 0; }
.ms-meta { display: flex; align-items: center; gap: 14px; }
.ms-rating { font-size: 14px; color: var(--color-accent); }
.ms-verify { display: inline-block; font-size: 12px; padding: 3px 12px; border-radius: 20px; }
.ms-verify.verified { background: rgba(39, 174, 96, 0.2); color: #2ecc71; border: 1px solid rgba(39, 174, 96, 0.4); }
.ms-verify.unverified { background: rgba(231, 76, 60, 0.15); color: #e74c3c; border: 1px solid rgba(231, 76, 60, 0.3); }

/* 店铺简介 */
.ms-desc { margin: 18px 0 0 100px; font-size: 14px; color: rgba(255,255,255,0.7); line-height: 1.7; max-width: 600px; }
.ms-desc-empty { color: rgba(255,255,255,0.35); font-style: italic; }

/* Contact 卡片 */
.ms-contact-card { background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.12); border-radius: 12px; padding: 20px 24px; min-width: 260px; }
.ms-contact-row { display: flex; justify-content: space-between; padding: 8px 0; font-size: 14px; }
.ms-contact-row:not(:last-child) { border-bottom: 1px solid rgba(255,255,255,0.08); }
.ms-contact-label { color: rgba(255,255,255,0.55); }
.ms-contact-val { font-weight: 500; }
.ms-license-link { color: var(--color-accent); text-decoration: underline; font-size: 13px; }
.ms-na { color: rgba(255,255,255,0.4); }

/* 轮播图 + 分类面板 同行 */
.ms-banner-row { display: flex; gap: 16px; margin-top: 20px; align-items: flex-start; }

/* 无轮播时：搜索栏 + 分类面板 同行 */
.ms-search-cat-row { display: flex; gap: 16px; margin-top: 20px; align-items: flex-start; }
.ms-search-cat-row .ms-search { flex: 1; max-width: none; }
.ms-search-cat-row .ms-cat-panel { margin-top: 0; }
.ms-banner { position: relative; flex: 1; min-width: 0; height: 350px; background: #1a1a2e; overflow: hidden; border-radius: 8px; }
.ms-banner-track { display: flex; height: 100%; transition: transform 0.5s ease; }
.ms-banner-track.notrans { transition: none; }
.ms-banner-slide { height: 100%; flex-shrink: 0; }
.ms-banner-slide img { width: 100%; height: 100%; object-fit: contain; }
.ms-banner-prev,
.ms-banner-next { position: absolute; top: 50%; transform: translateY(-50%); width: 44px; height: 44px; border-radius: 50%; border: none; background: rgba(255,255,255,0.15); color: #fff; font-size: 28px; cursor: pointer; transition: background 0.25s; display: flex; align-items: center; justify-content: center; }
.ms-banner-prev:hover,
.ms-banner-next:hover { background: rgba(255,255,255,0.3); }
.ms-banner-prev { left: 16px; }
.ms-banner-next { right: 16px; }
.ms-banner-dots { position: absolute; bottom: 12px; left: 50%; transform: translateX(-50%); display: flex; gap: 8px; }
.ms-banner-dots span { width: 10px; height: 10px; border-radius: 50%; background: rgba(255,255,255,0.35); cursor: pointer; transition: background 0.25s; }
.ms-banner-dots span.active { background: #fff; }

/* 分类面板 */
.ms-cat-panel { width: 260px; flex-shrink: 0; background: #fff; border-radius: 8px; padding: 16px; box-shadow: 0 2px 12px rgba(0,0,0,0.06); transition: all 0.3s; }
.ms-cat-panel.collapsed { padding-bottom: 16px; }
.ms-cat-header { display: flex; justify-content: space-between; align-items: center; cursor: pointer; user-select: none; margin-bottom: 12px; }
.ms-cat-title { font-size: 15px; font-weight: 700; margin: 0; color: var(--color-text); }
.ms-cat-toggle { font-size: 12px; color: var(--color-accent); transition: color 0.2s; }
.ms-cat-header:hover .ms-cat-toggle { color: #c4a35a; }
.ms-cat-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; max-height: 280px; overflow-y: auto; padding-right: 2px; align-content: start; }
/* 滚动条美化 */
.ms-cat-grid::-webkit-scrollbar { width: 4px; }
.ms-cat-grid::-webkit-scrollbar-track { background: transparent; }
.ms-cat-grid::-webkit-scrollbar-thumb { background: #ddd; border-radius: 2px; }
.ms-cat-grid::-webkit-scrollbar-thumb:hover { background: #bbb; }
.ms-cat-item { padding: 8px 6px; text-align: center; font-size: 12px; border-radius: 6px; cursor: pointer; color: #555; background: #f7f7f7; transition: all 0.2s; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ms-cat-item:hover { background: #eee; color: #333; }
.ms-cat-item.active { background: var(--color-accent); color: #fff; }

/* 搜索栏 */
.ms-search-bar { display: flex; align-items: center; padding: 20px 0 0; }
.ms-search { position: relative; width: 100%; max-width: 520px; }
.ms-search svg { position: absolute; left: 16px; top: 50%; transform: translateY(-50%); color: var(--color-text-light); }
.ms-search input { width: 100%; padding: 12px 16px 12px 42px; border: 1px solid var(--color-border); border-radius: 24px; font-size: 14px; outline: none; background: #fff; transition: border-color .25s; }
.ms-search input:focus { border-color: var(--color-accent); }

/* 商品区域 */
.ms-products-section { padding: 24px 0 80px; }
.ms-products-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; flex-wrap: wrap; gap: 16px; }
.ms-section-title { font-size: 22px; font-weight: 700; margin: 0; }

/* 入场动画 */
.slide-in-left { animation: slideLeft 0.6s ease both; }
.slide-in-right { animation: slideRight 0.6s ease both; }
@keyframes slideLeft { from { opacity: 0; transform: translateX(-40px); } to { opacity: 1; transform: translateX(0); } }
@keyframes slideRight { from { opacity: 0; transform: translateX(40px); } to { opacity: 1; transform: translateX(0); } }

/* 商品网格 */
.ms-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 16px; }
.ms-card { background: #fff; border-radius: var(--radius); overflow: hidden; box-shadow: var(--shadow-card); cursor: pointer; transition: all .35s; max-width: 260px; margin: 0 auto; width: 100%; }
.ms-card:hover { transform: translateY(-4px); box-shadow: 0 12px 32px rgba(0,0,0,0.1); }
.ms-card-img { position: relative; aspect-ratio: 3/4; background: #f5f5f5; overflow: hidden; }
.ms-card-img img { width: 100% !important; height: 100% !important; object-fit: cover; transition: transform .4s; }
.ms-card:hover .ms-card-img img { transform: scale(1.03); }
.ms-tag { position: absolute; top: 8px; left: 8px; background: var(--color-accent); color: #fff; font-size: 11px; padding: 2px 8px; border-radius: 10px; }

.ms-card-body { padding: 10px 12px; }
.ms-card-body h4 { font-size: 13px; font-weight: 600; margin-bottom: 6px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ms-card-bottom { display: flex; justify-content: space-between; align-items: center; }
.ms-cat { font-size: 10px; color: var(--color-accent); }
.ms-price { font-weight: 700; color: #c0392b; font-family: var(--font-en); font-size: 14px; }
.ms-sales { font-size: 11px; color: var(--color-text-light); margin-top: 4px; }

.ms-empty { text-align: center; padding: 80px 0; color: #999; font-size: 14px; }
.ms-pager { display: flex; justify-content: center; align-items: center; gap: 20px; padding: 48px 0 0; }
.ms-pager button { padding: 8px 24px; border: 1px solid var(--color-border); background: #fff; border-radius: var(--radius); font-size: 13px; cursor: pointer; }
.ms-pager button:disabled { opacity: .4; cursor: not-allowed; }
.ms-pager span { font-size: 13px; color: #888; }

@media (max-width: 1200px) { .ms-grid { grid-template-columns: repeat(4, 1fr); } }
@media (max-width: 1024px) { .ms-grid { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 768px) {
  .ms-banner-row,
  .ms-search-cat-row { flex-direction: column; }
  .ms-cat-panel { width: 100%; }
  .ms-cat-panel.collapsed .ms-cat-grid { max-height: 200px; }
  .ms-hero-inner { flex-direction: column; }
  .ms-desc { margin-left: 0; }
  .ms-grid { grid-template-columns: repeat(2, 1fr); }
  .ms-products-header { flex-direction: column; align-items: flex-start; }
}
</style>
