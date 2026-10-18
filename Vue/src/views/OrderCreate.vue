<template>
  <div class="page checkout-page">
    <section class="container">
      <p class="section-label">Checkout</p>
      <h1 class="section-title">确认订单</h1>

      <div class="checkout-grid" v-if="checkedItems.length > 0">
        <!-- 左侧：地址 + 商品 -->
        <div class="checkout-main">
          <!-- 选择地址 -->
          <div class="card">
            <div class="address-card-header">
              <h3>收货地址</h3>
              <button class="btn btn-sm btn-outline" @click="openAddrForm()" v-if="!showAddrForm">添加新地址</button>
            </div>

            <!-- 新增地址表单 — 始终在列表最上方 -->
            <div v-if="showAddrForm && !editingAddrId" class="addr-form addr-form-top">
              <h4>新增地址</h4>
              <div class="form-row">
                <input v-model="addrForm.receiverName" placeholder="收货人姓名" />
                <input v-model="addrForm.receiverPhone" placeholder="手机号" @blur="validatePhone" />
              </div>
              <p class="form-err" v-if="phoneErr">{{ phoneErr }}</p>
              <div class="form-row cols-3">
                <select v-model="selProvince" @change="onProvinceChange">
                  <option value="">选择省</option>
                  <option v-for="p in regionData" :key="p.value" :value="p.value">{{ p.label }}</option>
                </select>
                <select v-model="selCity" @change="onCityChange" :disabled="!selProvince">
                  <option value="">选择市</option>
                  <option v-for="c in cityList" :key="c.value" :value="c.value">{{ c.label }}</option>
                </select>
                <select v-model="selDistrict" @change="onDistrictChange" :disabled="!selCity">
                  <option value="">选择区/县</option>
                  <option v-for="d in districtList" :key="d.value" :value="d.value">{{ d.label }}</option>
                </select>
              </div>
              <div class="form-row">
                <input v-model="addrForm.detailAddress" placeholder="详细地址（街道、门牌号等）" />
              </div>
              <div class="form-acts">
                <button class="btn btn-primary btn-sm" @click="saveAddress" :disabled="savingAddr">{{ savingAddr ? '保存中...' : '保存' }}</button>
                <button class="btn btn-ghost btn-sm" @click="closeForm">取消</button>
              </div>
            </div>

            <div class="address-list" v-if="addresses.length > 0">
              <div
                class="address-card"
                v-for="addr in addresses"
                :key="addr.id"
                :class="{ active: selectedAddr === addr.id, editing: showAddrForm && editingAddrId === addr.id }"
                @click="editingAddrId !== addr.id && (selectedAddr = addr.id)"
                @dblclick="editAddr(addr)"
              >
                <template v-if="!(showAddrForm && editingAddrId === addr.id)">
                  <div class="addr-body">
                    <div class="addr-head">
                      <strong>{{ addr.receiverName || addr.contactName }}</strong>
                      <span class="addr-phone">{{ addr.receiverPhone || addr.contactPhone }}</span>
                    </div>
                    <p class="addr-full">{{ addr.province }}{{ addr.city }}{{ addr.district }}{{ addr.detailAddress || addr.detail }}</p>
                  </div>
                  <button class="act-btn" @click.stop="editAddr(addr)" title="编辑">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
                  </button>
                </template>

                <!-- 编辑表单 — 内联在该卡片内 -->
                <div v-else class="addr-form addr-form-inline" @click.stop>
                  <h4>编辑地址</h4>
                  <div class="form-row">
                    <input v-model="addrForm.receiverName" placeholder="收货人姓名" />
                    <input v-model="addrForm.receiverPhone" placeholder="手机号" @blur="validatePhone" />
                  </div>
                  <p class="form-err" v-if="phoneErr">{{ phoneErr }}</p>
                  <div class="form-row cols-3">
                    <select v-model="selProvince" @change="onProvinceChange">
                      <option value="">选择省</option>
                      <option v-for="p in regionData" :key="p.value" :value="p.value">{{ p.label }}</option>
                    </select>
                    <select v-model="selCity" @change="onCityChange" :disabled="!selProvince">
                      <option value="">选择市</option>
                      <option v-for="c in cityList" :key="c.value" :value="c.value">{{ c.label }}</option>
                    </select>
                    <select v-model="selDistrict" @change="onDistrictChange" :disabled="!selCity">
                      <option value="">选择区/县</option>
                      <option v-for="d in districtList" :key="d.value" :value="d.value">{{ d.label }}</option>
                    </select>
                  </div>
                  <div class="form-row">
                    <input v-model="addrForm.detailAddress" placeholder="详细地址（街道、门牌号等）" />
                  </div>
                  <div class="form-acts">
                    <button class="btn btn-primary btn-sm" @click="saveAddress" :disabled="savingAddr || phoneErr !== ''">{{ savingAddr ? '保存中...' : '保存' }}</button>
                    <button class="btn btn-ghost btn-sm" @click="closeForm">取消</button>
                  </div>
                </div>
              </div>
            </div>
            <p v-else-if="!showAddrForm" class="empty-tip">暂无收货地址，请添加</p>
          </div>

          <!-- 商品清单 -->
          <div class="card">
            <h3>商品清单</h3>
            <div class="checkout-item" v-for="item in checkedItems" :key="item.id">
              <img :src="item.productImage || '/images/products/OIP-C.webp'" :alt="item.productName" />
              <div class="item-info">
                <p>{{ item.productName }}</p>
                <p class="sku" v-if="item.skuCode">{{ item.skuCode }}</p>
              </div>
              <span>¥{{ item.price }} × {{ item.quantity }}</span>
              <strong>¥{{ (item.price * item.quantity).toFixed(2) }}</strong>
            </div>
          </div>
        </div>

        <!-- 右侧：金额汇总 -->
        <div class="checkout-summary card">
          <h3>订单汇总</h3>
          <div class="summary-row">
            <span>商品件数</span><span>{{ checkedItems.reduce((s, i) => s + i.quantity, 0) }} 件</span>
          </div>
          <div class="summary-row">
            <span>商品总额</span><span>¥{{ totalPrice }}</span>
          </div>
          <div class="summary-row total">
            <span>应付金额</span><em>¥{{ totalPrice }}</em>
          </div>
          <div class="form-row" style="margin-top:12px;">
            <input v-model="remark" placeholder="订单备注（选填）" />
          </div>
          <button class="btn btn-primary" style="width:100%;margin-top:16px;" @click="submitOrder" :disabled="!selectedAddr || submitting">
            {{ submitting ? '提交中...' : '提交订单' }}
          </button>
          <button class="btn btn-outline" style="width:100%;margin-top:10px;" @click="$router.go(-1)">取消</button>
          <p v-if="checkoutMsg" class="pub-msg">{{ checkoutMsg }}</p>
        </div>
      </div>

      <div v-else-if="loading" class="empty">加载中...</div>
      <div v-else class="empty">
        <p>没有待结算的商品</p>
        <router-link to="/cart" class="btn btn-outline" style="margin-top:16px;display:inline-block;">返回购物车</router-link>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../utils/http.js'
