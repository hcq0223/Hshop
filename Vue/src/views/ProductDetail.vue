<template>
  <div class="page detail-page">
    <section class="container" v-if="product">
      <div class="detail-grid">
        <!-- 左侧图片 -->
        <div class="detail-images">
          <div class="main-img-wrap">
            <img :src="selectedImg || product.mainImage || '/images/products/OIP-C.webp'" :alt="product.name" class="main-img" />
          </div>
          <div class="thumb-list" v-if="product.images && product.images.length">
            <img v-for="(img, i) in product.images" :key="i" :src="img" class="thumb" @click="selectedImg = img" />
          </div>
        </div>

        <!-- 右侧信息 -->
        <div class="detail-info">
          <div class="detail-title-row">
            <h1>{{ product.name }}</h1>
            <div class="detail-bread" v-if="product.parentCategoryName || product.category">
              {{ product.parentCategoryName ? product.parentCategoryName + ' · ' : '' }}{{ product.category?.name || product.categoryName || '' }}
            </div>
          </div>

          <!-- 商户 -->
          <div class="detail-merchant" v-if="product.merchant">
            <div class="dm-avatar-wrap">
              <img v-if="product.merchant.logoUrl" :src="product.merchant.logoUrl" class="dm-avatar" />
              <span v-else class="dm-avatar-fb">{{ (product.merchant.shopName || 'S')[0] }}</span>
            </div>
            <span class="dm-name">{{ product.merchant.shopName }}</span>
            <span class="dm-rating" v-if="product.merchant.rating || product.merchant.rating === 0">
              店铺评分 {{ Number(product.merchant.rating).toFixed(1) }}
            </span>
          </div>

          <!-- 价格 -->
          <div class="detail-price-row">
            <div class="detail-price">¥{{ currentSku ? currentSku.price : product.priceMin }}</div>
            <div class="detail-sales" v-if="product.salesCount">已售 {{ product.salesCount }} 件</div>
          </div>

          <!-- SKU 选择 -->
          <div class="sku-section" v-if="skus.length > 0">
            <div class="sku-row">
              <span class="sku-label">颜色</span>
              <div class="sku-options">
                <button v-for="c in uniqueColors" :key="c"
                  :class="{ active: selectedColor === c }"
                  @click="selectedColor = c; selectSku()">{{ c }}</button>
              </div>
            </div>
            <div class="sku-row">
              <span class="sku-label">尺码</span>
              <div class="sku-options">
                <button v-for="s in availableSizes" :key="s"
                  :class="{ active: selectedSize === s }"
                  @click="selectedSize = s; selectSku()">{{ s }}</button>
              </div>
            </div>
            <div class="sku-stock-row">
              <span class="sku-label">库存</span>
              <span class="sku-stock">{{ currentSku ? currentSku.stock : '-' }} 件</span>
            </div>
          </div>

          <!-- 操作 -->
          <div class="detail-actions" v-if="isCustomer">
            <div class="qty-box">
              <button @click="qty = Math.max(1, qty - 1)">−</button>
              <span>{{ qty }}</span>
              <button @click="qty++">+</button>
            </div>
            <button class="btn btn-primary" @click="addToCart" :disabled="!currentSku || currentSku.stock <= 0">加入购物车</button>
            <button class="btn btn-outline" @click="buyNow" :disabled="!currentSku || currentSku.stock <= 0">立即购买</button>
          </div>
          <div class="detail-merchant-notice" v-else-if="currentRole">
            <p>当前用户不可购买，如需购买请使用顾客账号登录</p>
          </div>
          <!-- 未登录时的操作按钮 -->
          <div class="detail-actions" v-else>
            <div class="qty-box">
              <button @click="qty = Math.max(1, qty - 1)">−</button>
              <span>{{ qty }}</span>
              <button @click="qty++">+</button>
            </div>
            <button class="btn btn-primary" @click="addToCart" :disabled="!currentSku || currentSku.stock <= 0">加入购物车</button>
            <button class="btn btn-outline" @click="buyNow" :disabled="!currentSku || currentSku.stock <= 0">立即购买</button>
          </div>
          <p v-if="msg" class="detail-msg">{{ msg }}</p>

          <!-- 购物车提示弹窗 -->
          <Teleport to="body">
            <Transition name="toast">
              <div class="cart-toast" v-if="cartToast.show">{{ cartToast.text }}</div>
            </Transition>
          </Teleport>

          <!-- 介绍放到购买下方 -->
          <p class="detail-desc">{{ product.description || '暂无描述' }}</p>
        </div>
      </div>

      <!-- 评价区 -->
      <div class="reviews-section">
        <h3>商品评价</h3>

        <!-- 评分统计 -->
        <div class="rating-stats" v-if="ratingStats">
          <div class="rating-avg-col">
            <div class="rating-avg-num">{{ ratingStats.average }}</div>
            <div class="rating-avg-stars">{{ '★'.repeat(Math.round(ratingStats.average)) }}{{ '☆'.repeat(5 - Math.round(ratingStats.average)) }}</div>
            <div class="rating-avg-count">{{ ratingStats.totalCount }} 条评价</div>
          </div>
          <div class="rating-bars-col">
            <div class="rating-bar-row" v-for="star in 5" :key="star">
              <span class="bar-label">{{ star }}星</span>
              <span class="bar-track">
                <span class="bar-fill" :style="{ width: barPercent(star) + '%' }"></span>
              </span>
              <span class="bar-count">{{ getDistCount(star) }}</span>
            </div>
          </div>
        </div>

        <!-- 评论列表 -->
        <div class="review-item" v-for="r in reviews" :key="r.id">
          <div class="review-top">
            <div class="review-user">
              <img v-if="r.customer?.avatarUrl" :src="r.customer.avatarUrl" class="review-avatar" />
              <span v-else class="review-avatar-fb">{{ (r.customer?.nickname || '匿')[0] }}</span>
              <strong>{{ r.customer?.nickname || '匿名用户' }}</strong>
            </div>
            <div class="review-actions">
              <span class="stars">{{ '★'.repeat(r.rating) }}{{ '☆'.repeat(5 - r.rating) }}</span>
              <button v-if="isAdmin" class="btn-act btn-del" @click="deleteReview(r.id)">删除</button>
              <button v-else-if="isMerchant && r.merchantId === myMerchantId" class="btn-act btn-appeal" @click="openAppeal(r)">申诉</button>
            </div>
          </div>
          <p class="review-content">{{ r.content }}</p>
          <p class="review-date">{{ r.createTime }}</p>
        </div>

        <!-- 加载更多 -->
        <div class="review-loading" v-if="loadingMore">加载中...</div>
        <div class="review-no-more" v-if="!hasMore && reviews.length > 0">— 没有更多评论了 —</div>
        <div class="review-empty" v-if="!loadingMore && reviews.length === 0">暂无评价</div>
      </div>

      <!-- 申诉弹窗 -->
      <Teleport to="body">
        <div class="modal-mask" v-if="appealShow" @click.self="appealShow = false">
          <div class="modal-card">
            <h3>申诉评论</h3>
            <div class="form-group">
              <label>申诉理由</label>
              <textarea v-model="appealReason" rows="4" placeholder="请说明申诉原因..."></textarea>
            </div>
            <p v-if="appealMsg" class="msg" :class="appealOk ? 'msg-success' : 'msg-error'">{{ appealMsg }}</p>
            <div class="modal-actions">
              <button class="btn btn-outline btn-sm" @click="appealShow = false">取消</button>
              <button class="btn btn-primary btn-sm" @click="submitAppeal" :disabled="appealLoading">{{ appealLoading ? '提交中...' : '提交申诉' }}</button>
            </div>
          </div>
        </div>
      </Teleport>
    </section>

    <div class="container" v-else-if="loading">加载中...</div>
    <div class="container" v-else>商品不存在</div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../utils/http.js'
