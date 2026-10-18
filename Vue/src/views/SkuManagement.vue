<template>
  <div class="skum-page">
    <section class="skum-container">
      <div class="skum-header">
        <router-link to="/merchant" class="skum-back">← 返回商品管理</router-link>
        <h1 class="skum-title">{{ productName || '规格管理' }}</h1>
        <span class="skum-count" v-if="skus.length">{{ skus.length }} 个规格</span>
      </div>

      <!-- 添加 / 编辑 SKU 表单 -->
      <div class="skum-card">
        <h3>{{ editingId ? '编辑规格' : '添加规格' }}</h3>
        <div class="skum-form">
          <div class="form-grid">
            <div class="field"><label>颜色</label><input v-model="form.color" placeholder="如：黑色" /></div>
            <div class="field"><label>尺码</label><input v-model="form.size" placeholder="如：L" /></div>
            <div class="field"><label>SKU编码</label><input v-model="form.skuCode" placeholder="如：T001-BLK-L" /></div>
            <div class="field"><label>价格</label><input v-model.number="form.price" type="number" min="0" placeholder="售价" /></div>
            <div class="field"><label>库存</label><input v-model.number="form.stock" type="number" min="0" placeholder="库存" /></div>
          </div>
          <div class="field" style="margin-top:12px;">
            <label>规格图片</label>
            <div class="upload-row">
              <div class="preview" v-if="imgPreview"><img :src="imgPreview" /></div>
              <label class="upload-btn">
                {{ imgPreview ? '更换' : '上传' }}
                <input type="file" accept="image/*" hidden @change="onUpload" />
              </label>
            </div>
          </div>
          <p v-if="msg" class="msg">{{ msg }}</p>
          <div class="form-acts">
            <button class="btn btn-primary" @click="editingId ? submitUpdate() : submitAdd()" :disabled="submitting">
              {{ submitting ? '提交中...' : editingId ? '保存修改' : '添加规格' }}
            </button>
            <button v-if="editingId" class="btn btn-outline" @click="cancelEdit">取消</button>
          </div>
        </div>
      </div>

      <!-- SKU 列表 -->
      <div class="skum-card" v-if="skus.length > 0">
        <table class="skum-table">
          <thead>
            <tr><th>图片</th><th>颜色</th><th>尺码</th><th>编码</th><th>价格</th><th>库存</th><th>重量(g)</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="s in skus" :key="s.id">
              <td><img :src="s.imageUrl || '/images/products/OIP-C.webp'" class="td-img" /></td>
              <td>{{ s.color }}</td>
              <td>{{ s.size }}</td>
              <td class="td-code">{{ s.skuCode }}</td>
              <td class="td-price">¥{{ s.price }}</td>
              <td :class="s.stock < 10 ? 'td-warn' : ''">{{ s.stock }}</td>
              <td>{{ s.weight || '-' }}</td>
              <td class="td-acts">
                <button class="btn btn-outline btn-sm" @click="startEdit(s)">编辑</button>
                <button class="btn btn-outline btn-sm del" @click="deleteSku(s.id)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="skum-card" v-else>
        <p class="empty">该商品暂无规格，请添加</p>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import http from '../utils/http.js'

const route = useRoute()
const productId = route.params.productId
const skus = ref([])
const productName = ref('')
const msg = ref('')
const submitting = ref(false)
const editingId = ref(null)

const emptyForm = () => ({ color: '', size: '', skuCode: '', price: 0, stock: 0, imageUrl: '' })
const form = reactive(emptyForm())
const imgPreview = ref('')

const fetchSkus = async () => {
  try {
    const { data } = await http.get(`/sku/list/${productId}`)
    if (data.code === 200) skus.value = data.data || []
  } catch (_) {}
}

const fetchProduct = async () => {
  try {
    const { data } = await http.get(`/product/${productId}`)
    if (data.code === 200 && data.data) productName.value = data.data.name
  } catch (_) {}
}

const onUpload = async (e) => {
  const file = e.target.files?.[0]
  if (!file) return
  try {
    const fd = new FormData(); fd.append('file', file)
    const { data } = await http.post('/common/upload', fd)
    if (data.code === 200) { form.imageUrl = data.data; imgPreview.value = URL.createObjectURL(file) }
  } catch (_) {}
}

const submitAdd = async () => {
  if (!form.color || !form.size || !form.skuCode) { msg.value = '请填颜色、尺码、编码'; return }
  if (!form.price || form.price <= 0) { msg.value = '价格>0'; return }
  submitting.value = true; msg.value = ''
  try {
    const { data } = await http.post('/sku/add', { ...form, productId })
    if (data.code === 200) {
      Object.assign(form, emptyForm()); imgPreview.value = ''; msg.value = ''
      fetchSkus()
    } else { msg.value = data.message }
  } catch (e) { msg.value = e.message }
  submitting.value = false
}