import regionData from '../utils/regionData.js'
import { refreshCartCount } from '../stores/auth.js'

const route = useRoute()
const router = useRouter()
const addresses = ref([])
const selectedAddr = ref(null)
const cartItems = ref([])
const directItem = ref(null)   // 直接购买的商品
const loading = ref(true)
const remark = ref('')
const submitting = ref(false)
const checkoutMsg = ref('')

// ── 地址表单 ──
const showAddrForm = ref(false)
const editingAddrId = ref(null)
const savingAddr = ref(false)
const phoneErr = ref('')
const addrForm = reactive({ receiverName: '', receiverPhone: '', province: '', city: '', district: '', detailAddress: '' })
const selProvince = ref('')
const selCity = ref('')
const selDistrict = ref('')

const cityList = computed(() => {
  const p = regionData.find(r => r.value === selProvince.value)
  return p ? p.children : []
})
const districtList = computed(() => {
  if (!cityList.value.length) return []
  const c = cityList.value.find(r => r.value === selCity.value)
  return c ? c.children : []
})
const onProvinceChange = () => {
  selCity.value = ''
  selDistrict.value = ''
  addrForm.province = regionData.find(r => r.value === selProvince.value)?.label || ''
  addrForm.city = ''
  addrForm.district = ''
}
const onCityChange = () => {
  selDistrict.value = ''
  addrForm.city = cityList.value.find(r => r.value === selCity.value)?.label || ''
  addrForm.district = ''
}
const onDistrictChange = () => {
  addrForm.district = districtList.value.find(r => r.value === selDistrict.value)?.label || ''
}