import { isLoggedIn, currentRole, getMerchantId, refreshCartCount } from '../stores/auth.js'

const route = useRoute()
const router = useRouter()
const isCustomer = computed(() => currentRole.value === 'customer')
const isAdmin = computed(() => currentRole.value === 'admin')
const isMerchant = computed(() => currentRole.value === 'merchant')
const myMerchantId = ref(getMerchantId())
const product = ref(null)
const skus = ref([])
const reviews = ref([])
const loading = ref(true)
const selectedColor = ref(null)
const selectedSize = ref(null)
const qty = ref(1)
const msg = ref('')
const cartToast = reactive({ show: false, text: '' })
let cartToastTimer = null

// 评论分页
const reviewPage = ref(1)
const hasMore = ref(true)
const loadingMore = ref(false)
const ratingStats = ref(null)
const REVIEW_SIZE = 10

// 申诉
const appealShow = ref(false)
const appealReason = ref('')
const appealLoading = ref(false)
const appealMsg = ref('')
const appealOk = ref(false)
const appealTarget = ref(null)

const showCartToast = (text) => {
  clearTimeout(cartToastTimer)
  cartToast.text = text
  cartToast.show = true
  cartToastTimer = setTimeout(() => { cartToast.show = false }, 2000)
}
const selectedImg = ref('')

