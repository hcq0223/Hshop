<template>
  <div class="login-page">
    <div class="login-wrap">
      <!-- 左侧品牌 -->
      <div class="login-brand">
        <router-link to="/" class="brand-logo">ShopAi</router-link>
        <div class="brand-content">
          <h2>完善个人信息</h2>
          <p>让商家更好地了解你，推荐更合适的商品。</p>
        </div>
        <p class="brand-copy">&copy; 2026 ShopAi</p>
      </div>

      <!-- 右侧表单 -->
      <div class="login-form-panel">
        <div class="form-box">
          <h3>个人信息</h3>
          <p class="form-sub">填写昵称和性别，帮助你获得更精准的推荐</p>

          <form @submit.prevent="handleSubmit" class="login-form">
            <div class="field">
              <label>昵称</label>
              <input v-model="form.nickname" type="text" placeholder="请输入你的昵称" />
            </div>

            <div class="field">
              <label>性别</label>
              <div class="gender-options">
                <label class="gender-btn" :class="{ active: form.gender === 'male' }">
                  <input type="radio" v-model="form.gender" value="male" />
                  男
                </label>
                <label class="gender-btn" :class="{ active: form.gender === 'female' }">
                  <input type="radio" v-model="form.gender" value="female" />
                  女
                </label>
                <label class="gender-btn" :class="{ active: form.gender === 'other' }">
                  <input type="radio" v-model="form.gender" value="other" />
                  其他
                </label>
              </div>
            </div>

            <div class="field">
              <label>头像</label>
              <div class="avatar-upload">
                <div class="avatar-preview" v-if="form.avatarPreview">
                  <img :src="form.avatarPreview" />
                </div>
                <label class="upload-btn">
                  {{ form.avatarPreview ? '更换图片' : '点击上传' }}
                  <input type="file" accept="image/*" hidden @change="onFileChange" />
                </label>
              </div>
              <p v-if="uploading" class="field-hint">上传中...</p>
            </div>

            <p v-if="errorMsg" class="error-msg">{{ errorMsg }}</p>
            <p v-if="successMsg" class="success-msg">{{ successMsg }}</p>

            <button type="submit" class="btn-submit" :disabled="loading || uploading">
              {{ loading ? '提交中...' : '保 存' }}
            </button>
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
  nickname: '',
  gender: '',
  avatarUrl: '',
  avatarPreview: '',
})

/** 选择文件后自动上传 */
const onFileChange = async (e) => {
  const file = e.target.files?.[0]
  if (!file) return
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    const { data } = await http.post('/common/upload', fd)
    if (data.code === 200) {
      form.avatarUrl = data.data
      form.avatarPreview = URL.createObjectURL(file)
    } else {
      errorMsg.value = data.message || '上传失败'
    }
  } catch (e) {
    errorMsg.value = '上传失败，请重试'
  } finally {
    uploading.value = false
  }
}

const handleSubmit = async () => {
  errorMsg.value = ''
  successMsg.value = ''

  if (!form.nickname.trim() && !form.gender) {
    return (errorMsg.value = '请至少填写昵称或选择性别')
  }

  loading.value = true
  try {
    const { data } = await http.post('/customer/add', {
      nickname: form.nickname.trim(),
      gender: form.gender || null,
      avatarUrl: form.avatarUrl.trim() || null,
    })
    if (data.code === 200) {
      successMsg.value = '保存成功！'
      setTimeout(() => router.push('/'), 800)
    } else {
      errorMsg.value = data.message || '保存失败'
    }
  } catch (e) {
    errorMsg.value = e.response?.data?.message || e.message || '网络异常'
  } finally {
    loading.value = false
  }
}

const skip = async () => {
  // 跳过时创建空 customers 行，保证后续接口正常
  try { await http.post('/customer/add', {}) } catch (_) {}
  router.push('/')
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
.brand-content p { color: #999; font-size: 15px; }
.brand-copy { font-size: 12px; color: #555; }

.login-form-panel { flex: 1; display: flex; align-items: center; justify-content: center; padding: 40px; background: #fff; }
.form-box { width: 100%; max-width: 420px; }
.form-box h3 { font-size: 28px; font-weight: 600; margin-bottom: 4px; }
.form-sub { font-size: 14px; color: #7a7a7a; margin-bottom: 28px; }

/* 性别选择 */
.gender-options { display: flex; gap: 8px; }
.gender-btn {
  flex: 1; padding: 12px 0; text-align: center;
  border: 1px solid #e8e5dd; border-radius: 8px;
  background: #fafaf8; cursor: pointer; font-size: 14px;
  transition: all 0.3s; color: #7a7a7a;
}
.gender-btn input { display: none; }
.gender-btn.active { border-color: #c9a96e; background: #fefcf7; color: #c9a96e; font-weight: 600; }

/* 表单 */
.field { margin-bottom: 20px; }
.field label { display: block; font-size: 13px; font-weight: 500; margin-bottom: 6px; color: #2c2c2c; }
.field input {
  width: 100%; padding: 12px 14px; font-size: 14px;
  border: 1px solid #e8e5dd; border-radius: 8px;
  outline: none; background: #fafaf8; transition: border-color 0.3s;
}
.field input:focus { border-color: #c9a96e; }
.field-hint { color: #999; font-size: 12px; margin-top: 4px; }

/* 头像上传 */
.avatar-upload { display: flex; align-items: center; gap: 16px; }
.avatar-preview { width: 64px; height: 64px; border-radius: 50%; overflow: hidden; border: 2px solid #e8e5dd; flex-shrink: 0; }
.avatar-preview img { width: 100%; height: 100%; object-fit: cover; }
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

@media (max-width: 768px) { .login-brand { display: none; } .login-form-panel { flex: 1; padding: 24px; } }
</style>
