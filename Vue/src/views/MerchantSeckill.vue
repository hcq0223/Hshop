<template>
  <div class="ms-page">
    <div class="container ms-main">
      <!-- 头部 -->
      <div class="ms-header">
        <div>
          <p class="section-label">Merchant Flash Sale</p>
          <h1 class="section-title">秒杀活动管理</h1>
          <p class="ms-sub">创建活动 → 添加商品并设置秒杀额度 → 发布，系统会在开抢前自动预热库存</p>
        </div>
        <div class="ms-header-actions">
          <router-link to="/merchant" class="btn btn-outline">返回商家后台</router-link>
          <button class="btn btn-primary" @click="openCreate">新建活动</button>
        </div>
      </div>

      <!-- 状态筛选 -->
      <div class="ms-filter">
        <button
          v-for="f in filters"
          :key="f.value"
          :class="{ active: statusFilter === f.value }"
          @click="statusFilter = f.value; fetchActivities()"
        >{{ f.label }}</button>
      </div>

      <!-- 活动列表 -->
      <div v-if="loading" class="ms-empty">加载中…</div>
      <div v-else-if="activities.length === 0" class="ms-empty">
        还没有秒杀活动，点右上角「新建活动」开始
      </div>

      <div v-else class="ms-list">
        <div v-for="act in activities" :key="act.id" class="ms-card">
          <div class="ms-card-main">
            <div class="ms-card-info">
              <div class="ms-card-title-row">
                <h3>{{ act.title }}</h3>
                <span class="ms-status" :class="act.status">{{ statusText(act.status) }}</span>
              </div>
              <p class="ms-card-time">{{ fmt(act.startTime) }} — {{ fmt(act.endTime) }}</p>
              <p class="ms-card-meta">
                总库存 {{ act.totalStock || 0 }} · 已售 {{ act.soldCount || 0 }}
                <template v-if="act.perUserLimit > 0"> · 每人限购 {{ act.perUserLimit }} 件</template>
              </p>
            </div>
            <div class="ms-card-actions">
              <button class="b b-outline b-xs" @click="openDetail(act)">商品与额度</button>
              <button v-if="canEdit(act)" class="b b-outline b-xs" @click="openEdit(act)">编辑</button>
              <button v-if="canEdit(act)" class="b b-outline b-xs" @click="publish(act)">发布</button>
              <button v-if="act.status === 'pending' || act.status === 'running'" class="b b-outline b-xs" @click="offline(act)">下线</button>
              <button v-if="canEdit(act)" class="b b-outline b-xs del" @click="remove(act)">删除</button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ============ 新建/编辑活动弹层 ============ -->
    <div v-if="formVisible" class="ms-overlay" @click.self="formVisible = false">
      <div class="ms-dialog">
        <h3 class="ms-dialog-title">{{ form.id ? '编辑活动' : '新建秒杀活动' }}</h3>
        <div class="ms-field">
          <label>活动名称</label>
          <input v-model="form.title" placeholder="如：双11 零点场" maxlength="128" />
        </div>
        <div class="ms-field-row">
          <div class="ms-field">
            <label>开抢时间</label>
            <input type="datetime-local" v-model="form.startTime" />
          </div>
          <div class="ms-field">
            <label>结束时间</label>
            <input type="datetime-local" v-model="form.endTime" />
          </div>
        </div>
        <div class="ms-field">
          <label>每人限购件数</label>
          <input type="number" min="0" v-model.number="form.perUserLimit" />
          <span class="ms-hint">0 表示不限购；秒杀通常设为 1</span>
        </div>
        <div class="ms-field">
          <label>活动说明（可选）</label>
          <textarea v-model="form.description" rows="2" maxlength="512"></textarea>
        </div>
        <p v-if="formError" class="ms-error">{{ formError }}</p>
        <div class="ms-dialog-actions">
          <button class="btn btn-outline" @click="formVisible = false">取消</button>
          <button class="btn btn-primary" :disabled="saving" @click="saveForm">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- ============ 商品与额度弹层 ============ -->
    <div v-if="detailVisible" class="ms-overlay" @click.self="detailVisible = false">
      <div class="ms-dialog ms-dialog--wide">
        <div class="ms-dialog-head">
          <h3 class="ms-dialog-title">{{ current.title }} · 商品与额度</h3>
          <button class="ms-close" @click="detailVisible = false">×</button>
        </div>

        <p v-if="!canEdit(current)" class="ms-notice">
          活动已发布，商品与额度不可修改。如需调整请先「下线」。
        </p>

        <!-- 已添加的商品 -->
        <table class="ms-table" v-if="stocks.length > 0">
          <thead>
            <tr>
              <th>SKU ID</th>
              <th>秒杀价</th>
              <th>原价</th>
              <th>总库存</th>
              <th>可抢</th>
              <th>已售</th>
              <th>预热</th>
              <th v-if="canEdit(current)">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in stocks" :key="s.id">
              <td class="ms-mono">{{ s.skuId }}</td>
              <td class="ms-price">¥{{ s.seckillPrice }}</td>
              <td class="ms-old">¥{{ s.originPrice }}</td>
              <td>{{ s.totalStock }}</td>
              <td>{{ s.availableStock }}</td>
              <td>{{ s.soldStock }}</td>
              <td>
                <span :class="s.preheated === 1 ? 'ms-yes' : 'ms-no'">
                  {{ s.preheated === 1 ? '已预热' : '未预热' }}
                </span>
              </td>
              <td v-if="canEdit(current)">
                <button class="b b-outline b-xs del" @click="removeStock(s)">移除</button>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-else class="ms-empty-inline">还没有添加商品</p>

        <!-- 添加商品 -->
        <template v-if="canEdit(current)">
          <div class="ms-add-head">
            <h4>添加商品</h4>
            <button class="b b-outline b-xs" @click="loadProducts">刷新商品列表</button>
          </div>
          <div class="ms-picker">
            <select v-model="pickProductId" @change="onPickProduct" class="ms-picker-select">
              <option :value="null" disabled>选择商品</option>
              <option v-for="p in products" :key="p.id" :value="p.id">
                {{ p.name }}（¥{{ p.priceMin }}~{{ p.priceMax }}）
              </option>
            </select>
            <select v-model="pickSkuId" class="ms-picker-select" :disabled="skus.length === 0">
              <option :value="null" disabled>
                {{ skus.length === 0 ? '请先选择商品' : '选择规格' }}
              </option>
              <option v-for="s in skus" :key="s.id" :value="s.id">
                {{ skuLabel(s) }} — 日常价 ¥{{ s.price }}
              </option>
            </select>
          </div>
          <div class="ms-picker-2">
            <div class="ms-field">
              <label>秒杀价</label>
              <input type="number" step="0.01" min="0.01" v-model.number="newStock.seckillPrice" />
              <span class="ms-hint">必须低于日常价</span>
            </div>
            <div class="ms-field">
              <label>秒杀库存</label>
              <input type="number" min="1" v-model.number="newStock.totalStock" />
            </div>
            <div class="ms-field">
              <label>每人限购</label>
              <input type="number" min="0" v-model.number="newStock.perUserLimit" />
            </div>
            <div class="ms-field">
              <label>排序</label>
              <input type="number" v-model.number="newStock.sort" />
            </div>
          </div>
          <p v-if="stockError" class="ms-error">{{ stockError }}</p>
          <button class="btn btn-primary" :disabled="savingStock" @click="saveStock">
            {{ savingStock ? '添加中…' : '添加 / 更新该规格' }}
          </button>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import http from '../utils/http.js'

