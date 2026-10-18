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
          <h2>欢迎回来</h2>
          <p>登录你的账户，继续品质购物之旅。</p>
        </div>
        <div class="brand-features">
          <div class="feat-item">
            <span class="feat-icon">✦</span>
            <span>精选品质好物</span>
          </div>
          <div class="feat-item">
            <span class="feat-icon">✦</span>
            <span>海量商家入驻</span>
          </div>
          <div class="feat-item">
            <span class="feat-icon">✦</span>
            <span>安全快捷交易</span>
          </div>
        </div>
        <p class="brand-copy">&copy; 2026 ShopAi</p>
      </div>

      <!-- 右侧表单 -->
      <div class="login-form-panel">
        <div class="form-box">
          <h3>登录</h3>
          <p class="form-sub">选择一种方式登录你的账户</p>

          <!-- 登录方式切换 -->
          <div class="login-tabs">
            <button :class="{ active: loginMode === 'username' }" @click="loginMode = 'username'; errorMsg = ''">
              账号
            </button>
            <button :class="{ active: loginMode === 'phone' }" @click="loginMode = 'phone'; errorMsg = ''">
              手机号
            </button>
            <button :class="{ active: loginMode === 'email' }" @click="loginMode = 'email'; errorMsg = ''">
              邮箱
            </button>
          </div>

          <form @submit.prevent="handleLogin" class="login-form">
            <!-- 用户名/账号输入 -->
            <div class="field" v-if="loginMode === 'username'">
              <label>账号</label>
              <input v-model="form.username" type="text" placeholder="请输入账号" autocomplete="username" />
            </div>

            <!-- 手机号输入 -->
            <div class="field" v-if="loginMode === 'phone'">
              <label>手机号</label>
              <input v-model="form.phone" type="text" placeholder="请输入手机号" maxlength="11" autocomplete="tel" />
            </div>

            <!-- 邮箱输入 -->
            <div class="field" v-if="loginMode === 'email'">
              <label>邮箱</label>
              <input v-model="form.email" type="email" placeholder="请输入邮箱地址" autocomplete="email" />
            </div>

            <!-- 密码 -->
            <div class="field">
              <label>密码</label>
              <input v-model="form.password" type="password" placeholder="请输入密码" autocomplete="current-password" />
            </div>

            <p v-if="errorMsg" class="error-msg">{{ errorMsg }}</p>

            <button type="submit" class="btn-submit" :disabled="loading">
              {{ loading ? '登录中...' : '登 录' }}
            </button>
          </form>

          <div class="form-footer">
            <router-link to="/register?role=customer">还没有账户？立即注册</router-link>
            <a href="#" class="forgot-link" @click.prevent="showForgot = true">忘记密码？</a>
          </div>
        </div>
      </div>
    </div>

    <!-- 忘记密码弹窗 -->
    <Teleport to="body">
      <div class="modal-mask" v-if="showForgot" @click.self="showForgot = false">
        <div class="modal-card">
          <h3>忘记密码</h3>
          <p class="modal-sub">通过手机号和邮箱验证重置密码</p>
          <div class="form-group">
            <label>手机号</label>
            <input v-model="forgotForm.phone" type="text" placeholder="请输入注册时的手机号" maxlength="11" />
          </div>
          <div class="form-group">
            <label>邮箱</label>
            <input v-model="forgotForm.email" type="email" placeholder="请输入注册时的邮箱" />
          </div>
          <div class="form-group">
            <label>新密码</label>
            <input v-model="forgotForm.password" type="password" placeholder="请输入新密码" />
          </div>
          <p v-if="forgotMsg" class="msg" :class="{ 'msg-error': forgotError, 'msg-success': !forgotError }">{{ forgotMsg }}</p>
          <div class="modal-actions">
            <button class="btn btn-outline btn-sm" @click="showForgot = false">取消</button>
            <button class="btn btn-primary btn-sm" @click="submitForgot" :disabled="forgotLoading">{{ forgotLoading ? '提交中...' : '重置密码' }}</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import http from '../utils/http.js'
