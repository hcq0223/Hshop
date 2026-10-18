<template>
  <div class="page profile-page">
    <section class="container">
      <p class="section-badge">PERSONAL CENTER</p>
      <h1 class="section-title">个人中心</h1>

      <template v-if="user">
        <div class="profile-grid">
        <!-- 左侧信息卡 -->
        <aside class="profile-sidebar">
          <div class="sidebar-card">
            <div class="avatar-section">
              <label class="avatar-wrap" title="点击更换头像">
                <img v-if="customerInfo.avatarUrl" :src="customerInfo.avatarUrl" class="avatar-pic" />
                <span v-else class="avatar-fallback">{{ (user.username || 'U')[0].toUpperCase() }}</span>
                <span class="avatar-badge">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/></svg>
                </span>
                <input type="file" accept="image/*" hidden @change="onAvatarChange" />
              </label>
              <h2 class="profile-name">{{ customerInfo.nickname || user.username }}</h2>
              <span class="profile-role">{{ user.role === 'admin' ? '管理员' : user.role === 'merchant' ? '商家' : '顾客' }}</span>
            </div>

            <ul class="info-items">
              <li><span class="info-key">账号</span><span class="info-val">{{ user.username }}</span></li>
              <li><span class="info-key">手机号</span><span class="info-val">{{ user.phone || '未绑定' }}</span></li>
              <li><span class="info-key">邮箱</span><span class="info-val">{{ user.email || '未绑定' }}</span></li>
              <li><span class="info-key">性别</span><span class="info-val">{{ genderLabel(customerInfo.gender) }}</span></li>
            </ul>

            <button class="btn btn-outline btn-block" @click="showEdit = true">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
              编辑个人信息
            </button>
          </div>
        </aside>

        <!--
          右侧地址区：仅顾客可见。
          原条件是 `currentRole !== 'admin'`，把商家也放进来了 —— 但商家在
          customers 表里没有记录，/api/address/list 与 /api/customer/me 都会
          拿不到数据（映射 customerId 得到 null），页面就是一片空白。
          现在改成严格等于 customer；路由层也已加了 roles: ['customer'] 兜底。
        -->
        <main class="profile-main" v-if="isCustomer">
          <div class="main-card">
            <div class="address-header">
              <div>
                <h3>收货地址</h3>
                <p class="address-sub">管理你的收货地址，最多可添加 20 个</p>
              </div>
              <button class="btn btn-primary btn-sm" @click="openAddrForm()">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                添加地址
              </button>
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
              <label class="form-check">
                <input type="checkbox" v-model="addrForm.isDefault" :true-value="1" :false-value="0" />
                <span>设为默认地址</span>
              </label>
              <div class="form-acts">
                <button class="btn btn-primary btn-sm" @click="saveAddress">添加</button>
                <button class="btn btn-ghost btn-sm" @click="closeForm">取消</button>
              </div>
            </div>

            <div class="address-list" v-if="addresses.length > 0">
              <div class="address-card" v-for="addr in addresses" :key="addr.id"
                   :class="{ default: addr.isDefault === 1, editing: showAddrForm && editingAddrId === addr.id }"
                   @dblclick="editAddr(addr)">
                <template v-if="!(showAddrForm && editingAddrId === addr.id)">
                  <div class="addr-body">
                    <div class="addr-head">
                      <strong>{{ addr.receiverName }}</strong>
                      <span class="addr-phone">{{ addr.receiverPhone }}</span>
                      <span class="default-tag" v-if="addr.isDefault === 1">默认</span>
                    </div>
                    <p class="addr-full">{{ addr.province }}{{ addr.city }}{{ addr.district }} {{ addr.detailAddress }}</p>
                  </div>
                  <div class="addr-acts">
                    <button class="act-btn" @click="editAddr(addr)" title="编辑">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
                    </button>
                    <button v-if="!addr.isDefault" class="act-btn" @click="setDefault(addr)" title="设为默认">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>
                    </button>
                    <button class="act-btn del" @click="deleteAddr(addr.id)" title="删除">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
                    </button>
                  </div>
                </template>

                <!-- 编辑表单 — 内联在该卡片下方 -->
                <div v-else class="addr-form addr-form-inline">
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
                  <label class="form-check">
                    <input type="checkbox" v-model="addrForm.isDefault" :true-value="1" :false-value="0" />
                    <span>设为默认地址</span>
                  </label>
                  <div class="form-acts">
                    <button class="btn btn-primary btn-sm" @click="saveAddress" :disabled="phoneErr !== ''">保存</button>
                    <button class="btn btn-ghost btn-sm" @click="closeForm">取消</button>
                  </div>
                </div>
              </div>
            </div>
            <div v-else-if="!showAddrForm" class="address-empty">
              <div class="empty-icon">
                <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1.2"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg>
              </div>
              <p>暂无收货地址</p>
              <span>点击上方按钮添加你的第一个地址</span>
            </div>
          </div>
        </main>

        <!-- 管理员：账号概览卡片（替代收货地址区） -->
        <main class="profile-main" v-else>
          <div class="main-card">
            <div class="address-header">
              <div>
                <h3>账号权限</h3>
                <p class="address-sub">管理员账号，拥有后台管理权限</p>
              </div>
            </div>
            <div class="admin-info-grid">
              <div class="admin-info-item">
                <span class="admin-info-icon">
                  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z"/></svg>
                </span>
                <span class="admin-info-label">角色</span>
                <span class="admin-info-value">系统管理员</span>
              </div>
              <div class="admin-info-item">
                <span class="admin-info-icon">
                  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="2" y="7" width="20" height="14" rx="2"/><path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"/></svg>
                </span>
                <span class="admin-info-label">商家审核</span>
                <span class="admin-info-value">审核入驻与提现申请</span>
              </div>
              <div class="admin-info-item">
                <span class="admin-info-icon">
                  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>
                </span>
                <span class="admin-info-label">订单管理</span>
                <span class="admin-info-value">全平台订单查看与干预</span>
              </div>
              <div class="admin-info-item">
                <span class="admin-info-icon">
                  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
                </span>
                <span class="admin-info-label">用户管理</span>
                <span class="admin-info-value">用户账号管理与权限分配</span>
              </div>
            </div>
            <div class="admin-notice">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
              <span>如需购买商品，请使用顾客账号登录</span>
            </div>
          </div>
        </main>
      </div>

      <!-- 编辑个人信息弹层 -->
      <Teleport to="body">
        <div class="modal-overlay" v-if="showEdit" @click.self="showEdit = false">
          <div class="modal-box">
            <h3>编辑个人信息</h3>
            <div class="form-row"><label>昵称</label><input v-model="editForm.nickname" placeholder="设置你的昵称" /></div>
            <div class="form-row"><label>性别</label>
              <select v-model="editForm.gender">
                <option value="">请选择</option>
                <option value="male">男</option>
                <option value="female">女</option>
                <option value="other">其他</option>
              </select>
            </div>
            <div class="form-acts">
              <button class="btn btn-primary btn-sm" @click="saveProfile">保存</button>
              <button class="btn btn-ghost btn-sm" @click="showEdit = false">取消</button>
            </div>
          </div>
        </div>
      </Teleport>
      </template>
      <div v-else-if="loading" class="page-empty">
        <p>加载中...</p>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import http from '../utils/http.js'