const activities = ref([])
const loading = ref(true)
const statusFilter = ref('')
const merchantId = ref(null)

const filters = [
  { label: '全部', value: '' },
  { label: '草稿', value: 'draft' },
  { label: '待开始', value: 'pending' },
  { label: '进行中', value: 'running' },
  { label: '已结束', value: 'ended' },
  { label: '已下线', value: 'offline' }
]

/* ---------- 表单 ---------- */
const formVisible = ref(false)
const saving = ref(false)
const formError = ref('')
const form = reactive({
  id: null, title: '', startTime: '', endTime: '', perUserLimit: 1, description: ''
})

/* ---------- 商品与额度 ---------- */
const detailVisible = ref(false)
const current = ref({})
const stocks = ref([])
const products = ref([])
const skus = ref([])
const pickProductId = ref(null)
const pickSkuId = ref(null)
const newStock = reactive({ seckillPrice: null, totalStock: 100, perUserLimit: 1, sort: 0 })
const stockError = ref('')
const savingStock = ref(false)

const statusText = (s) => ({
  draft: '草稿', pending: '待开始', running: '进行中',
  ended: '已结束', offline: '已下线'
}[s] || s)

/** 只有草稿/下线态可编辑（与后端 SeckillStatus.editable 保持一致） */
const canEdit = (act) => act && (act.status === 'draft' || act.status === 'offline')

