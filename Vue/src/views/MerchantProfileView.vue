<template>
  <div class="page profile-page">
    <section class="container">
      <p class="section-badge">MERCHANT PROFILE</p>
      <h1 class="section-title">店铺信息</h1>

      <template v-if="merchant">
        <div class="profile-grid">
          <!-- 左侧信息卡 -->
          <aside class="profile-sidebar">
            <div class="sidebar-card">
              <!-- 店铺Logo（仅展示，修改需通过编辑弹窗） -->
              <div class="avatar-wrap">
                <img v-if="merchant.logoUrl" :src="merchant.logoUrl" class="avatar-pic" />
                <span v-else class="avatar-fallback">{{ (merchant.shopName || 'S')[0].toUpperCase() }}</span>
              </div>
              <h2 class="profile-name">{{ merchant.shopName || '未设置' }}</h2>
              <span class="verify-badge" :class="merchant.isVerified === 1 ? 'verified' : 'unverified'">
                {{ merchant.isVerified === 1 ? '已认证' : '待审核' }}
              </span>
            </div>

            <!-- 店铺信息摘要 -->
            <ul class="info-items">
              <li><span class="info-key">店铺评分</span><span class="info-val">{{ merchant.rating || '5.00' }}</span></li>
              <li><span class="info-key">联系人</span><span class="info-val">{{ merchant.contactName || '-' }}</span></li>
              <li><span class="info-key">联系电话</span><span class="info-val">{{ merchant.contactPhone || '-' }}</span></li>
            </ul>

            <div class="sidebar-actions">
              <button class="btn btn-outline btn-block" @click="openEditShop">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
                编辑店铺信息
              </button>
              <button class="btn btn-outline btn-block" @click="openEditAccount">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
                账号信息
              </button>
            </div>
          </aside>

          <!-- 右侧详情 -->
          <main class="profile-main">
            <!-- 营业执照 -->
            <div class="main-card">
              <h3>营业执照</h3>
              <div v-if="merchant.businessLicense" class="license-box">
                <img :src="merchant.businessLicense" class="license-img" />
              </div>
              <p v-else class="empty-tip">未上传营业执照</p>
            </div>

            <!-- 店铺简介 -->
            <div class="main-card" style="margin-top:20px;">
              <h3>店铺简介</h3>
              <p class="shop-desc" v-if="merchant.shopDescription">{{ merchant.shopDescription }}</p>
              <p v-else class="empty-tip">暂无简介</p>
            </div>

            <!-- 实名信息（来自 merchant_real_name 表） -->
            <div class="main-card" style="margin-top:20px;">
              <div class="card-header">
                <div>
                  <h3>实名信息</h3>
                  <p class="card-sub">实名认证信息，修改需管理员审核</p>
                </div>
                <button class="btn btn-outline btn-sm" @click="openEditRealName">
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
                  修改
                </button>
              </div>
              <div class="realname-grid" v-if="merchant.realName">
                <div class="rn-item">
                  <span class="rn-label">真实姓名</span>
                  <span class="rn-value">{{ merchant.realName.realName }}</span>
                </div>
                <div class="rn-item">
                  <span class="rn-label">身份证号</span>
                  <span class="rn-value">{{ merchant.realName.cardId }}</span>
                </div>
                <div class="rn-item rn-item-full">
                  <span class="rn-label">身份证照片</span>
                  <a v-if="merchant.realName.cardUrl" :href="merchant.realName.cardUrl" target="_blank" class="link-sm">点击查看</a>
                  <span v-else class="text-muted">未上传</span>
                </div>
              </div>
              <p v-else class="empty-tip">未提交实名认证</p>
            </div>

            <!-- 账号信息卡片（来自 users 表） -->
            <div class="main-card" style="margin-top:20px;">
              <div class="card-header">
                <div>
                  <h3>账号信息</h3>
                  <p class="card-sub">登录凭证及账户状态</p>
                </div>
                <button class="btn btn-outline btn-sm" @click="openEditAccount">
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
                  编辑
                </button>
              </div>
              <div class="account-grid" v-if="accountUser">
                <div class="account-item">
                  <span class="ac-label">用户名</span>
                  <span class="ac-value">{{ accountUser.username || '-' }}</span>
                </div>
                <div class="account-item">
                  <span class="ac-label">手机号</span>
                  <span class="ac-value">{{ accountUser.phone || '未绑定' }}</span>
                </div>
                <div class="account-item">
                  <span class="ac-label">邮箱</span>
                  <span class="ac-value">{{ accountUser.email || '未绑定' }}</span>
                </div>
                <div class="account-item">
                  <span class="ac-label">角色</span>
                  <span class="ac-value role-tag">商家</span>
                </div>
                <div class="account-item">
                  <span class="ac-label">账号状态</span>
                  <span class="ac-value">
                    <span class="status-dot" :class="accountUser.status === 1 ? 'on' : 'off'"></span>
                    {{ accountUser.status === 1 ? '正常' : '已禁用' }}
                  </span>
                </div>
                <div class="account-item">
                  <span class="ac-label">注册时间</span>
                  <span class="ac-value text-mono">{{ formatTime(accountUser.createTime) }}</span>
                </div>
              </div>
            </div>

            <!-- 审核提示 -->
            <div class="audit-notice" v-if="merchant.isVerified !== 1">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
              <div>
                <strong>审核状态：{{ merchant.isVerified === 0 ? '待审核' : '审核未通过' }}</strong>
                <p>审核通过前，商品发布、订单管理等商家功能暂不可用。如有疑问请联系平台客服。</p>
              </div>
            </div>
          </main>
        </div>

        <!-- 编辑店铺弹层 -->
        <Teleport to="body">
          <div class="modal-overlay" v-if="showShopEdit" @click.self="showShopEdit = false">
            <div class="modal-box">
              <h3>编辑店铺信息</h3>
              <div class="form-row"><label>店铺名称</label><input v-model="shopForm.shopName" placeholder="店铺名称" /></div>
              <div class="form-row"><label>联系人</label><input v-model="shopForm.contactName" placeholder="联系人姓名" /></div>
              <div class="form-row"><label>联系电话</label><input v-model="shopForm.contactPhone" placeholder="联系电话" /></div>
              <div class="form-row"><label>店铺简介</label><textarea v-model="shopForm.shopDescription" rows="2" placeholder="店铺简介（选填）"></textarea></div>

              <!-- 店铺Logo -->
              <div class="form-row">
                <label>店铺Logo</label>
                <div class="upload-row">
                  <div class="upload-preview" v-if="shopForm.logoPreview">
                    <img :src="shopForm.logoPreview" />
                  </div>
                  <label class="upload-btn">
                    {{ shopForm.logoPreview ? '更换' : '上传' }}
                    <input type="file" accept="image/*" hidden @change="onEditLogoChange" />
                  </label>
                </div>
              </div>

              <!-- 营业执照重新上传 -->
              <div class="form-row">
                <label>营业执照</label>
                <div class="upload-row">
                  <div class="upload-preview" v-if="shopForm.licensePreview">
                    <img :src="shopForm.licensePreview" />
                  </div>
                  <label class="upload-btn">
                    {{ shopForm.licensePreview ? '更换' : '上传' }}
                    <input type="file" accept="image/*" hidden @change="onEditLicenseChange" />
                  </label>
                </div>
              </div>
              <p v-if="uploading" style="font-size:12px;color:#999;">上传中...</p>

              <!-- 轮播图 -->
              <div class="form-row">
                <label>轮播图</label>
                <div class="banner-upload-area">
                  <div class="banner-thumbs" v-if="shopForm.banners && shopForm.banners.length > 0">
                    <div class="banner-thumb" v-for="(url, i) in shopForm.banners" :key="i">
                      <img :src="url" />
                      <span class="banner-del" @click="removeBanner(i)">&times;</span>
                    </div>
                  </div>
                  <label class="upload-btn" v-if="!shopForm.banners || shopForm.banners.length < 5">
                    添加图片
                    <input type="file" accept="image/*" hidden @change="onBannerUpload" />
                  </label>
                  <span class="banner-count" v-if="shopForm.banners && shopForm.banners.length > 0">{{ shopForm.banners.length }} / 5</span>
                </div>
              </div>

              <p class="audit-warn">提交修改后需管理员审核通过才会更新，审核期间商家功能不受影响</p>

              <div class="form-acts">
                <button class="btn btn-primary btn-sm" @click="saveShopProfile">提交修改</button>
                <button class="btn btn-ghost btn-sm" @click="showShopEdit = false">取消</button>
              </div>
            </div>
          </div>
        </Teleport>

        <!-- 编辑账号信息弹层（users 表字段） -->
        <Teleport to="body">
          <div class="modal-overlay" v-if="showAccountEdit" @click.self="showAccountEdit = false">
            <div class="modal-box">
              <h3>编辑账号信息</h3>
              <div class="form-row"><label>用户名</label><input v-model="accountForm.username" placeholder="用户名" /></div>
              <div class="form-row"><label>手机号</label><input v-model="accountForm.phone" placeholder="手机号" maxlength="11" /></div>
              <div class="form-row"><label>邮箱</label><input v-model="accountForm.email" placeholder="邮箱" /></div>
              <p v-if="accountMsg" :class="accountOk ? 'pub-ok' : 'pub-err'">{{ accountMsg }}</p>
              <div class="form-acts">
                <button class="btn btn-primary btn-sm" @click="saveAccount">保存</button>
                <button class="btn btn-ghost btn-sm" @click="showAccountEdit = false">取消</button>
              </div>
            </div>
          </div>
        </Teleport>

        <!-- 编辑实名信息弹层 -->
        <Teleport to="body">
          <div class="modal-overlay" v-if="showRealNameEdit" @click.self="showRealNameEdit = false">
            <div class="modal-box">
              <h3>修改实名信息</h3>
              <div class="form-row"><label>真实姓名</label><input v-model="realnameForm.realName" placeholder="真实姓名" /></div>
              <div class="form-row"><label>身份证号</label>
                <div style="flex:1">
                  <input v-model="realnameForm.cardId" placeholder="身份证号" maxlength="18" style="width:100%"
                    @input="realnameForm.cardId = realnameForm.cardId.replace(/[^\dXx]/g, '')" />
                  <p v-if="realnameForm.cardId && realnameForm.cardId.length !== 18" style="font-size:11px;color:#e74c3c;margin:4px 0 0;">身份证号必须为18位</p>
                </div>
              </div>
              <div class="form-row">
                <label>身份证照片</label>
                <div class="upload-row">
                  <div class="upload-preview" v-if="realnameForm.cardPreview">
                    <img :src="realnameForm.cardPreview" />
                  </div>
                  <label class="upload-btn">
                    {{ realnameForm.cardPreview ? '更换' : '上传' }}
                    <input type="file" accept="image/*" hidden @change="onCardUrlChange" />
                  </label>
                </div>
              </div>
              <p v-if="uploading" style="font-size:12px;color:#999;">上传中...</p>
              <p class="audit-warn">修改实名信息需管理员审核通过后才会更新</p>
              <div class="form-acts">
                <button class="btn btn-primary btn-sm" @click="saveRealName">提交修改</button>
                <button class="btn btn-ghost btn-sm" @click="showRealNameEdit = false">取消</button>
              </div>
            </div>
          </div>
        </Teleport>

      </template>
      <div v-else-if="loading" class="page-empty"><p>加载中...</p></div>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import http from '../utils/http.js'
