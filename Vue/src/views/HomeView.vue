<template>
  <div class="home">

    <!-- ======== 1. 首屏 Hero 轮播图 ======== -->
    <section id="hero" class="hero-section">
      <div class="carousel">
        <div
          v-for="(banner, idx) in banners"
          :key="idx"
          class="carousel-slide"
          :class="{ active: idx === currentSlide }"
        >
          <img :src="banner.src" :alt="banner.alt" />
          <div class="carousel-overlay">
            <div class="container">
              <p class="slide-tag fade-up">{{ banner.tag }}</p>
              <h2 class="slide-title fade-up fade-up-delay-1">{{ banner.title }}</h2>
              <p class="slide-sub fade-up fade-up-delay-2">{{ banner.subtitle }}</p>
              <router-link to="/" class="btn btn-outline-light fade-up fade-up-delay-3">立即探索</router-link>
            </div>
          </div>
        </div>
      </div>
      <!-- 指示器 -->
      <div class="carousel-dots">
        <button
          v-for="(_, idx) in banners"
          :key="idx"
          :class="{ active: idx === currentSlide }"
          @click="goSlide(idx)"
        />
      </div>
      <!-- 箭头 -->
      <button class="carousel-arrow left" @click="prevSlide">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"/></svg>
      </button>
      <button class="carousel-arrow right" @click="nextSlide">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"/></svg>
      </button>
    </section>

    <!-- ======== 2. 品牌视频区 ======== -->
    <section id="video-section" class="video-section reveal-top">
      <div class="container">
        <p class="section-label">Brand Story</p>
        <h2 class="section-title">不止于衣 · 更是一种生活态度</h2>
        <p class="section-desc">每一针线，都是匠心。从面料甄选到版型打磨，我们相信好的衣服会说话。</p>
      </div>
      <div class="video-wrapper">
        <video
          ref="videoRef"
          :src="bgVideo"
          muted
          loop
          playsinline
          class="bg-video"
        />
        <div class="video-controls">
          <button class="play-btn" @click="toggleVideo">
            <svg v-if="!videoPlaying" width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><polygon points="5,3 19,12 5,21"/></svg>
            <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>
            {{ videoPlaying ? '暂停' : '播放' }}
          </button>
        </div>
      </div>
    </section>

    <!-- ======== 3. 推荐商品区 ======== -->
    <section id="recommend" class="recommend-section reveal-bottom">
      <div class="container">
        <p class="section-label">Curated Picks</p>
        <h2 class="section-title">本周精选 · 为你甄选</h2>
        <p class="section-desc">买手团队亲赴源头，严选当季面料与设计，只为把最好的给你。</p>

        <div class="product-grid">
          <div
            v-for="item in products"
            :key="item.id"
            class="product-card fade-up"
          >
            <div class="product-img-box">
              <img :src="item.mainImage || item.image" :alt="item.name" loading="lazy" />
              <div class="product-overlay">
                <button class="btn btn-outline-light btn-sm" @click="goDetail(item.id)">查看详情</button>
              </div>
            </div>
            <div class="product-info">
              <h4>{{ item.name }}</h4>
              <p class="product-price"><span v-if="item.priceMin != null">¥{{ item.priceMin }} ~ ¥{{ item.priceMax }}</span></p>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ======== 4. 关于平台 ======== -->
    <section id="about" class="about-section reveal-bottom">
      <div class="container">
        <p class="section-label">About ShopAi</p>
        <h2 class="section-title">一个值得信赖的服装平台</h2>
        <p class="section-desc" style="margin:0 auto 48px; text-align:center;">
          我们连接优质商家与品味消费者，从面料溯源到售后保障，
          用数字化能力重塑服装消费体验。
        </p>

        <div class="feature-grid">
          <div class="feature-card">
            <div class="feature-icon">
              <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
            </div>
            <h4>正品保障</h4>
            <p>品牌直供，层层质检，假一赔十</p>
          </div>
          <div class="feature-card">
            <div class="feature-icon">
              <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="1" y="3" width="15" height="13"/><polygon points="16 8 20 8 23 11 23 16 16 16 16 8"/><circle cx="5.5" cy="18.5" r="2.5"/><circle cx="18.5" cy="18.5" r="2.5"/></svg>
            </div>
            <h4>极速物流</h4>
            <p>下单后48小时内发货，全国顺丰包邮</p>
          </div>
          <div class="feature-card">
            <div class="feature-icon">
              <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
            </div>
            <h4>售后无忧</h4>
            <p>7天无理由退换，专属客服一对一</p>
          </div>
          <div class="feature-card">
            <div class="feature-icon">
              <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>
            </div>
            <h4>实时更新</h4>
            <p>每周上新 100+ 单品，紧跟潮流趋势</p>
          </div>
        </div>
      </div>
    </section>

  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import http from '../utils/http.js'