const openAddrForm = () => {
  editingAddrId.value = null
  Object.assign(addrForm, { receiverName: '', receiverPhone: '', province: '', city: '', district: '', detailAddress: '' })
  selProvince.value = ''
  selCity.value = ''
  selDistrict.value = ''
  phoneErr.value = ''
  showAddrForm.value = true
}

const editAddr = (addr) => {
  editingAddrId.value = addr.id
  Object.assign(addrForm, {
    receiverName: addr.receiverName || addr.contactName || '',
    receiverPhone: addr.receiverPhone || addr.contactPhone || '',
    province: addr.province || '',
    city: addr.city || '',
    district: addr.district || '',
    detailAddress: addr.detailAddress || addr.detail || '',
  })
  // 同步下拉框
  selProvince.value = ''
  selCity.value = ''
  selDistrict.value = ''
  if (addr.province) {
    const p = regionData.find(r => r.label === addr.province)
    if (p) {
      selProvince.value = p.value
      if (addr.city) {
        const c = p.children.find(r => r.label === addr.city)
        if (c) {
          selCity.value = c.value
          if (addr.district) {
            const d = c.children.find(r => r.label === addr.district)
            if (d) selDistrict.value = d.value
          }
        }
      }
    }
  }
  phoneErr.value = ''
  showAddrForm.value = true
}

const closeForm = () => {
  showAddrForm.value = false
  editingAddrId.value = null
  phoneErr.value = ''
}

const validatePhone = () => {
  const phone = addrForm.receiverPhone
  if (!phone) { phoneErr.value = ''; return }
  const re = /^1[3-9]\d{9}$/
  phoneErr.value = re.test(phone) ? '' : '请输入正确的手机号'
}

const saveAddress = async () => {
  if (!addrForm.receiverName || !addrForm.receiverPhone || !addrForm.province || !addrForm.city || !addrForm.district || !addrForm.detailAddress) {
    checkoutMsg.value = '请完整填写收货地址'
    return
  }
  if (addrForm.receiverPhone) {
    validatePhone()
    if (phoneErr.value) return
  }
  savingAddr.value = true
  try {
    let res
    if (editingAddrId.value) {
      const payload = { ...addrForm, id: editingAddrId.value, isDefault: 0 }
      res = await http.put(`/address/${editingAddrId.value}`, payload)
    } else {
      res = await http.post('/address/add', { ...addrForm })
    }
    if (res.data.code === 200) {
      closeForm()
      await fetchAddresses()
      if (addresses.value.length > 0) selectedAddr.value = addresses.value[0].id
    } else {
      checkoutMsg.value = res.data.message
    }
  } catch (_) {}
  savingAddr.value = false
}

/* 是否为直接购买模式 */
const isDirectBuy = computed(() => !!route.query.skuId)

/* 已勾选的购物车项 */
const checkedItems = computed(() => {
  if (isDirectBuy.value) return directItem.value ? [directItem.value] : []
  return cartItems.value.filter(i => i.checked)
})

/* 总价 */
const totalPrice = computed(() => {
  return checkedItems.value.reduce((sum, i) => sum + i.price * i.quantity, 0).toFixed(2)
})

/* 获取地址 */
const fetchAddresses = async () => {
  try {
    const { data } = await http.get('/address/list')
    if (data.code === 200) {
      addresses.value = data.data || []
      /* 自动选择默认地址 */
      const def = addresses.value.find(a => a.isDefault || a.isDefault === 1)
      if (def) selectedAddr.value = def.id
      else if (addresses.value.length > 0) selectedAddr.value = addresses.value[0].id
    }
  } catch (_) {}
}

