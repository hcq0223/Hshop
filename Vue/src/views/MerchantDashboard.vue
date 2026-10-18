<template>
  <div class="dash-page">
    <div class="dash-body">
      <!-- 统计卡片 -->
      <div class="stats-grid">
        <div class="stat-card">
          <span class="stat-num">{{ stats.products }}</span>
          <span class="stat-label">商品数</span>
        </div>
        <div class="stat-card">
          <span class="stat-num">{{ stats.orders }}</span>
          <span class="stat-label">总订单</span>
        </div>
        <div class="stat-card">
          <span class="stat-num">{{ stats.pendingOrders }}</span>
          <span class="stat-label">待处理</span>
        </div>
        <div class="stat-card">
          <span class="stat-num">¥{{ finance.totalRevenue }}</span>
          <span class="stat-label">总营收</span>
        </div>
      </div>

      <!-- 财务速览 -->
      <div class="finance-brief" v-if="tab !== 'finance'">
        <div class="fb-item">
          <span class="fb-val">¥{{ finance.availableBalance }}</span>
          <span class="fb-label">可提现余额</span>
        </div>
        <div class="fb-item">
          <span class="fb-val frozen">¥{{ finance.frozenBalance }}</span>
          <span class="fb-label">冻结资金</span>
        </div>
      </div>

      <!-- 选项卡 -->
      <div class="tabs-bar">
        <button :class="{ active: tab === 'products' }" @click="tab = 'products'">商品管理</button>
        <button :class="{ active: tab === 'orders' }" @click="tab = 'orders'">订单管理</button>
        <button :class="{ active: tab === 'add-product' }" @click="tab = 'add-product'">发布商品</button>
        <button :class="{ active: tab === 'finance' }" @click="tab = 'finance'; fetchFinance()">财务中心</button>
        <button :class="{ active: tab === 'complaints' }" @click="tab = 'complaints'; fetchMyComplaints()">我的申诉</button>
        <router-link to="/merchant/order-history" class="btn-history-link">订单历史 →</router-link>
      </div>

      <!-- ========== 商品管理 ========== -->
      <div v-if="tab === 'products'">
        <div class="card" v-if="products.length > 0">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width:60px">图片</th>
                <th>名称</th>
                <th>分类</th>
                <th>价格区间</th>
                <th>销量</th>
                <th>状态</th>
                <th style="width:180px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in products" :key="p.id" @dblclick="goSku(p)" class="prod-row" :title="'双击管理规格'">
                <td><img :src="p.mainImage || '/images/products/OIP-C.webp'" class="td-img" /></td>
                <td class="td-name">{{ p.name }}</td>
                <td class="td-meta">{{ getCatName(p.categoryId) }}</td>
                <td class="td-price">
                  <template v-if="p.priceMin != null && p.priceMax != null && (p.priceMin > 0 || p.priceMax > 0)">
                    ¥{{ p.priceMin }} ~ ¥{{ p.priceMax }}
                  </template>
                  <span v-else class="na">-</span>
                </td>
                <td>{{ p.salesCount || 0 }}</td>
                <td><span :class="p.status === 'on_sale' ? 's-on' : 's-off'">{{ p.status === 'on_sale' ? '上架' : '下架' }}</span></td>
                <td class="td-acts">
                  <button class="b b-outline b-xs" @click.stop="startEditProduct(p)">编辑</button>
                  <button class="b b-outline b-xs" @click.stop="toggleStatus(p)">{{ p.status === 'on_sale' ? '下架' : '上架' }}</button>
                  <button class="b b-outline b-xs" @click.stop="goSku(p)">规格</button>
                  <button class="b b-outline b-xs del" @click.stop="deleteProduct(p.id)">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="card empty-card" v-else><p>暂无商品，去发布一个吧</p></div>
      </div>

      <!-- ========== 订单管理 ========== -->
      <div v-if="tab === 'orders'">
        <div class="ostatus-bar">
          <button :class="{ active: orderStatus === '' }" @click="orderStatus = ''; fetchOrders()">全部</button>
          <button :class="{ active: orderStatus === 'paid' }" @click="orderStatus = 'paid'; fetchOrders()">待发货</button>
          <button :class="{ active: orderStatus === 'shipped' }" @click="orderStatus = 'shipped'; fetchOrders()">待收货</button>
          <button :class="{ active: orderStatus === 'refunding' }" @click="orderStatus = 'refunding'; fetchOrders()">退款中</button>
        </div>
        <div class="card" v-if="orders.length > 0">
          <table class="data-table">
            <thead>
              <tr><th>订单号</th><th>商品</th><th>金额</th><th>状态</th><th>时间</th><th style="width:80px">操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="o in orders" :key="o.id">
                <td class="td-ono">{{ o.orderNo }}</td>
                <td>{{ (o.items || o.orderItems || []).map(i => i.productName).join('、') || '-' }}</td>
                <td class="td-price">¥{{ o.totalAmount }}</td>
                <td>{{ statusMap[o.status] || o.status }}</td>
                <td class="td-date">{{ o.createTime }}</td>
                <td><button v-if="o.status === 'paid'" class="b b-primary b-xs"
                            :disabled="isShipping(o.id)" @click="shipOrder(o)">
                      {{ isShipping(o.id) ? '发货中…' : '发货' }}
                    </button>
                    <button v-if="o.status === 'refunding'" class="b b-outline b-xs"
                            :disabled="isRefundActing(o.id)" @click="approveRefund(o)">
                      {{ isRefundActing(o.id) ? '处理中…' : '同意退款' }}
                    </button>
                    <button v-if="o.status === 'refunding'" class="b b-outline b-xs"
                            :disabled="isRefundActing(o.id)" @click="rejectRefund(o)">
                      {{ isRefundActing(o.id) ? '处理中…' : '拒绝退款' }}
                    </button>
                    <button class="b b-outline b-xs" @click="$router.push(`/order/${o.id}`)">查看详情</button></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="card empty-card" v-else><p>暂无订单</p></div>
      </div>

      <!-- ========== 订单历史 ========== -->
      <!-- 已移至独立页面：/merchant/order-history -->

      <!-- ========== 发布商品 ========== -->
      <div v-if="tab === 'add-product'">
        <div class="card pub-panel" v-if="!createdProductId">
          <div class="pub-step">步骤一 · 商品信息</div>
          <div class="pub-grid">
            <div class="pub-cell"><label>商品名称</label><input v-model="newProduct.name" placeholder="商品名称" /></div>
            <div class="pub-cell"><label>分类</label>
              <select v-model="selectedTopCat" @change="newProduct.categoryId = ''">
                <option value="">顶级分类</option>
                <option v-for="c in topCats" :key="c.id" :value="c.id">{{ c.name }}</option>
              </select>
              <select v-model="newProduct.categoryId" v-if="selectedTopCat">
                <option value="">子分类</option>
                <option v-for="c in filteredSubCats" :key="c.id" :value="c.id">{{ c.name }}</option>
              </select>
            </div>
            <div class="pub-cell"><label>主图</label>
              <div class="up-row">
                <div class="up-preview" v-if="mainImagePreview"><img :src="mainImagePreview" /></div>
                <label class="up-btn">{{ mainImagePreview ? '更换' : '上传' }}<input type="file" accept="image/*" hidden @change="onMainImageUpload" /></label>
              </div>
            </div>
            <div class="pub-cell pub-cell-full"><label>描述</label><textarea v-model="newProduct.description" rows="2" placeholder="商品描述"></textarea></div>
          </div>
          <p v-if="pubMsg" class="pub-msg">{{ pubMsg }}</p>
          <button class="b b-primary" @click="createProduct" :disabled="creating">{{ creating ? '创建中...' : '创建商品' }}</button>
        </div>

        <div class="card pub-panel" v-else>
          <div class="pub-step">步骤二 · 添加规格 — {{ newProduct.name }}</div>
          <div class="sku-add-row">
            <input v-model="skuForm.color" placeholder="颜色" class="sku-inp" />
            <input v-model="skuForm.size" placeholder="尺码" class="sku-inp" />
            <input v-model="skuForm.skuCode" placeholder="SKU编码" class="sku-inp" />
            <input v-model.number="skuForm.price" type="number" placeholder="价格" class="sku-inp sku-inp-sm" min="0" />
            <input v-model.number="skuForm.stock" type="number" placeholder="库存" class="sku-inp sku-inp-sm" min="0" />
            <div class="up-row">
              <div class="up-preview up-preview-sm" v-if="skuImgPreview"><img :src="skuImgPreview" /></div>
              <label class="up-btn up-btn-sm">{{ skuImgPreview ? '换图' : '图' }}<input type="file" accept="image/*" hidden @change="onSkuImgUpload" /></label>
            </div>
            <button class="b b-primary b-xs" @click="addSku" :disabled="addingSku">{{ addingSku ? '...' : '添加' }}</button>
          </div>
          <p v-if="skuMsg" class="pub-msg">{{ skuMsg }}</p>
          <div v-if="skuList.length > 0" class="sku-list-bar">
            <span v-for="(s, idx) in skuList" :key="idx" class="sku-chip">
              {{ s.color }}/{{ s.size }} ¥{{ s.price }} ×{{ s.stock }}
              <button class="sku-chip-x" @click="removeSku(idx)">×</button>
            </span>
          </div>
          <button class="b b-outline" @click="finishProduct" style="margin-top:16px">完成发布</button>
        </div>
      </div>

      <!-- ========== 财务中心 ========== -->
      <div v-if="tab === 'finance'">
        <div class="card finance-panel">
          <h3 class="fp-title">资金概览</h3>
          <div class="fp-grid">
            <div class="fp-card">
              <span class="fp-num">¥{{ finance.totalRevenue }}</span>
              <span class="fp-label">总营收</span>
            </div>
            <div class="fp-card">
              <span class="fp-num av">¥{{ finance.availableBalance }}</span>
              <span class="fp-label">可提现余额</span>
            </div>
            <div class="fp-card">
              <span class="fp-num fz">¥{{ finance.frozenBalance }}</span>
              <span class="fp-label">冻结资金</span>
            </div>
          </div>
        </div>

        <!-- 提现按钮 -->
        <div class="card" style="margin-top:20px; padding: 24px;">
          <h3 style="margin-bottom:16px; font-size: 16px;">申请提现</h3>
          <div style="display:flex; gap:12px; align-items:center; flex-wrap:wrap;">
            <input v-model="withdrawAmount" type="number" placeholder="提现金额" class="sku-inp" style="width:160px;" min="0" />
            <input v-model="withdrawRemark" placeholder="备注（选填）" class="sku-inp" style="width:200px;" />
            <button class="b b-primary" @click="doWithdraw" :disabled="withdrawing">{{ withdrawing ? '提交中...' : '申请提现' }}</button>
          </div>
          <p v-if="withdrawMsg" class="pub-msg" :style="{ color: withdrawMsgColor }">{{ withdrawMsg }}</p>
        </div>

        <!-- 提现记录 -->
        <div class="card" v-if="withdraws.length > 0" style="margin-top:20px;">
          <h3 style="padding:24px 24px 0; font-size: 16px;">提现记录</h3>
          <table class="data-table">
            <thead><tr><th>提现金额</th><th>状态</th><th>备注</th><th>申请时间</th></tr></thead>
            <tbody>
              <tr v-for="w in withdraws" :key="w.id">
                <td class="td-price">¥{{ w.amount }}</td>
                <td>{{ withdrawStatusMap[w.status] || w.status }}</td>
                <td>{{ w.remark || '-' }}</td>
                <td class="td-date">{{ w.createdAt }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 历史周营收 -->
        <div class="card" style="margin-top:20px;">
          <h3 style="padding:24px 24px 0; font-size: 16px;">历史周营收</h3>
          <table class="data-table" v-if="weeklyRevenue.length > 0">
            <thead><tr><th>周起始</th><th>周结束</th><th>营收金额</th><th>订单数</th></tr></thead>
            <tbody>
              <tr v-for="r in weeklyRevenue" :key="r.id">
                <td>{{ r.weekStart }}</td>
                <td>{{ r.weekEnd }}</td>
                <td class="td-price">¥{{ r.totalRevenue }}</td>
                <td>{{ r.totalOrders }}</td>
              </tr>
            </tbody>
          </table>
          <p v-else style="padding: 32px 24px; text-align: center; color: #999;">暂无历史营收记录</p>
        </div>
      </div>

      <!-- ========== 我的申诉 ========== -->
      <div v-if="tab === 'complaints'">
        <table class="data-table" v-if="myComplaints.length > 0">
          <thead><tr><th>评论ID</th><th>申诉理由</th><th>状态</th><th>管理员备注</th><th>时间</th></tr></thead>
          <tbody>
            <tr v-for="c in myComplaints" :key="c.id">
              <td>{{ c.reviewId }}</td>
              <td class="td-content">{{ c.reason }}</td>
              <td>{{ complaintLabel(c.status) }}</td>
              <td>{{ c.adminNote || '-' }}</td>
              <td class="td-date">{{ c.createTime }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="empty">暂无申诉记录</p>
      </div>
    </div>

    <!-- ========== 编辑商品弹窗 ========== -->
    <div class="modal-overlay" v-if="editing" @click.self="cancelEditProduct">
      <div class="modal-card">
        <h3>编辑商品</h3>
        <div class="pub-grid" style="margin-top:16px">
          <div class="pub-cell"><label>商品名称</label><input v-model="editForm.name" /></div>
          <div class="pub-cell"><label>分类</label>
            <select v-model="editSelectedTopCat" @change="editForm.categoryId = ''">
              <option value="">顶级分类</option>
              <option v-for="c in topCats" :key="c.id" :value="c.id">{{ c.name }}</option>
            </select>
            <select v-model="editForm.categoryId" v-if="editSelectedTopCat">
              <option value="">子分类</option>
              <option v-for="c in editFilteredSubCats" :key="c.id" :value="c.id">{{ c.name }}</option>
            </select>
          </div>
          <div class="pub-cell"><label>主图</label>
            <div class="up-row">
              <div class="up-preview" v-if="editImgPreview"><img :src="editImgPreview" /></div>
              <label class="up-btn">{{ editImgPreview ? '更换' : '上传' }}<input type="file" accept="image/*" hidden @change="onEditImageUpload" /></label>
            </div>
          </div>
          <div class="pub-cell pub-cell-full"><label>描述</label><textarea v-model="editForm.description" rows="2"></textarea></div>
        </div>
        <p v-if="editMsg" class="pub-msg">{{ editMsg }}</p>
        <div class="form-acts">
          <button class="b b-primary" @click="submitEditProduct" :disabled="editSaving">{{ editSaving ? '保存中...' : '保存' }}</button>
          <button class="b b-outline" @click="cancelEditProduct">取消</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import http from '../utils/http.js'

const router = useRouter()
const tab = ref('products')
const products = ref([])
const orders = ref([])
const categories = ref([])
const orderStatus = ref('')
const pubMsg = ref('')
const selectedTopCat = ref('')
const merchantId = ref(null)

const newProduct = reactive({ name: '', categoryId: '', description: '', mainImage: '' })
const mainImagePreview = ref('')
const creating = ref(false)
const createdProductId = ref(null)

const skuForm = reactive({ color: '', size: '', skuCode: '', price: 0, stock: 0, imageUrl: '' })
const skuImgPreview = ref('')
const skuMsg = ref('')
const addingSku = ref(false)
const skuList = ref([])

/* ========== 编辑商品 ========== */
const editing = ref(false)
const editSaving = ref(false)
const editMsg = ref('')
const editSelectedTopCat = ref('')
const editImgPreview = ref('')
const editingProductId = ref(null)
const editForm = reactive({ name: '', categoryId: '', description: '', mainImage: '' })

const topCats = computed(() => categories.value.filter(c => c.parentId === 0))
const filteredSubCats = computed(() => categories.value.filter(c => c.parentId === selectedTopCat.value))
const editFilteredSubCats = computed(() => categories.value.filter(c => c.parentId === editSelectedTopCat.value))

const stats = reactive({ products: 0, orders: 0, pendingOrders: 0, totalAmount: '0' })
const finance = reactive({ totalRevenue: '0.00', availableBalance: '0.00', frozenBalance: '0.00' })
const statusMap = { pending_pay: '待付款', paid: '待发货', shipped: '待收货', received: '已收货', completed: '已完成', cancelled: '已取消', refunding: '退款中' }

const fetchMerchantId = async () => {
  try {
    const { data } = await http.get('/merchant/me/id')
    if (data.code === 200) merchantId.value = data.data.merchantId
  } catch (_) {}
}

const fetchProducts = async () => {
  try {
    if (!merchantId.value) return
    const { data } = await http.get(`/product/merchant/${merchantId.value}`)
    if (data.code === 200) {
      products.value = Array.isArray(data.data) ? data.data : (data.data.records || [])
      stats.products = products.value.length
    }
  } catch (_) {}
}

/** 拉取订单列表（不改动界面），失败返回 null */
const loadOrders = async () => {
  try {
    const params = {}
    if (orderStatus.value) params.status = orderStatus.value
    const { data } = await http.get('/order/merchant/orders', { params })
    if (data.code === 200) return (data.data.records || data.data || [])
  } catch (_) {}
  return null
}

/** 应用订单列表并同步顶部统计 */
const applyOrders = (list) => {
  orders.value = list
  stats.orders = list.length
  stats.pendingOrders = list.filter(o => o.status === 'paid').length
}

const fetchOrders = async () => {
  const list = await loadOrders()
  if (list) applyOrders(list)
}

const fetchCategories = async () => {
  try {
    const { data } = await http.get('/category/all')
    if (data.code === 200) categories.value = data.data || []
  } catch (_) {}
}

const getCatName = (id) => {
  const c = categories.value.find(cc => cc.id === id)
  return c ? c.name : '-'
}

const goSku = (p) => { router.push(`/merchant/sku/${p.id}`) }

const toggleStatus = async (product) => {
  const newStatus = product.status === 'on_sale' ? 'off_sale' : 'on_sale'
  // 上架前检查是否有规格
  if (newStatus === 'on_sale') {
    try {
      const { data } = await http.get(`/sku/list/${product.id}`)
      if (data.code === 200 && (!data.data || data.data.length === 0)) {
        alert('该商品没有规格（SKU），请先添加规格再上架')
        return
      }
    } catch (_) { return }
  }
  try {
    const { data } = await http.put(`/product/${product.id}/status`, null, { params: { status: newStatus } })
    if (data.code === 200) product.status = newStatus
  } catch (_) {}
}

const deleteProduct = async (id) => {
  if (!confirm('确定删除？')) return
  try {
    const { data } = await http.delete(`/product/${id}`)
    if (data.code === 200) fetchProducts()
  } catch (_) {}
}

/**
 * 正在发货的订单ID集合。
 *
 * 用途：① 连点保护 —— 发货是「弹两次框 + 一次写库」的多步操作，
 *         期间用户完全可能再点一次「发货」，导致重复提交与困惑；
 *       ② 按钮显示 loading 文案。
 * 用 Set 而不是单个布尔：一个页面上有多笔待发货订单，
 * 按订单粒度加锁才不会互相阻塞。
 */
const shippingIds = ref(new Set())

const isShipping = (id) => shippingIds.value.has(id)

const shipOrder = async (o) => {
  // 连点保护：该订单已在发货中，直接忽略后续点击
  if (isShipping(o.id)) return

  const tn = prompt('请输入物流单号（选填）:', '')
  // 取消第一个弹窗视为放弃发货 —— 原实现不判断，取消后会继续弹第二个框，
  // 用户体验很怪（明明不想发了却还被追问物流公司）
  if (tn === null) return
  const tc = prompt('请输入物流公司（选填）:', '')
  if (tc === null) return

  shippingIds.value.add(o.id)
  try {
    const payload = {}
    if (tn) payload.trackingNumber = tn
    if (tc) payload.trackingCompany = tc
    const { data } = await http.put(`/order/ship/${o.id}`, payload)
    if (data.code === 200) {
      // ============ 即时反馈：乐观更新本地状态 ============
      //
      // 【为什么不能只调 fetchOrders()】
      // 商家订单列表读的是 ES 索引，而 ES 是**异步同步**的：
      //   写 DB → 发 MQ → 消费 → 回源 Feign → 写 ES
      // 这条链路要走几十到几百毫秒。发货成功后立刻 fetchOrders()，
      // 几乎必然早于 ES 完成同步，于是拿回的还是旧状态 'paid'，
      // 用户看到的「发货」按钮纹丝不动 —— 必须手动刷新页面才变。
      //
      // 解决：先把本地这条订单的状态改掉（用户立刻看到「待收货」、
      // 按钮消失），再延迟拉一次真实数据做最终一致。
      // 这样既有即时反馈，又不会因为 ES 永久不同步而显示错状态。
      const idx = orders.value.findIndex(x => x.id === o.id)
      if (idx >= 0) {
        if (orderStatus.value === 'paid') {
          // 当前正筛选「待发货」：发货后它不再属于这个筛选，
          // 直接从列表移除最符合直觉（否则会出现「待收货」的订单
          // 挂在「待发货」标签下，看着很怪）。
          orders.value.splice(idx, 1)
        } else {
          orders.value[idx].status = 'shipped'
        }
      }
      // 顶部统计里的「待处理」也就是 paid 数量，同步减一
      stats.pendingOrders = Math.max(0, (stats.pendingOrders || 1) - 1)

      // 等 ES 收敛后再以服务端数据为准（含物流单号等字段）。
      // 【不能直接 fetchOrders()】：若此刻索引还是旧状态，会把刚改成「待收货」
      // 的订单刷回「待发货」、「发货」按钮重新出现，并且把上面乐观减掉的
      // 待处理数量重新算回去 —— 等于白改。
      await refetchOrderUntilSettled(o.id, 'paid')
    } else {
      // 后端明确返回业务失败（如状态已不是 paid、无权操作）。
      // 原实现这里什么都不做，用户点完毫无反馈 —— 只能反复点试探。
      alert(data.message || '发货失败，请稍后重试')
      // 刷新一次：若失败原因是「状态已变」，刷新能让界面回到真实状态，
      // 而不是让用户对着一个已经过期的按钮反复点。
      await fetchOrders()
    }
  } catch (e) {
    // 网络异常 / 401 等。
    // 【不要】再回退调 PUT /order/status/{id}：那个接口只改状态，
    // 不走 shipOrder 的校验与物流字段写入，会造成「状态变了但物流信息丢了」。
    alert(e.response?.data?.message || e.message || '网络异常，请稍后重试')
  } finally {
    // finally 保证任何分支都能解锁，否则一次失败后按钮永久卡在 loading
    shippingIds.value.delete(o.id)
  }
}

/* ========== 退款审核 ==========
 *
 * 与「发货」完全同一个坑：商家订单列表读的是 ES 投影，写接口返回时索引
 * 还没同步（实测 100~300ms），此时立刻 fetchOrders() 拿到的是旧的 'refunding'，
 * 「同意退款 / 拒绝退款」两个按钮纹丝不动，用户以为没点上就反复点。
 *
 * 与发货不同的是，退款审核的结果状态**无法在前端本地推断**：
 *   同意 → 全额退款为 cancelled、部分退款为 completed（都是终态，商家列表不展示）
 *   拒绝 → 回退到 preRefundStatus（可能是 paid / shipped / received）
 * 所以这里不用猜：先读一次 /order/detail/{id}，该接口**读库**且订单缓存在
 * 事务提交后立即失效，没有 ES 那种延迟，能立刻拿到权威状态；
 * 再以索引数据做最终一致。
 */
const refundActingIds = ref(new Set())

const isRefundActing = (id) => refundActingIds.value.has(id)

/** 把退款审核结果落到本地列表 */
const applyRefundResult = (orderId, status) => {
  const idx = orders.value.findIndex(x => x.id === orderId)
  if (idx < 0) return
  // completed / cancelled 是终态，商家列表默认不展示
  const isFinal = status === 'completed' || status === 'cancelled'
  // 当前处于某个状态筛选时，状态变化后不再满足筛选条件也要移除
  const matchesFilter = !orderStatus.value || orderStatus.value === status
  if (isFinal || !matchesFilter) {
    orders.value.splice(idx, 1)
    return
  }
  orders.value[idx].status = status
}

/**
 * 等 ES 投影追上这次写操作（订单已离场或不再是 staleStatus 即算追上）。
 * 只有确实追上才覆盖界面 —— 否则刚被乐观更新移除/改状态的卡片会闪回来。
 * 多轮仍未追上则兜底刷新一次（不会一直错下去）。
 */
const refetchOrderUntilSettled = async (orderId, staleStatus, tries = 6, delay = 300) => {
  for (let i = 0; i < tries; i += 1) {
    await new Promise(resolve => setTimeout(resolve, delay))
    const list = await loadOrders()
    if (!list) continue
    const found = list.find(x => x.id === orderId)
    if (!found || found.status !== staleStatus) {
      applyOrders(list)
      return
    }
  }
  await fetchOrders()
}

/** 退款审核统一入口：防连点 → 读库拿权威状态 → 等 ES 收敛 */
const runRefundAction = async (o, request, failText) => {
  if (refundActingIds.value.has(o.id)) return
  refundActingIds.value.add(o.id)
  try {
    const { data } = await request()
    if (data.code !== 200) {
      // 后端明确拒绝（多半是状态已不是 refunding，例如别人已处理过）。
      // 原实现什么都不做，用户点完毫无反馈 —— 这里提示并刷新，让界面回到真实状态。
      alert(data.message || failText)
      await fetchOrders()
      return
    }
    // 权威状态：详情接口读库，不受 ES 同步延迟影响
    try {
      const { data: detail } = await http.get(`/order/detail/${o.id}`)
      if (detail.code === 200 && detail.data?.status) {
        applyRefundResult(o.id, detail.data.status)
      }
    } catch (_) {
      // 详情取不到就退化为等 ES 收敛，不影响正确性
    }
    await refetchOrderUntilSettled(o.id, 'refunding')
  } catch (e) {
    // 不再回退调已废弃的状态直改接口
    alert(e.response?.data?.message || e.message || failText)
    await fetchOrders()
  } finally {
    refundActingIds.value.delete(o.id)
  }
}

const approveRefund = async (o) => {
  if (!confirm('确定同意退款吗？')) return
  await runRefundAction(o, () => http.post(`/order/refund/approve/${o.id}`), '同意退款失败，请稍后重试')
}

const rejectRefund = async (o) => {
  if (!confirm('确定拒绝退款吗？')) return
  await runRefundAction(o, () => http.post(`/order/refund/reject/${o.id}`), '拒绝退款失败，请稍后重试')
}

/* ========== 财务中心逻辑 ========== */
const withdrawAmount = ref('')
const withdrawRemark = ref('')
const withdrawing = ref(false)
const withdrawMsg = ref('')
const withdrawMsgColor = ref('#2a7')
const withdraws = ref([])
const weeklyRevenue = ref([])
const withdrawStatusMap = { pending: '待处理', approved: '已通过', completed: '已完成', rejected: '已拒绝' }

// 申诉记录
const myComplaints = ref([])

const fetchMyComplaints = async () => {
  try {
    const { data } = await http.get('/review/complaint/my')
    if (data.code === 200) myComplaints.value = data.data || []
  } catch (_) {}
}

const complaintLabel = s => ({ pending: '待处理', rejected: '已驳回', approved: '已通过' }[s] || s)

const fetchFinance = async () => {
  try {
    const { data } = await http.get('/merchant/me/finance')
    if (data.code === 200) {
      Object.assign(finance, data.data)
    }
    const wRes = await http.get('/merchant/withdraws')
    if (wRes.data.code === 200) withdraws.value = wRes.data.data || []
    const wrRes = await http.get('/merchant/weekly-revenue')
    if (wrRes.data.code === 200) weeklyRevenue.value = wrRes.data.data || []
  } catch (_) {}
}

const doWithdraw = async () => {
  const amt = parseFloat(withdrawAmount.value)
  if (!amt || amt <= 0) { withdrawMsg.value = '请输入有效金额'; withdrawMsgColor.value = '#c0392b'; return }
  if (amt > parseFloat(finance.availableBalance)) { withdrawMsg.value = '可提现余额不足'; withdrawMsgColor.value = '#c0392b'; return }
  withdrawing.value = true; withdrawMsg.value = ''
  try {
    const { data } = await http.post('/merchant/withdraw', { amount: amt, remark: withdrawRemark.value })
    if (data.code === 200) {
      withdrawMsg.value = '提现申请已提交'; withdrawMsgColor.value = '#2a7'
      withdrawAmount.value = ''; withdrawRemark.value = ''
      fetchFinance()
    } else { withdrawMsg.value = data.message; withdrawMsgColor.value = '#c0392b' }
  } catch (_) { withdrawMsg.value = '提现失败'; withdrawMsgColor.value = '#c0392b' }
  withdrawing.value = false
}

/* ========== 编辑商品逻辑 ========== */
const startEditProduct = (p) => {
  editingProductId.value = p.id
  editForm.name = p.name
  editForm.categoryId = p.categoryId
  editForm.description = p.description || ''
  editForm.mainImage = p.mainImage || ''
  editImgPreview.value = p.mainImage || ''

  // 反查顶级分类
  const cat = categories.value.find(c => c.id === p.categoryId)
  if (cat && cat.parentId) {
    editSelectedTopCat.value = cat.parentId
  } else if (cat) {
    editSelectedTopCat.value = cat.id
  } else {
    editSelectedTopCat.value = ''
  }

  editMsg.value = ''
  editing.value = true
}

const cancelEditProduct = () => {
  editing.value = false
  editingProductId.value = null
  Object.assign(editForm, { name: '', categoryId: '', description: '', mainImage: '' })
  editImgPreview.value = ''
  editSelectedTopCat.value = ''
  editMsg.value = ''
}

const onEditImageUpload = async (e) => {
  const file = e.target.files?.[0]
  if (!file) return
  try {
    const fd = new FormData(); fd.append('file', file)
    const { data } = await http.post('/common/upload', fd)
    if (data.code === 200) {
      editForm.mainImage = data.data
      editImgPreview.value = URL.createObjectURL(file)
    }
  } catch (_) {}
}

const submitEditProduct = async () => {
  if (!editForm.name) { editMsg.value = '请输入商品名称'; return }
  if (!editForm.categoryId) { editMsg.value = '请选择分类'; return }
  editSaving.value = true; editMsg.value = ''
  try {
    const { data } = await http.put(`/product/${editingProductId.value}`, { id: editingProductId.value, ...editForm })
    if (data.code === 200) {
      cancelEditProduct()
      fetchProducts()
    } else { editMsg.value = data.message }
  } catch (e) { editMsg.value = e.message }
  editSaving.value = false
}

/* ========== 发布商品逻辑 ========== */
const onMainImageUpload = async (e) => {
  const file = e.target.files?.[0]
  if (!file) return
  pubMsg.value = '上传中...'
  try {
    const fd = new FormData(); fd.append('file', file)
    const { data } = await http.post('/common/upload', fd)
    if (data.code === 200) {
      newProduct.mainImage = data.data
      mainImagePreview.value = URL.createObjectURL(file)
      pubMsg.value = ''
    } else { pubMsg.value = data.message || '上传失败' }
  } catch (_) { pubMsg.value = '上传失败' }
}

const createProduct = async () => {
  if (!newProduct.name) { pubMsg.value = '请输入商品名称'; return }
  if (!newProduct.categoryId) { pubMsg.value = '请选择分类'; return }
  if (!newProduct.mainImage) { pubMsg.value = '请上传商品主图'; return }
  creating.value = true; pubMsg.value = ''
  try {
    const { data } = await http.post('/product', newProduct)
    if (data.code === 200) {
      createdProductId.value = data.data.id || data.data
    } else { pubMsg.value = data.message }
  } catch (e) { pubMsg.value = e.message }
  creating.value = false
}

const onSkuImgUpload = async (e) => {
  const file = e.target.files?.[0]
  if (!file) return
  skuMsg.value = '上传中...'
  try {
    const fd = new FormData(); fd.append('file', file)
    const { data } = await http.post('/common/upload', fd)
    if (data.code === 200) {
      skuForm.imageUrl = data.data
      skuImgPreview.value = URL.createObjectURL(file)
      skuMsg.value = ''
    } else { skuMsg.value = data.message || '上传失败' }
  } catch (_) { skuMsg.value = '上传失败' }
}

const addSku = async () => {
  if (!skuForm.color || !skuForm.size || !skuForm.skuCode) { skuMsg.value = '请填写颜色、尺码和SKU编码'; return }
  if (!skuForm.price || skuForm.price <= 0) { skuMsg.value = '价格必须大于0'; return }
  if (!skuForm.stock && skuForm.stock !== 0) { skuMsg.value = '请填写库存'; return }
  addingSku.value = true; skuMsg.value = ''
  try {
    const { data } = await http.post('/sku/add', { ...skuForm, productId: createdProductId.value })
    if (data.code === 200) {
      skuList.value.push({ ...skuForm })
      Object.assign(skuForm, { color: '', size: '', skuCode: '', price: 0, stock: 0, imageUrl: '' })
      skuImgPreview.value = ''
    } else { skuMsg.value = data.message }
  } catch (e) { skuMsg.value = e.message }
  addingSku.value = false
}

const removeSku = (idx) => { skuList.value.splice(idx, 1) }

const finishProduct = () => {
  Object.assign(newProduct, { name: '', categoryId: '', description: '', mainImage: '' })
  skuList.value = []
  Object.assign(skuForm, { color: '', size: '', skuCode: '', price: 0, stock: 0, imageUrl: '' })
  mainImagePreview.value = ''; skuImgPreview.value = ''
  createdProductId.value = null; selectedTopCat.value = ''; pubMsg.value = ''; skuMsg.value = ''
  fetchProducts()
  tab.value = 'products'
}

const resetPublishState = () => {
  if (createdProductId.value) {
    Object.assign(newProduct, { name: '', categoryId: '', description: '', mainImage: '' })
    skuList.value = []
    Object.assign(skuForm, { color: '', size: '', skuCode: '', price: 0, stock: 0, imageUrl: '' })
    mainImagePreview.value = ''; skuImgPreview.value = ''
    createdProductId.value = null; selectedTopCat.value = ''; pubMsg.value = ''; skuMsg.value = ''
  }
}

watch(tab, (val) => { if (val !== 'add-product') resetPublishState() })

const init = async () => { await fetchMerchantId(); fetchProducts(); fetchOrders(); fetchCategories(); fetchFinance() }
onMounted(() => { init() })
</script>

<style scoped>
/* ========== page shell ========== */
.dash-page { min-height: 100vh; background: #f4f3ef; padding: 100px 0 80px; }
.dash-body { max-width: 1200px; margin: 0 auto; padding: 0 24px; }

/* ========== stats ========== */
.stats-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 28px; }
.stat-card { background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,.04); padding: 24px; text-align: center; }
.stat-num { display: block; font-size: 28px; font-weight: 700; color: #1a1a2e; font-family: 'Georgia', serif; }
.stat-label { font-size: 13px; color: #999; margin-top: 4px; display: block; }

/* ========== tabs ========== */
.tabs-bar { display: flex; gap: 8px; margin-bottom: 20px; align-items: center; }
.tabs-bar button { padding: 8px 20px; border: 1px solid #ddd; background: #fff; border-radius: 6px; font-size: 13px; cursor: pointer; color: #555; transition: all .2s; }
.tabs-bar button.active { background: #1a1a2e; color: #fff; border-color: #1a1a2e; }
.tabs-bar .btn-history-link { margin-left: auto; padding: 8px 20px; border: 1px dashed #1a1a2e; border-radius: 6px; font-size: 13px; cursor: pointer; color: #1a1a2e; text-decoration: none; transition: all .2s; }
.tabs-bar .btn-history-link:hover { background: rgba(26,26,46,0.05); }

/* ========== cards & tables ========== */
.card { background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,.04); overflow: auto; }
.empty-card { padding: 48px; text-align: center; color: #999; font-size: 14px; }

.data-table { width: 100%; border-collapse: collapse; font-size: 13px; white-space: nowrap; min-width: 600px; }
.data-table thead th { padding: 10px 12px; text-align: left; background: #fafaf8; border-bottom: 2px solid #eee; font-weight: 600; color: #777; font-size: 11px; text-transform: uppercase; letter-spacing: .3px; }
.data-table tbody td { padding: 10px 12px; border-bottom: 1px solid #f0f0f0; vertical-align: middle; }
.data-table tbody tr:last-child td { border-bottom: none; }
.prod-row { cursor: default; transition: background .15s; }
.prod-row:hover { background: #fafaf5; }
.td-img { width: 44px; height: 56px; object-fit: cover; border-radius: 4px; display: block; }
.td-name { font-weight: 500; max-width: 200px; overflow: hidden; text-overflow: ellipsis; }
.td-meta { color: #888; font-size: 12px; }
.td-price { font-family: 'Georgia', serif; font-weight: 600; color: #c0392b; }
.td-ono { font-family: monospace; font-size: 12px; color: #555; }
.td-date { font-size: 11px; color: #aaa; }
.td-acts { white-space: nowrap; }
.td-acts .b { margin-right: 4px; }
.td-acts .b:last-child { margin-right: 0; }
.na { color: #ccc; }
.s-on { color: #27ae60; background: #eafaf1; padding: 1px 8px; border-radius: 10px; font-size: 11px; white-space: nowrap; }
.s-off { color: #999; background: #f3f3f3; padding: 1px 8px; border-radius: 10px; font-size: 11px; white-space: nowrap; }

/* buttons */
.b { display: inline-block; border: none; border-radius: 6px; cursor: pointer; font-size: 13px; transition: all .2s; padding: 7px 16px; }
.b-primary { background: #1a1a2e; color: #fff; }
.b-primary:hover { background: #2a2a42; }
.b-outline { background: #fff; border: 1px solid #d0d0d0; color: #555; }
.b-outline:hover { border-color: #aaa; }
.b-xs { padding: 4px 10px; font-size: 11px; }
.b:disabled { opacity: .5; cursor: not-allowed; }
.del { color: #c0392b !important; border-color: #c0392b !important; }

/* order status bar */
.ostatus-bar { display: flex; gap: 6px; margin-bottom: 12px; }
.ostatus-bar button { padding: 5px 14px; border: 1px solid #ddd; background: #fff; border-radius: 16px; font-size: 12px; cursor: pointer; color: #666; }
.ostatus-bar button.active { background: #1a1a2e; color: #fff; border-color: #1a1a2e; }

/* ========== publish ========== */
.pub-panel { padding: 24px; max-width: 800px; }
.pub-step { font-size: 14px; font-weight: 600; margin-bottom: 16px; color: #333; }
.pub-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.pub-cell { display: flex; flex-direction: column; gap: 4px; }
.pub-cell label { font-size: 12px; color: #888; }
.pub-cell input, .pub-cell select, .pub-cell textarea { padding: 8px 10px; border: 1px solid #e0ddd5; border-radius: 6px; font-size: 13px; outline: none; resize: vertical; font-family: inherit; }
.pub-cell input:focus, .pub-cell select:focus, .pub-cell textarea:focus { border-color: #c9a96e; }
.pub-cell-full { grid-column: 1 / -1; }
.pub-msg { font-size: 13px; margin-top: 12px; color: #2a7; }

/* upload */
.up-row { display: flex; align-items: center; gap: 8px; }
.up-preview { width: 64px; height: 80px; border-radius: 6px; overflow: hidden; border: 1px solid #e0ddd5; background: #fafaf8; flex-shrink: 0; }
.up-preview img { width: 100%; height: 100%; object-fit: cover; }
.up-preview-sm { width: 36px; height: 48px; }
.up-btn { padding: 8px 14px; font-size: 12px; border: 1px dashed #c9a96e; border-radius: 6px; color: #c9a96e; cursor: pointer; display: inline-block; }
.up-btn:hover { background: #fefcf7; }
.up-btn-sm { padding: 4px 10px; font-size: 11px; }

/* sku */
.sku-add-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.sku-inp { width: 100px; padding: 6px 8px; border: 1px solid #e0ddd5; border-radius: 6px; font-size: 12px; outline: none; }
.sku-inp:focus { border-color: #c9a96e; }
.sku-inp-sm { width: 72px; }
.sku-list-bar { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 14px; }
.sku-chip { display: inline-flex; align-items: center; gap: 4px; padding: 4px 10px; background: #f0ede4; border-radius: 12px; font-size: 12px; color: #555; }
.sku-chip-x { border: none; background: none; cursor: pointer; font-size: 14px; color: #c0392b; padding: 0 2px; }

/* ========== modal ========== */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.35); display: flex; align-items: center; justify-content: center; z-index: 2000; }
.modal-card { background: #fff; border-radius: 12px; padding: 28px; width: 520px; max-width: 90vw; max-height: 90vh; overflow-y: auto; box-shadow: 0 12px 40px rgba(0,0,0,.15); }
.modal-card h3 { font-size: 16px; margin-bottom: 4px; }
.form-acts { display: flex; gap: 8px; margin-top: 16px; }

/* ========== finance ========== */
.finance-brief { display: flex; gap: 16px; margin-bottom: 20px; }
.fb-item { background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,.04); padding: 16px 24px; text-align: center; flex: 1; }
.fb-val { display: block; font-size: 22px; font-weight: 700; color: #27ae60; font-family: 'Georgia', serif; }
.fb-val.frozen { color: #e67e22; }
.fb-label { font-size: 12px; color: #999; margin-top: 2px; display: block; }

.finance-panel { padding: 24px; }
.fp-title { font-size: 16px; margin-bottom: 20px; }
.fp-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
.fp-card { text-align: center; padding: 20px; background: #fafaf8; border-radius: 8px; }
.fp-num { display: block; font-size: 28px; font-weight: 700; color: #1a1a2e; font-family: 'Georgia', serif; }
.fp-num.av { color: #27ae60; }
.fp-num.fz { color: #e67e22; }
.fp-label { font-size: 13px; color: #999; margin-top: 4px; display: block; }

@media (max-width: 768px) { .stats-grid { grid-template-columns: repeat(2, 1fr); } .pub-grid { grid-template-columns: 1fr; } .fp-grid { grid-template-columns: 1fr; } }

.empty { text-align: center; padding: 60px 0; color: #999; font-size: 13px; }
.td-content { max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.td-date { white-space: nowrap; font-size: 12px; color: #999; }
</style>