const router = useRouter()

/* ===== 轮播数据 ===== */
const banners = [
  { src: '/images/banner/banner1.png', alt: '2026春夏系列', tag: '2026 S/S COLLECTION', title: '春夏季 · 轻盈登场', subtitle: '甄选天然面料，以简约线条勾勒优雅轮廓' },
  { src: '/images/banner/banner2.png', alt: '新中式系列', tag: 'NEW ARRIVAL', title: '新中式 · 东方美学', subtitle: '传统与现代交织，为日常注入一抹诗意' },
  { src: '/images/banner/banner3.png', alt: '极简主义', tag: 'MINIMALISM', title: '极简 · 少即是多', subtitle: '去掉浮饰，回归穿衣的本质' },
  { src: '/images/banner/banner4.png', alt: '秋冬系列', tag: 'COMING SOON', title: '秋冬季 · 温柔守护', subtitle: '提前预览，让温暖如期而至' },
]

const currentSlide = ref(0)
let timer = null

const resetAuto = () => {
  clearInterval(timer)
  timer = setInterval(() => {
    currentSlide.value = (currentSlide.value + 1) % banners.length
  }, 5000)
}

const goSlide = (idx) => { currentSlide.value = idx; resetAuto() }
const prevSlide = () => { currentSlide.value = (currentSlide.value - 1 + banners.length) % banners.length; resetAuto() }
const nextSlide = () => { currentSlide.value = (currentSlide.value + 1) % banners.length; resetAuto() }

/* ===== 视频 ===== */
const bgVideo = '/video/bg.mp4'
const videoRef = ref(null)
const videoPlaying = ref(false)

const toggleVideo = () => {
  if (!videoRef.value) return
  if (videoRef.value.paused) { videoRef.value.play(); videoPlaying.value = true }
  else { videoRef.value.pause(); videoPlaying.value = false }
}

/* ===== 滚动入场动画 ===== */
const observeSections = () => {
  const sections = document.querySelectorAll('.reveal-top, .reveal-bottom')
  if (!sections.length) return
  const obs = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) entry.target.classList.add('revealed')
      else entry.target.classList.remove('revealed')
    })
  }, { threshold: 0.15 })
  sections.forEach(el => obs.observe(el))
}

/* ===== 推荐商品（按销量取前6） ===== */
const products = ref([])

const fetchHotProducts = async () => {
  try {
    const { data } = await http.get('/product/hot', { params: { limit: 6 } })
    if (data.code === 200) {
      products.value = (data.data || []).map(p => ({
        ...p,
        image: p.mainImage || (p.images && p.images.length > 0 ? p.images[0] : '/images/products/OIP-C.webp')
      }))
    }
  } catch (_) {}
}

const goDetail = (id) => { router.push(`/product/${id}`) }

onMounted(() => {
  resetAuto()
  observeSections()
  fetchHotProducts()
  if (videoRef.value) {
    videoRef.value.addEventListener('play', () => (videoPlaying.value = true))
    videoRef.value.addEventListener('pause', () => (videoPlaying.value = false))
  }
})

onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
/* ===== 1. Hero 轮播图 ===== */
.hero-section {
  position: relative;
  height: 100vh;
  min-height: 600px;
  overflow: hidden;
  background: var(--color-bg-dark);
}

.carousel-slide {
  position: absolute;
  inset: 0;
  opacity: 0;
  transition: opacity 1s ease;
  pointer-events: none;
}
.carousel-slide.active {
  opacity: 1;
  pointer-events: auto;
}
.carousel-slide img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  filter: brightness(0.55);
}

.carousel-overlay {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 80px 0 100px;
  background: linear-gradient(transparent 0%, rgba(15, 15, 26, 0.55) 50%, rgba(15, 15, 26, 0.9) 100%);
  color: #fff;
}

.slide-tag {
  font-family: var(--font-en);
  font-size: 12px;
  letter-spacing: 4px;
  text-transform: uppercase;
  color: var(--color-accent);
  margin-bottom: 12px;
}
.slide-title {
  font-size: 52px;
  font-weight: 600;
  letter-spacing: 2px;
  max-width: 600px;
}
.slide-sub {
  font-size: 16px;
  color: rgba(255, 255, 255, 0.7);
  margin: 16px 0 32px;
  max-width: 480px;
  line-height: 1.8;
}