const uniqueColors = computed(() => [...new Set(skus.value.map(s => s.color).filter(Boolean))])
const availableSizes = computed(() => {
  return [...new Set(skus.value.filter(s => s.color === selectedColor.value).map(s => s.size).filter(Boolean))]
})
const currentSku = computed(() => {
  return skus.value.find(s => s.color === selectedColor.value && s.size === selectedSize.value) || null
})

const selectSku = () => {}

const fetchDetail = async () => {
  try {
    const { data } = await http.get(`/product/${route.params.id}`)
    if (data.code === 200) product.value = data.data
  } catch (_) {}
}

const fetchSkus = async () => {
  try {
    const { data } = await http.get(`/sku/list/${route.params.id}`)
    if (data.code === 200) {
      skus.value = data.data || []
      if (skus.value.length > 0) {
        selectedColor.value = skus.value[0].color
        selectedSize.value = skus.value[0].size
      }
    }
  } catch (_) {}
}

const fetchReviews = async (page = 1) => {
  if (page === 1) reviewPage.value = 1
  try {
    const { data } = await http.get(`/review/product/${route.params.id}`, { params: { page, size: REVIEW_SIZE } })
    if (data.code === 200) {
      const d = data.data
      if (page === 1) reviews.value = d.list || []
      else reviews.value.push(...(d.list || []))
      hasMore.value = d.hasMore
    }
  } catch (_) {}
}

const fetchRatingStats = async () => {
  try {
    const { data } = await http.get(`/review/product/${route.params.id}/stats`)
    if (data.code === 200) ratingStats.value = data.data
  } catch (_) {}
}

const loadMore = () => {
  if (loadingMore.value || !hasMore.value) return
  loadingMore.value = true
  fetchReviews(reviewPage.value + 1).finally(() => {
    reviewPage.value++
    loadingMore.value = false
  })
}

const barPercent = (star) => {
  if (!ratingStats.value) return 0
  const d = ratingStats.value.distribution
  const t = ratingStats.value.totalCount
  if (!t) return 0
  return Math.round((d[star] || 0) / t * 100)
}

const getDistCount = (star) => {
  if (!ratingStats.value) return 0
  return ratingStats.value.distribution[star] || 0
}

// 滚动加载
const handleScroll = () => {
  const el = document.documentElement
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 300) {
    loadMore()
  }
}

// 管理员删除评论
const deleteReview = async (id) => {
  if (!confirm('确认删除该评论？')) return
  try {
    const { data } = await http.delete(`/review/${id}`)
    if (data.code === 200) {
      reviews.value = reviews.value.filter(r => r.id !== id)
      fetchRatingStats()
    } else alert(data.message || '删除失败')
  } catch (e) { alert(e.message || '网络异常') }
}

// 商家申诉
const openAppeal = (r) => {
  appealTarget.value = r
  appealReason.value = ''
  appealMsg.value = ''
  appealOk.value = false
  appealShow.value = true
}