import regionData from '../utils/regionData.js'
import { currentUser, currentRole, isCustomer, setAvatarUrl } from '../stores/auth.js'

const user = currentUser
const loading = ref(true)
const showEdit = ref(false)
const uploading = ref(false)
const addresses = ref([])
const showAddrForm = ref(false)
const editingAddrId = ref(null)      // 当前正在编辑的地址 ID（null = 新增模式）
const phoneErr = ref('')             // 手机号校验错误
const customerInfo = reactive({ nickname: '', gender: '', avatarUrl: '' })
const editForm = reactive({ nickname: '', gender: '' })
const addrForm = reactive({ receiverName: '', receiverPhone: '', province: '', city: '', district: '', detailAddress: '', isDefault: 0 })

// 省市区三级联动
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

// 根据已有 province/city/district 文本反选下拉框
const syncRegionSelectors = (provinceText, cityText, districtText) => {
  selProvince.value = ''
  selCity.value = ''
  selDistrict.value = ''
  if (provinceText) {
    const p = regionData.find(r => r.label === provinceText)
    if (p) {
      selProvince.value = p.value
      if (cityText) {
        const c = p.children.find(r => r.label === cityText)
        if (c) {
          selCity.value = c.value
          if (districtText) {
            const d = c.children.find(r => r.label === districtText)
            if (d) selDistrict.value = d.value
          }
        }
      }
    }
  }
}

/* 获取客户扩展信息 */
const fetchCustomerInfo = async () => {
  try {
    const { data } = await http.get('/customer/me')
    if (data.code === 200) {
      const c = data.data || {}
      customerInfo.nickname = c.nickname || ''
      customerInfo.gender = c.gender || ''
      customerInfo.avatarUrl = c.avatarUrl || ''
      if (c.avatarUrl) setAvatarUrl(c.avatarUrl)
    }
  } catch (_) {}
}