import { setAuth } from '../stores/auth.js'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const errorMsg = ref('')
const loginMode = ref('username')

// 忘记密码
const showForgot = ref(false)
const forgotLoading = ref(false)
const forgotMsg = ref('')
const forgotError = ref(false)
const forgotForm = reactive({ phone: '', email: '', password: '' })

const form = reactive({
  username: '',
  phone: '',
  email: '',
  password: '',
})

const handleLogin = async () => {
  errorMsg.value = ''

  // 根据登录方式构建请求路径
  let url = ''
  const payload = { password: form.password }

  if (loginMode.value === 'username') {
    if (!form.username.trim()) return (errorMsg.value = '请输入账号')
    url = '/auth/NameLogin'
    payload.username = form.username.trim()
  } else if (loginMode.value === 'phone') {
    if (!form.phone.trim()) return (errorMsg.value = '请输入手机号')
    url = '/auth/PhoneLogin'
    payload.phone = form.phone.trim()
  } else if (loginMode.value === 'email') {
    if (!form.email.trim()) return (errorMsg.value = '请输入邮箱')
    url = '/auth/EmailLogin'
    payload.email = form.email.trim()
  }

  if (!form.password) return (errorMsg.value = '请输入密码')

  loading.value = true
  try {
    const { data } = await http.post(url, payload)

    if (data.code === 200) {
      const { token, user, needsInfo } = data.data
      setAuth(token, user)

      const role = user?.role || 'customer'
      const redirect = route.query.redirect

      if (redirect) {
        router.push(redirect)
      } else if (role === 'admin') {
        router.push('/admin')
      } else if (role === 'merchant') {
        // needsInfo 由后端返回：true → 新商家未入驻，跳信息填写页
        if (needsInfo) {
          router.push('/merchant-info')
        } else {
          router.push('/merchant')
        }
      } else {
        // customer: needsInfo 由后端返回，true → 跳完善信息页
        if (needsInfo) {
          router.push('/complete-profile')
        } else {
          router.push('/')
        }
      }
    } else {
      errorMsg.value = data.message || '登录失败'
    }
  } catch (e) {
    errorMsg.value = e.response?.data?.message || e.message || '网络异常'
  } finally {
    loading.value = false
  }
}

const submitForgot = async () => {
  forgotMsg.value = ''
  forgotError.value = false
  if (!forgotForm.phone.trim()) return showForgotErr('请输入手机号')
  if (!forgotForm.email.trim()) return showForgotErr('请输入邮箱')
  if (!forgotForm.password) return showForgotErr('请输入新密码')
  if (forgotForm.password.length < 6) return showForgotErr('密码至少6位')
  forgotLoading.value = true
  try {
    const { data } = await http.post('/auth/reset-pwd', {
      phone: forgotForm.phone.trim(),
      email: forgotForm.email.trim(),
      password: forgotForm.password,
    })
    if (data.code === 200) {
      forgotError.value = false
      forgotMsg.value = '密码重置成功，请使用新密码登录'
      setTimeout(() => { showForgot.value = false; forgotMsg.value = '' }, 2000)
    } else {
      showForgotErr(data.message || '重置失败')
    }
  } catch (e) {
    showForgotErr(e.response?.data?.message || e.message || '网络异常')
  } finally {
    forgotLoading.value = false
  }
}

const showForgotErr = (msg) => {
  forgotError.value = true
  forgotMsg.value = msg
}
</script>