import { currentUser } from '../stores/auth.js'

const user = currentUser
const loading = ref(true)
const showShopEdit = ref(false)
const showAccountEdit = ref(false)
const showRealNameEdit = ref(false)
const uploading = ref(false)
const merchant = ref(null)
const accountMsg = ref('')
const accountOk = ref(false)

/* merchants 表数据可从接口直接取 */
/* users 表数据通过 merchant.user 获取 */
const accountUser = computed(() => {
  if (!merchant.value) return null
  /* getMerchantById 现在联查 users 表，user 字段挂载在 merchant 上 */
  return merchant.value.user || null
})

const shopForm = reactive({
  shopName: '',
  contactName: '',
  contactPhone: '',
  shopDescription: '',
  logoUrl: '',
  logoPreview: '',
  businessLicense: '',
  licensePreview: '',
  banners: [],
})

const accountForm = reactive({
  username: '',
  phone: '',
  email: '',
})

const realnameForm = reactive({
  realName: '',
  cardId: '',
  cardUrl: '',
  cardPreview: '',
})

/* 格式化时间 */
const formatTime = (t) => {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

/* ========== 获取商户详情 ========== */
const fetchMerchant = async () => {
  loading.value = true
  try {
    const { data } = await http.get('/merchant/me')
    if (data.code === 200) {
      merchant.value = data.data
    }
  } catch (_) {}
  loading.value = false
}

/* ========== Logo 上传（编辑弹窗内） ========== */
const onEditLogoChange = async (e) => {
  const file = e.target.files?.[0]
  if (!file) return
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    const { data } = await http.post('/common/upload', fd)
    if (data.code === 200) {
      shopForm.logoUrl = data.data
      shopForm.logoPreview = URL.createObjectURL(file)
    }
  } catch (_) {}
  uploading.value = false
}