/* 获取地址列表 */
const fetchAddresses = async () => {
  try {
    const { data } = await http.get('/address/list')
    if (data.code === 200) addresses.value = data.data || []
  } catch (_) {}
  loading.value = false
}

const genderLabel = g => g === 'male' ? '男' : g === 'female' ? '女' : g === 'other' ? '其他' : '未设置'

/* 头像上传 */
const onAvatarChange = async (e) => {
  const file = e.target.files?.[0]
  if (!file) return
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    const { data } = await http.post('/common/upload', fd)
    if (data.code === 200) {
      const url = data.data
      customerInfo.avatarUrl = url
      setAvatarUrl(url)
      await http.put('/customer/update', {
        nickname: customerInfo.nickname,
        gender: customerInfo.gender || null,
        avatarUrl: url,
      })
    }
  } catch (_) {}
  uploading.value = false
}

/* 编辑信息 */
const saveProfile = async () => {
  try {
    const payload = {
      nickname: editForm.nickname,
      gender: editForm.gender || null,
      avatarUrl: customerInfo.avatarUrl || null,
    }
    const { data } = await http.put('/customer/update', payload)
    if (data.code === 200) {
      Object.assign(customerInfo, payload)
      showEdit.value = false
      alert('保存成功')
    } else {
      alert(data.message)
    }
  } catch (e) { alert(e.message) }
}

/* 打开新增表单 */
const openAddrForm = () => {
  editingAddrId.value = null
  Object.assign(addrForm, { receiverName: '', receiverPhone: '', province: '', city: '', district: '', detailAddress: '', isDefault: 0 })
  selProvince.value = ''
  selCity.value = ''
  selDistrict.value = ''
  phoneErr.value = ''
  showAddrForm.value = true
}

/* 编辑地址 */
const editAddr = (addr) => {
  editingAddrId.value = addr.id
  Object.assign(addrForm, {
    receiverName: addr.receiverName || '',
    receiverPhone: addr.receiverPhone || '',
    province: addr.province || '',
    city: addr.city || '',
    district: addr.district || '',
    detailAddress: addr.detailAddress || '',
    isDefault: addr.isDefault || 0
  })
  syncRegionSelectors(addr.province, addr.city, addr.district)
  phoneErr.value = ''
  showAddrForm.value = true
}

/* 关闭表单 */
const closeForm = () => {
  showAddrForm.value = false
  editingAddrId.value = null
  phoneErr.value = ''
}

/* 手机号校验：中国大陆手机号正则 */
const validatePhone = () => {
  const phone = addrForm.receiverPhone
  if (!phone) { phoneErr.value = ''; return }
  const re = /^1[3-9]\d{9}$/
  phoneErr.value = re.test(phone) ? '' : '请输入正确的手机号'
}

/* 保存地址 */
const saveAddress = async () => {
  // 提交前做一次完整校验
  if (addrForm.receiverPhone) {
    validatePhone()
    if (phoneErr.value) return
  }
  try {
    let res
    if (editingAddrId.value) {
      const payload = { ...addrForm, id: editingAddrId.value }
      res = await http.put(`/address/${editingAddrId.value}`, payload)
    } else {
      res = await http.post('/address/add', { ...addrForm })
    }
    if (res.data.code === 200) {
      closeForm()
      fetchAddresses()
    } else {
      alert(res.data.message)
    }
  } catch (_) {}
}

/* 设默认 */
const setDefault = async (addr) => {
  try {
    const { data } = await http.put('/address/setDefaultAddress', { id: addr.id, customerId: addr.customerId })
    if (data.code === 200) fetchAddresses()
  } catch (_) {}
}

/* 删除地址 */
const deleteAddr = async (id) => {
  if (!confirm('确定删除？')) return
  try {
    const { data } = await http.delete('/address/deleteAddress', { data: { id } })
    if (data.code === 200) fetchAddresses()
  } catch (_) {}
}

onMounted(async () => {
  await fetchCustomerInfo()
  if (user.value) {
    Object.assign(editForm, {
      nickname: customerInfo.nickname || '',
      gender: customerInfo.gender || '',
    })
  }
  fetchAddresses()
})
</script>

