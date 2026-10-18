<template>
  <div class="login-page">
    <div class="login-wrap">
      <!-- 左侧品牌 -->
      <div class="login-brand">
        <div class="brand-decor">
          <span class="decor-dot dot-1"></span>
          <span class="decor-dot dot-2"></span>
          <span class="decor-dot dot-3"></span>
          <span class="decor-line line-1"></span>
          <span class="decor-line line-2"></span>
        </div>
        <router-link to="/" class="brand-logo">ShopAi</router-link>
        <div class="brand-content">
          <h2>{{ isMerchant ? '商家入驻' : '加入我们' }}</h2>
          <p>{{ isMerchant ? '开通店铺，触达千万顾客。' : '创建账户，开启品质购物之旅。' }}</p>
        </div>
        <div class="brand-features">
          <div class="feat-item">
            <span class="feat-icon">✦</span>
            <span>{{ isMerchant ? '一键开店，轻松管理' : '精选品质好物' }}</span>
          </div>
          <div class="feat-item">
            <span class="feat-icon">✦</span>
            <span>{{ isMerchant ? '实时数据，洞察经营' : '海量商家入驻' }}</span>
          </div>
          <div class="feat-item">
            <span class="feat-icon">✦</span>
            <span>{{ isMerchant ? '安全结算，极速提现' : '安全快捷交易' }}</span>
          </div>
        </div>
        <p class="brand-copy">&copy; 2026 ShopAi</p>
      </div>

      <!-- 右侧表单 -->
      <div class="login-form-panel">
        <div class="form-box">
          <h3>{{ isMerchant ? '商家入驻' : '顾客注册' }}</h3>
          <p class="form-sub">填写以下信息创建你的{{ isMerchant ? '商家' : '' }}账户</p>

          <!-- 角色切换 -->
          <div class="role-tabs">
            <router-link to="/register?role=customer" class="role-btn" :class="{ active: !isMerchant }">
              我是顾客
            </router-link>
            <router-link to="/register?role=merchant" class="role-btn" :class="{ active: isMerchant }">
              我是商家
            </router-link>
          </div>

          <form @submit.prevent="handleRegister" class="login-form">
            <div class="field">
              <label>账号 *</label>
              <input v-model="form.username" type="text" placeholder="字母开头，6-10位字母/数字/符号" maxlength="10" />
              <p class="field-hint" v-if="usernameHint">{{ usernameHint }}</p>
            </div>

            <div class="field">
              <label>手机号 *</label>
              <input v-model="form.phone" type="text" placeholder="请输入手机号" maxlength="11" />
              <p class="field-hint" v-if="phoneError">{{ phoneError }}</p>
            </div>

            <div class="field">
              <label>邮箱</label>
              <input v-model="form.email" type="email" placeholder="请输入邮箱（选填）" />
            </div>

            <div class="field">
              <label>密码 *</label>
              <input v-model="form.password" type="password" placeholder="请输入密码（至少6位）" />
            </div>

            <p v-if="errorMsg" class="error-msg">{{ errorMsg }}</p>
            <p v-if="successMsg" class="success-msg">{{ successMsg }}</p>

            <button type="submit" class="btn-submit" :disabled="loading">
              {{ loading ? '注册中...' : (isMerchant ? '提交入驻' : '注 册') }}
            </button>
          </form>

          <div class="form-footer">
            已有账户？<router-link to="/login">立即登录</router-link>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../utils/http.js'
import { setAuth } from '../stores/auth.js'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const errorMsg = ref('')
const successMsg = ref('')
const phoneError = ref('')
const usernameHint = ref('')

/* 手机号正则：中国大陆1开头，第二位3-9，共11位 */
const PHONE_REGEX = /^1[3-9]\d{9}$/

/* 账号正则：必须以字母开头，6-10位，只允许字母/数字/下划线/连字符 */
const USERNAME_REGEX = /^[a-zA-Z][a-zA-Z0-9_-]{5,9}$/

/* 通过 URL 参数判断角色 */
const isMerchant = computed(() => route.query.role === 'merchant')

const form = reactive({
  username: '',
  phone: '',
  email: '',
  password: '',
})