/* ========== 打开编辑弹层 ========== */
const openEditShop = () => {
  if (!merchant.value) return
  Object.assign(shopForm, {
    shopName: merchant.value.shopName || '',
    contactName: merchant.value.contactName || '',
    contactPhone: merchant.value.contactPhone || '',
    shopDescription: merchant.value.shopDescription || '',
    logoUrl: merchant.value.logoUrl || '',
    logoPreview: merchant.value.logoUrl || '',
    businessLicense: merchant.value.businessLicense || '',
    licensePreview: merchant.value.businessLicense || '',
    banners: parseBanners(merchant.value.banners),
  })
  showShopEdit.value = true
}

const openEditAccount = () => {
  const u = accountUser.value
  Object.assign(accountForm, {
    username: u?.username || '',
    phone: u?.phone || '',
    email: u?.email || '',
  })
  accountMsg.value = ''
  showAccountEdit.value = true
}

/* ========== 执照上传 ========== */
const onEditLicenseChange = async (e) => {
  const file = e.target.files?.[0]
  if (!file) return
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    const { data } = await http.post('/common/upload', fd)
    if (data.code === 200) {
      shopForm.businessLicense = data.data
      shopForm.licensePreview = URL.createObjectURL(file)
    }
  } catch (_) {}
  uploading.value = false
}