const startEdit = (s) => {
  editingId.value = s.id
  Object.assign(form, { color: s.color, size: s.size, skuCode: s.skuCode, price: s.price, stock: s.stock, imageUrl: s.imageUrl })
  imgPreview.value = s.imageUrl || ''
  msg.value = ''
}

const cancelEdit = () => {
  editingId.value = null
  Object.assign(form, emptyForm())
  imgPreview.value = ''
  msg.value = ''
}

const submitUpdate = async () => {
  submitting.value = true; msg.value = ''
  try {
    const { data } = await http.put('/sku/update', { id: editingId.value, ...form })
    if (data.code === 200) { cancelEdit(); fetchSkus() }
    else { msg.value = data.message }
  } catch (e) { msg.value = e.message }
  submitting.value = false
}

const deleteSku = async (id) => {
  if (!confirm('确定删除该规格？')) return
  try {
    const { data } = await http.delete(`/sku/delete/${id}`)
    if (data.code === 200) fetchSkus()
  } catch (_) {}
}

onMounted(() => { fetchProduct(); fetchSkus() })
</script>

<style scoped>
.skum-page { min-height: 100vh; background: #f7f6f3; padding: 60px 0 80px; }
.skum-container { max-width: 960px; margin: 0 auto; padding: 0 24px; }

.skum-header { display: flex; align-items: baseline; gap: 16px; margin-bottom: 24px; }
.skum-back { font-size: 13px; color: #c9a96e; text-decoration: none; }
.skum-back:hover { text-decoration: underline; }
.skum-title { font-size: 24px; font-weight: 700; margin: 0; }
.skum-count { font-size: 13px; color: #999; }

.skum-card { background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,.04); padding: 20px 24px; margin-bottom: 16px; }
.skum-card h3 { font-size: 15px; margin-bottom: 14px; }

.skum-form { max-width: 720px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 12px; }
.field label { display: block; font-size: 12px; color: #888; margin-bottom: 4px; }
.field input { width: 100%; padding: 8px 10px; border: 1px solid #e0ddd5; border-radius: 6px; font-size: 13px; outline: none; }
.field input:focus { border-color: #c9a96e; }

.upload-row { display: flex; align-items: center; gap: 12px; }
.preview { width: 64px; height: 80px; border-radius: 4px; overflow: hidden; border: 1px solid #e0ddd5; background: #fafaf8; }
.preview img { width: 100%; height: 100%; object-fit: cover; }
.upload-btn { padding: 8px 14px; font-size: 12px; border: 1px dashed #c9a96e; border-radius: 6px; color: #c9a96e; cursor: pointer; }
.upload-btn:hover { background: #fefcf7; }

.msg { font-size: 13px; margin-top: 10px; color: #2a7; }
.form-acts { margin-top: 14px; display: flex; gap: 8px; }

.skum-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.skum-table th { text-align: left; padding: 10px 8px; border-bottom: 2px solid #eee; font-weight: 600; color: #666; font-size: 11px; text-transform: uppercase; }
.skum-table td { padding: 10px 8px; border-bottom: 1px solid #f0f0f0; vertical-align: middle; }
.td-img { width: 44px; height: 56px; object-fit: cover; border-radius: 4px; }
.td-code { font-family: monospace; font-size: 12px; color: #888; }
.td-price { font-weight: 600; color: #c0392b; }
.td-warn { color: #e67e22; font-weight: 600; }
.td-acts { white-space: nowrap; }
.td-acts .btn { margin-right: 4px; }
.td-acts .btn:last-child { margin-right: 0; }
.del { color: #c0392b !important; border-color: #c0392b !important; }

.btn { padding: 8px 16px; border: none; border-radius: 6px; font-size: 13px; cursor: pointer; transition: all .2s; }
.btn-primary { background: #1a1a2e; color: #fff; }
.btn-primary:hover { background: #2a2a42; }
.btn-outline { background: #fff; border: 1px solid #d0d0d0; color: #555; }
.btn-outline:hover { border-color: #aaa; }
.btn-sm { padding: 4px 10px; font-size: 11px; }
.btn:disabled { opacity: .5; cursor: not-allowed; }
.empty { text-align: center; padding: 40px 0; color: #999; font-size: 14px; }

@media (max-width: 640px) { .form-grid { grid-template-columns: 1fr 1fr; } }
</style>
