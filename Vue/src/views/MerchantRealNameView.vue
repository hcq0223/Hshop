<template>
  <div class="login-page">
    <div class="login-wrap">
      <!-- 左侧品牌 -->
      <div class="login-brand">
        <router-link to="/" class="brand-logo">ShopAi</router-link>
        <div class="brand-content">
          <h2>实名认证</h2>
          <p>根据监管要求，商家需完成实名认证后才能正常经营。信息将与入驻资料一同提交管理员审核。</p>
        </div>
        <p class="brand-copy">&copy; 2026 ShopAi</p>
      </div>

      <!-- 右侧表单 -->
      <div class="login-form-panel">
        <div class="form-box">
          <h3>实名信息</h3>
          <p class="form-sub">请如实填写以下身份信息</p>

          <form @submit.prevent="handleSubmit" class="login-form">
            <!-- 真实姓名 -->
            <div class="field">
              <label>真实姓名 *</label>
              <input v-model="form.realName" type="text" placeholder="请输入身份证上的姓名" maxlength="50" />
            </div>

            <!-- 身份证号 -->
            <div class="field">
              <label>身份证号 *</label>
              <input v-model="form.cardId" type="text" placeholder="请输入18位身份证号码" maxlength="18"
                @input="form.cardId = form.cardId.replace(/[^\dXx]/g, '')" />
              <p class="field-hint" v-if="form.cardId && form.cardId.length !== 18" style="color:#e74c3c;">身份证号必须为18位</p>
            </div>

            <!-- 身份证照片上传 -->
            <div class="field">
              <label>身份证照片 *</label>
              <div class="upload-row">
                <div class="upload-preview" v-if="form.cardPreview">
                  <img :src="form.cardPreview" />
                </div>
                <label class="upload-btn">
                  {{ form.cardPreview ? '更换照片' : '点击上传' }}
                  <input type="file" accept="image/*" hidden @change="onFileChange" />
                </label>
              </div>
              <p class="field-hint" v-if="uploading">上传中...</p>
            </div>

            <p v-if="errorMsg" class="error-msg">{{ errorMsg }}</p>
            <p v-if="successMsg" class="success-msg">{{ successMsg }}</p>

            <button type="submit" class="btn-submit" :disabled="loading || uploading">
              {{ loading ? '提交中...' : '提交认证' }}
            </button>

            <p class="form-notice">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#c9a96e" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
              实名信息将与入驻资料一同提交，由管理员统一审核
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
const uploading = ref(false)
const errorMsg = ref('')
const successMsg = ref('')

const form = reactive({
  realName: '',
  cardId: '',
  cardUrl: '',
  cardPreview: '',
})

/** 上传身份证照片 */
const onFileChange = async (e) => {
  const file = e.target.files?.[0]
  if (!file) return
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    const { data } = await http.post('/common/upload', fd)
    if (data.code === 200) {
      form.cardUrl = data.data
      form.cardPreview = URL.createObjectURL(file)
    } else {
      errorMsg.value = data.message || '上传失败'
    }
  } catch (_) {
    errorMsg.value = '上传失败，请重试'
  } finally {
    uploading.value = false
  }
}

const handleSubmit = async () => {
  errorMsg.value = ''
  successMsg.value = ''

  // 必填校验
  if (!form.realName.trim()) return (errorMsg.value = '请输入真实姓名')
  if (!form.cardId.trim()) return (errorMsg.value = '请输入身份证号码')
  if (form.cardId.trim().length !== 18) return (errorMsg.value = '身份证号码应为18位')
  if (!form.cardUrl) return (errorMsg.value = '请上传身份证照片')

  loading.value = true
  try {
    const { data } = await http.post('/merchant/real-name', {
      realName: form.realName.trim(),
      cardId: form.cardId.trim(),
      cardUrl: form.cardUrl,
    })
    if (data.code === 200) {
      successMsg.value = '入驻信息已全部提交，请等待管理员审核，即将返回首页...'
      setTimeout(() => router.push('/'), 2500)
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
.upload-preview { width: 80px; height: 56px; border-radius: 8px; overflow: hidden; border: 1px solid #e8e5dd; flex-shrink: 0; background: #fafaf8; }
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
