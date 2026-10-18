<template>
  <div class="login-page">
    <div class="login-wrap">
      <!-- 左侧品牌 -->
      <div class="login-brand">
        <router-link to="/" class="brand-logo">ShopAi</router-link>
        <div class="brand-content">
          <h2>完善店铺信息</h2>
          <p>填写真实的店铺资料，提交后等待管理员审核。审核通过后即可正常使用商家功能。</p>
        </div>
        <p class="brand-copy">&copy; 2026 ShopAi</p>
      </div>

      <!-- 右侧表单 -->
      <div class="login-form-panel">
        <div class="form-box">
          <h3>店铺信息</h3>
          <p class="form-sub">以下信息必须如实填写，提交后将进入审核流程</p>

          <form @submit.prevent="handleSubmit" class="login-form">
            <!-- 店铺名称 -->
            <div class="field">
              <label>店铺名称 *</label>
              <input v-model="form.shopName" type="text" placeholder="请输入店铺名称" maxlength="100" />
            </div>

            <!-- 联系人 -->
            <div class="field">
              <label>联系人姓名 *</label>
              <input v-model="form.contactName" type="text" placeholder="请输入联系人姓名" maxlength="50" />
            </div>

            <!-- 联系电话 -->
            <div class="field">
              <label>联系电话 *</label>
              <input v-model="form.contactPhone" type="text" placeholder="请输入联系电话" maxlength="20" />
            </div>

            <!-- 店铺Logo -->
            <div class="field">
              <label>店铺Logo *</label>
              <div class="upload-row">
                <div class="upload-preview" v-if="form.logoPreview">
                  <img :src="form.logoPreview" />
                </div>
                <label class="upload-btn">
                  {{ form.logoPreview ? '更换Logo' : '点击上传' }}
                  <input type="file" accept="image/*" hidden @change="e => onFileChange(e, 'logo')" />
                </label>
              </div>
              <p class="field-hint" v-if="uploadingLogo">上传中...</p>
            </div>

            <!-- 营业执照 -->
            <div class="field">
              <label>营业执照 *</label>
              <div class="upload-row">
                <div class="upload-preview" v-if="form.licensePreview">
                  <img :src="form.licensePreview" />
                </div>
                <label class="upload-btn">
                  {{ form.licensePreview ? '更换执照' : '点击上传' }}
                  <input type="file" accept="image/*" hidden @change="e => onFileChange(e, 'license')" />
                </label>
              </div>
              <p class="field-hint" v-if="uploadingLicense">上传中...</p>
            </div>

            <!-- 店铺简介 -->
            <div class="field">
              <label>店铺简介</label>
              <textarea v-model="form.shopDescription" placeholder="介绍你的店铺特色（选填）" rows="3"></textarea>
            </div>

            <p v-if="errorMsg" class="error-msg">{{ errorMsg }}</p>
            <p v-if="successMsg" class="success-msg">{{ successMsg }}</p>

            <button type="submit" class="btn-submit" :disabled="loading || uploadingLogo || uploadingLicense">
              {{ loading ? '提交中...' : '提交审核' }}
            </button>

            <p class="form-notice">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#c9a96e" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
              提交后将由管理员审核，审核通过前商家功能暂不可用
            </p>
          </form>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import http from '../utils/http.js'

const router = useRouter()
const loading = ref(false)
const errorMsg = ref('')
const successMsg = ref('')
const uploadingLogo = ref(false)
const uploadingLicense = ref(false)

const form = reactive({
  shopName: '',
  contactName: '',
  contactPhone: '',
  logoUrl: '',
  logoPreview: '',
  licenseUrl: '',
  licensePreview: '',
  shopDescription: '',
})

/** 上传文件（logo 或 营业执照） */
const onFileChange = async (e, type) => {
  const file = e.target.files?.[0]
  if (!file) return
  const uploadRef = type === 'logo' ? uploadingLogo : uploadingLicense
  uploadRef.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    const { data } = await http.post('/common/upload', fd)
    if (data.code === 200) {
      if (type === 'logo') {
        form.logoUrl = data.data
        form.logoPreview = URL.createObjectURL(file)
      } else {
        form.licenseUrl = data.data
        form.licensePreview = URL.createObjectURL(file)
      }
    } else {
      errorMsg.value = data.message || '上传失败'
    }
  } catch (_) {
    errorMsg.value = '上传失败，请重试'
  } finally {
    uploadRef.value = false
  }
}