const submitAppeal = async () => {
  if (!appealReason.value.trim()) { appealMsg.value = '请输入申诉理由'; appealOk.value = false; return }
  appealLoading.value = true
  try {
    const { data } = await http.post('/review/complaint/add', {
      reviewId: appealTarget.value.id,
      reason: appealReason.value.trim(),
    })
    if (data.code === 200) {
      appealOk.value = true
      appealMsg.value = '申诉已提交'
      setTimeout(() => { appealShow.value = false }, 1500)
    } else {
      appealOk.value = false
      appealMsg.value = data.message || '提交失败'
    }
  } catch (e) {
    appealOk.value = false
    appealMsg.value = e.response?.data?.message || e.message || '网络异常'
  } finally {
    appealLoading.value = false
  }
}

const addToCart = async () => {
  if (!isLoggedIn.value) { router.push('/login'); return }
  if (!currentSku.value) { msg.value = '请选择规格'; return }
  try {
    const { data } = await http.post('/cart/add', null, { params: { skuId: currentSku.value.id, quantity: qty.value } })
    if (data.code === 200) {
      showCartToast('已加入购物车')
      await refreshCartCount()
    }
    else msg.value = data.message
  } catch (e) { msg.value = e.message }
}

const buyNow = async () => {
  if (!isLoggedIn.value) { router.push('/login'); return }
  if (!currentSku.value) { msg.value = '请选择规格'; return }
  router.push({ path: '/order-create', query: { skuId: currentSku.value.id, quantity: qty.value } })
}

onMounted(async () => {
  // 商家需先获取 merchantId（login 不返回，需调 /merchant/me/id）
  if (isMerchant.value && !myMerchantId.value) {
    try {
      const { data: meData } = await http.get('/merchant/me/id')
      if (meData.code === 200 && meData.data && meData.data.merchantId) {
        myMerchantId.value = meData.data.merchantId
      }
    } catch (_) {}
  }
  await Promise.all([fetchDetail(), fetchSkus(), fetchReviews(), fetchRatingStats()])
  loading.value = false
  window.addEventListener('scroll', handleScroll, { passive: true })
})

onUnmounted(() => {
  window.removeEventListener('scroll', handleScroll)
})
</script>

<style scoped>
.detail-page { padding: 100px 0 60px; }
.detail-grid { display: grid; grid-template-columns: 500px 1fr; gap: 48px; align-items: start; }