const fmt = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '')

const skuLabel = (s) => {
  const parts = [s.color, s.size].filter(Boolean)
  return parts.length ? parts.join(' / ') : (s.skuCode || `SKU ${s.id}`)
}

/* ---------- 数据加载 ---------- */

const fetchActivities = async () => {
  loading.value = true
  try {
    const params = {}
    if (statusFilter.value) params.status = statusFilter.value
    const { data } = await http.get('/seckill/merchant/activities', { params })
    if (data.code === 200) activities.value = data.data || []
  } catch (e) {
    activities.value = []
  } finally {
    loading.value = false
  }
}

const fetchMerchantId = async () => {
  try {
    const { data } = await http.get('/merchant/me/id')
    if (data.code === 200) merchantId.value = data.data.merchantId
  } catch (_) {}
}

const loadProducts = async () => {
  if (!merchantId.value) await fetchMerchantId()
  if (!merchantId.value) return
  try {
    const { data } = await http.get(`/product/merchant/${merchantId.value}`)
    if (data.code === 200) {
      products.value = Array.isArray(data.data) ? data.data : (data.data.records || [])
    }
  } catch (_) {}
}

const onPickProduct = async () => {
  pickSkuId.value = null
  skus.value = []
  if (!pickProductId.value) return
  try {
    const { data } = await http.get(`/sku/list/${pickProductId.value}`)
    if (data.code === 200) skus.value = data.data || []
  } catch (_) {}
}

const openDetail = async (act) => {
  current.value = act
  detailVisible.value = true
  stockError.value = ''
  pickProductId.value = null
  pickSkuId.value = null
  skus.value = []
  newStock.seckillPrice = null
  newStock.totalStock = 100
  newStock.perUserLimit = act.perUserLimit || 1
  newStock.sort = 0
  await fetchStocks(act.id)
  if (canEdit(act)) await loadProducts()
}

const fetchStocks = async (activityId) => {
  try {
    const { data } = await http.get(`/seckill/merchant/activities/${activityId}/stocks`)
    if (data.code === 200) stocks.value = data.data || []
  } catch (_) {
    stocks.value = []
  }
}

/* ---------- 活动增删改 ---------- */

const openCreate = () => {
  form.id = null
  form.title = ''
  form.startTime = ''
  form.endTime = ''
  form.perUserLimit = 1
  form.description = ''
  formError.value = ''
  formVisible.value = true
}

const openEdit = (act) => {
  form.id = act.id
  form.title = act.title
  // datetime-local 需要 "yyyy-MM-ddTHH:mm"，后端返回带秒，截掉
  form.startTime = toLocalInput(act.startTime)
  form.endTime = toLocalInput(act.endTime)
  form.perUserLimit = act.perUserLimit || 0
  form.description = act.description || ''
  formError.value = ''
  formVisible.value = true
}

/** "yyyy-MM-dd HH:mm:ss" → "yyyy-MM-ddTHH:mm"（datetime-local 的格式要求） */
const toLocalInput = (s) => {
  if (!s) return ''
  return String(s).replace(' ', 'T').slice(0, 16)
}

/** "yyyy-MM-ddTHH:mm" → "yyyy-MM-dd HH:mm:ss"（后端期望的格式） */
const toApiTime = (s) => {
  if (!s) return null
  const v = String(s).replace('T', ' ')
  return v.length === 16 ? `${v}:00` : v
}