/* ========== 轮播图上传 ========== */
const parseBanners = (raw) => {
  if (!raw) return []
  try { return JSON.parse(raw) } catch (_) { return [] }
}
const onBannerUpload = async (e) => {
  const file = e.target.files?.[0]
  if (!file) return
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    const { data } = await http.post('/common/upload', fd)
    if (data.code === 200) {
      if (!shopForm.banners) shopForm.banners = []
      shopForm.banners.push(data.data)
    }
  } catch (_) {}
  uploading.value = false
}
const removeBanner = (i) => {
  shopForm.banners.splice(i, 1)
}

/* ========== 保存店铺信息 ========== */
const saveShopProfile = async () => {
  try {
    const payload = { ...shopForm }
    delete payload.licensePreview
    delete payload.logoPreview
    payload.banners = JSON.stringify(payload.banners || [])
    const { data } = await http.put('/merchant/me/store', payload)
    if (data.code === 200) {
      showShopEdit.value = false
      alert('修改已提交，等待管理员审核。审核通过后刷新页面即可看到更新。')
    } else {
      alert(data.message)
    }
  } catch (e) { alert(e.message) }
}

/* ========== 实名信息修改 ========== */
const openEditRealName = () => {
  const rn = merchant.value?.realName
  Object.assign(realnameForm, {
    realName: rn?.realName || '',
    cardId: rn?.cardId || '',
    cardUrl: rn?.cardUrl || '',
    cardPreview: rn?.cardUrl || '',
  })
  showRealNameEdit.value = true
}