/* 直接购买：根据 skuId 获取商品信息 */
const fetchDirectItem = async () => {
  const skuId = route.query.skuId   // 保持字符串，防止 Snowflake ID 精度丢失
  const quantity = Number(route.query.quantity) || 1
  try {
    const { data } = await http.get(`/sku/${skuId}`)
    if (data.code === 200 && data.data) {
      const sku = data.data
      directItem.value = {
        skuId: sku.id,
        productName: sku.product?.name || sku.productName || '商品',
        productImage: sku.imageUrl || sku.product?.mainImage,
        price: sku.price,
        quantity: quantity,
        skuCode: `${sku.color || ''} / ${sku.size || ''}`,
      }
    }
  } catch (_) {}
  loading.value = false
}

/* 获取购物车 */
const fetchCart = async () => {
  try {
    const { data } = await http.get('/cart/list')
    if (data.code === 200) {
      const raw = data.data || []
      const allItems = raw.map(i => ({
        ...i,
        /* 展平嵌套字段 */
        productName: i.product?.name || i.productName,
        productImage: i.product?.mainImage || i.productImage || i.sku?.imageUrl,
        price: i.sku?.price || i.price,
        skuCode: i.sku?.skuCode || i.skuCode,
        merchantId: i.product?.merchantId,
      }))
    cartItems.value = allItems
    /* 如果 URL 传了 cartIds，只勾选这些 */
    if (route.query.cartIds) {
      const idSet = new Set(route.query.cartIds.split(','))
      cartItems.value.forEach(i => { i.checked = idSet.has(String(i.id)) })
    }
    }
  } catch (_) {}
  loading.value = false
}

/* 提交订单 */
const submitOrder = async () => {
  if (!selectedAddr.value) { checkoutMsg.value = '请选择收货地址'; return }
  submitting.value = true
  try {
    let payload = { addressId: selectedAddr.value }

    if (isDirectBuy.value) {
      // 直接购买
      payload.skuId = directItem.value.skuId
      payload.quantity = directItem.value.quantity
    } else {
      // 购物车结算
      const merchantId = checkedItems.value[0]?.merchantId
      if (!merchantId) { checkoutMsg.value = '无法获取商户信息'; submitting.value = false; return }
      const selectedCartIds = checkedItems.value.map(i => i.id)
      payload.merchantId = merchantId
      payload.selectedCartIds = selectedCartIds
    }

    if (remark.value) payload.remark = remark.value

    const { data } = await http.post('/order/create', payload)
    if (data.code === 200) {
      await refreshCartCount()
      const orders = data.data
      if (Array.isArray(orders) && orders.length > 1) {
        // 多商家拆单 → 跳转到订单列表
        router.push('/orders')
      } else {
        // 单个订单 → 跳转到订单详情
        const order = Array.isArray(orders) ? orders[0] : orders
        router.push(`/order/${order.id || order}`)
      }
    } else {
      checkoutMsg.value = data.message
    }
  } catch (e) { checkoutMsg.value = e.message }
  submitting.value = false
}

onMounted(() => {
  fetchAddresses()
  if (isDirectBuy.value) {
    fetchDirectItem()
  } else {
    fetchCart()
  }
})
</script>

