<template>
  <div class="pl-page">
    <div class="pl-main container">
      <!-- 标题行 -->
      <div class="pl-header">
        <div class="pl-header-left slide-in-left">
          <p class="section-label">All Products</p>
          <h1 class="section-title">全部商品</h1>
          <div class="pl-search">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
            <input v-model="keyword" @keyup.enter="doSearch" placeholder="搜索商品..." />
          </div>
        </div>
        <div class="pl-header-right slide-in-right">
          <span class="pl-tagline">发现属于你的那一件</span>
        </div>
      </div>

      <!-- 工具栏：全部 + 父分类 + 筛选 -->
      <div class="pl-toolbar">
        <div class="pl-toolbar-cats">
          <button :class="{ active: !activeParent && !activeCategory }" @click="selectAll()">全部</button>
          <button v-for="c in parentCats" :key="c.id"
            :class="{ active: activeParent === c.id }"
            @click="selectParent(c)">
            {{ c.name }}
          </button>
        </div>
        <button class="pl-filter-btn" :class="{ open: filterOpen }" @click="filterOpen = !filterOpen">
          筛选
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
            :class="{ rotated: filterOpen }">
            <polyline points="6 9 12 15 18 9"/>
          </svg>
        </button>
      </div>

      <!-- 筛选展开面板 -->
      <div class="pl-filter-panel" v-show="filterOpen">
        <div class="pl-filter-panel-inner">
          <!-- 分类行（子分类） -->
          <div class="pl-filter-row" v-if="subCatsForParent.length > 0">
            <span class="pl-filter-label">分类</span>
            <div class="pl-filter-tags">
              <button :class="{ active: !activeCategory }" @click="selectChild(null)">全部{{ parentName }}</button>
              <button v-for="c in subCatsForParent" :key="c.id"
                :class="{ active: activeCategory === c.id }"
                @click="selectChild(c)">
                {{ c.name }}
              </button>
            </div>
          </div>
          <div class="pl-filter-row" v-else-if="!activeParent">
            <span class="pl-filter-label">分类</span>
            <span class="pl-filter-hint">请先在工具栏选择分类</span>
          </div>
          <!-- 价格区间 -->
          <div class="pl-filter-row">
            <span class="pl-filter-label">价格</span>
            <div class="pl-filter-price">
              <input type="number" v-model="minPrice" placeholder="最低价" @keyup.enter="applyFilter" />
              <span class="pl-price-sep">—</span>
              <input type="number" v-model="maxPrice" placeholder="最高价" @keyup.enter="applyFilter" />
              <button class="pl-price-ok" @click="applyFilter">确定</button>
            </div>
          </div>
          <!-- 有子分类但子分类为空 -->
          <div class="pl-filter-row" v-if="activeParent && subCatsForParent.length === 0">
            <span class="pl-filter-hint">该分类下暂无子分类</span>
          </div>
        </div>
      </div>

      <!-- 商品网格 -->
      <div class="pl-grid" v-if="products.length > 0">
        <div v-for="item in products" :key="item.id" class="pl-card" @click="$router.push(`/product/${item.id}`)">
          <div class="pl-card-img">
            <img :src="item.mainImage || '/images/products/OIP-C.webp'" :alt="item.name" loading="lazy" />
            <span class="pl-tag" v-if="item.status === 'on_sale'">热卖</span>
          </div>
          <div class="pl-card-body">
            <h4>{{ item.name }}</h4>
            <div class="pl-card-footer">
              <div class="pl-card-shop">
                <img :src="item.shopLogo || '/images/products/OIP-C.webp'" class="pl-shop-avatar" />
                <span class="pl-shop-name">{{ item.shopName || '店铺' }}</span>
              </div>
              <div class="pl-card-right">
                <p class="pl-card-cat" v-if="item.parentCategoryName || item.categoryName">
                  {{ item.parentCategoryName ? item.parentCategoryName + ' · ' : '' }}{{ item.categoryName || '' }}
                </p>
                <span class="pl-price">¥{{ item.priceMin }}</span>
                <span class="pl-sales" v-if="item.salesCount != null">已售 {{ item.salesCount }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
      <div v-else class="pl-empty">暂无商品</div>

      <!-- 翻页 -->
      <div class="pl-pager" v-if="totalPages > 1">
        <button :disabled="page <= 1" @click="page--; fetchProducts()">上一页</button>
        <span>{{ page }} / {{ totalPages }}</span>
        <button :disabled="page >= totalPages" @click="page++; fetchProducts()">下一页</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import http from '../utils/http.js'

const allCategories = ref([])
const activeParent = ref(null)
const activeCategory = ref(null)
const parentName = ref('')
const keyword = ref('')
const minPrice = ref('')
const maxPrice = ref('')
const products = ref([])
const page = ref(1)
const size = ref(12)
const totalPages = ref(1)
const filterOpen = ref(false)

const parentCats = computed(() => allCategories.value.filter(c => c.parentId === 0))

const subCatsForParent = computed(() => {
  if (!activeParent.value) return []
  return allCategories.value.filter(c => c.parentId === activeParent.value)
})

const fetchCategories = async () => {
  try {
    const { data } = await http.get('/category/all')
    if (data.code === 200) allCategories.value = data.data || []
  } catch (_) {}
}

const buildCategoryIds = () => {
  if (activeCategory.value) {
    return { categoryId: activeCategory.value, categoryIds: null }
  }
  if (activeParent.value) {
    const ids = subCatsForParent.value.map(c => c.id)
    return { categoryId: null, categoryIds: ids.length > 0 ? ids : [activeParent.value] }
  }
  return { categoryId: null, categoryIds: null }
}

const fetchProducts = async () => {
  try {
    const params = { page: page.value, size: size.value }
    const ids = buildCategoryIds()
    if (ids.categoryId) params.categoryId = ids.categoryId
    if (ids.categoryIds && ids.categoryIds.length > 0) {
      params.categoryIds = ids.categoryIds.join(',')
    }
    if (keyword.value) params.keyword = keyword.value
    if (minPrice.value) params.minPrice = minPrice.value
    if (maxPrice.value) params.maxPrice = maxPrice.value
    const { data } = await http.get('/search/product', { params })
    if (data.code === 200) {
      const res = data.data
      products.value = res.records || []
      totalPages.value = Math.ceil((res.total || 0) / size.value)
    }
  } catch (_) {}
}

const selectAll = () => {
  activeParent.value = null
  activeCategory.value = null
  parentName.value = ''
  page.value = 1
  fetchProducts()
}

const selectParent = (c) => {
  if (activeParent.value === c.id && !activeCategory.value) {
    activeParent.value = null
    activeCategory.value = null
    parentName.value = ''
    page.value = 1
    fetchProducts()
    return
  }
  activeParent.value = c.id
  activeCategory.value = null
  parentName.value = c.name
  filterOpen.value = true
  page.value = 1
  fetchProducts()
}

const selectChild = (c) => {
  if (c) {
    activeCategory.value = c.id
  } else {
    activeCategory.value = null
  }
  page.value = 1
  fetchProducts()
}

const applyFilter = () => {
  page.value = 1
  fetchProducts()
}

const doSearch = () => {
  page.value = 1
  fetchProducts()
}

onMounted(() => { fetchCategories(); fetchProducts() })
</script>

<style scoped>
.pl-page { background: var(--color-bg); min-height: 100vh; }
.pl-main { max-width: 1280px; margin: 0 auto; padding: 120px 24px 80px; }

/* 标题行 */
.pl-header { display: flex; align-items: flex-start; margin-bottom: 20px; }
.pl-header-left .section-label { margin-bottom: 6px; }
.section-title { margin-bottom: 16px; font-size: 36px; }
.pl-header-right { padding-top: 40px; flex-shrink: 0; margin-left: auto; margin-right: 200px; }

/* 入场动画 */
.slide-in-left { animation: slideLeft 0.6s ease both; }
.slide-in-right { animation: slideRight 0.6s ease both; }
@keyframes slideLeft {
  from { opacity: 0; transform: translateX(-40px); }
  to   { opacity: 1; transform: translateX(0); }
}
@keyframes slideRight {
  from { opacity: 0; transform: translateX(40px); }
  to   { opacity: 1; transform: translateX(0); }
}
.pl-tagline {
  font-family: '华文行楷', 'STXingkai', 'KaiTi', 'STKaiti', '楷体', serif;
  font-size: 26px;
  font-weight: 700;
  color: #1a1a1a;
  letter-spacing: 4px;
  white-space: nowrap;
}

/* 工具栏 */
.pl-toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; flex-wrap: wrap; gap: 8px; }
.pl-toolbar-cats { display: flex; flex-wrap: wrap; gap: 8px; }
.pl-toolbar-cats button,
.pl-filter-btn {
  padding: 8px 20px;
  border: 1px solid var(--color-border);
  background: #fff;
  border-radius: 20px;
  font-size: 13px;
  cursor: pointer;
  color: var(--color-text);
  transition: all .25s;
  display: inline-flex; align-items: center; gap: 6px;
}
.pl-toolbar-cats button:hover,
.pl-filter-btn:hover { border-color: var(--color-accent); color: var(--color-accent); }
.pl-toolbar-cats button.active { background: var(--color-primary); color: #fff; border-color: var(--color-primary); }
.pl-filter-btn.open { background: var(--color-primary); color: #fff; border-color: var(--color-primary); }
.pl-filter-btn svg { transition: transform .25s; }
.pl-filter-btn svg.rotated { transform: rotate(180deg); }

/* 筛选展开面板 */
.pl-filter-panel {
  background: #fff;
  border-radius: var(--radius);
  box-shadow: var(--shadow-card);
  border: 1px solid var(--color-border);
  margin-bottom: 24px;
  overflow: hidden;
}
.pl-filter-panel-inner { padding: 16px 20px; }
.pl-filter-row { display: flex; align-items: flex-start; margin-bottom: 14px; }
.pl-filter-row:last-child { margin-bottom: 0; }
.pl-filter-label { flex-shrink: 0; width: 48px; font-size: 12px; color: var(--color-text-light); padding-top: 7px; letter-spacing: 1px; }
.pl-filter-tags { display: flex; flex-wrap: wrap; gap: 8px; flex: 1; }
.pl-filter-tags button {
  padding: 6px 18px;
  border: 1px solid #e8e5dd;
  background: #fafaf8;
  border-radius: 18px;
  font-size: 13px;
  cursor: pointer;
  color: #555;
  transition: all .2s;
}
.pl-filter-tags button:hover { border-color: var(--color-accent); color: var(--color-accent); }
.pl-filter-tags button.active { background: var(--color-primary); color: #fff; border-color: var(--color-primary); }
.pl-filter-hint { font-size: 12px; color: #bbb; padding-top: 7px; }

/* 价格区间 */
.pl-filter-price { display: flex; align-items: center; gap: 8px; }
.pl-filter-price input {
  width: 100px;
  padding: 7px 10px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  font-size: 13px;
  outline: none;
  background: #fafaf8;
  transition: border-color .25s;
}
.pl-filter-price input:focus { border-color: var(--color-accent); }
.pl-price-sep { color: #ccc; font-size: 13px; }
.pl-price-ok {
  padding: 7px 16px;
  border: none;
  background: var(--color-primary);
  color: #fff;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  transition: background .25s;
}
.pl-price-ok:hover { background: #2a2a42; }

/* 搜索 */
.pl-search { position: relative; margin-top: 4px; }
.pl-search svg { position: absolute; left: 14px; top: 50%; transform: translateY(-50%); color: var(--color-text-light); }
.pl-search input {
  width: 100%; max-width: 480px; padding: 10px 14px 10px 40px;
  border: 1px solid var(--color-border); border-radius: 20px;
  font-size: 13px; outline: none; background: #fff;
  transition: border-color .25s;
}
.pl-search input:focus { border-color: var(--color-accent); }

/* 商品网格 */
.pl-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 16px; }
.pl-card { background: #fff; border-radius: var(--radius); overflow: hidden; box-shadow: var(--shadow-card); cursor: pointer; transition: all .35s; max-width: 260px; margin: 0 auto; width: 100%; }
.pl-card-img { position: relative; aspect-ratio: 3/4; background: #f5f5f5; overflow: hidden; }
.pl-card-img img { width: 100% !important; height: 100% !important; object-fit: cover; transition: transform .4s; }
.pl-card:hover .pl-card-img img { transform: scale(1.03); }
.pl-tag { position: absolute; top: 8px; left: 8px; background: var(--color-accent); color: #fff; font-size: 11px; padding: 2px 8px; border-radius: 10px; }
.pl-card-body { padding: 10px 12px; }
.pl-card-body h4 { font-size: 13px; font-weight: 600; margin-bottom: 6px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 卡片底部行：左店铺 + 右价格 */
.pl-card-footer { display: flex; justify-content: space-between; align-items: flex-end; gap: 6px; }
.pl-card-shop { display: flex; align-items: center; gap: 5px; min-width: 0; flex: 1; overflow: hidden; }
.pl-shop-avatar { width: 18px; height: 18px; border-radius: 50%; object-fit: cover; flex-shrink: 0; }
.pl-shop-name { font-size: 11px; color: #999; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.pl-card-right { display: flex; flex-direction: column; align-items: flex-end; flex-shrink: 0; }
.pl-card-cat { font-size: 10px; color: var(--color-accent); margin-bottom: 2px; }
.pl-price { font-weight: 700; color: #c0392b; font-family: var(--font-en); font-size: 14px; }
.pl-sales { font-size: 11px; color: var(--color-text-light); }

.pl-empty { text-align: center; padding: 100px 0; color: #999; font-size: 14px; }
.pl-pager { display: flex; justify-content: center; align-items: center; gap: 20px; padding: 48px 0 0; }
.pl-pager button { padding: 8px 24px; border: 1px solid var(--color-border); background: #fff; border-radius: var(--radius); font-size: 13px; cursor: pointer; }
.pl-pager button:disabled { opacity: .4; cursor: not-allowed; }
.pl-pager span { font-size: 13px; color: #888; }

@media (max-width: 1200px) { .pl-grid { grid-template-columns: repeat(4, 1fr); } }
@media (max-width: 1024px) { .pl-grid { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 768px) {
  .pl-grid { grid-template-columns: repeat(2, 1fr); }
  .pl-header { flex-direction: column; }
  .pl-header-right { padding-top: 12px; margin-left: 0; }
}
</style>