const onCardUrlChange = async (e) => {
  const file = e.target.files?.[0]
  if (!file) return
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    const { data } = await http.post('/common/upload', fd)
    if (data.code === 200) {
      realnameForm.cardUrl = data.data
      realnameForm.cardPreview = URL.createObjectURL(file)
    }
  } catch (_) {}
  uploading.value = false
}

const saveRealName = async () => {
  try {
    if (!realnameForm.realName.trim()) return alert('请输入真实姓名')
    if (!realnameForm.cardId.trim()) return alert('请输入身份证号码')
    if (realnameForm.cardId.trim().length !== 18) return alert('身份证号码应为18位')
    const payload = {
      realName: realnameForm.realName,
      cardId: realnameForm.cardId,
      cardUrl: realnameForm.cardUrl,
    }
    const { data } = await http.put('/merchant/me/real-name', payload)
    if (data.code === 200) {
      showRealNameEdit.value = false
      alert('实名信息修改已提交，等待管理员审核')
    } else {
      alert(data.message)
    }
  } catch (e) { alert(e.message) }
}

/* ========== 保存账号信息（users 表） ========== */
const saveAccount = async () => {
  accountMsg.value = ''
  accountOk.value = false
  try {
    const { data } = await http.put('/merchant/me/account', {
      username: accountForm.username,
      phone: accountForm.phone,
      email: accountForm.email,
    })
    if (data.code === 200) {
      accountOk.value = true
      accountMsg.value = '账号信息更新成功'
      setTimeout(() => {
        showAccountEdit.value = false
        accountMsg.value = ''
      }, 1200)
      fetchMerchant()
    } else {
      accountMsg.value = data.message || '更新失败'
    }
  } catch (e) {
    accountMsg.value = e.response?.data?.message || e.message || '网络异常'
  }
}

onMounted(() => {
  fetchMerchant()
})
</script>