const saveForm = async () => {
  formError.value = ''
  if (!form.title.trim()) { formError.value = '请填写活动名称'; return }
  if (!form.startTime || !form.endTime) { formError.value = '请填写起止时间'; return }
  if (form.endTime <= form.startTime) { formError.value = '结束时间必须晚于开始时间'; return }

  saving.value = true
  try {
    const payload = {
      title: form.title.trim(),
      startTime: toApiTime(form.startTime),
      endTime: toApiTime(form.endTime),
      perUserLimit: form.perUserLimit ?? 1,
      description: form.description || null
    }
    const res = form.id
      ? await http.put(`/seckill/merchant/activities/${form.id}`, payload)
      : await http.post('/seckill/merchant/activities', payload)
    if (res.data.code === 200) {
      formVisible.value = false
      fetchActivities()
    } else {
      formError.value = res.data.message || '保存失败'
    }
  } catch (e) {
    formError.value = e.response?.data?.message || e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

const publish = async (act) => {
  if (!confirm(`发布活动「${act.title}」？发布后商品与额度将不可修改。`)) return
  await simpleCall(() => http.put(`/seckill/merchant/activities/${act.id}/publish`))
}

const offline = async (act) => {
  if (!confirm(`下线活动「${act.title}」？将立即停止抢购并清除已预热的库存。`)) return
  await simpleCall(() => http.put(`/seckill/merchant/activities/${act.id}/offline`))
}

const remove = async (act) => {
  if (!confirm(`删除活动「${act.title}」？其商品额度会一并删除。`)) return
  await simpleCall(() => http.delete(`/seckill/merchant/activities/${act.id}`))
}

const removeStock = async (s) => {
  if (!confirm('从活动中移除该规格？')) return
  await simpleCall(() => http.delete(`/seckill/merchant/stocks/${s.id}`))
  await fetchStocks(current.value.id)
}

const saveStock = async () => {
  stockError.value = ''
  if (!pickSkuId.value) { stockError.value = '请选择商品与规格'; return }
  if (!newStock.seckillPrice || newStock.seckillPrice <= 0) { stockError.value = '请填写秒杀价'; return }
  if (!newStock.totalStock || newStock.totalStock <= 0) { stockError.value = '秒杀库存必须大于 0'; return }

  savingStock.value = true
  try {
    const { data } = await http.post(
      `/seckill/merchant/activities/${current.value.id}/stocks`,
      {
        skuId: pickSkuId.value,
        seckillPrice: newStock.seckillPrice,
        totalStock: newStock.totalStock,
        perUserLimit: newStock.perUserLimit,
        sort: newStock.sort
      }
    )
    if (data.code === 200) {
      pickSkuId.value = null
      newStock.seckillPrice = null
      await fetchStocks(current.value.id)
    } else {
      stockError.value = data.message || '添加失败'
    }
  } catch (e) {
    // 后端的校验信息（秒杀价不能高于日常价 / 商品不属于当前商家）在这里回显
    stockError.value = e.response?.data?.message || e.message || '添加失败'
  } finally {
    savingStock.value = false
  }
}

/** 统一处理简单操作（发布/下线/删除）：成功刷新列表，失败弹出后端原因 */
const simpleCall = async (fn) => {
  try {
    const { data } = await fn()
    if (data.code === 200) {
      fetchActivities()
      if (detailVisible.value) detailVisible.value = false
    } else {
      alert(data.message || '操作失败')
    }
  } catch (e) {
    alert(e.response?.data?.message || e.message || '操作失败')
  }
}

onMounted(() => {
  fetchMerchantId()
  fetchActivities()
})
</script>

<style scoped>
.ms-page { background: var(--color-bg); min-height: 100vh; padding: 110px 0 80px; }
.ms-main { max-width: 1280px; }

.ms-header {
  display: flex; align-items: flex-start; justify-content: space-between;
  gap: 20px; margin-bottom: 24px; flex-wrap: wrap;
}
.ms-sub { font-size: 13px; color: var(--color-text-light); margin-top: 4px; }
.ms-header-actions { display: flex; gap: 10px; }

.ms-filter { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 20px; }
.ms-filter button {
  padding: 7px 18px; border: 1px solid var(--color-border); background: #fff;
  border-radius: 20px; font-size: 13px; cursor: pointer; color: var(--color-text);
  transition: all .2s;
}
.ms-filter button:hover { border-color: var(--color-accent); color: var(--color-accent); }
.ms-filter button.active { background: var(--color-primary); color: #fff; border-color: var(--color-primary); }

.ms-empty { text-align: center; padding: 80px 0; color: var(--color-text-light); }
.ms-empty-inline { font-size: 13px; color: var(--color-text-light); padding: 12px 0; }

.ms-list { display: flex; flex-direction: column; gap: 14px; }
.ms-card {
  background: #fff; border: 1px solid var(--color-border);
  border-radius: var(--radius-lg); box-shadow: var(--shadow-card);
  padding: 18px 22px;
}
.ms-card-main { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.ms-card-title-row { display: flex; align-items: center; gap: 10px; }
.ms-card-title-row h3 { font-size: 16px; font-weight: 600; color: var(--color-primary); }
.ms-status { font-size: 11px; padding: 2px 10px; border-radius: 10px; background: #f0f0f0; color: #888; }
.ms-status.draft { background: #ececec; color: #777; }
.ms-status.pending { background: var(--color-accent); color: #fff; }
.ms-status.running { background: #c0392b; color: #fff; }
.ms-status.ended { background: #d8d5cd; color: #fff; }
.ms-status.offline { background: #999; color: #fff; }
.ms-card-time { font-size: 12px; color: var(--color-text-light); margin-top: 3px; }
.ms-card-meta { font-size: 12px; color: var(--color-text-light); }
.ms-card-actions { display: flex; gap: 8px; flex-wrap: wrap; }

.b { padding: 6px 14px; border-radius: 4px; font-size: 12px; cursor: pointer; transition: all .2s; background: #fff; }
.b-outline { border: 1px solid var(--color-border); color: var(--color-text); }
.b-outline:hover { border-color: var(--color-accent); color: var(--color-accent); }
.b-xs { padding: 5px 12px; font-size: 12px; }
.b.del:hover { border-color: #c0392b; color: #c0392b; }

/* 弹层 */
.ms-overlay {
  position: fixed; inset: 0; background: rgba(0, 0, 0, .45);
  display: flex; align-items: center; justify-content: center;
  z-index: 2000; padding: 24px;
}
.ms-dialog {
  background: #fff; border-radius: var(--radius-lg); padding: 28px 32px;
  width: 100%; max-width: 520px; max-height: 88vh; overflow-y: auto;
  box-shadow: 0 20px 60px rgba(0, 0, 0, .2);
}
.ms-dialog--wide { max-width: 900px; }
.ms-dialog-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 4px; }
.ms-dialog-title { font-size: 18px; font-weight: 600; color: var(--color-primary); margin-bottom: 16px; }
.ms-close { border: none; background: none; font-size: 24px; line-height: 1; cursor: pointer; color: #999; }

.ms-field { display: flex; flex-direction: column; gap: 5px; margin-bottom: 14px; flex: 1; }
.ms-field label { font-size: 12px; color: var(--color-text-light); }
.ms-field input, .ms-field textarea, .ms-picker-select {
  padding: 9px 12px; border: 1px solid var(--color-border); border-radius: 6px;
  font-size: 13px; outline: none; background: #fafaf8; color: var(--color-text);
  font-family: inherit;
}
.ms-field input:focus, .ms-field textarea:focus, .ms-picker-select:focus { border-color: var(--color-accent); }
.ms-field-row { display: flex; gap: 12px; }
.ms-hint { font-size: 11px; color: #aaa; }

.ms-notice {
  font-size: 12px; color: #8a6d3b; background: #fcf8e3;
  border: 1px solid #faebcc; border-radius: 6px; padding: 8px 12px; margin-bottom: 14px;
}
.ms-error { font-size: 12px; color: #c0392b; margin: 8px 0; }

.ms-dialog-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 8px; }

/* 表格 */
.ms-table { width: 100%; border-collapse: collapse; font-size: 13px; margin-bottom: 20px; }
.ms-table th {
  text-align: left; font-size: 12px; font-weight: 500; color: var(--color-text-light);
  padding: 8px 10px; border-bottom: 1px solid var(--color-border);
}
.ms-table td { padding: 9px 10px; border-bottom: 1px solid #f4f2ee; }
.ms-mono { font-family: var(--font-en); font-size: 12px; color: #666; }
.ms-price { color: #c0392b; font-weight: 600; }
.ms-old { color: #bbb; text-decoration: line-through; font-size: 12px; }
.ms-yes { color: #2e7d5b; font-size: 12px; }
.ms-no { color: #c9a96e; font-size: 12px; }

.ms-add-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.ms-add-head h4 { font-size: 14px; font-weight: 600; color: var(--color-primary); }
.ms-picker { display: flex; gap: 12px; margin-bottom: 14px; }
.ms-picker-select { flex: 1; }
.ms-picker-2 { display: flex; gap: 12px; flex-wrap: wrap; }
.ms-picker-2 .ms-field { min-width: 130px; }

@media (max-width: 768px) {
  .ms-header { flex-direction: column; }
  .ms-picker, .ms-field-row { flex-direction: column; }
}
</style>
