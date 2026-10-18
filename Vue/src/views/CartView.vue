<template>
  <div class="page cart-page">
    <section class="container">
      <div class="cart-header-row">
        <div class="slide-in-left">
          <p class="section-label">Shopping Cart</p>
          <h1 class="section-title">购物车</h1>
        </div>
      </div>

      <div class="cart-wrap" v-if="items.length > 0">
        <!-- 表头 -->
        <div class="cart-header">
          <span class="col-check"><input type="checkbox" v-model="allChecked" @change="toggleAll" /></span>
          <span class="col-img">商品</span>
          <span class="col-info"></span>
          <span class="col-price">单价</span>
          <span class="col-qty">数量</span>
          <span class="col-sub">小计</span>
          <span class="col-act">操作</span>
        </div>

        <!-- 列表 -->
        <div class="cart-item" v-for="item in items" :key="item.id">
          <span class="col-check"><input type="checkbox" v-model="item.checked" /></span>
          <span class="col-img">
            <img :src="item.productImage || '/images/products/OIP-C.webp'" :alt="item.productName" />
          </span>
          <span class="col-info">
            <p class="item-name">{{ item.productName }}</p>
            <p class="item-sku" v-if="item.skuCode">{{ item.skuCode }}</p>
          </span>
          <span class="col-price">¥{{ item.price }}</span>
          <span class="col-qty">
            <button @click="changeQty(item, -1)">−</button>
            <span>{{ item.quantity }}</span>
            <button @click="changeQty(item, 1)">+</button>
          </span>
          <span class="col-sub">¥{{ (item.price * item.quantity).toFixed(2) }}</span>
          <span class="col-act">
            <button class="del-btn" @click="removeItem(item)">删除</button>
          </span>
        </div>

        <!-- 底部栏 -->
        <div class="cart-footer">
          <div class="footer-left">
            <span class="total-label">已选 {{ checkedItems.length }} 件，合计：</span>
            <span class="total-price">¥{{ totalPrice }}</span>
          </div>
          <button class="btn btn-primary" @click="checkout" :disabled="checkedItems.length === 0">去结算</button>
        </div>
      </div>

      <div v-else-if="loading" class="empty">加载中...</div>
      <div v-else class="empty">
        <p>购物车空空如也</p>
        <router-link to="/products" class="btn btn-outline" style="margin-top:16px;display:inline-block;">去逛逛</router-link>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import http from '../utils/http.js'
import { setCartCount } from '../stores/auth.js'

const router = useRouter()
const items = ref([])
const loading = ref(true)
const allChecked = ref(true)

/* 勾选商品 */
const checkedItems = computed(() => items.value.filter(i => i.checked))

/* 总价 */
const totalPrice = computed(() => {
  return checkedItems.value.reduce((sum, i) => sum + i.price * i.quantity, 0).toFixed(2)
})

/* 获取购物车 */
const fetchCart = async () => {
  try {
    const { data } = await http.get('/cart/list')
    if (data.code === 200) {
      items.value = (data.data || []).map(i => ({
        ...i,
        checked: true,
        /* 展平嵌套字段，方便模板直接使用 */
        productName: i.product?.name || i.productName,
        productImage: i.product?.mainImage || i.productImage || i.sku?.imageUrl,
        price: i.sku?.price || i.price,
        skuCode: i.sku?.skuCode || i.skuCode,
        stock: i.sku?.stock,
        merchantId: i.product?.merchantId
      }))
      setCartCount(items.value.length)
    }
  } catch (_) {}
  loading.value = false
}

/* 全选 / 全不选 */
const toggleAll = () => {
  items.value.forEach(i => i.checked = allChecked.value)
}

/* 改数量 */
const changeQty = async (item, delta) => {
  const qty = item.quantity + delta
  if (qty < 1) return
  try {
    const { data } = await http.put(`/cart/updateQuantity/${item.id}`, null, { params: { quantity: qty } })
    if (data.code === 200) item.quantity = qty
  } catch (_) {}
}

/* 删除 */
const removeItem = async (item) => {
  try {
    // skuId 来自嵌套 sku 对象或展平字段
    const skuId = item.sku?.id || item.skuId
    const { data } = await http.delete(`/cart/deleteBySku`, { params: { skuId: skuId } })
    if (data.code === 200) {
      items.value = items.value.filter(i => i.id !== item.id)
      setCartCount(items.value.length)
    }
  } catch (_) {}
}

/* 结算 */
const checkout = () => {
  const ids = checkedItems.value.map(i => i.id)
  if (ids.length === 0) return
  router.push({ name: 'OrderCreate', query: { cartIds: ids.join(',') } })
}

onMounted(fetchCart)
</script>

<style scoped>
.cart-page { padding: 120px 0 80px; }

.cart-header-row { margin-bottom: 32px; }

/* 入场动画 */
.slide-in-left { animation: slideLeft 0.6s ease both; }
@keyframes slideLeft {
  from { opacity: 0; transform: translateX(-40px); }
  to   { opacity: 1; transform: translateX(0); }
}
.cart-wrap { background: #fff; border-radius: var(--radius-lg); box-shadow: var(--shadow-soft); overflow: hidden; }

.cart-header, .cart-item { display: flex; align-items: center; padding: 16px 20px; font-size: 13px; }
.cart-header { background: var(--color-bg); font-weight: 600; color: var(--color-text-light); }
.cart-item { border-bottom: 1px solid var(--color-border); }
.cart-item:hover { background: #fafafa; }

.col-check { width: 40px; flex-shrink: 0; }
.col-img { width: 100px; flex-shrink: 0; }
.col-img img { width: 80px !important; height: 80px !important; object-fit: cover; border-radius: 6px; }
.col-info { flex: 1; padding: 0 12px; }
.item-name { font-size: 14px; font-weight: 500; margin-bottom: 4px; }
.item-sku { font-size: 12px; color: #999; }
.col-price { width: 100px; text-align: center; flex-shrink: 0; }
.col-qty { width: 120px; text-align: center; flex-shrink: 0; display: flex; align-items: center; justify-content: center; gap: 8px; }
.col-qty button { width: 28px; height: 28px; border: 1px solid var(--color-border); background: #fff; border-radius: 4px; cursor: pointer; font-size: 14px; line-height: 26px; }
.col-qty span { font-size: 14px; min-width: 28px; }
.col-sub { width: 100px; text-align: center; flex-shrink: 0; font-weight: 600; color: #c0392b; }
.col-act { width: 80px; text-align: center; flex-shrink: 0; }
.del-btn { padding: 4px 12px; border: 1px solid var(--color-border); background: #fff; border-radius: 4px; color: #999; font-size: 12px; cursor: pointer; transition: all var(--transition); }
.del-btn:hover { color: #c0392b; border-color: #c0392b; }

.cart-footer { display: flex; align-items: center; justify-content: space-between; padding: 20px 28px; }
.total-label { font-size: 14px; color: var(--color-text-light); }
.total-price { font-family: var(--font-en); font-size: 22px; font-weight: 700; color: #c0392b; margin-left: 8px; }

.empty { text-align: center; padding: 80px 0; color: #999; }

@media (max-width: 768px) {
  .col-price, .col-sub { display: none; }
  .cart-item { flex-wrap: wrap; gap: 8px; }
}
</style>