const handleSubmit = async () => {
  errorMsg.value = ''
  successMsg.value = ''

  // 必填校验
  if (!form.shopName.trim()) return (errorMsg.value = '请输入店铺名称')
  if (!form.contactName.trim()) return (errorMsg.value = '请输入联系人姓名')
  if (!form.contactPhone.trim()) return (errorMsg.value = '请输入联系电话')
  if (!form.logoUrl) return (errorMsg.value = '请上传店铺Logo')
  if (!form.licenseUrl) return (errorMsg.value = '请上传营业执照')

  loading.value = true
  try {
    const payload = {
      shopName: form.shopName.trim(),
      contactName: form.contactName.trim(),
      contactPhone: form.contactPhone.trim(),
      logoUrl: form.logoUrl,
      businessLicense: form.licenseUrl,
      shopDescription: form.shopDescription.trim(),
    }
    const { data } = await http.post('/merchant/apply', payload)
    if (data.code === 200) {
      successMsg.value = '店铺信息已提交，即将进入实名认证...'
      setTimeout(() => router.push('/merchant-real-name'), 1500)
    } else {
      errorMsg.value = data.message || '提交失败'
    }
  } catch (e) {
    errorMsg.value = e.response?.data?.message || e.message || '网络异常'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page { min-height: 100vh; }
.login-wrap { display: flex; min-height: 100vh; }

.login-brand {
  flex: 1; background: #0f0f1a; display: flex; flex-direction: column;
  justify-content: space-between; padding: 48px 56px; color: #fff;
}
.brand-logo { font-family: 'Georgia', serif; font-size: 24px; font-weight: 600; letter-spacing: 3px; color: #fff; }
.brand-content h2 { font-size: 36px; font-weight: 600; margin-bottom: 12px; }
.brand-content p { color: #999; font-size: 15px; line-height: 1.6; }
.brand-copy { font-size: 12px; color: #555; }

.login-form-panel { flex: 1; display: flex; align-items: center; justify-content: center; padding: 40px; background: #fff; overflow-y: auto; }
.form-box { width: 100%; max-width: 460px; }
.form-box h3 { font-size: 28px; font-weight: 600; margin-bottom: 4px; }
.form-sub { font-size: 14px; color: #7a7a7a; margin-bottom: 28px; }

/* 表单 */
.field { margin-bottom: 18px; }
.field label { display: block; font-size: 13px; font-weight: 500; margin-bottom: 6px; color: #2c2c2c; }
.field input, .field textarea {
  width: 100%; padding: 12px 14px; font-size: 14px;
  border: 1px solid #e8e5dd; border-radius: 8px;
  outline: none; background: #fafaf8; transition: border-color 0.3s;
  font-family: inherit; resize: vertical;
}
.field input:focus, .field textarea:focus { border-color: #c9a96e; }
.field-hint { color: #999; font-size: 12px; margin-top: 4px; }

/* 上传 */
.upload-row { display: flex; align-items: center; gap: 14px; }
.upload-preview { width: 64px; height: 64px; border-radius: 8px; overflow: hidden; border: 1px solid #e8e5dd; flex-shrink: 0; background: #fafaf8; }
.upload-preview img { width: 100%; height: 100%; object-fit: cover; }
.upload-btn {
  padding: 10px 20px; font-size: 13px; border: 1px dashed #c9a96e;
  border-radius: 6px; color: #c9a96e; cursor: pointer; transition: all 0.3s;
}
.upload-btn:hover { background: #fefcf7; }

.error-msg { color: #d44; font-size: 13px; margin-bottom: 16px; }
.success-msg { color: #2a7; font-size: 13px; margin-bottom: 16px; }

.btn-submit {
  width: 100%; padding: 14px; font-size: 15px; font-weight: 600;
  letter-spacing: 2px; background: #1a1a2e; color: #fff;
  border: none; border-radius: 0; cursor: pointer; transition: all 0.3s;
}
.btn-submit:hover { background: #2a2a42; }
.btn-submit:disabled { opacity: 0.6; cursor: not-allowed; }

.form-notice {
  display: flex; align-items: center; gap: 6px;
  margin-top: 18px; font-size: 12px; color: #999; text-align: center; justify-content: center;
}

@media (max-width: 768px) { .login-brand { display: none; } .login-form-panel { flex: 1; padding: 24px; } }
</style>