<style scoped>
.profile-page { padding: 100px 0 80px; min-height: 100vh; background: #f9f8f5; }

.section-badge {
  font-family: var(--font-en); font-size: 11px; font-weight: 600;
  letter-spacing: 4px; color: var(--color-accent); margin: 0 0 6px;
}
.section-title { font-size: 28px; font-weight: 700; margin: 0 0 36px; }

/* Grid */
.profile-grid {
  display: grid; grid-template-columns: 340px 1fr;
  gap: 32px; align-items: start;
}

/* Sidebar */
.sidebar-card {
  background: #fff; border-radius: 14px; box-shadow: 0 2px 20px rgba(0,0,0,0.05);
  padding: 36px 32px 32px; text-align: center; position: sticky; top: 90px;
}
.avatar-wrap {
  display: block; position: relative; width: 96px; height: 96px;
  margin: 0 auto 16px; border-radius: 12px;
}
.avatar-pic {
  display: block; width: 96px; height: 96px; border-radius: 12px;
  object-fit: cover; box-shadow: 0 0 0 4px #fff, 0 0 0 6px var(--color-accent);
}
.avatar-fallback {
  display: flex; align-items: center; justify-content: center;
  width: 96px; height: 96px; border-radius: 12px;
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

.profile-name { font-size: 20px; font-weight: 700; margin: 0 0 8px; }
.verify-badge {
  display: inline-block; font-size: 12px; padding: 3px 14px; border-radius: 20px;
}
.verify-badge.verified { background: #eafaf1; color: #27ae60; border: 1px solid #a3e4bc; }
.verify-badge.unverified { background: #fef9e7; color: #b7950b; border: 1px solid #f9e79f; }

.info-items { list-style: none; padding: 0; margin: 24px 0; border-top: 1px solid var(--color-border); }
.info-items li {
  display: flex; justify-content: space-between; align-items: center;
  padding: 13px 0; font-size: 14px; border-bottom: 1px solid #f0eee8;
}
.info-key { color: #999; }
.info-val { font-weight: 600; color: #2c2c2c; }

.sidebar-actions { display: flex; flex-direction: column; gap: 8px; }

.btn-block {
  width: 100%; display: inline-flex; align-items: center; justify-content: center;
  gap: 6px; padding: 11px; border-radius: 8px; font-size: 14px;
}

/* Main */
.main-card {
  background: #fff; border-radius: 14px; box-shadow: 0 2px 20px rgba(0,0,0,0.05);
  padding: 28px;
}
.main-card h3 { font-size: 16px; font-weight: 700; margin: 0 0 4px; }

.card-header {
  display: flex; justify-content: space-between; align-items: flex-start;
  margin-bottom: 20px;
}
.card-header h3 { margin-bottom: 2px; }
.card-sub { font-size: 12px; color: #aaa; margin: 0; }

/* 账号信息网格 */
.account-grid {
  display: grid; grid-template-columns: 1fr 1fr;
  gap: 16px 32px;
}
.account-item {
  display: flex; flex-direction: column; gap: 4px;
  padding: 12px 0; border-bottom: 1px solid #f5f3ec;
}
.ac-label { font-size: 12px; color: #999; }
.ac-value { font-size: 14px; font-weight: 600; color: #2c2c2c; display: flex; align-items: center; gap: 6px; }
.ac-value.text-mono { font-family: 'SF Mono', 'Consolas', monospace; font-size: 12px; font-weight: 400; color: #666; }
.role-tag {
  font-size: 11px; background: #eef2ff; color: #4f46e5; padding: 2px 10px; border-radius: 10px;
  font-weight: 500; display: inline-block; width: fit-content;
}
.status-dot {
  width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0;
}
.status-dot.on { background: #27ae60; box-shadow: 0 0 4px rgba(39,174,96,0.4); }
.status-dot.off { background: #e74c3c; box-shadow: 0 0 4px rgba(231,76,60,0.4); }

.pub-ok { color: #27ae60; font-size: 13px; margin-bottom: 12px; }
.pub-err { color: #d44; font-size: 13px; margin-bottom: 12px; }

.license-box { border: 1px solid #eeece4; border-radius: 8px; overflow: hidden; }
.license-img { width: 100%; display: block; object-fit: contain; max-height: 300px; }

.shop-desc { font-size: 14px; color: #555; line-height: 1.8; }
.empty-tip { font-size: 13px; color: #ccc; text-align: center; padding: 24px 0; }

/* 实名信息 */
.realname-grid {
  display: grid; grid-template-columns: 1fr 1fr;
  gap: 16px 32px;
}
.rn-item {
  display: flex; flex-direction: column; gap: 4px;
  padding: 12px 0; border-bottom: 1px solid #f5f3ec;
}
.rn-item-full { grid-column: 1 / -1; }
.rn-label { font-size: 12px; color: #999; }
.rn-value { font-size: 14px; font-weight: 600; color: #2c2c2c; }
.link-sm { font-size: 13px; color: var(--color-accent); text-decoration: underline; }
.text-muted { font-size: 13px; color: #ccc; }

/* 审核提示 */
.audit-notice {
  display: flex; gap: 14px; align-items: flex-start;
  background: #fef9e7; border: 1px solid #f9e79f; border-radius: 12px;
  padding: 18px 20px; margin-top: 24px; color: #7d6608;
}
.audit-notice svg { flex-shrink: 0; margin-top: 2px; }
.audit-notice strong { font-size: 14px; display: block; margin-bottom: 4px; }
.audit-notice p { font-size: 13px; margin: 0; color: #9a7d0a; }

/* Modal */
.modal-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.35);
  display: flex; align-items: center; justify-content: center; z-index: 2000;
  backdrop-filter: blur(4px);
}
.modal-box {
  background: #fff; border-radius: 16px;
  padding: 36px; width: 460px; max-width: 92vw;
  box-shadow: 0 20px 60px rgba(0,0,0,0.15);
  max-height: 90vh; overflow-y: auto;
}
.modal-box h3 { font-size: 18px; font-weight: 700; margin: 0 0 28px; }
.form-row { margin-bottom: 14px; display: flex; align-items: flex-start; gap: 8px; }
.form-row label { width: 72px; flex-shrink: 0; font-size: 13px; color: #666; padding-top: 10px; }
.form-row input, .form-row textarea, .form-row select {
  flex: 1; padding: 10px 12px; border: 1px solid #e8e2d4; border-radius: 8px;
  font-size: 13px; outline: none; background: #fff; font-family: inherit; resize: vertical;
}
.form-row input:focus, .form-row textarea:focus, .form-row select:focus { border-color: var(--color-accent); }

.upload-row { display: flex; align-items: center; gap: 12px; }
.upload-preview { width: 56px; height: 56px; border-radius: 6px; overflow: hidden; border: 1px solid #e8e2d4; }
.upload-preview img { width: 100%; height: 100%; object-fit: cover; }
.upload-btn {
  padding: 8px 16px; font-size: 12px; border: 1px dashed var(--color-accent);
  border-radius: 6px; color: var(--color-accent); cursor: pointer; transition: all 0.3s;
}
.upload-btn:hover { background: #fefcf7; }

.audit-warn { font-size: 12px; color: #b7950b; margin-top: 8px; padding: 10px; background: #fef9e7; border-radius: 6px; }

/* 轮播图上传统一区域 */
.banner-upload-area { display: flex; align-items: flex-start; gap: 8px; flex-wrap: wrap; }
.banner-thumbs { display: flex; gap: 8px; flex-wrap: wrap; }
.banner-thumb { position: relative; width: 64px; height: 64px; border-radius: 6px; overflow: hidden; border: 1px solid #e8e2d4; }
.banner-thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }
.banner-del { position: absolute; top: 2px; right: 2px; width: 20px; height: 20px; background: rgba(0,0,0,0.55); color: #fff; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 14px; line-height: 1; cursor: pointer; transition: background 0.2s; }
.banner-del:hover { background: #e74c3c; }
.banner-count { font-size: 12px; color: #999; align-self: flex-end; padding-bottom: 8px; }

.form-acts { display: flex; gap: 8px; margin-top: 24px; }

/* Buttons */
.btn-primary { background: var(--color-primary); color: #fff; border: none; }
.btn-outline { background: #fff; color: var(--color-primary); border: 1px solid var(--color-primary); }
.btn-ghost { background: transparent; color: #999; border: none; }
.btn-sm { padding: 8px 20px; font-size: 13px; border-radius: 8px; cursor: pointer; font-weight: 500; transition: all 0.2s; }
.btn-primary:hover { background: #2a2a42; }
.btn-outline:hover { background: #f8f8fa; }
.btn-ghost:hover { color: #666; }

.page-empty { text-align: center; padding: 80px 0; color: #999; }

@media (max-width: 768px) {
  .profile-grid { grid-template-columns: 1fr; }
  .sidebar-card { position: static; }
  .account-grid { grid-template-columns: 1fr; }
}
</style>