/* 左侧图片 */
.detail-images { position: sticky; top: 100px; }
.main-img-wrap { border-radius: var(--radius); overflow: hidden; background: #f5f5f5; }
.main-img { width: 100% !important; height: auto !important; aspect-ratio: 3/4; object-fit: cover; display: block; }
.thumb-list { display: flex; gap: 8px; margin-top: 10px; }
.thumb { width: 56px; height: 56px; object-fit: cover; border-radius: 4px; cursor: pointer; border: 2px solid transparent; opacity: .6; transition: all .2s; }
.thumb:hover { opacity: 1; border-color: var(--color-accent); }

/* 右侧信息 */
.detail-info { min-width: 0; padding-top: 20px; }

/* 标题行：名称 + 右下角分类 */
.detail-title-row { margin-bottom: 16px; }
.detail-title-row h1 { font-size: 24px; font-weight: 700; line-height: 1.3; }
.detail-bread { font-size: 12px; color: var(--color-accent); margin-top: 4px; }

/* 商户卡片 */
.detail-merchant { display: flex; align-items: center; gap: 12px; padding: 14px 16px; background: var(--color-bg); border-radius: var(--radius); margin-bottom: 20px; }
.dm-avatar-wrap { width: 44px; height: 44px; border-radius: 12px; flex-shrink: 0; }
.dm-avatar { display: block; width: 44px; height: 44px; border-radius: 12px; object-fit: cover; box-shadow: 0 0 0 3px #fff, 0 0 0 5px var(--color-accent); }
.dm-avatar-fb { display: flex; align-items: center; justify-content: center; width: 44px; height: 44px; border-radius: 12px; background: linear-gradient(135deg, #1a1a2e 0%, #2d2d44 100%); color: #fff; font-family: var(--font-en); font-size: 18px; font-weight: 700; box-shadow: 0 0 0 3px #fff, 0 0 0 5px var(--color-accent); }
.dm-name { font-size: 14px; font-weight: 600; }
.dm-rating { font-size: 13px; color: var(--color-accent); margin-left: auto; }

/* 价格行 */
.detail-price-row { display: flex; align-items: baseline; gap: 16px; margin-bottom: 20px; }
.detail-price { font-family: var(--font-en); font-size: 26px; font-weight: 700; color: #c0392b; }
.detail-sales { font-size: 13px; color: var(--color-text-light); }

/* 介绍（购买下方） */
.detail-desc { font-size: 14px; color: var(--color-text-light); line-height: 1.7; margin-top: 20px; padding-top: 20px; border-top: 1px solid var(--color-border); }

/* 价格行 */
.detail-price-row { display: flex; align-items: baseline; gap: 16px; margin-bottom: 20px; }
.detail-price { font-family: var(--font-en); font-size: 26px; font-weight: 700; color: #c0392b; }
.detail-sales { font-size: 13px; color: var(--color-text-light); }

/* 商户 */
.detail-merchant { display: flex; align-items: center; gap: 12px; padding: 14px 16px; background: var(--color-bg); border-radius: var(--radius); margin-bottom: 20px; }
.dm-avatar-wrap { width: 44px; height: 44px; border-radius: 12px; flex-shrink: 0; }
.dm-avatar { display: block; width: 44px; height: 44px; border-radius: 12px; object-fit: cover; box-shadow: 0 0 0 3px #fff, 0 0 0 5px var(--color-accent); }
.dm-avatar-fb { display: flex; align-items: center; justify-content: center; width: 44px; height: 44px; border-radius: 12px; background: linear-gradient(135deg, #1a1a2e 0%, #2d2d44 100%); color: #fff; font-family: var(--font-en); font-size: 18px; font-weight: 700; box-shadow: 0 0 0 3px #fff, 0 0 0 5px var(--color-accent); }
.dm-name { font-size: 14px; font-weight: 600; }
.dm-rating { font-size: 13px; color: var(--color-accent); margin-left: auto; }

/* SKU */
.sku-section { margin-bottom: 24px; }
.sku-row { display: flex; align-items: center; gap: 16px; margin-bottom: 10px; }
.sku-label { font-size: 12px; color: var(--color-text-light); width: 32px; flex-shrink: 0; }
.sku-options { display: flex; gap: 8px; flex-wrap: wrap; }
.sku-options button { padding: 7px 18px; border: 1px solid var(--color-border); background: #fff; border-radius: 20px; font-size: 13px; cursor: pointer; transition: all .2s; }
.sku-options button.active { border-color: var(--color-primary); background: var(--color-primary); color: #fff; }
.sku-stock-row { display: flex; align-items: center; gap: 16px; margin-top: 8px; }
.sku-stock { font-size: 13px; color: #e67e22; font-weight: 600; }

/* 操作 */
.detail-actions { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }

/* 商户提示 */
.detail-merchant-notice { padding: 24px; background: #fdfaf4; border: 1px solid var(--color-accent-light); border-radius: var(--radius); text-align: center; }
.detail-merchant-notice p { font-size: 14px; color: #8b7355; }
.qty-box { display: flex; align-items: center; border: 1px solid var(--color-border); border-radius: var(--radius); overflow: hidden; }
.qty-box button { width: 36px; height: 36px; border: none; background: var(--color-bg); font-size: 16px; cursor: pointer; }
.qty-box span { width: 36px; text-align: center; font-size: 14px; }
.detail-msg { font-size: 13px; color: #27ae60; margin-top: 8px; }

/* 购物车 toast 弹窗 */
.cart-toast {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  background: rgba(0, 0, 0, 0.78);
  color: #fff;
  padding: 14px 32px;
  border-radius: 8px;
  font-size: 15px;
  z-index: 9999;
  pointer-events: none;
  white-space: nowrap;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.25);
}
.toast-enter-active { transition: opacity 0.25s ease, transform 0.25s ease; }
.toast-leave-active { transition: opacity 0.2s ease, transform 0.2s ease; }
.toast-enter-from { opacity: 0; transform: translate(-50%, -50%) scale(0.85); }
.toast-leave-to { opacity: 0; transform: translate(-50%, -50%) scale(0.9); }

/* 评价 */
.reviews-section { margin-top: 64px; border-top: 1px solid var(--color-border); padding-top: 40px; }
.reviews-section h3 { font-size: 20px; margin-bottom: 24px; }

/* 评分统计 */
.rating-stats { display: flex; gap: 48px; padding: 24px 32px; background: var(--color-bg); border-radius: 12px; margin-bottom: 32px; }
.rating-avg-col { text-align: center; min-width: 100px; }
.rating-avg-num { font-family: var(--font-en); font-size: 48px; font-weight: 700; color: var(--color-accent); line-height: 1; }
.rating-avg-stars { color: var(--color-accent); font-size: 16px; margin: 6px 0; }
.rating-avg-count { font-size: 12px; color: #999; }
.rating-bars-col { flex: 1; display: flex; flex-direction: column; gap: 6px; justify-content: center; }
.rating-bar-row { display: flex; align-items: center; gap: 8px; }
.bar-label { font-size: 12px; color: #666; width: 28px; text-align: right; }
.bar-track { flex: 1; height: 8px; background: #e8e5dd; border-radius: 4px; overflow: hidden; }
.bar-fill { height: 100%; background: var(--color-accent); border-radius: 4px; transition: width 0.4s; }
.bar-count { font-size: 12px; color: #999; width: 28px; }

/* 评论列表 */
.review-item { padding: 16px 0; border-bottom: 1px solid var(--color-border); }
.review-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.review-user { display: flex; align-items: center; gap: 8px; }
.review-avatar { width: 28px; height: 28px; border-radius: 50%; object-fit: cover; }
.review-avatar-fb { width: 28px; height: 28px; border-radius: 50%; background: #1a1a2e; color: #fff; display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 600; }
.review-actions { display: flex; align-items: center; gap: 12px; }
.stars { color: var(--color-accent); }
.btn-act { font-size: 12px; padding: 4px 10px; border-radius: 4px; border: none; cursor: pointer; }
.btn-del { background: #e74c3c; color: #fff; }
.btn-appeal { background: transparent; color: var(--color-accent); border: 1px solid var(--color-accent); }
.review-content { font-size: 14px; line-height: 1.6; color: var(--color-text); }
.review-date { font-size: 12px; color: #999; margin-top: 8px; }

.review-loading { text-align: center; padding: 20px; color: #999; font-size: 13px; }
.review-no-more { text-align: center; padding: 24px; color: #ccc; font-size: 13px; }
.review-empty { text-align: center; padding: 40px; color: #aaa; font-size: 14px; }

/* 申诉弹窗 */
.modal-mask { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.3); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal-card { background: #fff; border-radius: 12px; padding: 28px; width: 420px; max-width: 90vw; box-shadow: 0 12px 40px rgba(0,0,0,0.15); }
.modal-card h3 { font-size: 18px; margin-bottom: 16px; }
.form-group { margin-bottom: 14px; }
.form-group label { display: block; font-size: 12px; font-weight: 500; color: #666; margin-bottom: 4px; }
.form-group textarea { width: 100%; padding: 10px 12px; border: 1px solid #e8e5dd; border-radius: 6px; font-size: 14px; outline: none; resize: vertical; }
.form-group textarea:focus { border-color: var(--color-accent); }
.msg { font-size: 13px; margin-bottom: 8px; }
.msg-error { color: #d44; }
.msg-success { color: #27ae60; }
.modal-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 8px; }

@media (max-width: 768px) { .detail-grid { grid-template-columns: 1fr; } .detail-images { position: static; } }
</style>