<style scoped>
.checkout-page { padding: 120px 0 80px; }
.checkout-grid { display: grid; grid-template-columns: 1fr 380px; gap: 24px; }
.card { background: #fff; border-radius: var(--radius); box-shadow: var(--shadow-card); padding: 24px; margin-bottom: 20px; }
.card h3 { font-size: 16px; margin-bottom: 16px; }

.address-list { display: flex; flex-direction: column; gap: 10px; }
.address-card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.address-card-header h3 { font-size: 16px; margin-bottom: 0; }
.address-card { padding: 14px 16px; border: 2px solid var(--color-border); border-radius: var(--radius); cursor: pointer; transition: border-color var(--transition); display: flex; justify-content: space-between; align-items: center; }
.address-card.active { border-color: var(--color-primary); background: #f8f9ff; }
.address-card.editing { border-color: var(--color-primary); background: #fff; flex-direction: column; align-items: stretch; cursor: default; }
.address-card strong { font-size: 14px; margin-right: 12px; }
.address-card p { font-size: 13px; color: var(--color-text-light); margin-top: 4px; }
.empty-tip { font-size: 13px; color: #999; padding: 10px 0; }

/* 地址内联字段 */
.addr-body { flex: 1; }
.addr-head { display: flex; align-items: center; gap: 8px; margin-bottom: 2px; }
.addr-phone { font-size: 13px; color: #888; }
.addr-full { font-size: 13px; color: #888; margin: 0; }

/* 编辑按钮小图标 */
.act-btn {
  width: 30px; height: 30px; display: flex; align-items: center; justify-content: center;
  border: 1px solid var(--color-border); background: #fff; border-radius: 6px;
  cursor: pointer; color: #999; transition: all 0.2s; flex-shrink: 0; margin-left: 8px;
}
.act-btn:hover { border-color: var(--color-primary); color: var(--color-primary); }

/* 地址表单 */
.addr-form { margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--color-border); }
.addr-form-top { margin-top: 0; margin-bottom: 14px; padding-top: 0; border-top: none; }
.addr-form-inline { margin-top: 12px; padding: 16px 0 0; border-top: 1px solid var(--color-border); width: 100%; }
.addr-form h4 { font-size: 14px; margin-bottom: 12px; }
.form-row { display: flex; gap: 10px; margin-bottom: 10px; }
.form-row.cols-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; }
.form-row.cols-3 select { width: 100%; padding: 10px; border: 1px solid var(--color-border); border-radius: 4px; font-size: 13px; outline: none; }
.form-row.cols-3 select:disabled { opacity: .5; cursor: not-allowed; background: #f5f5f5; }
.form-err { font-size: 12px; color: #c0392b; margin: -6px 0 8px; }
.form-acts { display: flex; gap: 8px; }
.btn-sm { padding: 8px 18px; font-size: 13px; }
.btn-ghost { background: transparent; border: 1px solid var(--color-border); color: var(--color-text); border-radius: var(--radius); cursor: pointer; }
.btn-ghost:hover { border-color: #ccc; }
.btn-outline { background: transparent; border: 1px solid var(--color-accent); color: var(--color-accent); border-radius: var(--radius); cursor: pointer; transition: all .2s; }
.btn-outline:hover { background: var(--color-accent); color: #fff; }

.checkout-item { display: flex; align-items: center; padding: 12px 0; border-bottom: 1px solid var(--color-border); gap: 12px; }
.checkout-item > img { width: 72px !important; height: 72px !important; object-fit: cover; border-radius: 6px; }
.item-info { flex: 1; font-size: 14px; }
.item-info p { margin-bottom: 4px; }
.sku { font-size: 12px; color: #999; }
.checkout-item span { font-size: 13px; color: var(--color-text-light); }
.checkout-item strong { font-family: var(--font-en); font-size: 14px; }

.checkout-summary { align-self: start; position: sticky; top: 100px; }
.summary-row { display: flex; justify-content: space-between; padding: 8px 0; font-size: 14px; color: var(--color-text-light); }
.summary-row.total { border-top: 2px solid var(--color-primary); padding-top: 12px; margin-top: 8px; }
.summary-row.total em { font-family: var(--font-en); font-size: 22px; font-weight: 700; color: #c0392b; font-style: normal; }
.form-row input { width: 100%; padding: 10px; border: 1px solid var(--color-border); border-radius: 4px; font-size: 13px; outline: none; }
.form-row input:focus { border-color: var(--color-accent); }
.pub-msg { font-size: 13px; margin-top: 12px; color: #2a7; }
.empty { text-align: center; padding: 80px 0; color: #999; }

@media (max-width: 768px) { .checkout-grid { grid-template-columns: 1fr; } }
</style>