const handleRegister = async () => {
  errorMsg.value = ''
  successMsg.value = ''
  phoneError.value = ''
  usernameHint.value = ''

  /* 账号校验 */
  if (!form.username.trim()) return (errorMsg.value = '请输入账号')
  if (!USERNAME_REGEX.test(form.username.trim())) {
    usernameHint.value = '账号必须以字母开头，6-10位，只允许字母/数字/下划线/连字符'
    return (errorMsg.value = '账号格式不正确')
  }
  if (!form.phone.trim()) return (errorMsg.value = '请输入手机号')
  if (!PHONE_REGEX.test(form.phone.trim())) {
    phoneError.value = '请输入正确的手机号'
    return (errorMsg.value = '手机号格式不正确')
  }
  if (!form.password || form.password.length < 6) return (errorMsg.value = '密码至少6位')

  loading.value = true
  try {
    const payload = {
      username: form.username.trim(),
      phone: form.phone.trim(),
      email: form.email.trim(),
      password: form.password,
      role: isMerchant.value ? 'merchant' : 'customer',
    }

    const { data } = await http.post('/auth/register', payload)

    if (data.code === 200) {
      // 注册成功后自动登录，token/user 从返回中获取
      const res = data.data
      if (res && res.token) {
        setAuth(res.token, res.user || { id: res.userId, username: payload.username, phone: payload.phone, role: payload.role })
        // 商户直接跳商家信息填写，顾客跳到信息补全页面
        if (payload.role === 'merchant') {
          successMsg.value = '注册成功！即将跳转到商家信息填写...'
          setTimeout(() => router.push('/merchant-info'), 1500)
        } else {
          successMsg.value = '注册成功！请完善个人信息...'
          setTimeout(() => router.push('/complete-profile'), 1000)
        }
      } else {
        // 后端未返回 token（旧接口格式），直接跳到登录页
        successMsg.value = '注册成功！即将跳转到登录页...'
        setTimeout(() => router.push('/login'), 1500)
      }
    } else {
      errorMsg.value = data.message || '注册失败'
    }
  } catch (e) {
    errorMsg.value = e.message || '网络异常，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page { min-height: 100vh; background: #f5f5f5; }
.login-wrap { display: flex; min-height: 100vh; }

.login-brand {
  flex: 1;
  background: linear-gradient(160deg, #0c0c1a 0%, #14142a 30%, #1a1a36 60%, #0f0f24 100%);
  display: flex; flex-direction: column;
  justify-content: space-between; padding: 48px 56px; color: #fff;
  position: relative; overflow: hidden;
}
/* 装饰元素 */
.brand-decor { position: absolute; inset: 0; pointer-events: none; }
.decor-dot {
  position: absolute; border-radius: 50%;
  background: radial-gradient(circle, rgba(201,169,110,0.5) 0%, transparent 70%);
}
.dot-1 { width: 180px; height: 180px; top: -40px; right: -40px; }
.dot-2 { width: 120px; height: 120px; bottom: 25%; left: -30px; opacity: 0.6; }
.dot-3 { width: 80px; height: 80px; top: 35%; right: 20%; opacity: 0.3; }
.decor-line {
  position: absolute; border-radius: 2px;
  background: linear-gradient(90deg, transparent, rgba(201,169,110,0.15), transparent);
}
.line-1 { width: 60%; height: 1px; top: 22%; left: 20%; }
.line-2 { width: 40%; height: 1px; bottom: 30%; right: 10%; }
.brand-logo { font-family: 'Georgia', serif; font-size: 24px; font-weight: 600; letter-spacing: 3px; color: #fff; position: relative; z-index: 1; }
.brand-content { position: relative; z-index: 1; }
.brand-content h2 { font-size: 36px; font-weight: 600; margin-bottom: 12px; }
.brand-content p { color: #999; font-size: 15px; }
.brand-features { position: relative; z-index: 1; display: flex; flex-direction: column; gap: 12px; }
.feat-item { display: flex; align-items: center; gap: 10px; font-size: 13px; color: #aaa; }
.feat-icon { color: #c9a96e; font-size: 10px; }
.brand-copy { font-size: 12px; color: #555; position: relative; z-index: 1; }

.login-form-panel {
  flex: 1; display: flex; align-items: center; justify-content: center; padding: 40px;
  background: #fff;
}
.form-box { width: 100%; max-width: 420px; }
.form-box h3 { font-size: 28px; font-weight: 600; margin-bottom: 4px; }
.form-sub { font-size: 14px; color: #7a7a7a; margin-bottom: 24px; }

/* 角色切换 */
.role-tabs { display: flex; gap: 2px; margin-bottom: 24px; background: #f5f5f5; border-radius: 8px; padding: 4px; }
.role-btn {
  flex: 1; display: flex; align-items: center; justify-content: center;
  padding: 10px 0; font-size: 13px; font-weight: 500; border: none;
  background: transparent; border-radius: 6px; cursor: pointer;
  color: #7a7a7a; text-decoration: none; transition: all 0.3s;
}
.role-btn.active { background: #fff; color: #1a1a2e; font-weight: 600; box-shadow: 0 1px 4px rgba(0,0,0,0.08); }

/* 表单 */
.field { margin-bottom: 20px; }
.field label { display: block; font-size: 13px; font-weight: 500; margin-bottom: 6px; color: #2c2c2c; }
.field input {
  width: 100%; padding: 12px 14px; font-size: 14px;
  border: 1px solid #e8e5dd; border-radius: 8px;
  outline: none; background: #fafaf8; transition: border-color 0.3s;
}
.field input:focus { border-color: #c9a96e; }
.field-hint { color: #d44; font-size: 12px; margin-top: 4px; }

.error-msg { color: #d44; font-size: 13px; margin-bottom: 16px; }
.success-msg { color: #2a7; font-size: 13px; margin-bottom: 16px; }

.btn-submit {
  width: 100%; padding: 14px; font-size: 15px; font-weight: 600;
  letter-spacing: 2px; background: #1a1a2e; color: #fff;
  border: none; border-radius: 0; cursor: pointer; transition: all 0.3s;
}
.btn-submit:hover { background: #2a2a42; }
.btn-submit:disabled { opacity: 0.6; cursor: not-allowed; }

.form-footer { text-align: center; margin-top: 24px; font-size: 14px; color: #7a7a7a; }
.form-footer a { color: #c9a96e; font-weight: 600; text-decoration: none; }

@media (max-width: 768px) { .login-brand { display: none; } .login-form-panel { flex: 1; padding: 24px; } }
</style>