<style scoped>
.profile-page { padding: 100px 0 80px; min-height: 100vh; background: #f9f8f5; }

/* ---- Section Header (in sidebar) ---- */
.section-badge {
  font-family: var(--font-en); font-size: 11px; font-weight: 600;
  letter-spacing: 4px; color: var(--color-accent); margin: 0 0 6px;
  text-align: left;
}
.section-title { font-size: 28px; font-weight: 700; margin: 0 0 36px; text-align: left; }

/* ---- Grid ---- */
.profile-grid {
  display: grid; grid-template-columns: 340px 1fr;
  gap: 32px; align-items: start;
}

/* ---- Sidebar Card ---- */
.sidebar-card {
  background: #fff; border-radius: 14px; box-shadow: 0 2px 20px rgba(0,0,0,0.05);
  padding: 36px 32px 32px; text-align: center; position: sticky; top: 90px;
}

/* Avatar */
.avatar-section { margin-bottom: 28px; }
.avatar-wrap {
  display: block; position: relative; width: 96px; height: 96px;
  margin: 0 auto 16px; cursor: pointer; border-radius: 50%;
}
.avatar-pic {
  display: block; width: 96px; height: 96px; border-radius: 50%;
  object-fit: cover; box-shadow: 0 0 0 4px #fff, 0 0 0 6px var(--color-accent);
}
.avatar-fallback {
  display: flex; align-items: center; justify-content: center;
  width: 96px; height: 96px; border-radius: 50%;
  background: linear-gradient(135deg, #1a1a2e 0%, #2d2d44 100%);
  color: #fff; font-family: var(--font-en); font-size: 36px; font-weight: 700;
  box-shadow: 0 0 0 4px #fff, 0 0 0 6px var(--color-accent);
}
.avatar-badge {
  position: absolute; bottom: 2px; right: 2px;
  width: 28px; height: 28px; border-radius: 50%;
  background: var(--color-primary); color: #fff;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 2px 6px rgba(0,0,0,0.2);
  transition: transform 0.3s, background 0.3s;
}
.avatar-wrap:hover .avatar-badge { transform: scale(1.1); background: var(--color-accent); }

.profile-name { font-size: 20px; font-weight: 700; margin: 0 0 4px; }
.profile-role {
  font-size: 12px; color: var(--color-accent);
  padding: 3px 14px; border: 1px solid var(--color-accent-light);
  border-radius: 20px; background: #fefcf7;
}

/* Info items */
.info-items { list-style: none; padding: 0; margin: 24px 0; border-top: 1px solid var(--color-border); }
.info-items li {
  display: flex; justify-content: space-between; align-items: center;
  padding: 13px 0; font-size: 14px; border-bottom: 1px solid #f0eee8;
}
.info-key { color: #999; }
.info-val { font-weight: 600; color: #2c2c2c; }

.btn-block {
  width: 100%; display: inline-flex; align-items: center; justify-content: center;
  gap: 6px; padding: 11px; border-radius: 8px; font-size: 14px;
}

/* ---- Main Area ---- */
.main-card {
  background: #fff; border-radius: 14px; box-shadow: 0 2px 20px rgba(0,0,0,0.05);
  padding: 36px;
}

.address-header {
  display: flex; justify-content: space-between; align-items: flex-start;
  margin-bottom: 28px; padding-bottom: 20px;
  border-bottom: 1px solid #f0eee8;
}
.address-header h3 { font-size: 18px; font-weight: 700; margin: 0 0 4px; }
.address-sub { font-size: 13px; color: #aaa; margin: 0; }
.address-header .btn-sm { display: inline-flex; align-items: center; gap: 5px; }

/* Address cards */
.address-list { display: flex; flex-direction: column; gap: 14px; }
.address-card {
  display: flex; justify-content: space-between; align-items: center;
  padding: 20px; border: 1px solid #eeece4; border-radius: 12px;
  background: #fdfdfb; transition: all 0.25s;
}
.address-card:hover { border-color: #d4c9a8; }
.address-card.default { border-color: var(--color-accent); background: #fffdf7; }
.address-card.editing { border-color: var(--color-primary); background: #fff; flex-direction: column; align-items: stretch; padding-bottom: 4px; }
.addr-body { flex: 1; }
.addr-head { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.addr-head strong { font-size: 15px; }
.addr-phone { font-size: 13px; color: #888; }
.addr-full { font-size: 13px; color: #888; margin: 0; }
.default-tag {
  font-size: 10px; color: var(--color-accent); background: #fef5e7;
  padding: 2px 10px; border-radius: 10px; font-weight: 600;
}

.addr-acts { display: flex; gap: 4px; flex-shrink: 0; margin-left: 16px; }
.act-btn {
  width: 34px; height: 34px; display: flex; align-items: center; justify-content: center;
  border: 1px solid #e8e2d4; background: #fff; border-radius: 8px;
  cursor: pointer; color: #999; transition: all 0.2s;
}
.act-btn:hover { border-color: var(--color-primary); color: var(--color-primary); background: #f8f8f8; }
.act-btn.del:hover { border-color: #c0392b; color: #c0392b; background: #fef5f5; }

/* Empty state */
.address-empty { text-align: center; padding: 48px 20px; }
.empty-icon { margin-bottom: 16px; }
.address-empty p { font-size: 15px; color: #999; margin: 0 0 6px; }
.address-empty span { font-size: 13px; color: #ccc; }

/* Address form */
.addr-form {
  margin-top: 24px; padding: 24px; background: #fafaf7;
  border-radius: 12px; border: 1px solid #eeece4;
}
.addr-form-top {
  margin-top: 0; margin-bottom: 20px;
}
.addr-form-inline {
  margin-top: 16px; padding: 20px 0 0; background: transparent;
  border: none; border-top: 1px solid #eeece4;
  width: 100%;
}
.addr-form h4 { font-size: 15px; font-weight: 600; margin: 0 0 18px; }

.form-err { font-size: 12px; color: #c0392b; margin: -6px 0 8px; }

.form-row { margin-bottom: 12px; display: flex; gap: 10px; }
.form-row.cols-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; }
.form-row input, .form-row select {
  padding: 11px 14px; border: 1px solid #e8e2d4; border-radius: 8px;
  font-size: 13px; outline: none; flex: 1; background: #fff; color: var(--color-text);
  transition: border-color 0.2s; cursor: pointer;
}
.form-row input:focus, .form-row select:focus { border-color: var(--color-accent); }
.form-row select:disabled { opacity: 0.5; cursor: not-allowed; background: #f5f5f5; }

.form-check {
  display: flex; align-items: center; gap: 8px;
  font-size: 13px; color: #888; cursor: pointer; margin-top: 8px;
}
.form-check input { width: auto; }

.form-acts { display: flex; gap: 8px; margin-top: 20px; }

/* ---- Modal ---- */
.modal-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.35);
  display: flex; align-items: center; justify-content: center; z-index: 2000;
  backdrop-filter: blur(4px);
}
.modal-box {
  background: #fff; border-radius: 16px;
  padding: 36px; width: 420px; max-width: 92vw;
  box-shadow: 0 20px 60px rgba(0,0,0,0.15);
}
.modal-box h3 { font-size: 18px; font-weight: 700; margin: 0 0 28px; }
.modal-box .form-row { align-items: center; }
.modal-box .form-row label { width: 56px; flex-shrink: 0; font-size: 13px; color: #666; }
.modal-box .form-acts { margin-top: 24px; }

.page-empty { text-align: center; padding: 120px 0; color: #999; }

/* ---- Buttons ---- */
.btn-primary { background: var(--color-primary); color: #fff; border: none; }
.btn-outline { background: #fff; color: var(--color-primary); border: 1px solid var(--color-primary); }
.btn-ghost { background: transparent; color: #999; border: none; }
.btn-sm { padding: 8px 20px; font-size: 13px; border-radius: 8px; cursor: pointer; font-weight: 500; transition: all 0.2s; }
.btn-primary:hover { background: #2a2a42; }
.btn-outline:hover { background: #f8f8fa; }
.btn-ghost:hover { color: #666; }

/* 管理员权限卡片 */
.admin-info-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px; margin-bottom: 20px; }
.admin-info-item { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 20px 12px; background: #fafaf8; border-radius: 8px; text-align: center; }
.admin-info-icon { color: #c9a96e; }
.admin-info-label { font-size: 12px; color: #999; text-transform: uppercase; letter-spacing: 0.5px; }
.admin-info-value { font-size: 13px; color: #555; }
.admin-notice { display: flex; align-items: center; gap: 8px; padding: 14px 16px; background: #fdfaf4; border: 1px solid #e8d5a3; border-radius: 8px; font-size: 13px; color: #8b7355; }
.admin-notice svg { color: #c9a96e; flex-shrink: 0; }

@media (max-width: 768px) {
  .profile-grid { grid-template-columns: 1fr; }
  .sidebar-card { position: static; }
  .address-card { flex-direction: column; gap: 12px; }
  .addr-acts { margin-left: 0; }
}
</style>