<style scoped>
.login-page { min-height: 100vh; background: #f5f5f5; }
.login-wrap { display: flex; min-height: 100vh; }

.login-brand {
  flex: 1;
  background: linear-gradient(160deg, #0c0c1a 0%, #14142a 30%, #1a1a36 60%, #0f0f24 100%);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 48px 56px;
  color: #fff;
  position: relative;
  overflow: hidden;
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
.brand-logo {
  font-family: 'Georgia', 'Times New Roman', serif;
  font-size: 24px;
  font-weight: 600;
  letter-spacing: 3px;
  color: #fff;
  position: relative; z-index: 1;
}
.brand-content { position: relative; z-index: 1; }
.brand-content h2 { font-size: 36px; font-weight: 600; margin-bottom: 12px; }
.brand-content p { color: #999; font-size: 15px; }
.brand-features { position: relative; z-index: 1; display: flex; flex-direction: column; gap: 12px; }
.feat-item { display: flex; align-items: center; gap: 10px; font-size: 13px; color: #aaa; }
.feat-icon { color: #c9a96e; font-size: 10px; }
.brand-copy { font-size: 12px; color: #555; position: relative; z-index: 1; }

/* 右侧表单 */
.login-form-panel {
  flex: 1; display: flex; align-items: center; justify-content: center; padding: 40px;
  background: #fff;
}
.form-box { width: 100%; max-width: 420px; }
.form-box h3 { font-size: 28px; font-weight: 600; margin-bottom: 4px; }
.form-sub { font-size: 14px; color: #7a7a7a; margin-bottom: 28px; }

.login-tabs { display: flex; gap: 2px; margin-bottom: 24px; background: #f5f5f5; border-radius: 8px; padding: 4px; }
.login-tabs button {
  flex: 1; padding: 10px 0; font-size: 13px; font-weight: 500;
  border: none; background: transparent; border-radius: 6px; cursor: pointer;
  color: #7a7a7a; transition: all 0.3s;
}
.login-tabs button.active { background: #fff; color: #1a1a2e; font-weight: 600; box-shadow: 0 1px 4px rgba(0,0,0,0.08); }

.field { margin-bottom: 20px; }
.field label { display: block; font-size: 13px; font-weight: 500; margin-bottom: 6px; color: #2c2c2c; }
.field input {
  width: 100%; padding: 12px 14px; font-size: 14px;
  border: 1px solid #e8e5dd; border-radius: 8px;
  outline: none; background: #fafaf8; transition: border-color 0.3s;
}
.field input:focus { border-color: #c9a96e; }
.field input::placeholder { color: #aaa; }

.error-msg { color: #d44; font-size: 13px; margin-bottom: 16px; }

.btn-submit {
  width: 100%; padding: 14px; font-size: 15px; font-weight: 600;
  letter-spacing: 2px; background: #1a1a2e; color: #fff;
  border: none; border-radius: 0; cursor: pointer; transition: all 0.3s;
}
.btn-submit:hover { background: #2a2a42; }
.btn-submit:disabled { opacity: 0.6; cursor: not-allowed; }

.form-footer { text-align: center; margin-top: 24px; display: flex; justify-content: space-between; align-items: center; }
.form-footer a { font-size: 14px; color: #7a7a7a; text-decoration: none; }
.form-footer a:hover { color: #c9a96e; }

/* 忘记密码弹窗 */
.modal-mask { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.3); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal-card { background: #fff; border-radius: 12px; padding: 28px; width: 420px; max-width: 90vw; box-shadow: 0 12px 40px rgba(0,0,0,0.15); }
.modal-card h3 { margin-bottom: 4px; font-size: 20px; color: #1a1a2e; }
.modal-sub { font-size: 13px; color: #999; margin-bottom: 20px; }
.form-group { margin-bottom: 14px; }
.form-group label { display: block; font-size: 12px; font-weight: 500; color: #666; margin-bottom: 4px; text-transform: uppercase; letter-spacing: 1px; }
.form-group input { width: 100%; padding: 10px 12px; border: 1px solid #e8e5dd; border-radius: 6px; font-size: 14px; background: #fafaf8; color: #1a1a2e; outline: none; }
.form-group input:focus { border-color: #c9a96e; }
.form-group input::placeholder { color: #aaa; }
.msg { font-size: 13px; margin-bottom: 8px; }
.msg-error { color: #d44; }
.msg-success { color: #27ae60; }
.modal-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 8px; }

@media (max-width: 768px) {
  .login-brand { display: none; }
  .login-form-panel { flex: 1; padding: 24px; }
}
</style>