/* 指示器 */
.carousel-dots {
  position: absolute;
  bottom: 48px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  gap: 10px;
  z-index: 10;
}
.carousel-dots button {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  border: 1px solid rgba(255, 255, 255, 0.5);
  background: transparent;
  cursor: pointer;
  transition: all var(--transition);
}
.carousel-dots button.active {
  background: var(--color-accent);
  border-color: var(--color-accent);
}

/* 箭头 */
.carousel-arrow {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  background: rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(8px);
  border: 1px solid rgba(255, 255, 255, 0.2);
  color: #fff;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  z-index: 10;
  transition: background var(--transition);
}
.carousel-arrow:hover {
  background: rgba(255, 255, 255, 0.2);
}
.carousel-arrow.left { left: 24px; }
.carousel-arrow.right { right: 24px; }

/* ===== 2. 视频区 ===== */
.video-section {
  padding: 80px 0 0;
  text-align: center;
}

.video-wrapper {
  margin-top: 48px;
  position: relative;
  max-width: 1120px;
  margin-left: auto;
  margin-right: auto;
  border-radius: var(--radius-lg);
  overflow: hidden;
  box-shadow: var(--shadow-soft);
}

.bg-video {
  width: 100%;
  height: auto;
  max-height: 560px;
  object-fit: cover;
}

.video-controls {
  position: absolute;
  bottom: 20px;
  left: 20px;
}

.play-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 22px;
  background: rgba(255, 255, 255, 0.12);
  backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.2);
  color: #fff;
  font-size: 13px;
  letter-spacing: 1px;
  border-radius: 24px;
  cursor: pointer;
  transition: background var(--transition);
}
.play-btn:hover {
  background: rgba(255, 255, 255, 0.22);
}

/* ===== 3. 推荐商品 ===== */
.recommend-section {
  padding: 80px 0;
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 28px;
  margin-top: 40px;
}

.product-card {
  background: var(--color-bg-card);
  border-radius: var(--radius);
  overflow: hidden;
  box-shadow: var(--shadow-card);
  transition: transform var(--transition), box-shadow var(--transition);
  cursor: pointer;
}
.product-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.1);
}

.product-img-box {
  position: relative;
  aspect-ratio: 3 / 4;
  overflow: hidden;
  background: #f5f5f5;
}
.product-img-box img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.5s ease;
}
.product-card:hover .product-img-box img {
  transform: scale(1.04);
}

.product-overlay {
  position: absolute;
  inset: 0;
  background: rgba(15, 15, 26, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: opacity var(--transition);
}
.product-card:hover .product-overlay {
  opacity: 1;
}

.product-info {
  padding: 16px 18px;
}
.product-info h4 {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 4px;
}
.product-price {
  font-family: var(--font-en);
  font-size: 15px;
  color: var(--color-accent);
  font-weight: 600;
}

/* ===== 4. 关于平台 ===== */
.about-section {
  padding: 80px 0;
  text-align: center;
}
.about-section .section-desc {
  margin-left: auto;
  margin-right: auto;
}

.feature-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 32px;
  margin-top: 16px;
}

.feature-card {
  padding: 32px 20px;
  border-radius: var(--radius);
  border: 1px solid var(--color-border);
  transition: border-color var(--transition), box-shadow var(--transition);
}
.feature-card:hover {
  border-color: var(--color-accent);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.04);
}

.feature-icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-bg);
  border-radius: 50%;
  color: var(--color-accent);
}

.feature-card h4 {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 6px;
}
.feature-card p {
  font-size: 13px;
  color: var(--color-text-light);
  line-height: 1.6;
}

/* ===== 响应式 ===== */
@media (max-width: 1024px) {
  .slide-title { font-size: 38px; }
  .product-grid { grid-template-columns: repeat(2, 1fr); }
  .feature-grid { grid-template-columns: repeat(2, 1fr); }
}

@media (max-width: 768px) {
  .hero-section { height: 70vh; min-height: 420px; }
  .slide-title { font-size: 28px; }
  .slide-sub { font-size: 14px; }
  .carousel-arrow { display: none; }
  .product-grid { grid-template-columns: 1fr; }
  .feature-grid { grid-template-columns: 1fr; }
}

/* ===== 滚动入场动画 ===== */
.reveal-top {
  opacity: 0;
  transform: scale(1.12) translateY(-30px);
  transition: opacity 0.9s ease, transform 0.9s ease;
}
.reveal-top.revealed {
  opacity: 1;
  transform: scale(1) translateY(0);
}

.reveal-bottom {
  opacity: 0;
  transform: scale(0.92) translateY(40px);
  transition: opacity 0.9s ease, transform 0.9s ease;
}
.reveal-bottom.revealed {
  opacity: 1;
  transform: scale(1) translateY(0);
}
</style>
